package com.familyfinance.ledger.recurring;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.QueryHint;

public interface RecurringOccurrenceRepository
        extends JpaRepository<RecurringOccurrence, Long>, JpaSpecificationExecutor<RecurringOccurrence> {
    boolean existsByRuleIdAndDueOn(Long ruleId, LocalDate dueOn);
    List<RecurringOccurrence> findByRuleIdOrderByDueOnAscIdAsc(Long ruleId);
    List<RecurringOccurrence> findByHouseholdIdAndStatusAndDueOnLessThanEqualOrderByDueOnAscIdAsc(Long householdId, RecurringOccurrenceStatus status, LocalDate dueOn);

    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("""
            update RecurringOccurrence occurrence
            set occurrence.status = com.familyfinance.ledger.recurring.RecurringOccurrenceStatus.CANCELLED
            where occurrence.rule.id = :ruleId
              and occurrence.status = com.familyfinance.ledger.recurring.RecurringOccurrenceStatus.PENDING
            """)
    int cancelPendingByRuleId(Long ruleId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "1000"))
    @EntityGraph(attributePaths = {"rule", "rule.account", "rule.member", "rule.category", "assignedUser",
            "confirmedTransaction"})
    Optional<RecurringOccurrence> findLockedByIdAndHouseholdId(Long id, Long householdId);
}
