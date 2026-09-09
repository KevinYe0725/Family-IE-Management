package com.familyfinance.budget;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetTemplateRepository extends JpaRepository<BudgetTemplate, Long> {

    java.util.List<BudgetTemplate> findByHouseholdIdOrderByIdDesc(Long householdId);

    Optional<BudgetTemplate> findByIdAndHouseholdId(Long id, Long householdId);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "select t from BudgetTemplate t where t.id=:id and t.household.id=:householdId")
    Optional<BudgetTemplate> findLockedByIdAndHouseholdId(
            @org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("householdId") Long householdId);

    boolean existsByHouseholdIdAndName(Long householdId, String name);
}
