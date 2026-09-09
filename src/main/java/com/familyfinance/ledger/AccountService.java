package com.familyfinance.ledger;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDate;
import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.accounting.CashAccountingService;
import com.familyfinance.accounting.LedgerReadService;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final CashAccountingService cash;
    private final LedgerReadService ledger;
    private final AccountingRequests requests;
    private final com.familyfinance.accounting.MultiCurrencyPolicy currencyPolicy;
    private final BankAccountRepository bankAccounts;
    private final FinancialAccountReferenceGuard references;

    public AccountService(
            FinancialAccountRepository accounts,
            CurrentMembership currentMembership,
            FamilyMutationAuthorization mutationAuthorization,
            Clock clock, CashAccountingService cash, LedgerReadService ledger, AccountingRequests requests,
            com.familyfinance.accounting.MultiCurrencyPolicy currencyPolicy, BankAccountRepository bankAccounts,
            FinancialAccountReferenceGuard references) {
        this.accounts = accounts;
        this.currentMembership = currentMembership;
        this.mutationAuthorization = mutationAuthorization;
        this.clock = clock;
        this.cash=cash; this.ledger=ledger; this.requests=requests;
        this.currencyPolicy=currencyPolicy;
        this.bankAccounts=bankAccounts;
        this.references=references;
    }

    public AccountPage list(Authentication authentication, int page, int size) {
        long householdId = currentMembership.require(authentication).householdId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var result = accounts.findByHouseholdIdAndArchivedAtIsNull(
                householdId,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
        return new AccountPage(
                result.getContent().stream().map(this::response).toList(),
                safePage,
                safeSize,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }

    public AccountResponse get(Authentication authentication, long accountId) {
        long householdId = currentMembership.require(authentication).householdId();
        return response(findOne(householdId, accountId));
    }

    @Transactional
    public AccountResponse create(Authentication authentication, AccountCreateRequest request) {
        return create(authentication,request,AccountingRequests.key(null));
    }

    @Transactional
    public AccountResponse create(Authentication authentication, AccountCreateRequest request, String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        return create(access, request, key, null);
    }

    /** Parent bank creation calls this method to reuse the cash-opening transaction. */
    @Transactional
    AccountResponse createForBank(
            FamilyMutationAuthorization.LockedFamilyAccess access,
            AccountCreateRequest request,
            BankAccount bankAccount,
            String key) {
        return create(access, request, key, bankAccount);
    }

    private AccountResponse create(
            FamilyMutationAuthorization.LockedFamilyAccess access,
            AccountCreateRequest request,
            String key,
            BankAccount suppliedBankAccount) {
        key=AccountingRequests.key(key);
        String digest=requests.digest("ACCOUNT_CREATE",access.context().userId(),request);
        Long previous=requests.replay(access.context().householdId(),key,digest);
        if(previous!=null) return mutationResponse(accounts.findLockedByIdAndHouseholdId(previous,access.context().householdId())
            .orElseThrow(()->new ResourceNotFoundException("账户不存在")));
        Map<String, String> fields = new LinkedHashMap<>();
        String name = normalizeRequiredName(request == null ? null : request.name(), fields);
        AccountType type = requireType(request == null ? null : request.type(), fields);
        String currency = requireCurrency(request == null ? null : request.currency(), fields);
        Long openingBalance = parseOpeningBalance(request == null ? null : request.openingBalance(), fields);
        Details details = details(type, request == null ? null : request.walletProvider(),
                request == null ? null : request.bankName(), request == null ? null : request.cardLastFour(), fields);
        requireWalletCurrency(details,currency,fields);
        throwIfInvalid(fields);
        LocalDate openingOn=cash.date(request.openingOn(),"openingOn");
        validateUnique(access.context().householdId(), name, null);
        try {
            BankAccount bankAccount = suppliedBankAccount;
            if (type == AccountType.BANK && bankAccount == null) {
                bankAccount = bankAccounts.saveAndFlush(new BankAccount(
                        access.household(), name, details.bankName(), details.cardLastFour()));
            }
            if (type != AccountType.BANK && suppliedBankAccount != null) {
                throw new RequestValidationException(Map.of("type", "银行卡主账户只能关联银行卡子账户"));
            }
            FinancialAccount account = accounts.saveAndFlush(new FinancialAccount(
                    access.household(), name, type, currency, openingBalance));
            if (bankAccount != null) {
                account.attachBankAccount(bankAccount);
            }
            account.updateDetails(details.walletProvider(), details.bankName(), details.cardLastFour());
            cash.opening(account,openingBalance,openingOn,access.context().userId(),key);
            accounts.flush();
            requests.record(access.context().householdId(),key,digest,account.getId());
            return mutationResponse(account);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
    }

    @Transactional
    public AccountResponse update(Authentication authentication, long accountId, AccountPatchRequest request) {
        return update(authentication,accountId,request,AccountingRequests.key(null));
    }

    @Transactional
    public AccountResponse update(Authentication authentication, long accountId, AccountPatchRequest request,String key) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        FinancialAccount account = accounts.findLockedByIdAndHouseholdId(accountId,householdId)
                .orElseThrow(()->new ResourceNotFoundException("账户不存在"));
        key=AccountingRequests.key(key);
        String digest=requests.digest("ACCOUNT_UPDATE:"+accountId,access.context().userId(),request);
        if(requests.replay(householdId,key,digest)!=null) return mutationResponse(account);
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
        if(!currency.equals(account.getCurrency()))fields.put("currency","账户币种创建后不可修改，请新建对应币种账户");
        Long openingBalance = request == null || request.openingBalance() == null
                ? account.getOpeningBalanceCents()
                : parseOpeningBalance(request.openingBalance(), fields);
        // Omitted metadata preserves values within a type; type changes discard inapplicable details.
        Details details = details(type,
                request != null && request.walletProvider() != null ? request.walletProvider()
                        : type == AccountType.WALLET ? account.getWalletProvider() : null,
                request != null && request.bankName() != null ? request.bankName()
                        : type == AccountType.BANK ? account.getBankName() : null,
                request != null && request.cardLastFour() != null ? request.cardLastFour()
                        : type == AccountType.BANK ? account.getCardLastFour() : null, fields);
        requireWalletCurrency(details,currency,fields);
        throwIfInvalid(fields);
        boolean openingChange=request!=null&&(request.openingBalance()!=null||request.openingOn()!=null);
        LocalDate openingOn=openingChange
            ?cash.date(request.openingOn()!=null?request.openingOn():account.getOpeningOn()==null?null:account.getOpeningOn().toString(),"openingOn")
            :account.getOpeningOn();
        validateUnique(householdId, name, accountId);
        try {
            BankAccount bankAccount = account.getBankAccount();
            boolean groupedNameChange = bankAccount != null && type == AccountType.BANK
                    && request != null && request.name() != null;
            if (type == AccountType.BANK && bankAccount == null) {
                bankAccount = bankAccounts.saveAndFlush(new BankAccount(
                        access.household(), name, details.bankName(), details.cardLastFour()));
                account.attachBankAccount(bankAccount);
            }
            if (type != AccountType.BANK && bankAccount != null) {
                account.detachBankAccount();
                bankAccount = null;
            }
            if (bankAccount != null && type == AccountType.BANK) {
                String parentName = groupedNameChange ? name : bankAccount.getName();
                List<FinancialAccount> children = accounts.findLockedByBankAccountIdAndHouseholdId(
                        bankAccount.getId(), householdId);
                boolean legacySingleChild = children.size() == 1
                        && children.get(0).getName().equals(bankAccount.getName());
                if (groupedNameChange && !legacySingleChild) {
                    validateGroupedNames(householdId, children, parentName);
                }
                bankAccount.update(parentName, details.bankName(), details.cardLastFour());
                for (FinancialAccount child : children) {
                    if (groupedNameChange) {
                        child.rename(legacySingleChild
                                ? parentName : BankAccountService.childName(parentName, child.getCurrency()));
                    }
                    child.updateBankMetadata(details.bankName(), details.cardLastFour());
                }
                name = groupedNameChange
                        ? (legacySingleChild ? parentName : BankAccountService.childName(parentName, account.getCurrency()))
                        : account.getName();
            }
            account.update(name, type, currency, openingBalance);
            if (type == AccountType.BANK && account.getBankAccount() != null) {
                account.updateBankMetadata(details.bankName(), details.cardLastFour());
            } else {
                account.updateDetails(details.walletProvider(), details.bankName(), details.cardLastFour());
            }
            if(openingChange) cash.opening(account,openingBalance,openingOn,access.context().userId(),key);
            accounts.flush();
            requests.record(householdId,key,digest,accountId);
            return mutationResponse(account);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
    }

    @Transactional
    public void archive(Authentication authentication, long accountId) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireAdmin(authentication);
        long householdId = access.context().householdId();
        FinancialAccount account = accounts.findLockedByIdAndHouseholdId(accountId,householdId)
                .orElseThrow(()->new ResourceNotFoundException("账户不存在"));
        if (account.isArchived()) {
            return;
        }
        // An unknown opening cannot be hidden: reports require explicit initialization.
        cash.requireConfirmed(account);
        // Authorization holds the household write lock before this fresh balance read.
        if(cash.currentBalance(householdId,accountId)!=0)
            throw new ResourceConflictException("ACCOUNT_BALANCE_NOT_ZERO", "账户余额不为零，无法归档");
        if (references.hasBlockingReferences(householdId, accountId)) {
            throw new ResourceConflictException("RESOURCE_IN_USE", "账户仍被有效业务使用，无法归档");
        }
        account.archive(clock.instant());
        accounts.flush();
    }

    private FinancialAccount findOne(long householdId, long accountId) {
        return accounts.findByIdAndHouseholdId(accountId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("账户不存在"));
    }

    private AccountResponse response(FinancialAccount account) {
        return AccountResponse.from(account,ledger.balance(account.getHousehold().getId(),"CASH:"+account.getId()));
    }
    private AccountResponse mutationResponse(FinancialAccount account) {
        return AccountResponse.from(account,cash.currentBalance(account.getHousehold().getId(),account.getId()));
    }

    private void validateUnique(long householdId, String name, Long accountId) {
        boolean duplicate = accountId == null
                ? accounts.existsByHouseholdIdAndName(householdId, name)
                : accounts.existsByHouseholdIdAndNameAndIdNot(householdId, name, accountId);
        if (duplicate) {
            throw duplicateName();
        }
    }

    private void validateGroupedNames(long householdId, java.util.List<FinancialAccount> children, String parentName) {
        for (FinancialAccount child : children) {
            String desired = BankAccountService.childName(parentName, child.getCurrency());
            if (desired.length() > 100) {
                throw new RequestValidationException(Map.of("name", "银行卡名称加币种后不能超过 100 个字符"));
            }
            boolean siblingConflict = children.stream()
                    .anyMatch(other -> other != child && desired.equals(other.getName()));
            boolean outsideConflict = accounts.findLockedByHouseholdIdAndName(householdId, desired)
                    .filter(existing -> children.stream().noneMatch(other -> existing.getId().equals(other.getId())))
                    .isPresent();
            if (siblingConflict || outsideConflict) throw duplicateName();
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

    private record Details(WalletProvider walletProvider, String bankName, String cardLastFour) {}

    private static Details details(AccountType type, WalletProvider provider, String rawBank, String rawTail,
            Map<String, String> fields) {
        String bank = rawBank == null || rawBank.isBlank() ? null : rawBank.trim();
        String tail = rawTail == null || rawTail.isBlank() ? null : rawTail.trim();
        if (provider != null && type != AccountType.WALLET)
            fields.put("walletProvider", "只有电子钱包可以选择钱包平台");
        if (bank != null && type != AccountType.BANK)
            fields.put("bankName", "只有银行卡可以填写银行名称");
        if (tail != null && type != AccountType.BANK)
            fields.put("cardLastFour", "只有银行卡可以填写尾号");
        if (bank != null && bank.length() > 80)
            fields.put("bankName", "银行名称不能超过 80 个字符");
        if (tail != null && !tail.matches("[0-9]{4}"))
            fields.put("cardLastFour", "请仅填写四位数字尾号，不要填写完整卡号");
        return new Details(provider, bank, tail);
    }

    private String requireCurrency(String rawCurrency, Map<String, String> fields) {
        String currency = rawCurrency == null ? "" : rawCurrency.trim().toUpperCase(java.util.Locale.ROOT);
        currencyPolicy.validate(currency,fields);
        return currency;
    }

    private static void requireWalletCurrency(Details details,String currency,Map<String,String> fields) {
        if((details.walletProvider()==WalletProvider.ALIPAY||details.walletProvider()==WalletProvider.WECHAT)&&!currency.equals("CNY"))
            fields.put("currency","支付宝和微信余额仅支持人民币");
    }

    private static Long parseOpeningBalance(String rawAmount, Map<String, String> fields) {
        String amount = rawAmount == null ? "" : rawAmount.trim();
        if (!amount.matches("^-?\\d+(?:\\.\\d{1,2})?$")) {
            fields.put("openingBalance", "金额格式必须是最多两位小数的数字");
            return null;
        }
        boolean negative = amount.startsWith("-");
        if(negative) { fields.put("openingBalance","期初余额不能为负"); return null; }
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
