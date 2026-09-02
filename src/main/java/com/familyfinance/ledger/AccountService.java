package com.familyfinance.ledger;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.math.BigInteger;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final BigInteger MAX_OPENING_BALANCE_CENTS = BigInteger.valueOf(99_999_999_999L);

    private final FinancialAccountRepository accounts;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final Clock clock;

    public AccountService(
            FinancialAccountRepository accounts,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock) {
        this.accounts = accounts;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
    }

    public AccountPage list(Authentication authentication, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var result = accounts.findByHouseholdIdAndArchivedAtIsNull(
                householdId,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
        return new AccountPage(
                result.getContent().stream().map(AccountResponse::from).toList(),
                safePage,
                safeSize,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }

    public AccountResponse get(Authentication authentication, long accountId) {
        long householdId = currentMembership.require(authentication).householdId();
        return AccountResponse.from(findOne(householdId, accountId));
    }

    @Transactional
    public AccountResponse create(Authentication authentication, AccountCreateRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        Map<String, String> fields = new LinkedHashMap<>();
        String name = normalizeRequiredName(request == null ? null : request.name(), fields);
        AccountType type = requireType(request == null ? null : request.type(), fields);
        String currency = requireCurrency(request == null ? null : request.currency(), fields);
        Long openingBalance = parseOpeningBalance(request == null ? null : request.openingBalance(), fields);
        throwIfInvalid(fields);
        validateUnique(access.context().householdId(), name, null);
        try {
            FinancialAccount account = accounts.saveAndFlush(new FinancialAccount(
                    access.household(), name, type, currency, openingBalance));
            return AccountResponse.from(account);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
    }

    @Transactional
    public AccountResponse update(Authentication authentication, long accountId, AccountPatchRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        FinancialAccount account = findOne(householdId, accountId);
        if (account.isArchived()) {
            throw new ResourceConflictException("ACCOUNT_ARCHIVED", "账户已归档");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        String name = request == null || request.name() == null
                ? account.getName()
                : normalizeRequiredName(request.name(), fields);
        AccountType type = request == null || request.type() == null ? account.getType() : request.type();
        String currency = request == null || request.currency() == null
                ? account.getCurrency()
                : requireCurrency(request.currency(), fields);
        Long openingBalance = request == null || request.openingBalance() == null
                ? account.getOpeningBalanceCents()
                : parseOpeningBalance(request.openingBalance(), fields);
        throwIfInvalid(fields);
        validateUnique(householdId, name, accountId);
        try {
            account.update(name, type, currency, openingBalance);
            accounts.flush();
            return AccountResponse.from(account);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
    }

    @Transactional
    public void archive(Authentication authentication, long accountId) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        FinancialAccount account = findOne(householdId, accountId);
        if (account.isArchived()) {
            return;
        }
        if (accounts.countActiveRecurringReferences(householdId, accountId) > 0) {
            throw new ResourceConflictException("RESOURCE_IN_USE", "账户仍被有效周期规则使用，无法归档");
        }
        account.archive(clock.instant());
        accounts.flush();
    }

    private FinancialAccount findOne(long householdId, long accountId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("账户不存在"));
    }

    private void validateUnique(long householdId, String name, Long accountId) {
        boolean duplicate = accountId == null
                ? accounts.existsByHouseholdIdAndName(householdId, name)
                : accounts.existsByHouseholdIdAndNameAndIdNot(householdId, name, accountId);
        if (duplicate) {
            throw duplicateName();
        }
    }

    private static String normalizeRequiredName(String rawName, Map<String, String> fields) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            fields.put("name", "账户名称不能为空");
        } else if (name.length() > 100) {
            fields.put("name", "账户名称长度不能超过 100 个字符");
        }
        return name;
    }

    private static AccountType requireType(AccountType type, Map<String, String> fields) {
        if (type == null) {
            fields.put("type", "账户类型不能为空");
        }
        return type;
    }

    private static String requireCurrency(String rawCurrency, Map<String, String> fields) {
        String currency = rawCurrency == null ? "" : rawCurrency.trim().toUpperCase(java.util.Locale.ROOT);
        if (!FinancialAccount.STAGE_TWO_CURRENCY.equals(currency)) {
            fields.put("currency", "第二阶段账户币种只能是 CNY");
        }
        return currency;
    }

    private static Long parseOpeningBalance(String rawAmount, Map<String, String> fields) {
        String amount = rawAmount == null ? "" : rawAmount.trim();
        if (!amount.matches("^-?\\d+(?:\\.\\d{1,2})?$")) {
            fields.put("openingBalance", "金额格式必须是最多两位小数的数字");
            return null;
        }
        boolean negative = amount.startsWith("-");
        String unsigned = negative ? amount.substring(1) : amount;
        String[] parts = unsigned.split("\\.", -1);
        BigInteger cents = new BigInteger(parts[0]).multiply(BigInteger.valueOf(100));
        if (parts.length == 2) {
            cents = cents.add(BigInteger.valueOf(Long.parseLong(
                    parts[1].length() == 1 ? parts[1] + "0" : parts[1])));
        }
        if (cents.compareTo(MAX_OPENING_BALANCE_CENTS) > 0) {
            fields.put("openingBalance", "金额不能超过 999,999,999.99");
            return null;
        }
        return negative ? cents.negate().longValueExact() : cents.longValueExact();
    }

    private static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
    }

    private static ResourceConflictException duplicateName() {
        return new ResourceConflictException("RESOURCE_CONFLICT", "同一家庭的账户名称不能重复");
    }
}
