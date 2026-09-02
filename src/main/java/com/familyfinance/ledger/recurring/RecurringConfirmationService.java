package com.familyfinance.ledger.recurring;

import com.familyfinance.category.Category;
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
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import com.familyfinance.transaction.TransactionSourceType;
import java.time.Clock;
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

    public RecurringConfirmationService(
            RecurringOccurrenceRepository occurrences,
            FinancialTransactionRepository transactions,
            FinancialAccountRepository accounts,
            FamilyMemberRepository members,
            CategoryRepository categories,
            FamilyMutationAuthorization mutationAuthorization,
            FamilyPermissionService permissions,
            Clock clock) {
        this.occurrences = occurrences;
        this.transactions = transactions;
        this.accounts = accounts;
        this.members = members;
        this.categories = categories;
        this.mutationAuthorization = mutationAuthorization;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional
    public RecurringOccurrenceResponse confirm(Authentication authentication, long occurrenceId) {
        FamilyMutationAuthorization.LockedFamilyAccess access = mutationAuthorization.requireCurrent(authentication);
        long householdId = access.context().householdId();
        RecurringOccurrence occurrence = occurrences.findLockedByIdAndHouseholdId(occurrenceId, householdId)
                .orElseThrow(() -> new ResourceNotFoundException("周期发生项不存在"));
        Long assigneeId = occurrence.getAssignedUser() == null ? null : occurrence.getAssignedUser().getId();
        if (assigneeId == null) {
            throw new ResourceConflictException("OCCURRENCE_UNASSIGNED", "周期发生项尚未分配，无法确认");
        }
        permissions.requireCanConfirmAssignedOccurrence(access.context(), assigneeId);
        if (occurrence.getStatus() == RecurringOccurrenceStatus.CONFIRMED) {
            return RecurringOccurrenceResponse.from(occurrence);
        }
        if (occurrence.getStatus() == RecurringOccurrenceStatus.CANCELLED) {
            throw new ResourceConflictException("OCCURRENCE_CANCELLED", "周期发生项已取消");
        }
        if (occurrence.getAssignedUser().getStatus() != AppUserStatus.ACTIVE) {
            throw staleReference();
        }

        RecurringRule rule = occurrence.getRule();
        FinancialAccount account = accounts
                .findByIdAndHouseholdIdAndArchivedAtIsNull(rule.getAccount().getId(), householdId)
                .orElseThrow(RecurringConfirmationService::staleReference);
        FamilyMember member = members.findByIdAndHouseholdId(rule.getMember().getId(), householdId)
                .orElseThrow(RecurringConfirmationService::staleReference);
        Category category = categories.findByIdAndHouseholdId(rule.getCategory().getId(), householdId)
                .filter(candidate -> candidate.getKind() == rule.getKind())
                .orElseThrow(RecurringConfirmationService::staleReference);

        FinancialTransaction existing = transactions
                .findBySourceTypeAndSourceId(TransactionSourceType.RECURRING, occurrenceId)
                .orElse(null);
        if (existing != null) {
            occurrence.confirm(existing);
            occurrences.flush();
            return RecurringOccurrenceResponse.from(occurrence);
        }

        try {
            FinancialTransaction transaction = transactions.saveAndFlush(FinancialTransaction.recurring(
                    access.household(), account, access.membership().getUser(), member, category,
                    rule.getKind(), rule.getAmountCents(), occurrence.getDueOn(), occurrenceId, clock.instant()));
            occurrence.confirm(transaction);
            occurrences.flush();
            return RecurringOccurrenceResponse.from(occurrence);
        } catch (DataIntegrityViolationException exception) {
            throw new ResourceConflictException(
                    "RECURRING_CONFIRMATION_RACE", "周期账单已由另一请求确认，请重试");
        }
    }

    private static ResourceConflictException staleReference() {
        return new ResourceConflictException("STALE_REFERENCE", "周期规则关联的账户、分类或成员已失效");
    }
}
