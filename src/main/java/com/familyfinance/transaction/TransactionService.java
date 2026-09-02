package com.familyfinance.transaction;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.AppUser;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.family.FamilyPermissionService;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.shared.Money;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
public class TransactionService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final Sort DEFAULT_SORT = Sort.by(
            Sort.Order.desc("occurredOn"),
            Sort.Order.desc("id"));

    private final FamilyMemberRepository memberRepository;
    private final CategoryRepository categoryRepository;
    private final FinancialTransactionRepository transactionRepository;
    private final FinancialAccountRepository accountRepository;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final FamilyPermissionService permissions;
    private final TransactionFilterParser filterParser;

    public TransactionService(
            FamilyMemberRepository memberRepository,
            CategoryRepository categoryRepository,
            FinancialTransactionRepository transactionRepository,
            FinancialAccountRepository accountRepository,
            FamilyMutationAuthorization mutationAuthorization,
            FamilyPermissionService permissions,
            TransactionFilterParser filterParser) {
        this.memberRepository = memberRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.mutationAuthorization = mutationAuthorization;
        this.permissions = permissions;
        this.filterParser = filterParser;
    }

    public TransactionPage list(long householdId, TransactionFilter filter, int page, int size) {
        TransactionCriteria criteria = filterParser.parse(householdId, filter);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        var result = transactionRepository.findAll(
                TransactionSpecifications.matching(criteria),
                PageRequest.of(safePage, safeSize, DEFAULT_SORT));
        return new TransactionPage(
                result.getContent().stream().map(TransactionResponse::from).toList(),
                safePage,
                safeSize,
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }

    public List<FinancialTransaction> findAllForCsvExport(long householdId, TransactionFilter filter) {
        TransactionCriteria criteria = filterParser.parse(householdId, filter);
        return transactionRepository.findAll(TransactionSpecifications.matching(criteria), DEFAULT_SORT);
    }

    public TransactionResponse get(long householdId, long transactionId) {
        return TransactionResponse.from(findOne(householdId, transactionId));
    }

    @Transactional
    public TransactionResponse create(Authentication authentication, TransactionRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireCurrent(authentication);
        long householdId = access.context().householdId();
        Map<String, String> fields = new LinkedHashMap<>();
        Household household = access.household();
        TransactionKind kind = require(request.kind(), "kind", "收支类型不能为空", fields);
        Long amountCents = parseAmount(require(request.amount(), "amount", "金额不能为空", fields), fields);
        LocalDate occurredOn = parseDate(require(request.occurredOn(), "occurredOn", "日期不能为空", fields), "occurredOn", fields);
        FinancialAccount account = resolveActiveAccount(householdId, request.accountId(), fields);
        FamilyMember member = resolveMember(householdId, request.memberId(), fields);
        Category category = resolveCategory(householdId, request.categoryId(), kind, fields);
        AppUser creator = access.membership().getUser();
        throwIfInvalid(fields);

        Instant now = Instant.now();
        try {
            FinancialTransaction transaction = transactionRepository.saveAndFlush(new FinancialTransaction(
                    household,
                    account,
                    creator,
                    member,
                    category,
                    kind,
                    amountCents,
                    occurredOn,
                    normalizeText(request.merchant()),
                    normalizeText(request.location()),
                    normalizeText(request.note()),
                    now,
                    now));
            return TransactionResponse.from(transaction);
        } catch (DataIntegrityViolationException exception) {
            throw persistenceConflict();
        }
    }

    @Transactional
    public TransactionResponse update(
            Authentication authentication,
            long transactionId,
            TransactionPatchRequest request) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireCurrent(authentication);
        long householdId = access.context().householdId();
        FinancialTransaction transaction = findOne(householdId, transactionId);
        permissions.requireCanMutateTransaction(access.context(), transaction.getCreatedByUser().getId());
        Map<String, String> fields = new LinkedHashMap<>();
        TransactionKind kind = request.kind() == null ? transaction.getKind() : request.kind();
        Long amountCents = request.amount() == null ? transaction.getAmountCents() : parseAmount(request.amount(), fields);
        LocalDate occurredOn = request.occurredOn() == null
                ? transaction.getOccurredOn()
                : parseDate(request.occurredOn(), "occurredOn", fields);
        FinancialAccount account = request.accountId() == null
                ? transaction.getAccount()
                : resolveActiveAccount(householdId, request.accountId(), fields);
        FamilyMember member = request.memberId() == null
                ? transaction.getMember()
                : resolveMember(householdId, request.memberId(), fields);
        Category category = request.categoryId() == null
                ? transaction.getCategory()
                : resolveCategory(householdId, request.categoryId(), kind, fields);
        if (request.categoryId() == null && category.getKind() != kind) {
            fields.put("categoryId", "分类类型必须和收支类型一致");
        }
        throwIfInvalid(fields);

        try {
            transaction.updateDetails(
                    account,
                    member,
                    category,
                    kind,
                    amountCents,
                    occurredOn,
                    request.merchant() == null ? transaction.getMerchant() : normalizeText(request.merchant()),
                    request.location() == null ? transaction.getLocation() : normalizeText(request.location()),
                    request.note() == null ? transaction.getNote() : normalizeText(request.note()),
                    Instant.now());
            transactionRepository.flush();
            return TransactionResponse.from(transaction);
        } catch (DataIntegrityViolationException exception) {
            throw persistenceConflict();
        }
    }

    @Transactional
    public void delete(Authentication authentication, long transactionId) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireCurrent(authentication);
        long householdId = access.context().householdId();
        FinancialTransaction transaction = findOne(householdId, transactionId);
        permissions.requireCanMutateTransaction(access.context(), transaction.getCreatedByUser().getId());
        if (transaction.getSourceType() != TransactionSourceType.MANUAL) {
            throw new ResourceConflictException(
                    "RESOURCE_IN_USE",
                    "该收支记录由周期账单或贷款确认生成，属于关联历史，无法删除");
        }
        transactionRepository.delete(transaction);
    }

    private FinancialTransaction findOne(long householdId, long transactionId) {
        return transactionRepository.findOne((root, query, builder) -> builder.and(
                        builder.equal(root.get("household").get("id"), householdId),
                        builder.equal(root.get("id"), transactionId)))
                .orElseThrow(() -> new ResourceNotFoundException("收支记录不存在"));
    }

    private FamilyMember resolveMember(long householdId, Long memberId, Map<String, String> fields) {
        if (memberId == null) {
            fields.put("memberId", "成员不能为空");
            return null;
        }
        return memberRepository.findByIdAndHouseholdId(memberId, householdId)
                .orElseGet(() -> {
                    fields.put("memberId", "成员不存在");
                    return null;
                });
    }

    private Category resolveCategory(
            long householdId,
            Long categoryId,
            TransactionKind kind,
            Map<String, String> fields) {
        if (categoryId == null) {
            fields.put("categoryId", "分类不能为空");
            return null;
        }
        Category category = categoryRepository.findByIdAndHouseholdId(categoryId, householdId)
                .orElse(null);
        if (category == null) {
            fields.put("categoryId", "分类不存在");
            return null;
        }
        if (kind != null && category.getKind() != kind) {
            fields.put("categoryId", "分类类型必须和收支类型一致");
        }
        return category;
    }

    private FinancialAccount resolveActiveAccount(
            long householdId,
            Long accountId,
            Map<String, String> fields) {
        if (accountId == null) {
            fields.put("accountId", "账户不能为空");
            return null;
        }
        return accountRepository.findByIdAndHouseholdIdAndArchivedAtIsNull(accountId, householdId)
                .orElseGet(() -> {
                    fields.put("accountId", "账户不存在");
                    return null;
                });
    }

    private static <T> T require(T value, String field, String message, Map<String, String> fields) {
        if (value == null || (value instanceof String text && text.trim().isEmpty())) {
            fields.put(field, message);
            return null;
        }
        return value;
    }

    private static Long parseAmount(String amount, Map<String, String> fields) {
        if (amount == null) {
            return null;
        }
        try {
            return Money.parseCents(amount);
        } catch (IllegalArgumentException exception) {
            fields.put("amount", exception.getMessage());
            return null;
        }
    }

    private static LocalDate parseDate(String rawDate, String field, Map<String, String> fields) {
        if (rawDate == null) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate.trim());
        } catch (DateTimeParseException exception) {
            fields.put(field, "日期格式必须是 YYYY-MM-DD");
            return null;
        }
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void throwIfInvalid(Map<String, String> fields) {
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
    }

    private static ResourceConflictException persistenceConflict() {
        return new ResourceConflictException(
                "RESOURCE_CONFLICT",
                "收支记录关联的数据已变化，请刷新后重试");
    }

}
