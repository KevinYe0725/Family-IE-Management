package com.familyfinance.investment;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.Money;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InvestmentTradeService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final long MAX_CENTS = 99_999_999_999L;
    private static final Pattern QUANTITY = Pattern.compile("^\\d{1,15}(?:\\.\\d{1,4})?$");
    private static final Pattern NON_NEGATIVE_MONEY = Pattern.compile("^(\\d+)(?:\\.(\\d{1,2}))?$");
    private static final BigInteger MAX_CENTS_INTEGER = BigInteger.valueOf(MAX_CENTS);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Sort LIST_SORT = Sort.by(
            Sort.Order.desc("tradedOn"), Sort.Order.desc("id"));
    private static final Sort REPLAY_SORT = Sort.by(
            Sort.Order.asc("tradedOn"), Sort.Order.asc("id"));

    private final InvestmentTradeRepository trades;
    private final InvestmentAccountService accountService;
    private final SecurityService securityService;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final PositionCalculator calculator;
    private final Clock clock;
    private final com.familyfinance.accounting.AccountingRequests requests;
    private final InvestmentAccountingService accounting;
    private final jakarta.persistence.EntityManager entities;
    private final InvestmentSetupService setup;
    @org.springframework.beans.factory.annotation.Autowired private com.familyfinance.accounting.DeploymentRevisionGate deployment;

    public InvestmentTradeService(
            InvestmentTradeRepository trades,
            InvestmentAccountService accountService,
            SecurityService securityService,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock,com.familyfinance.accounting.AccountingRequests requests,
            InvestmentAccountingService accounting,jakarta.persistence.EntityManager entities,
            InvestmentSetupService setup) {
        this.trades = trades;
        this.accountService = accountService;
        this.securityService = securityService;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.calculator = new PositionCalculator();
        this.clock = clock;
        this.requests=requests;this.accounting=accounting;this.entities=entities;this.setup=setup;
    }

    public InvestmentTradePage list(
            Authentication authentication,
            Long accountId,
            Long securityId,
            InvestmentTradeType type,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        long householdId = currentMembership.require(authentication).householdId();
        Map<String, String> fields = new LinkedHashMap<>();
        if (from != null && to != null && from.isAfter(to)) fields.put("from", "开始日期不能晚于结束日期");
        SecurityService.throwIfInvalid(fields);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Specification<InvestmentTrade> specification = (root, query, builder) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(builder.equal(root.get("household").get("id"), householdId));
            if (accountId != null) predicates.add(builder.equal(root.get("account").get("id"), accountId));
            if (securityId != null) predicates.add(builder.equal(root.get("security").get("id"), securityId));
            if (type != null) predicates.add(builder.equal(root.get("type"), type));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("tradedOn"), from));
            if (to != null) predicates.add(builder.lessThanOrEqualTo(root.get("tradedOn"), to));
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var result = trades.findAll(specification, PageRequest.of(safePage, safeSize, LIST_SORT));
        return new InvestmentTradePage(
                result.getContent().stream()
                        .map(trade -> InvestmentTradeResponse.from(trade, cashImpact(trade)))
                        .toList(),
                safePage, safeSize, result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    public InvestmentTradeResponse get(Authentication authentication, long id) {
        long householdId = currentMembership.require(authentication).householdId();
        InvestmentTrade trade = findOne(householdId, id);
        return InvestmentTradeResponse.from(trade, cashImpact(trade));
    }

    /** Internal occurrence replay; caller has already authorized and locked this household. */
    @Transactional
    InvestmentTradeResponse currentIfPresent(long householdId,long id) {
        var existing=trades.findCurrent(id,householdId);
        if(existing.isEmpty())return null;
        entities.detach(existing.get());
        return trades.findCurrent(id,householdId)
                .map(trade->InvestmentTradeResponse.from(trade,cashImpact(trade))).orElse(null);
    }

    @Transactional
    public InvestmentTradeMutationResponse create(Authentication authentication, InvestmentTradeRequest request) {
        return create(authentication,request,com.familyfinance.accounting.AccountingRequests.key(null));
    }
    @Transactional
    public InvestmentTradeMutationResponse create(Authentication authentication,InvestmentTradeRequest request,String key) {
        var access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        String digest=requests.digest("INVESTMENT_TRADE_CREATE",access.context().userId(),request);
        Long original=requests.replay(householdId,key,digest);
        if(original!=null)return replayResponse(householdId,original);
        ParsedTrade parsed = parseCreate(householdId, request);
        if (parsed.account().isArchived()) throw archivedAccount();
        var history=currentHistory(householdId,parsed.account().getId(),parsed.security().getId());
        requireAppend(history,parsed.tradedOn(),parsed.type());
        InvestmentPosition before=calculate(history);
        accounting.requireBalance(householdId,parsed.account().getId(),parsed.security().getId(),before.costCents());
        try {
            InvestmentTrade trade = new InvestmentTrade(
                    access.household(), parsed.account(), parsed.security(), parsed.type(), parsed.quantity(),
                    parsed.priceCents(), parsed.feeCents(), parsed.tradedOn(), access.membership().getUser());
            trade.confirmAccounting(parsed.type()==InvestmentTradeType.OPENING?null:parsed.account().getFundingAccountId());
            trades.saveAndFlush(trade);
            history.add(trade);
            InvestmentPosition position = calculate(history);
            accounting.post(trade,before,position,access.context().userId(),key,false);
            requests.record(householdId,key,digest,trade.getId());
            setup.recordCompleted(householdId, access.context().userId());
            return mutationResponse(trade, position);
        } catch (DataIntegrityViolationException exception) {
            throw persistenceConflict();
        }
    }

    @Transactional
    public InvestmentTradeMutationResponse update(
            Authentication authentication, long id, InvestmentTradePatchRequest request) {
        return update(authentication,id,request,com.familyfinance.accounting.AccountingRequests.key(null));
    }
    @Transactional
    public InvestmentTradeMutationResponse update(Authentication authentication,long id,InvestmentTradePatchRequest request,String key) {
        var access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        String digest=requests.digest("INVESTMENT_TRADE_UPDATE:"+id,access.context().userId(),request);
        if(requests.replay(householdId,key,digest)!=null)return replayResponse(householdId,id);
        InvestmentTrade trade = findCurrent(householdId, id);
        requireManual(trade);
        rejectImmutablePatch(request);
        long oldAccountId = trade.getAccount().getId();
        long oldSecurityId = trade.getSecurity().getId();
        var oldHistory=currentHistory(householdId,oldAccountId,oldSecurityId);
        trade=oldHistory.stream().filter(t->t.getId()==id).findFirst().orElseThrow();
        requireTail(oldHistory,id);
        InvestmentPosition oldPosition=calculate(oldHistory);
        accounting.requireBalance(householdId,oldAccountId,oldSecurityId,oldPosition.costCents());
        oldHistory.removeIf(t->t.getId()==id);
        ParsedTrade parsed = parsePatch(householdId, trade, request);
        if(parsed.account().isArchived())throw archivedAccount();
        if(oldAccountId!=parsed.account().getId()||oldSecurityId!=parsed.security().getId())
            throw new ResourceConflictException("TRADE_POSITION_IMMUTABLE","更正不能更换投资账户或证券，请撤销尾笔后重新添加");
        if(parsed.type()!=trade.getType())throw new ResourceConflictException("TRADE_TYPE_IMMUTABLE","更正不能更换交易类型，请撤销尾笔后重新添加");
        requireAppend(oldHistory,parsed.tradedOn(),parsed.type());
        InvestmentPosition before=calculate(oldHistory);
        try {
            trade.update(
                    parsed.account(), parsed.security(), parsed.type(), parsed.quantity(), parsed.priceCents(),
                    parsed.feeCents(), parsed.tradedOn());
            trades.flush();
            oldHistory.add(trade);
            InvestmentPosition position = calculate(oldHistory);
            accounting.post(trade,before,position,access.context().userId(),key,true);
            requests.record(householdId,key,digest,id);
            return mutationResponse(trade, position);
        } catch (DataIntegrityViolationException exception) {
            throw persistenceConflict();
        }
    }

    @Transactional
    public void delete(Authentication authentication, long id) {
        delete(authentication,id,com.familyfinance.accounting.AccountingRequests.key(null));
    }
    @Transactional
    public void delete(Authentication authentication,long id,String key) {
        var access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        String digest=requests.digest("INVESTMENT_TRADE_DELETE:"+id,access.context().userId(),null);
        if(requests.replay(householdId,key,digest)!=null)return;
        InvestmentTrade trade = findCurrent(householdId, id);
        requireManual(trade);
        long accountId = trade.getAccount().getId();
        long securityId = trade.getSecurity().getId();
        if(accountService.findCurrent(householdId,accountId).isArchived())throw archivedAccount();
        var history=currentHistory(householdId,accountId,securityId);
        requireTail(history,id);
        accounting.requireBalance(householdId,accountId,securityId,calculate(history).costCents());
        accounting.reverse(trade,access.context().userId(),key);
        trades.delete(trade);
        trades.flush();
        requests.record(householdId,key,digest,id);
    }

    private ParsedTrade parseCreate(long householdId, InvestmentTradeRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (request == null) {
            fields.put("request", "交易内容不能为空");
            throw new InvestmentValidationException(fields);
        }
        if (request.createdBy() != null) fields.put("createdBy", "创建者由当前登录账号确定");
        if (request.sourceType() != null || request.sourceId() != null) {
            fields.put("sourceType", "公开接口只能创建手工交易");
        }
        InvestmentAccount account = resolveAccount(householdId, request.accountId(), fields);
        Security security = resolveSecurity(
                request.securityId(), request.tsCode(), request.securityName(), null, fields);
        requireMatchingCurrency(account,security,fields);
        InvestmentTradeType type = request.type();
        if (type == null) fields.put("type", "交易类型不能为空");
        if (security != null && !security.isCatalogVerified()
                && (type == InvestmentTradeType.BUY || type == InvestmentTradeType.OPENING)) {
            throw new ResourceConflictException("SECURITY_NOT_LISTED", "请从股票搜索结果中选择证券");
        }
        BigDecimal quantity = parseQuantity(request.quantity(), type, null, fields);
        BigDecimal price = parsePrice(request.price(),type,fields);
        Long fee = parseNonNegativeMoney(request.fee(), "fee", 0L, fields);
        LocalDate tradedOn = request.tradedOn();
        validateDate(tradedOn, fields);
        validateShape(type, quantity, fee, fields);
        SecurityService.throwIfInvalid(fields);
        return new ParsedTrade(account, security, type, quantity, price, fee, tradedOn);
    }

    private ParsedTrade parsePatch(
            long householdId, InvestmentTrade trade, InvestmentTradePatchRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        InvestmentAccount account = request == null || request.accountId() == null
                ? accountService.findCurrent(householdId,trade.getAccount().getId())
                : resolveAccount(householdId, request.accountId(), fields);
        Security security = resolveSecurity(
                request == null ? null : request.securityId(),
                request == null ? null : request.tsCode(),
                request == null ? null : request.securityName(),
                trade.getSecurity(), fields);
        requireMatchingCurrency(account,security,fields);
        InvestmentTradeType type = request == null || request.type() == null ? trade.getType() : request.type();
        BigDecimal quantity = parseQuantity(
                request == null ? null : request.quantity(), type, trade.getQuantity(), fields);
        BigDecimal price = request == null || request.price() == null
                ? trade.getUnitPrice()
                : parsePrice(request.price(),type,fields);
        Long fee = request == null || request.fee() == null
                ? trade.getFeeCents()
                : parseNonNegativeMoney(request.fee(), "fee", trade.getFeeCents(), fields);
        LocalDate tradedOn = request == null || request.tradedOn() == null
                ? trade.getTradedOn()
                : request.tradedOn();
        validateDate(tradedOn, fields);
        validateShape(type, quantity, fee, fields);
        SecurityService.throwIfInvalid(fields);
        return new ParsedTrade(account, security, type, quantity, price, fee, tradedOn);
    }

    private InvestmentAccount resolveAccount(long householdId, Long id, Map<String, String> fields) {
        if (id == null) {
            fields.put("accountId", "投资账户不能为空");
            return null;
        }
        try {
            return accountService.findCurrent(householdId, id);
        } catch (ResourceNotFoundException exception) {
            fields.put("accountId", "投资账户不存在");
            return null;
        }
    }

    private static void requireMatchingCurrency(InvestmentAccount account,Security security,Map<String,String> fields) {
        if(account!=null&&security!=null&&!account.getCurrency().equals(security.getCurrency()))
            fields.put("accountId","投资账户与证券币种必须一致，请选择对应币种账户");
    }

    private Security resolveSecurity(
            Long id, String code, String name, Security current, Map<String, String> fields) {
        boolean hasCode = code != null || name != null;
        if (id != null && hasCode) {
            fields.put("securityId", "证券编号和代码名称只能填写一种");
            return null;
        }
        if (id != null) return securityService.findActive(id, fields);
        if (hasCode) return securityService.resolve(code, name, fields);
        if (current != null) return current;
        fields.put("securityId", "证券编号或代码名称不能为空");
        return null;
    }

    private void validateDate(LocalDate date, Map<String, String> fields) {
        if (date == null) fields.put("tradedOn", "交易日期不能为空");
        else if (date.isAfter(LocalDate.now(clock.withZone(SHANGHAI)))) {
            fields.put("tradedOn", "交易日期不能晚于今天");
        }
    }

    private static BigDecimal parseQuantity(
            String raw, InvestmentTradeType type, BigDecimal current, Map<String, String> fields) {
        if (type == InvestmentTradeType.DIVIDEND || type == InvestmentTradeType.FEE) {
            if (raw != null) fields.put("quantity", "分红和独立费用不能填写数量");
            return null;
        }
        if (raw == null) {
            if (current != null) return current;
            fields.put("quantity", "买卖数量不能为空");
            return null;
        }
        String value = raw.trim();
        if (!QUANTITY.matcher(value).matches()) {
            fields.put("quantity", "买卖数量必须为最多四位小数的正数");
            return null;
        }
        BigDecimal quantity = new BigDecimal(value);
        if (quantity.signum() <= 0) {
            fields.put("quantity", "买卖数量必须大于 0");
            return null;
        }
        return quantity.setScale(4);
    }

    private static Long parsePositiveMoney(String raw, String field, Map<String, String> fields) {
        try {
            return Money.parseCents(raw);
        } catch (IllegalArgumentException exception) {
            fields.put(field, exception.getMessage());
            return null;
        }
    }
    private BigDecimal parsePrice(String raw,InvestmentTradeType type,Map<String,String> fields) {
        if(type==InvestmentTradeType.DIVIDEND||type==InvestmentTradeType.FEE){
            Long cents=parsePositiveMoney(raw,"price",fields);return cents==null?null:BigDecimal.valueOf(cents,2);
        }
        try{BigDecimal price=UnitPrice.parse(raw);deployment.requireCompatibleUnitPrice(price);return price;}catch(IllegalArgumentException error){fields.put("price",error.getMessage());return null;}
    }

    private static Long parseNonNegativeMoney(
            String raw, String field, long defaultValue, Map<String, String> fields) {
        if (raw == null) return defaultValue;
        var matcher = NON_NEGATIVE_MONEY.matcher(raw.trim());
        if (!matcher.matches()) {
            fields.put(field, "金额格式必须是最多两位小数的非负数字");
            return null;
        }
        BigInteger cents = new BigInteger(matcher.group(1)).multiply(BigInteger.valueOf(100));
        if (matcher.group(2) != null) {
            String fraction = matcher.group(2);
            cents = cents.add(BigInteger.valueOf(Long.parseLong(
                    fraction.length() == 1 ? fraction + "0" : fraction)));
        }
        if (cents.compareTo(MAX_CENTS_INTEGER) > 0) {
            fields.put(field, "金额不能超过 999,999,999.99");
            return null;
        }
        return cents.longValueExact();
    }

    private static void validateShape(
            InvestmentTradeType type, BigDecimal quantity, Long fee, Map<String, String> fields) {
        if(type==InvestmentTradeType.OPENING && fee!=null && fee!=0)fields.put("fee","期初持仓不能附加交易费用");
        if ((type == InvestmentTradeType.DIVIDEND || type == InvestmentTradeType.FEE)
                && fee != null && fee != 0) {
            fields.put("fee", "分红和独立费用不能再填写附加费用");
        }
        if ((type == InvestmentTradeType.BUY || type == InvestmentTradeType.SELL) && quantity == null) {
            fields.putIfAbsent("quantity", "买卖数量不能为空");
        }
    }

    private InvestmentPosition calculate(java.util.List<InvestmentTrade> history) {
        try {
            return calculator.calculate(history.stream().map(InvestmentTrade::toPositionTrade).toList(),null);
        } catch (InsufficientHoldingException exception) {
            throw new ResourceConflictException("INSUFFICIENT_HOLDING", exception.getMessage());
        } catch (ArithmeticException exception) {
            throw new ResourceConflictException("POSITION_OVERFLOW", "持仓金额超出系统可表示范围");
        }
    }

    private static long cashImpact(InvestmentTrade trade) {
        long gross = trade.getQuantity() == null
                ? trade.getPriceCents()
                : trade.getQuantity().multiply(trade.getUnitPrice()).movePointRight(2)
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
        return switch (trade.getType()) {
            case OPENING -> 0;
            case BUY -> Math.negateExact(Math.addExact(gross, trade.getFeeCents()));
            case SELL -> Math.subtractExact(gross, trade.getFeeCents());
            case DIVIDEND -> gross;
            case FEE -> Math.negateExact(gross);
        };
    }

    private static void rejectImmutablePatch(InvestmentTradePatchRequest request) {
        if (request == null) return;
        Map<String, String> fields = new LinkedHashMap<>();
        if (request.createdBy() != null) fields.put("createdBy", "创建者不可修改");
        if (request.sourceType() != null) fields.put("sourceType", "交易来源不可修改");
        if (request.sourceId() != null) fields.put("sourceId", "外部来源编号不可修改");
        if (!fields.isEmpty()) throw new RequestValidationException(fields);
    }

    private static void requireManual(InvestmentTrade trade) {
        if (trade.getSourceType() != InvestmentTradeSourceType.MANUAL) {
            throw new ResourceConflictException("IMPORTED_TRADE_IMMUTABLE", "导入交易属于来源历史，无法修改或删除");
        }
    }

    private InvestmentTrade findOne(long householdId, long id) {
        return trades.findByIdAndHouseholdId(id, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("投资交易不存在"));
    }

    private InvestmentTrade findCurrent(long h,long id) {
        var trade=trades.findCurrent(id,h).orElseThrow(()->new ResourceNotFoundException("投资交易不存在"));
        entities.detach(trade);
        return trades.findCurrent(id,h).orElseThrow();
    }
    private java.util.List<InvestmentTrade> currentHistory(long h,long account,long security) {
        // Refresh can fall back to a snapshot select after Hibernate already holds the lock.
        // Evict just these unchanged roots, then hydrate them with another explicit locking query.
        trades.currentHistory(h,account,security).forEach(entities::detach);
        var history=new java.util.ArrayList<>(trades.currentHistory(h,account,security));
        if(history.stream().anyMatch(t->!t.isAccountingConfirmed()))
            throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","该持仓包含未确认的旧交易，不能追加、更正或删除");
        return history;
    }
    private InvestmentTradeMutationResponse replayResponse(long h,long id) {
        var trade=findCurrent(h,id);
        var history=currentHistory(h,trade.getAccount().getId(),trade.getSecurity().getId());
        return mutationResponse(history.stream().filter(t->t.getId()==id).findFirst().orElseThrow(),calculate(history));
    }
    private static void requireTail(java.util.List<InvestmentTrade> history,long id) {
        if(history.isEmpty()||history.get(history.size()-1).getId()!=id)
            throw new ResourceConflictException("HISTORICAL_TRADE_DEPENDENCY","后续交易依赖本笔成本，请从最后一笔交易开始更正或撤销");
    }
    private static void requireAppend(java.util.List<InvestmentTrade> history,LocalDate day,InvestmentTradeType type) {
        if(!history.isEmpty()&&(type==InvestmentTradeType.OPENING||day.isBefore(history.get(history.size()-1).getTradedOn())))
            throw new ResourceConflictException("HISTORICAL_TRADE_DEPENDENCY","期初只能作为首笔，新增或更正日期不能早于该持仓最后一笔");
    }

    private static InvestmentTradeMutationResponse mutationResponse(
            InvestmentTrade trade, InvestmentPosition position) {
        return new InvestmentTradeMutationResponse(
                InvestmentTradeResponse.from(trade, cashImpact(trade)),
                InvestmentPositionResponse.from(trade.getAccount().getId(), trade.getSecurity(), position));
    }

    private static ResourceConflictException archivedAccount() {
        return new ResourceConflictException("INVESTMENT_ACCOUNT_ARCHIVED", "投资账户已归档");
    }

    private static ResourceConflictException persistenceConflict() {
        return new ResourceConflictException("RESOURCE_CONFLICT", "投资交易无法保存，请刷新后重试");
    }

    private record ParsedTrade(
            InvestmentAccount account,
            Security security,
            InvestmentTradeType type,
            BigDecimal quantity,
            BigDecimal priceCents,
            long feeCents,
            LocalDate tradedOn) {
    }
}
