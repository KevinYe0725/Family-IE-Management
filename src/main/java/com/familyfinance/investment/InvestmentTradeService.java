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

    public InvestmentTradeService(
            InvestmentTradeRepository trades,
            InvestmentAccountService accountService,
            SecurityService securityService,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock) {
        this.trades = trades;
        this.accountService = accountService;
        this.securityService = securityService;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.calculator = new PositionCalculator();
        this.clock = clock;
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

    @Transactional
    public InvestmentTradeMutationResponse create(Authentication authentication, InvestmentTradeRequest request) {
        var access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        ParsedTrade parsed = parseCreate(householdId, request);
        if (parsed.account().isArchived()) throw archivedAccount();
        try {
            InvestmentTrade trade = trades.saveAndFlush(new InvestmentTrade(
                    access.household(), parsed.account(), parsed.security(), parsed.type(), parsed.quantity(),
                    parsed.priceCents(), parsed.feeCents(), parsed.tradedOn(), access.membership().getUser()));
            InvestmentPosition position = replay(householdId, parsed.account().getId(), parsed.security().getId());
            return mutationResponse(trade, position);
        } catch (DataIntegrityViolationException exception) {
            throw persistenceConflict();
        }
    }

    @Transactional
    public InvestmentTradeMutationResponse update(
            Authentication authentication, long id, InvestmentTradePatchRequest request) {
        var access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        InvestmentTrade trade = findOne(householdId, id);
        requireManual(trade);
        rejectImmutablePatch(request);
        long oldAccountId = trade.getAccount().getId();
        long oldSecurityId = trade.getSecurity().getId();
        ParsedTrade parsed = parsePatch(householdId, trade, request);
        if (parsed.account().isArchived() && parsed.account().getId() != oldAccountId) {
            throw archivedAccount();
        }
        try {
            trade.update(
                    parsed.account(), parsed.security(), parsed.type(), parsed.quantity(), parsed.priceCents(),
                    parsed.feeCents(), parsed.tradedOn());
            trades.flush();
            if (oldAccountId != parsed.account().getId() || oldSecurityId != parsed.security().getId()) {
                replay(householdId, oldAccountId, oldSecurityId);
            }
            InvestmentPosition position = replay(
                    householdId, parsed.account().getId(), parsed.security().getId());
            return mutationResponse(trade, position);
        } catch (DataIntegrityViolationException exception) {
            throw persistenceConflict();
        }
    }

    @Transactional
    public void delete(Authentication authentication, long id) {
        var access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        InvestmentTrade trade = findOne(householdId, id);
        requireManual(trade);
        long accountId = trade.getAccount().getId();
        long securityId = trade.getSecurity().getId();
        trades.delete(trade);
        trades.flush();
        replay(householdId, accountId, securityId);
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
        InvestmentTradeType type = request.type();
        if (type == null) fields.put("type", "交易类型不能为空");
        BigDecimal quantity = parseQuantity(request.quantity(), type, null, fields);
        Long price = parsePositiveMoney(request.price(), "price", fields);
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
                ? trade.getAccount()
                : resolveAccount(householdId, request.accountId(), fields);
        Security security = resolveSecurity(
                request == null ? null : request.securityId(),
                request == null ? null : request.tsCode(),
                request == null ? null : request.securityName(),
                trade.getSecurity(), fields);
        InvestmentTradeType type = request == null || request.type() == null ? trade.getType() : request.type();
        BigDecimal quantity = parseQuantity(
                request == null ? null : request.quantity(), type, trade.getQuantity(), fields);
        Long price = request == null || request.price() == null
                ? trade.getPriceCents()
                : parsePositiveMoney(request.price(), "price", fields);
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
            return accountService.findOne(householdId, id);
        } catch (ResourceNotFoundException exception) {
            fields.put("accountId", "投资账户不存在");
            return null;
        }
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
        if ((type == InvestmentTradeType.DIVIDEND || type == InvestmentTradeType.FEE)
                && fee != null && fee != 0) {
            fields.put("fee", "分红和独立费用不能再填写附加费用");
        }
        if ((type == InvestmentTradeType.BUY || type == InvestmentTradeType.SELL) && quantity == null) {
            fields.putIfAbsent("quantity", "买卖数量不能为空");
        }
    }

    private InvestmentPosition replay(long householdId, long accountId, long securityId) {
        try {
            return calculator.calculate(
                    trades.findByHouseholdIdAndAccountIdAndSecurityId(
                                    householdId, accountId, securityId, REPLAY_SORT)
                            .stream().map(InvestmentTrade::toPositionTrade).toList(),
                    null);
        } catch (InsufficientHoldingException exception) {
            throw new ResourceConflictException("INSUFFICIENT_HOLDING", exception.getMessage());
        } catch (ArithmeticException exception) {
            throw new ResourceConflictException("POSITION_OVERFLOW", "持仓金额超出系统可表示范围");
        }
    }

    private static long cashImpact(InvestmentTrade trade) {
        long gross = trade.getQuantity() == null
                ? trade.getPriceCents()
                : trade.getQuantity().multiply(BigDecimal.valueOf(trade.getPriceCents()))
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
        return switch (trade.getType()) {
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
            long priceCents,
            long feeCents,
            LocalDate tradedOn) {
    }
}
