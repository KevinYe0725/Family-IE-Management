package com.familyfinance.ledger.recurring;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface RecurringRuleRepository extends JpaRepository<RecurringRule, Long> {
    @EntityGraph(attributePaths = {"account", "member", "category", "assignedUser", "createdBy"})
    Page<RecurringRule> findByHouseholdIdAndActiveTrue(Long householdId, Pageable pageable);

    @EntityGraph(attributePaths = {"account", "member", "category", "assignedUser", "createdBy"})
    Page<RecurringRule> findByHouseholdId(Long householdId, Pageable pageable);

    @EntityGraph(attributePaths = {"account", "member", "category", "assignedUser", "createdBy"})
    Optional<RecurringRule> findByIdAndHouseholdId(Long id, Long householdId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"household", "account", "member", "category", "assignedUser", "createdBy"})
    List<RecurringRule> findByActiveTrueAndPausedFalseAndNextDueOnLessThanEqualOrderByIdAsc(LocalDate dueOn);
}
