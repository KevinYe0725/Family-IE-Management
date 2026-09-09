package com.familyfinance.investment;

import com.familyfinance.accounting.MultiCurrencyPolicy;
import com.familyfinance.family.*;
import com.familyfinance.market.*;
import com.familyfinance.shared.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** External requests complete BEFORE short catalog/snapshot write transactions. */
@Service
public class OverseasInvestmentService {
 private final OverseasMarketService market;private final SecurityRepository securities;private final JdbcTemplate jdbc;
 private final FamilyMutationAuthorization authorization;private final CurrentMembership membership;private final FamilyPermissionService permissions;
 private final MultiCurrencyPolicy policy;private final Clock clock;private final TransactionTemplate transaction;
 private final ConcurrentHashMap<Long,Instant> refreshed=new ConcurrentHashMap<>();
 public OverseasInvestmentService(OverseasMarketService market,SecurityRepository securities,JdbcTemplate jdbc,FamilyMutationAuthorization authorization,
         CurrentMembership membership,FamilyPermissionService permissions,MultiCurrencyPolicy policy,Clock clock,PlatformTransactionManager manager){
  this.market=market;this.securities=securities;this.jdbc=jdbc;this.authorization=authorization;this.membership=membership;this.permissions=permissions;this.policy=policy;this.clock=clock;transaction=new TransactionTemplate(manager);
 }
 public record Resolve(String market,String symbol){}
 public SecurityResponse resolve(Authentication auth,Resolve request){
  return resolveCatalog(auth,request,false);
 }
 /** Publishes only a provider-verified public instrument, never an account, holding or trade. */
 public SecurityResponse watch(Authentication auth,Resolve request){
  return resolveCatalog(auth,request,true);
 }
 private SecurityResponse resolveCatalog(Authentication auth,Resolve request,boolean watchOnly){
  policy.requireEnabled();var context=membership.require(auth);if(!watchOnly)permissions.requireAdmin(context);
  if(request==null||request.symbol()==null||request.market()==null)throw invalid("请提供市场和股票代码");
  String symbol=request.symbol().trim().toUpperCase(Locale.ROOT),marketName=request.market().trim().toUpperCase(Locale.ROOT);
  var directory=market.search(auth,marketName,symbol);
  if(!"READY".equals(directory.state())||directory.stale())throw new ResourceConflictException("SECURITY_DIRECTORY_NOT_READY","股票目录尚未就绪，请稍后重试");
  var instrument=directory.items().stream().filter(i->i.symbol().equals(symbol)).findFirst().orElseThrow(()->new ResourceNotFoundException("当前目录中没有这只股票"));
  if(!(marketName.equals("HK")&&instrument.currency().equals("HKD"))&&!(marketName.equals("US")&&instrument.currency().equals("USD")))throw invalid("当前支持港币港股和美元美股，此证券币种暂不支持记账");
  Security candidate=Security.overseas(instrument);
  return transaction.execute(ignored->{
   if(watchOnly)authorization.requireCurrent(auth);else authorization.requireAdmin(auth);
   jdbc.queryForObject("select id from security_catalog_state where id=1 for update",Long.class);
   Security value=securities.findByTsCode(candidate.getTsCode()).orElse(null);
   if(value==null)value=securities.saveAndFlush(candidate);else value.publishCatalog(candidate.getName());
   return SecurityResponse.from(value);
  });
 }
 public int refreshHeld(Authentication auth){
  var context=membership.require(auth);permissions.requireAdmin(context);
  List<Long> ids=jdbc.queryForList("""
    select distinct t.security_id from investment_trades t join securities s on s.id=t.security_id
    where t.household_id=? and s.market in ('HK','US')
    group by t.account_id,t.security_id having sum(case when t.trade_type in ('BUY','OPENING') then t.quantity when t.trade_type='SELL' then -t.quantity else 0 end)>0
    """,Long.class,context.householdId());
  int saved=0;for(long id:ids)try{if(refresh(id)>0)saved++;}catch(MarketProviderException|ResourceNotFoundException ignored){}
  return saved;
 }
 @org.springframework.scheduling.annotation.Scheduled(cron="0 10 7,18 * * *",zone="Asia/Shanghai")
 public void refreshScheduled(){
  for(long id:jdbc.queryForList("select distinct t.security_id from investment_trades t join securities s on s.id=t.security_id where s.market in ('HK','US')",Long.class))
   try{refresh(id);}catch(RuntimeException ignored){}
 }
 public int refresh(long id){
  Security security=securities.findById(id).orElseThrow(()->new ResourceNotFoundException("证券不存在"));
  if(!Set.of("HK","US").contains(security.getMarket()))return 0;
  Instant now=clock.instant();
  synchronized(refreshed){var prior=refreshed.get(id);if(prior!=null&&prior.plusSeconds(60).isAfter(now))return 0;refreshed.put(id,now);}
  var response=market.verifiedCandles(security.getMarket(),security.getSymbol());
  if(!security.getCurrency().equals(response.instrument().currency())||!security.getExchange().equals(response.instrument().exchange()))throw new MarketProviderException("MARKET_UPSTREAM_INVALID",false);
  return transaction.execute(ignored->{
   int count=0;
   for(var bar:response.bars()){
    LocalDate day=Instant.ofEpochMilli(bar.timestamp()).atZone(ZoneId.of(security.getTimezone())).toLocalDate();
    BigDecimal price=bar.close().setScale(6,java.math.RoundingMode.HALF_UP);
    if(price.signum()<=0)continue;
    var previous=jdbc.queryForList("select close_price from overseas_price_snapshots where security_id=? and trade_date=? order by id desc limit 1",BigDecimal.class,id,day);
    if(!previous.isEmpty()&&previous.get(0).compareTo(price)==0)continue;
    jdbc.update("insert into overseas_price_snapshots(security_id,trade_date,close_price,fetched_at) values(?,?,?,?)",id,day,price,java.sql.Timestamp.from(response.fetchedAt()));count++;
   }return count;
  });
 }
 public MarketPriceResponse price(Security security,LocalDate day){
  return jdbc.query("select trade_date,close_price,fetched_at from overseas_price_snapshots where security_id=? and trade_date<=? order by trade_date desc,id desc limit 1",
    (rs,n)->new MarketPriceResponse(security.getId(),security.getTsCode(),security.getName(),UnitPrice.format(rs.getBigDecimal(2)),QuoteSource.SINA,rs.getObject(1,LocalDate.class),rs.getTimestamp(3).toInstant(),rs.getObject(1,LocalDate.class).isBefore(day),null,security.getCurrency()),security.getId(),day)
    .stream().findFirst().orElse(new MarketPriceResponse(security.getId(),security.getTsCode(),security.getName(),null,null,null,null,true,"NO_QUOTE",security.getCurrency()));
 }
 private static RequestValidationException invalid(String text){return new RequestValidationException(Map.of("security",text));}
}
