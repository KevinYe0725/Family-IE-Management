package com.familyfinance.ledger.recurring;

import com.familyfinance.category.Category;
import com.familyfinance.accounting.CashAccountingService;
import com.familyfinance.accounting.LedgerReadService;
import com.familyfinance.accounting.AccountingRequests;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.family.FamilyMutationAuthorization;
import com.familyfinance.family.FamilyPermissionService;
import com.familyfinance.household.AppUserStatus;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.ledger.FinancialAccountRepository;
import com.familyfinance.shared.ResourceConflictException;
import com.familyfinance.shared.ResourceNotFoundException;
import com.familyfinance.shared.Money;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionSourceType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringConfirmationService {
    private final RecurringOccurrenceRepository occurrences;
    private final FinancialTransactionRepository transactions;
    private final FinancialAccountRepository accounts;
    private final FamilyMemberRepository members;
    private final CategoryRepository categories;
    private final FamilyMutationAuthorization mutationAuthorization;
    private final FamilyPermissionService permissions;
    private final Clock clock;
    private final CashAccountingService cash;
    private final LedgerReadService ledger;
    private final jakarta.persistence.EntityManager entityManager;
    private final AccountingRequests requests;

    public RecurringConfirmationService(
            RecurringOccurrenceRepository occurrences,
            FinancialTransactionRepository transactions,
            FinancialAccountRepository accounts,
            FamilyMemberRepository members,
            CategoryRepository categories,
            FamilyMutationAuthorization mutationAuthorization,
            FamilyPermissionService permissions,
            Clock clock,CashAccountingService cash,LedgerReadService ledger,jakarta.persistence.EntityManager entityManager,AccountingRequests requests) {
        this.occurrences = occurrences;
        this.transactions = transactions;
        this.accounts = accounts;
        this.members = members;
        this.categories = categories;
        this.mutationAuthorization = mutationAuthorization;
        this.permissions = permissions;
        this.clock = clock;
        this.cash=cash; this.ledger=ledger;
        this.entityManager=entityManager;
        this.requests=requests;
    }

    @Transactional
    public RecurringOccurrenceResponse confirm(Authentication authentication, long occurrenceId) {
        return confirm(authentication,occurrenceId,LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))));
    }
    @Transactional
    public RecurringOccurrenceResponse confirm(Authentication authentication,long occurrenceId,java.time.LocalDate occurredOn) {
        return confirm(authentication, occurrenceId, occurredOn, (String) null);
    }
    @Transactional
    public RecurringOccurrenceResponse confirm(
            Authentication authentication, long occurrenceId, java.time.LocalDate occurredOn, String amount) {
        Long amountOverrideCents = parseAmountOverride(amount);
        return confirm(authentication, occurrenceId, occurredOn, amountOverrideCents);
    }
    private RecurringOccurrenceResponse confirm(
            Authentication authentication, long occurrenceId, java.time.LocalDate occurredOn,
            Long amountOverrideCents) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireCurrent(authentication);
        long householdId = access.context().householdId();
        RecurringOccurrence occurrence = occurrences.findLockedByIdAndHouseholdId(occurrenceId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("周期发生项不存在"));
        // Refresh after locking: an entity graph may have hydrated from a repeatable-read snapshot.
        entityManager.refresh(occurrence, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        Long assigneeId = occurrence.getAssignedUser() == null ? null : occurrence.getAssignedUser().getId();
        if (assigneeId == null) {
            throw new ResourceConflictException("OCCURRENCE_UNASSIGNED", "周期发生项尚未分配，无法确认");
        }
        permissions.requireCanConfirmAssignedOccurrence(access.context(), assigneeId);
        if (occurrence.getStatus() == RecurringOccurrenceStatus.CONFIRMED) {
            requireMatchingAmount(occurrence.getConfirmedTransaction(), amountOverrideCents);
            return RecurringOccurrenceResponse.from(occurrence);
        }
        if (occurrence.getStatus() == RecurringOccurrenceStatus.CANCELLED) {
            throw new ResourceConflictException("OCCURRENCE_CANCELLED", "周期发生项已取消");
        }
        if (occurrence.getAssignedUser().getStatus() != AppUserStatus.ACTIVE) {
            throw staleReference();
        }
        RecurringRule rule = occurrence.getRule();
        entityManager.refresh(rule,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        long amountCents = amountOverrideCents == null ? rule.getAmountCents() : amountOverrideCents;
        String key="recurring:"+occurrenceId;
        String digest=requests.digest("RECURRING_CONFIRM", access.context().userId(),
                java.util.Map.of("occurrenceId", occurrenceId, "amountCents", amountCents));
        String historicalDigest=requests.digest("RECURRING_CONFIRM", access.context().userId(), occurrenceId);
        Long replay=requests.replay(householdId,key,digest,historicalDigest);

        FinancialAccount account = accounts
                .findLockedByIdAndHouseholdId(rule.getAccount().getId(), householdId).filter(a->!a.isArchived())
                .orElseThrow(RecurringConfirmationService::staleReference);
        entityManager.refresh(account,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        FamilyMember member = members.findByIdAndHouseholdId(rule.getMember().getId(), householdId)
                .orElseThrow(RecurringConfirmationService::staleReference);
        Category category = categories.findByIdAndHouseholdId(rule.getCategory().getId(), householdId)
                .filter(candidate -> candidate.getKind() == rule.getKind())
                .orElseThrow(RecurringConfirmationService::staleReference);

        FinancialTransaction existing = transactions
                .findLockedByHouseholdIdAndSourceTypeAndSourceId(householdId, TransactionSourceType.RECURRING, occurrenceId)
                .orElse(null);
        if (existing != null) {
            requireMatchingAmount(existing, amountOverrideCents);
            if(ledger.currentSource(householdId,"TRANSACTION",existing.getId()).isEmpty())
                throw new ResourceConflictException("ACCOUNTING_NOT_INITIALIZED","历史周期收支未初始化账务");
            occurrence.confirm(existing);
            occurrences.flush();
            if(replay==null) requests.record(householdId,key,digest,existing.getId());
            return RecurringOccurrenceResponse.from(occurrence);
        }

        try {
            cash.requireConfirmed(account);
            FinancialTransaction transaction = transactions.saveAndFlush(FinancialTransaction.recurring(
                    access.household(), account, access.membership().getUser(), member, category,
                    rule.getKind(), amountCents, occurredOn, occurrenceId, clock.instant()));
            cash.postTransaction(transaction,key);
            occurrence.confirm(transaction);
            occurrences.flush();
            requests.record(householdId,key,digest,transaction.getId());
            return RecurringOccurrenceResponse.from(occurrence);
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException(
                    "RECURRING_CONFIRMATION_RACE", "周期账单已由另一请求确认，请重试");
        }
    }

    private void requireMatchingAmount(FinancialTransaction transaction, Long amountOverrideCents) {
        if (amountOverrideCents == null) return; // Legacy callers retry without an amount.
        entityManager.refresh(transaction, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!amountOverrideCents.equals(transaction.getAmountCents())) {
            throw new ResourceConflictException("IDEMPOTENCY_KEY_REUSED", "本期账单已按其他金额入账，请在收支明细中更正");
        }
    }

    private static Long parseAmountOverride(String amount) {
        if (amount == null) return null;
        try {
            return Money.parseCents(amount);
        } catch (IllegalArgumentException exception) {
            throw new RequestValidationException(java.util.Map.of("amount", exception.getMessage()));
        }
    }

    @Transactional
    public RecurringBatchConfirmResponse confirmBatch(
            Authentication authentication, List<Long> occurrenceIds, LocalDate occurredOn) {
        if (occurrenceIds == null || occurrenceIds.isEmpty()) {
            throw new com.familyfinance.shared.RequestValidationException(
                    Map.of("occurrenceIds", "至少选择一条待确认账单"));
        }
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>(occurrenceIds);
        if (uniqueIds.contains(null) || uniqueIds.stream().anyMatch(id -> id <= 0)) {
            throw new com.familyfinance.shared.RequestValidationException(
                    Map.of("occurrenceIds", "账单编号无效"));
        }
        if (uniqueIds.size() > 100) {
            throw new com.familyfinance.shared.RequestValidationException(
                    Map.of("occurrenceIds", "单次最多确认 100 条账单"));
        }
        List<RecurringOccurrenceResponse> confirmed = new ArrayList<>(uniqueIds.size());
        for (Long id : uniqueIds) confirmed.add(confirm(authentication, id, occurredOn));
        return new RecurringBatchConfirmResponse(occurrenceIds.size(), confirmed.size(), confirmed);
    }

    @Transactional
    public RecurringOccurrenceResponse cancel(Authentication authentication, long occurrenceId) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireCurrent(authentication);
        RecurringOccurrence occurrence = occurrences.findLockedByIdAndHouseholdId(
                        occurrenceId, access.context().householdId())
                .orElseThrow(() -> new ResourceNotFoundException("周期发生项不存在"));
        Long assigneeId = occurrence.getAssignedUser() == null ? null : occurrence.getAssignedUser().getId();
        permissions.requireCanConfirmAssignedOccurrence(access.context(), assigneeId);
        if (occurrence.getStatus() == RecurringOccurrenceStatus.CONFIRMED) {
            throw new ResourceConflictException("OCCURRENCE_CONFIRMED", "已确认的周期发生项不能跳过");
        }
        if (occurrence.getStatus() == RecurringOccurrenceStatus.CANCELLED) {
            return RecurringOccurrenceResponse.from(occurrence);
        }
        occurrence.cancel();
        occurrences.flush();
        return RecurringOccurrenceResponse.from(occurrence);
    }

    private static ResourceConflictException staleReference() {
        return new ResourceConflictException("STALE_REFERENCE", "周期规则关联的账户、分类或成员已失效");
    }
}
