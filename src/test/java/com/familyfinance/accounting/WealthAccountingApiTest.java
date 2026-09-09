package com.familyfinance.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class WealthAccountingApiTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper mapper;
 @Autowired JdbcTemplate jdbc;
 @Autowired LedgerReadService ledger;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 @Autowired jakarta.persistence.EntityManager em;
 @Autowired com.familyfinance.reporting.NetWorthSnapshotService snapshots;
 MockHttpSession session;
 long household,cash,investment;
 @BeforeEach void fixture() throws Exception {
  String email=UUID.randomUUID()+"@wealth.test";
  mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"displayName\":\"Wealth\",\"password\":\"wealth-test-password\",\"mode\":\"CREATE\",\"householdName\":\"Wealth\"}")).andExpect(status().isCreated());
  session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",email).param("password","wealth-test-password")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
  household=jdbc.queryForObject("select household_id from app_users where email=?",Long.class,email);
  cash=jdbc.queryForObject("select id from financial_accounts where household_id=?",Long.class,household);
  change("/api/accounts/"+cash,"{\"openingBalance\":\"500000.00\",\"openingOn\":\"2026-01-01\"}","fund").andExpect(status().isOk());
  investment=id(send("/api/investment-accounts","{\"name\":\"Broker\",\"brokerName\":\"Local\",\"currency\":\"CNY\",\"fundingAccountId\":"+cash+"}","broker").andExpect(status().isCreated()));
  if(jdbc.queryForObject("select count(*) from securities where ts_code='600000.SH'",Long.class)==0)
   jdbc.update("insert into securities(market,ts_code,name,security_type,active,catalog_verified) values('SH','600000.SH','浦发银行','STOCK',true,true)");
  else jdbc.update("update securities set active=true,catalog_verified=true where ts_code='600000.SH'");
 }
 @Test void paidAssetMovesCashWithoutCreatingWealthAndArchiveCannotEraseIt() throws Exception {
  long a=id(send("/api/assets",asset("PURCHASE","500000.00","500000.00"),"asset").andExpect(status().isCreated()));
  assertThat(ledger.balance(household,"CASH:"+cash)).isZero();
  assertThat(ledger.balance(household,"ASSET:"+a)).isEqualTo(50000000);
  mvc.perform(delete("/api/assets/"+a).session(session).with(csrf())).andExpect(status().isConflict());
  mvc.perform(get("/api/net-worth").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.netWorth").value("500000.00"));
 }
 @Test void investmentExportKeepsFeeHeavySaleNetAmountsNumeric() throws Exception {
  trade("BUY","100","0.01","0.00","2026-01-02","small-buy").andExpect(status().isCreated());
  trade("SELL","100","0.01","2.00","2026-01-03","fee-heavy-sale").andExpect(status().isCreated());
  assertThat(ledger.balance(household,"CASH:"+cash)).isEqualTo(49999800);
  String csv=mvc.perform(get("/api/investment-trades/export.csv").session(session))
   .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  String[] sale=csv.lines().filter(line->line.contains(",SELL,")).findFirst().orElseThrow().split(",",-1);
  assertThat(sale[10]).isEqualTo("-1.00");
  assertThat(sale[11]).isEqualTo("-1.00");
  assertThat(ledger.balances(household)).isEqualTo(ledger.reconstructedBalances(household));
 }
 @Test void valuationIsNoncashAndDisposalPostsProceedsAndLoss() throws Exception {
  long a=id(send("/api/assets",asset("OPENING",null,"1000.00"),"asset").andExpect(status().isCreated()));
  send("/api/assets/"+a+"/valuations","{\"valuedOn\":\"2026-01-03\",\"value\":\"1200.00\"}","v1").andExpect(status().isCreated());
  send("/api/assets/"+a+"/valuations","{\"valuedOn\":\"2026-01-03\",\"value\":\"1100.00\"}","v2").andExpect(status().isCreated());
  assertThat(jdbc.queryForObject("select count(*) from asset_valuations where asset_id=?",Long.class,a)).isEqualTo(3);
  send("/api/assets/"+a+"/valuations","{\"valuedOn\":\"2026-01-02\",\"value\":\"900.00\"}","past").andExpect(status().isConflict());
  for(int i=0;i<2;i++)send("/api/assets/"+a+"/dispose","{\"disposedOn\":\"2026-01-04\",\"proceeds\":\"1000.00\",\"cashAccountId\":"+cash+"}","dispose").andExpect(status().isOk());
  assertThat(ledger.balance(household,"ASSET:"+a)).isZero();
  assertThat(ledger.balance(household,"CASH:"+cash)).isEqualTo(50100000);
  mvc.perform(get("/api/net-worth?asOf=2026-01-02").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.netWorth").value("501000.00"));
  mvc.perform(get("/api/dashboard?month=2026-01").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.expense").value("0.00"))
   .andExpect(jsonPath("$.data.summary.cashIn").value("1000.00"));
  mvc.perform(get("/api/budgets/expense-summary?periodMonth=2026-01").session(session)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.expense").value("100.00"));
 }
 @Test void buySellAndFeesReconcileWeightedCostAndKeepSoldRealizedProfit() throws Exception {
  long buy=idTrade(trade("BUY","10","100.00","10.00","2026-01-02","buy").andExpect(status().isCreated()));
  assertThat(ledger.balance(household,"CASH:"+cash)).isEqualTo(49899000);
  trade("SELL","4","120.00","2.00","2026-01-03","sell1").andExpect(status().isCreated()).andExpect(jsonPath("$.data.position.cost").value("606.00"));
  change("/api/investment-trades/"+buy,"{\"price\":\"90.00\"}","past").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("HISTORICAL_TRADE_DEPENDENCY"));
  trade("SELL","7","120.00","0.00","2026-01-04","over").andExpect(status().isConflict());
  trade("DIVIDEND",null,"20.00",null,"2026-01-04","dividend").andExpect(status().isCreated());
  trade("FEE",null,"3.00",null,"2026-01-05","fee").andExpect(status().isCreated());
  trade("SELL","6","110.00","1.00","2026-01-06","sell2").andExpect(status().isCreated());
  assertThat(ledger.balance(household,"CASH:"+cash)).isEqualTo(50014400);
  mvc.perform(get("/api/portfolio").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totals.realizedProfit").value("144.00"));
 }
 @Test void openingHoldingUsesEquityAndMissingQuoteUsesExplicitCostEstimate() throws Exception {
  trade("OPENING","10","100.00","0.00","2026-01-02","opening").andExpect(status().isCreated()).andExpect(jsonPath("$.data.trade.cashImpact").value("0.00"));
  assertThat(ledger.balance(household,"CASH:"+cash)).isEqualTo(50000000);
  mvc.perform(get("/api/net-worth?asOf=2026-01-02").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.netWorth").value("501000.00"));
  mvc.perform(get("/api/portfolio").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.positions[0].marketValue").doesNotExist()).andExpect(jsonPath("$.data.positions[0].valuationStatus").value("COST_ESTIMATE"));
 }
 @Test void tailCorrectionsAndDeletionRetainAuditAndRejectArchivedOriginalCash() throws Exception {
  long t=idTrade(trade("BUY","10","100.00","0.00","2026-01-02","buy").andExpect(status().isCreated()));
  for(int i=0;i<2;i++)change("/api/investment-trades/"+t,"{\"price\":\"90.00\"}","correct").andExpect(status().isOk());
  assertThat(ledger.balance(household,"CASH:"+cash)).isEqualTo(49910000);
  for(int i=0;i<2;i++)mvc.perform(delete("/api/investment-trades/"+t).session(session).with(csrf()).header("Idempotency-Key","delete")).andExpect(status().isNoContent());
  mvc.perform(get("/api/accounting/history?sourceType=INVESTMENT_TRADE&sourceId="+t).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(4));
  assertThat(ledger.balance(household,"CASH:"+cash)).isEqualTo(50000000);
 }
 @Test void insufficientPurchaseRollsBackAndLegacyReportsAreExplicitlyIncomplete() throws Exception {
  send("/api/assets",asset("PURCHASE","500001.00","500001.00"),"short").andExpect(status().isConflict());
  assertThat(jdbc.queryForObject("select count(*) from assets where household_id=?",Long.class,household)).isZero();
  jdbc.update("update financial_accounts set opening_confirmed=false,opening_on=null where id=?",cash);
  mvc.perform(get("/api/net-worth").session(session)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_NOT_INITIALIZED"));
 }
 String asset(String mode,String price,String value){return "{\"name\":\"Asset\",\"type\":\"OTHER\",\"currentValue\":\""+value+"\",\"accountingMode\":\""+mode+"\",\"accountingOn\":\"2026-01-02\""+(price==null?"":",\"purchaseValue\":\""+price+"\",\"acquiredOn\":\"2026-01-02\",\"fundingAccountId\":"+cash)+"}";}
 @Test void originalCashAndInvestmentArchiveGuardsPreventRestoringHiddenHoldings()throws Exception {
  long t=idTrade(trade("BUY","5000","100.00","0.00","2026-01-02","buy-all").andExpect(status().isCreated()));
  long next=id(send("/api/accounts","{\"name\":\"Next\",\"type\":\"CASH\",\"currency\":\"CNY\",\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}","next").andExpect(status().isCreated()));
  change("/api/investment-accounts/"+investment,"{\"fundingAccountId\":"+next+"}","fund-next").andExpect(status().isOk());
  mvc.perform(delete("/api/accounts/"+cash).session(session).with(csrf())).andExpect(status().isNoContent());
  mvc.perform(delete("/api/investment-trades/"+t).session(session).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
  change("/api/investment-trades/"+t,"{\"price\":\"99.00\"}","refund-old").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
  long sell=idTrade(trade("SELL","5000","100.00","0.00","2026-01-03","sell-all").andExpect(status().isCreated()));
  assertThat(ledger.balance(household,"CASH:"+next)).isEqualTo(50000000);
  mvc.perform(delete("/api/investment-accounts/"+investment).session(session).with(csrf())).andExpect(status().isNoContent());
  mvc.perform(delete("/api/investment-trades/"+sell).session(session).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVESTMENT_ACCOUNT_ARCHIVED"));
  change("/api/investment-trades/"+sell,"{\"price\":\"101.00\"}","restore").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVESTMENT_ACCOUNT_ARCHIVED"));
  mvc.perform(get("/api/net-worth?asOf=2026-01-02").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.netWorth").value("500000.00"));
 }
 @Test void openingIsFirstOnlyAndCashDatesAndTailChronologyAreEnforced()throws Exception {
  change("/api/accounts/"+cash,"{\"openingOn\":\"2026-01-03\"}","later").andExpect(status().isOk());
  trade("BUY","1","100.00","0.00","2026-01-02","too-early").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ACTIVITY_BEFORE_OPENING"));
  trade("OPENING","1","100.00","0.00","2026-01-02","opening").andExpect(status().isCreated());
  long buy=idTrade(trade("BUY","1","100.00","0.00","2026-01-03","buy").andExpect(status().isCreated()));
  trade("OPENING","1","100.00","0.00","2026-01-04","second-opening").andExpect(status().isConflict());
  change("/api/investment-trades/"+buy,"{\"tradedOn\":\"2026-01-01\"}","before-first").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("HISTORICAL_TRADE_DEPENDENCY"));
  assertThat(ledger.balance(household,"CASH:"+cash)).isEqualTo(49990000);
 }
 @Test void loanPrincipalIsCashOutButOnlyInterestIsExpenseBudgetAndAnnualTotal()throws Exception {
  long member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);
  long actor=jdbc.queryForObject("select id from app_users where household_id=?",Long.class,household);
  long category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
  long loan=id(send("/api/loans","{\"name\":\"Loan\",\"type\":\"OTHER\",\"memberId\":"+member+",\"assignedUserId\":"+actor+",\"paymentAccountId\":"+cash+",\"paymentCategoryId\":"+category+",\"principal\":\"1000.00\",\"annualRate\":0.1,\"termMonths\":1,\"repaymentMethod\":\"CUSTOM\",\"startOn\":\"2026-01-01\",\"fundingMode\":\"DISBURSEMENT\",\"accountingOn\":\"2026-01-02\",\"disbursementAccountId\":"+cash+",\"customSchedule\":[{\"dueOn\":\"2026-01-03\",\"principal\":\"1000.00\",\"interest\":\"100.00\"}]}","loan").andExpect(status().isCreated()));
  long installment=jdbc.queryForObject("select id from loan_installments where loan_id=?",Long.class,loan);
  send("/api/loan-installments/"+installment+"/confirm","{\"paidOn\":\"2026-01-03\"}","pay").andExpect(status().isOk());
  send("/api/budgets","{\"periodMonth\":\"2026-01\",\"scopeType\":\"CATEGORY\",\"categoryId\":"+category+",\"amount\":\"1000.00\"}","budget").andExpect(status().isCreated());
  mvc.perform(get("/api/dashboard?month=2026-01").session(session)).andExpect(status().isOk())
   .andExpect(jsonPath("$.data.summary.expense").value("1100.00")).andExpect(jsonPath("$.data.summary.income").value("0.00"))
   .andExpect(jsonPath("$.data.summary.cashIn").value("1000.00")).andExpect(jsonPath("$.data.summary.cashOut").value("1100.00")).andExpect(jsonPath("$.data.summary.principalPaid").value("1000.00"));
  mvc.perform(get("/api/budgets/usage?periodMonth=2026-01").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].spent").value("100.00"));
  mvc.perform(get("/api/plugins/annual-stats?year=2026").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.expense").value("1100.00"));
  mvc.perform(get("/api/net-worth?asOf=2026-01-02").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.netWorth").value("500000.00")).andExpect(jsonPath("$.data.liability").value("1000.00"));
  mvc.perform(get("/api/net-worth?asOf=2026-01-03").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.netWorth").value("499900.00")).andExpect(jsonPath("$.data.liability").value("0.00"));
 }
 @Test void historicalSnapshotHasNoFuturePricesAndLabelsCostEstimate()throws Exception {
  trade("OPENING","10","100.00","0.00","2026-01-02","holding").andExpect(status().isCreated());
  long security=jdbc.queryForObject("select security_id from investment_trades where household_id=?",Long.class,household);
  long actor=jdbc.queryForObject("select id from app_users where household_id=?",Long.class,household);
  jdbc.update("insert into manual_price_overrides(household_id,security_id,effective_on,price_cents,created_by) values(?,?,?,?,?)",household,security,java.sql.Date.valueOf("2026-01-04"),20000,actor);
  mvc.perform(get("/api/portfolio?asOf=2026-01-03").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totals.estimatedValue").value("1000.00"));
  mvc.perform(get("/api/portfolio?asOf=2026-01-04").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.totals.marketValue").value("2000.00"));
  assertThat(snapshots.generate(household,java.time.LocalDate.of(2026,1,3)).getNetWorthCents()).isEqualTo(50100000);
  mvc.perform(get("/api/net-worth").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.history[0].valuationEstimated").value(true));
 }
 @Test void earlierSnapshotStillSeesCurrentTradesCostAndReplayedSources()throws Exception {
  trade("BUY","10","100.00","0.00","2026-01-02","first").andExpect(status().isCreated());
  earlier(()->trade("BUY","10","200.00","0.00","2026-01-03","second").andExpect(status().isCreated()),
    ()->trade("SELL","10","200.00","0.00","2026-01-04","sell").andExpect(status().isCreated()).andExpect(jsonPath("$.data.position.cost").value("1500.00")));
  assertThat(ledger.balance(household,"CASH:"+cash)).isEqualTo(49900000);
  earlier(()->trade("DIVIDEND",null,"20.00",null,"2026-01-05","replay").andExpect(status().isCreated()),
    ()->trade("DIVIDEND",null,"20.00",null,"2026-01-05","replay").andExpect(status().isCreated()).andExpect(jsonPath("$.data.trade.price").value("20.00")));
 }
 @Test void earlierSnapshotUsesLatestAssetValueAndRejectsHistoricalValuation()throws Exception {
  long a=id(send("/api/assets",asset("OPENING",null,"1000.00"),"asset").andExpect(status().isCreated()));
  earlier(()->send("/api/assets/"+a+"/valuations","{\"valuedOn\":\"2026-01-04\",\"value\":\"1200.00\"}","first-value").andExpect(status().isCreated()),
    ()->send("/api/assets/"+a+"/valuations","{\"valuedOn\":\"2026-01-04\",\"value\":\"1300.00\"}","second-value").andExpect(status().isCreated()));
  assertThat(ledger.balance(household,"ASSET:"+a)).isEqualTo(130000);
  earlier(()->send("/api/assets/"+a+"/valuations","{\"valuedOn\":\"2026-01-06\",\"value\":\"1400.00\"}","latest-value").andExpect(status().isCreated()),
    ()->send("/api/assets/"+a+"/valuations","{\"valuedOn\":\"2026-01-05\",\"value\":\"1100.00\"}","old-value").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_ACCOUNTING_CHRONOLOGY")));
 }
 void earlier(Checked outside,Checked inside)throws Exception {
  var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  boolean mysql=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c->"MySQL".equals(c.getMetaData().getDatabaseProductName())));
  if(mysql)tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  try{tx.executeWithoutResult(s->{
   jdbc.queryForObject("select count(*) from investment_trades where household_id=?",Long.class,household);
   em.createQuery("select a from Asset a where a.household.id=:h",com.familyfinance.asset.Asset.class).setParameter("h",household).getResultList();
   em.find(com.familyfinance.investment.InvestmentAccount.class,investment);
   em.createQuery("select t from InvestmentTrade t where t.household.id=:h",com.familyfinance.investment.InvestmentTrade.class).setParameter("h",household).getResultList();
   try{pool.submit(()->{outside.run();return null;}).get(10,java.util.concurrent.TimeUnit.SECONDS);inside.run();if(s.isRollbackOnly())s.setRollbackOnly();}
   catch(Exception e){throw new RuntimeException(e);}
  });}catch(org.springframework.transaction.UnexpectedRollbackException expected) {
   // An expected rejected HTTP command marks its enclosing test transaction rollback-only.
  }finally{pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
 }
 @FunctionalInterface interface Checked{void run()throws Exception;}
 @Test void investmentArchiveRejectsLedgerCostDriftEvenWhenTradeQuantityIsZero()throws Exception {
  trade("BUY","1","100.00","0.00","2026-01-02","buy").andExpect(status().isCreated());
  trade("SELL","1","100.00","0.00","2026-01-03","sell").andExpect(status().isCreated());
  jdbc.update("update ledger_accounts set balance_amount=1.00 where household_id=? and account_code like 'POSITION:%'",household);
  mvc.perform(delete("/api/investment-accounts/"+investment).session(session).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNTING_BALANCE_MISMATCH"));
 }
 @Test void disposalAtRevaluedAmountPreservesCumulativeAssetValuationBridge()throws Exception {
  long a=id(send("/api/assets",asset("PURCHASE","100.00","100.00"),"purchase100").andExpect(status().isCreated()));
  send("/api/assets/"+a+"/valuations","{\"valuedOn\":\"2026-02-01\",\"value\":\"200.00\"}","revalue200").andExpect(status().isCreated());
  send("/api/assets/"+a+"/dispose","{\"disposedOn\":\"2026-03-01\",\"proceeds\":\"200.00\",\"cashAccountId\":"+cash+"}","dispose200").andExpect(status().isOk()).andExpect(jsonPath("$.data.disposalBookGain").value("0.00"));
  mvc.perform(get("/api/net-worth?asOf=2026-03-01").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.netWorth").value("500100.00")).andExpect(jsonPath("$.data.cumulativeAssetValuationChange").value("100.00"));
  mvc.perform(get("/api/dashboard?month=2026-03").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.income").value("0.00"));
 }
 @Test void differingPurchaseValuationIsAnExplicitNoncashGainAndZeroDisposalIsAudited()throws Exception {
  long a=id(send("/api/assets",asset("PURCHASE","500000.00","550000.00"),"buy-value").andExpect(status().isCreated()));
  mvc.perform(get("/api/dashboard?month=2026-01").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.summary.income").value("0.00")).andExpect(jsonPath("$.data.summary.noncashValuationChange").value("50000.00"));
  assertThat(ledger.balance(household,"ASSET:"+a)).isEqualTo(55000000);
  long zero=id(send("/api/assets",asset("OPENING",null,"0.00"),"zero").andExpect(status().isCreated()));
  long before=jdbc.queryForObject("select count(*) from ledger_journals where household_id=?",Long.class,household);
  send("/api/assets/"+zero+"/dispose","{\"disposedOn\":\"2026-01-03\",\"proceeds\":\"0.00\"}","dispose-zero").andExpect(status().isOk()).andExpect(jsonPath("$.data.disposedOn").value("2026-01-03")).andExpect(jsonPath("$.data.disposedBy").isNumber());
  assertThat(jdbc.queryForObject("select count(*) from ledger_journals where household_id=?",Long.class,household)).isEqualTo(before);
  send("/api/assets/"+zero+"/valuations","{\"valuedOn\":\"2026-01-04\",\"value\":\"1.00\"}","revive").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ASSET_ARCHIVED"));
 }
 @Test void earlierSnapshotUsesFutureFundingAccountButOriginalCashForCorrection()throws Exception {
  long buy=idTrade(trade("BUY","1","100.00","0.00","2026-01-02","first-buy").andExpect(status().isCreated()));
  long next=id(send("/api/accounts","{\"name\":\"Next cash\",\"type\":\"CASH\",\"currency\":\"CNY\",\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}","next-cash").andExpect(status().isCreated()));
  earlier(()->change("/api/investment-accounts/"+investment,"{\"fundingAccountId\":"+next+"}","switch-default").andExpect(status().isOk()),
    ()->trade("SELL","1","100.00","0.00","2026-01-03","future-sell").andExpect(status().isCreated()).andExpect(jsonPath("$.data.trade.cashAccountId").value(next)));
  assertThat(ledger.balance(household,"CASH:"+cash)).isEqualTo(49990000);
  assertThat(ledger.balance(household,"CASH:"+next)).isEqualTo(10000);
  mvc.perform(get("/api/investment-trades/"+buy).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.cashAccountId").value(cash));
 }
 @Test void correctedHistoryRecomputesIncomeReplacementAndDeletionWithoutUpdatingStoredSnapshot() throws Exception {
  change("/api/accounts/"+cash,"{\"openingBalance\":\"100.00\"}","small-opening").andExpect(status().isOk());
  long income=postedTransaction("INCOME","50.00","2026-01-02",null,"income");
  snapshots.generate(household,java.time.LocalDate.of(2026,1,3));
  change("/api/transactions/"+income,"{\"amount\":\"20.00\"}","correct-income").andExpect(status().isOk());
  assertReadOnlyHistory("120.00",false,0);
  mvc.perform(delete("/api/transactions/"+income).session(session).with(csrf())).andExpect(status().isNoContent());
  assertReadOnlyHistory("100.00",false,0);
  assertThat(jdbc.queryForObject("select net_worth_cents from net_worth_snapshots where household_id=?",Long.class,household)).isEqualTo(15000);
 }
 @Test void correctedHistoryRecomputesNoncashValuationAndDateQualifiedQuotesWithoutWritingProvenance() throws Exception {
  long a=id(send("/api/assets",asset("OPENING",null,"1000.00"),"asset").andExpect(status().isCreated()));
  trade("OPENING","10","100.00","0.00","2026-01-02","holding").andExpect(status().isCreated());
  snapshots.generate(household,java.time.LocalDate.of(2026,1,3));
  send("/api/assets/"+a+"/valuations","{\"valuedOn\":\"2026-01-03\",\"value\":\"1200.00\"}","value").andExpect(status().isCreated());
  long security=jdbc.queryForObject("select security_id from investment_trades where household_id=?",Long.class,household);
  long actor=jdbc.queryForObject("select id from app_users where household_id=?",Long.class,household);
  jdbc.update("insert into manual_price_overrides(household_id,security_id,effective_on,price_cents,created_by) values(?,?,?,?,?)",household,security,java.sql.Date.valueOf("2026-01-04"),20000,actor);
  assertReadOnlyHistory("502200.00",true,1);
  mvc.perform(get("/api/net-worth?asOf=2026-01-04").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.investment.missingPrice").value(false));
  jdbc.update("insert into manual_price_overrides(household_id,security_id,effective_on,price_cents,created_by) values(?,?,?,?,?)",household,security,java.sql.Date.valueOf("2026-01-03"),15000,actor);
  assertReadOnlyHistory("502700.00",false,0);
  assertThat(jdbc.queryForMap("select net_worth_cents,valuation_estimated,unpriced_positions from net_worth_snapshots where household_id=?",household))
    .containsEntry("net_worth_cents",50200000L).containsEntry("valuation_estimated",true).containsEntry("unpriced_positions",1);
 }
 void assertReadOnlyHistory(String expected,boolean estimated,int unpriced) {
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
  tx.setReadOnly(true);
  tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
  tx.executeWithoutResult(s->{
   try { mvc.perform(get("/api/net-worth?asOf=2026-01-03").session(session)).andExpect(status().isOk())
     .andExpect(jsonPath("$.data.netWorth").value(expected)).andExpect(jsonPath("$.data.history[0].netWorth").value(expected))
     .andExpect(jsonPath("$.data.history[0].valuationEstimated").value(estimated))
     .andExpect(jsonPath("$.data.history[0].unpricedPositions").value(unpriced))
     .andExpect(jsonPath("$.data.history[0].accountingBasis").value("LEDGER_AS_OF"));
   } catch(Exception e){ throw new RuntimeException(e); }
  });
 }
 @Test void parentBudgetIncludesPostedChildExpenseAndNotifiesTheSameAtLimitScope() throws Exception {
  long parent=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
  long child=id(send("/api/categories","{\"name\":\"Child\",\"kind\":\"EXPENSE\",\"color\":\"#112233\",\"parentId\":"+parent+"}","child").andExpect(status().isCreated()));
  var today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
  long budget=id(send("/api/budgets","{\"periodMonth\":\""+java.time.YearMonth.from(today)+"\",\"scopeType\":\"CATEGORY\",\"categoryId\":"+parent+",\"amount\":\"100.00\"}","budget").andExpect(status().isCreated()));
  postedTransaction("EXPENSE","100.00",today.toString(),child,"expense");
  mvc.perform(get("/api/budgets/usage?periodMonth="+java.time.YearMonth.from(today)+"&rollupCategories=true").session(session)).andExpect(status().isOk())
    .andExpect(jsonPath("$.data[0].spent").value("100.00")).andExpect(jsonPath("$.data[0].status").value("AT_LIMIT"));
  for(int i=0;i<2;i++)send("/api/notifications/generate","{}","notify"+i).andExpect(status().isOk());
  mvc.perform(get("/api/notifications").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].type").value("BUDGET_LIMIT"));
  assertThat(jdbc.queryForObject("select count(*) from notifications where household_id=? and reference_type='BUDGET' and reference_id=?",Long.class,household,budget)).isEqualTo(1);
 }
 @Test void investmentAccountPatchOmitsImmutableCurrencyAndRetainsHistoricalFunding() throws Exception {
  long buy=idTrade(trade("BUY","1","100.00","0.00","2026-01-02","buy").andExpect(status().isCreated()));
  long next=id(send("/api/accounts","{\"name\":\"Next\",\"type\":\"CASH\",\"currency\":\"CNY\",\"openingBalance\":\"0.00\",\"openingOn\":\"2026-01-01\"}","next").andExpect(status().isCreated()));
  change("/api/investment-accounts/"+investment,"{\"name\":\"Renamed\",\"brokerName\":\"Local\",\"fundingAccountId\":"+next+"}","patch").andExpect(status().isOk()).andExpect(jsonPath("$.data.fundingAccountId").value(next));
  change("/api/investment-accounts/"+investment,"{\"currency\":\"CNY\"}","currency").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fields.currency").exists());
  mvc.perform(get("/api/investment-trades/"+buy).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.data.cashAccountId").value(cash));
 }
 long postedTransaction(String kind,String amount,String day,Long category,String key) throws Exception {
  long member=jdbc.queryForObject("select id from family_members where household_id=?",Long.class,household);
  long cat=category!=null?category:jdbc.queryForObject("select min(id) from categories where household_id=? and kind=?",Long.class,household,kind);
  return id(send("/api/transactions","{\"kind\":\""+kind+"\",\"amount\":\""+amount+"\",\"occurredOn\":\""+day+"\",\"accountId\":"+cash+",\"memberId\":"+member+",\"categoryId\":"+cat+"}",key).andExpect(status().isCreated()));
 }
 @Test void databaseRejectsPartialLoanAndAllocationTuplesWhileKeepingLegacyAndValidRows() throws Exception {
  long category=jdbc.queryForObject("select min(id) from categories where household_id=? and kind='EXPENSE'",Long.class,household);
  long actor=jdbc.queryForObject("select id from app_users where household_id=?",Long.class,household);
  long loan=id(send("/api/loans","{\"name\":\"Integrity\",\"type\":\"OTHER\",\"assignedUserId\":"+actor+",\"paymentAccountId\":"+cash+",\"paymentCategoryId\":"+category+",\"principal\":\"1000.00\",\"annualRate\":0,\"termMonths\":1,\"repaymentMethod\":\"EQUAL_PAYMENT\",\"startOn\":\"2026-01-01\",\"fundingMode\":\"OPENING\",\"accountingOn\":\"2026-01-01\"}","loan").andExpect(status().isCreated()));
  jdbc.update("update loans set funding_mode=null,accounting_on=null,disbursement_account_id=null where id=?",loan);
  for(String invalid:new String[]{"accounting_on='2026-01-01'", "disbursement_account_id="+cash, "accounting_on='2026-01-01',disbursement_account_id="+cash})
   org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("update loans set "+invalid+" where id=?",loan)).isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(error->assertThat(error.getMessage()).containsIgnoringCase("check"));
  jdbc.update("update loans set funding_mode='DISBURSEMENT',accounting_on='2026-01-01',disbursement_account_id=? where id=?",cash,loan);
  jdbc.update("update loans set funding_mode='OPENING',disbursement_account_id=null where id=?",loan);
  long payment=postedTransaction("EXPENSE","1.00","2026-01-02",category,"payment-fixture");
  jdbc.update("update financial_transactions set source_type='LOAN_PAYMENT',source_id=?,loan_principal_cents=100,loan_interest_cents=0 where id=?",loan,payment);
  for(String invalid:new String[]{"loan_principal_cents=null", "loan_interest_cents=null", "loan_principal_cents=-1,loan_interest_cents=101", "loan_interest_cents=1"})
   org.assertj.core.api.Assertions.assertThatThrownBy(()->jdbc.update("update financial_transactions set "+invalid+" where id=?",payment)).isInstanceOf(org.springframework.dao.DataAccessException.class).satisfies(error->assertThat(error.getMessage()).containsIgnoringCase("check"));
  jdbc.update("update financial_transactions set source_type='MANUAL',source_id=null,loan_principal_cents=null,loan_interest_cents=null where id=?",payment);
  assertThat(jdbc.queryForObject("select count(*) from loans where id=?",Long.class,loan)).isEqualTo(1);
 }
 @Test void historyRemainsBoundedHouseholdScopedAndExcludesLegacyRows() throws Exception {
  long other=household;
  snapshots.generate(other,java.time.LocalDate.of(2026,1,28));
  fixture();
  for(int day=1;day<=26;day++)snapshots.generate(household,java.time.LocalDate.of(2026,1,day));
  jdbc.update("update net_worth_snapshots set accounting_basis='LEGACY' where household_id=? and snapshot_on='2026-01-26'",household);
  mvc.perform(get("/api/net-worth").session(session)).andExpect(status().isOk())
    .andExpect(jsonPath("$.data.history.length()").value(23))
    .andExpect(jsonPath("$.data.history[0].snapshotOn").value("2026-01-25"))
    .andExpect(jsonPath("$.data.history[22].snapshotOn").value("2026-01-03"));
  assertThat(jdbc.queryForObject("select count(*) from net_worth_snapshots where household_id=?",Long.class,household)).isEqualTo(26);
 }
 @Test void dailySnapshotsSkipIncompleteHouseholdAndContinueWithReadyHousehold()throws Exception {
  long incomplete=household;
  jdbc.update("update financial_accounts set opening_confirmed=false,opening_on=null where id=?",cash);
  fixture();
  snapshots.generateDaily();
  assertThat(jdbc.queryForObject("select count(*) from net_worth_snapshots where household_id=?",Long.class,incomplete)).isZero();
  assertThat(jdbc.queryForObject("select count(*) from net_worth_snapshots where household_id=?",Long.class,household)).isEqualTo(1);
 }
 ResultActions trade(String type,String quantity,String price,String fee,String day,String key)throws Exception{return send("/api/investment-trades","{\"accountId\":"+investment+",\"tsCode\":\"600000.SH\",\"securityName\":\"浦发银行\",\"type\":\""+type+"\",\"price\":\""+price+"\",\"tradedOn\":\""+day+"\""+(quantity==null?"":",\"quantity\":\""+quantity+"\"")+(fee==null?"":",\"fee\":\""+fee+"\"")+"}",key);}
 ResultActions send(String url,String body,String key)throws Exception{return mvc.perform(post(url).session(session).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body));}
 ResultActions change(String url,String body,String key)throws Exception{return mvc.perform(patch(url).session(session).with(csrf()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body));}
 long id(ResultActions result)throws Exception{return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data").path("id").asLong();}
 long idTrade(ResultActions result)throws Exception{return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data").path("trade").path("id").asLong();}
}
