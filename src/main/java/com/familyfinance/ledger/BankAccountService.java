package com.familyfinance.ledger;

import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.accounting.CashAccountingService;
import com.familyfinance.accounting.LedgerReadService;
import com.familyfinance.accounting.MultiCurrencyPolicy;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.familyfinance.ledger.BankAccountDtos.*;

/** Coordinates a bank identity and its existing single-currency cash accounts. */
@Service
@Transactional(readOnly = true)
public class BankAccountService {

    private final BankAccountRepository bankAccounts;
    private final FinancialAccountRepository accounts;
    private final AccountService accountService;
    private final CurrentMembership currentMembership;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final AccountingRequests requests;
    private final CashAccountingService cash;
    private final LedgerReadService ledger;
    private final MultiCurrencyPolicy currencyPolicy;
    private final Clock clock;
    private final FinancialAccountReferenceGuard references;

    public BankAccountService(
            BankAccountRepository bankAccounts,
            FinancialAccountRepository accounts,
            AccountService accountService,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            AccountingRequests requests,
            CashAccountingService cash,
            LedgerReadService ledger,
            MultiCurrencyPolicy currencyPolicy,
            Clock clock,
            FinancialAccountReferenceGuard references) {
        this.bankAccounts = bankAccounts;
        this.accounts = accounts;
        this.accountService = accountService;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.requests = requests;
        this.cash = cash;
        this.ledger = ledger;
        this.currencyPolicy = currencyPolicy;
        this.clock = clock;
        this.references = references;
    }

    public List<BankAccountDtos.BankAccount> list(Authentication authentication) {
        long householdId = currentMembership.require(authentication).householdId();
        return bankAccounts.findByHouseholdIdAndArchivedAtIsNullOrderByIdDesc(householdId).stream()
                .map(this::response)
                .toList();
    }

    @Transactional
    public BankAccountDtos.BankAccount create(Authentication authentication, CreateRequest request, String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        String requestKey = AccountingRequests.key(key);
        String digest = requests.digest("BANK_ACCOUNT_CREATE", access.context().userId(), request);
        Long replay = requests.replay(householdId, requestKey, digest);
        if (replay != null) {
            return responseLocked(bankAccounts.findLockedByIdAndHouseholdId(replay, householdId)
                    .orElseThrow(() -> new ResourceNotFoundException("银行卡主账户不存在")));
        }

        ValidatedParent validated = validateCreate(request);
        BankAccount parent = bankAccounts.saveAndFlush(new BankAccount(
                access.household(), validated.name(), validated.bankName(), validated.cardLastFour()));
        for (BalanceInput balance : validated.balances()) {
            accountService.createForBank(
                    access,
                    childRequest(parent, balance),
                    parent,
                    childKey(requestKey, balance.currency()));
        }
        bankAccounts.flush();
        requests.record(householdId, requestKey, digest, parent.getId());
        return responseLocked(parent);
    }

    @Transactional
    public BankAccountDtos.BankAccount update(Authentication authentication, long id, PatchRequest request, String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        BankAccount parent = bankAccounts.findLockedByIdAndHouseholdId(id, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("银行卡主账户不存在"));
        String requestKey = AccountingRequests.key(key);
        String digest = requests.digest("BANK_ACCOUNT_UPDATE:" + id, access.context().userId(), request);
        if (requests.replay(householdId, requestKey, digest) != null) {
            return responseLocked(parent);
        }
        if (parent.isArchived()) {
            throw new ResourceConflictException("BANK_ACCOUNT_ARCHIVED", "银行卡主账户已归档");
        }
        if (request == null) {
            throw new RequestValidationException(Map.of("request", "请求不能为空"));
        }

        String name = request.name() == null ? parent.getName() : requiredName(request.name());
        String bankName = request.bankName() == null ? parent.getBankName() : optionalBankName(request.bankName());
        String cardLastFour = request.cardLastFour() == null
                ? parent.getCardLastFour() : optionalCardLastFour(request.cardLastFour());
        List<FinancialAccount> children = accounts.findLockedByBankAccountIdAndHouseholdId(id, householdId);
        boolean nameChanged = !name.equals(parent.getName());
        if (nameChanged) {
            validateChildNames(householdId, name, children);
        }
        parent.update(name, bankName, cardLastFour);
        for (FinancialAccount child : children) {
            if (nameChanged) {
                child.rename(childName(name, child.getCurrency()));
            }
            child.updateBankMetadata(bankName, cardLastFour);
        }
        try {
            bankAccounts.flush();
            accounts.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException("RESOURCE_CONFLICT", "同一家庭的账户名称不能重复");
        }
        requests.record(householdId, requestKey, digest, id);
        return responseLocked(parent);
    }

    @Transactional
    public BankAccountDtos.BankAccount addBalance(Authentication authentication, long id, BalanceRequest request, String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        BankAccount parent = bankAccounts.findLockedByIdAndHouseholdId(id, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("银行卡主账户不存在"));
        String requestKey = AccountingRequests.key(key);
        String digest = requests.digest("BANK_ACCOUNT_BALANCE_CREATE:" + id, access.context().userId(), request);
        Long replay = requests.replay(householdId, requestKey, digest);
        if (replay != null) {
            return responseLocked(bankAccounts.findLockedByIdAndHouseholdId(id, householdId)
                    .orElseThrow(() -> new ResourceNotFoundException("银行卡主账户不存在")));
        }
        if (parent.isArchived()) {
            throw new ResourceConflictException("BANK_ACCOUNT_ARCHIVED", "银行卡主账户已归档");
        }
        BalanceInput balance = validateBalance(request);
        if (accounts.findLockedByBankAccountIdAndHouseholdId(id, householdId).stream()
                .anyMatch(child -> child.getCurrency().equals(balance.currency()))) {
            throw new ResourceConflictException("BANK_CURRENCY_EXISTS", "该银行卡已添加此币种");
        }
        try {
            accountService.createForBank(access, childRequest(parent, balance), parent,
                    childKey(requestKey, balance.currency()));
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new ResourceConflictException("BANK_CURRENCY_EXISTS", "该银行卡已添加此币种");
        }
        requests.record(householdId, requestKey, digest, id);
        return responseLocked(parent);
    }

    @Transactional
    public void archive(Authentication authentication, long id) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        BankAccount parent = bankAccounts.findLockedByIdAndHouseholdId(id, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("银行卡主账户不存在"));
        if (parent.isArchived()) {
            return;
        }
        List<FinancialAccount> children = accounts.findLockedByBankAccountIdAndHouseholdId(id, householdId);
        java.time.Instant archivedAt = clock.instant();
        for (FinancialAccount child : children) {
            if (child.isArchived()) {
                continue;
            }
            cash.requireConfirmed(child);
            if (cash.currentBalance(householdId, child.getId()) != 0) {
                throw new ResourceConflictException("ACCOUNT_BALANCE_NOT_ZERO", "银行卡子账户余额不为零，无法归档");
            }
            if (references.hasBlockingReferences(householdId, child.getId())) {
                throw new ResourceConflictException("RESOURCE_IN_USE", "银行卡子账户仍被有效业务使用，无法归档");
            }
        }
        for (FinancialAccount child : children) {
            if (!child.isArchived()) {
                child.archive(archivedAt);
            }
        }
        parent.archive(archivedAt);
        accounts.flush();
        bankAccounts.flush();
    }

    private BankAccountDtos.BankAccount response(com.familyfinance.ledger.BankAccount parent) {
        long householdId = parent.getHousehold().getId();
        List<AccountResponse> children = accounts.findByBankAccountIdAndHouseholdIdOrderByCurrency(parent.getId(), householdId)
                .stream()
                .map(child -> AccountResponse.from(child, ledger.balance(householdId, "CASH:" + child.getId())))
                .toList();
        return new BankAccountDtos.BankAccount(
                parent.getId(), parent.getName(), parent.getBankName(), parent.getCardLastFour(),
                parent.getArchivedAt(), children);
    }

    private BankAccountDtos.BankAccount responseLocked(com.familyfinance.ledger.BankAccount parent) {
        long householdId = parent.getHousehold().getId();
        List<AccountResponse> children = accounts.findLockedByBankAccountIdAndHouseholdId(parent.getId(), householdId)
                .stream()
                .map(child -> AccountResponse.from(child, cash.currentBalance(householdId, child.getId())))
                .toList();
        return new BankAccountDtos.BankAccount(
                parent.getId(), parent.getName(), parent.getBankName(), parent.getCardLastFour(),
                parent.getArchivedAt(), children);
    }

    private ValidatedParent validateCreate(CreateRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (request == null) {
            throw new RequestValidationException(Map.of("request", "请求不能为空"));
        }
        String name = optionalRequiredName(request.name(), fields);
        String bankName = optionalBankName(request.bankName(), fields);
        String cardLastFour = optionalCardLastFour(request.cardLastFour(), fields);
        List<BalanceRequest> requests = request.balances();
        if (requests == null || requests.isEmpty()) {
            fields.put("balances", "至少添加一个币种余额");
            throw new RequestValidationException(fields);
        }
        Set<String> seen = new HashSet<>();
        List<BalanceInput> balances = new java.util.ArrayList<>();
        for (BalanceRequest balance : requests) {
            BalanceInput value = validateBalance(balance, fields);
            if (value != null && !seen.add(value.currency())) {
                fields.put("balances", "同一银行卡不能重复添加同一币种");
            } else if (value != null) {
                balances.add(value);
                if (childName(name, value.currency()).length() > 100) {
                    fields.put("name", "银行卡名称加币种后不能超过 100 个字符");
                }
            }
        }
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
        return new ValidatedParent(name, bankName, cardLastFour, balances);
    }

    private BalanceInput validateBalance(BalanceRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        BalanceInput result = validateBalance(request, fields);
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
        return result;
    }

    private BalanceInput validateBalance(BalanceRequest request, Map<String, String> fields) {
        if (request == null) {
            fields.put("balances", "币种余额不能为空");
            return null;
        }
        String currency = request.currency() == null ? "" : request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        currencyPolicy.validate(currency, fields);
        if (currency.isEmpty()) {
            fields.put("currency", "币种不能为空");
        }
        if (request.openingBalance() == null || request.openingBalance().trim().isEmpty()
                || !request.openingBalance().trim().matches("\\d+(?:\\.\\d{1,2})?")) {
            fields.put("openingBalance", "金额格式必须是最多两位小数的非负数字");
        }
        if (request.openingOn() == null || request.openingOn().isBlank()) {
            fields.put("openingOn", "期初日期不能为空");
        }
        if (!fields.isEmpty()) return null;
        try {
            return new BalanceInput(currency, request.openingBalance(), java.time.LocalDate.parse(request.openingOn()));
        } catch (java.time.DateTimeException exception) {
            fields.put("openingOn", "期初日期格式不正确");
            return null;
        }
    }

    private AccountCreateRequest childRequest(BankAccount parent, BalanceInput balance) {
        return new AccountCreateRequest(
                childName(parent.getName(), balance.currency()),
                AccountType.BANK,
                balance.currency(),
                balance.openingBalance(),
                balance.openingOn().toString(),
                null,
                parent.getBankName(),
                parent.getCardLastFour());
    }

    private void validateChildNames(long householdId, String parentName, List<FinancialAccount> children) {
        for (FinancialAccount child : children) {
            String name = childName(parentName, child.getCurrency());
            if (name.length() > 100) {
                throw new RequestValidationException(Map.of("name", "银行卡名称加币种后不能超过 100 个字符"));
            }
            boolean siblingConflict = children.stream()
                    .anyMatch(other -> other != child && name.equals(other.getName()));
            boolean outsideConflict = accounts.findLockedByHouseholdIdAndName(householdId, name)
                    .filter(existing -> children.stream().noneMatch(other -> existing.getId().equals(other.getId())))
                    .isPresent();
            if (siblingConflict || outsideConflict) {
                throw new ResourceConflictException("RESOURCE_CONFLICT", "同一家庭的账户名称不能重复");
            }
        }
    }

    static String childName(String parentName, String currency) {
        return parentName + " · " + currency;
    }

    static String childKey(String parentKey, String currency) {
        String material = "BANK_ACCOUNT_CHILD:" + parentKey + ":" + currency;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(String.format(java.util.Locale.ROOT, "%02x", value));
            }
            return "bank-child:" + currency.toLowerCase(java.util.Locale.ROOT) + ":" + hex;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requiredName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new RequestValidationException(Map.of("name", "银行卡名称不能为空且不超过 100 个字符"));
        }
        return name;
    }

    private static String optionalRequiredName(String raw, Map<String, String> fields) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 100) {
            fields.put("name", "银行卡名称不能为空且不超过 100 个字符");
        }
        return name;
    }

    private static String optionalBankName(String raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        String value = optionalBankName(raw, fields);
        if (!fields.isEmpty()) throw new RequestValidationException(fields);
        return value;
    }

    private static String optionalBankName(String raw, Map<String, String> fields) {
        String value = raw == null || raw.isBlank() ? null : raw.trim();
        if (value != null && value.length() > 80) {
            fields.put("bankName", "银行名称不能超过 80 个字符");
        }
        return value;
    }

    private static String optionalCardLastFour(String raw) {
        Map<String, String> fields = new LinkedHashMap<>();
        String value = optionalCardLastFour(raw, fields);
        if (!fields.isEmpty()) throw new RequestValidationException(fields);
        return value;
    }

    private static String optionalCardLastFour(String raw, Map<String, String> fields) {
        String value = raw == null || raw.isBlank() ? null : raw.trim();
        if (value != null && !value.matches("[0-9]{4}")) {
            fields.put("cardLastFour", "请仅填写四位数字尾号，不要填写完整卡号");
        }
        return value;
    }

    private record ValidatedParent(String name, String bankName, String cardLastFour, List<BalanceInput> balances) {
    }
}
