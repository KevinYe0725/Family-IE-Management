package com.familyfinance.category;

import com.familyfinance.household.Household;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByHouseholdOrderById(Household household);

    List<Category> findByHouseholdIdOrderById(Long householdId);

    Optional<Category> findByIdAndHouseholdId(Long id, Long householdId);

    boolean existsByHouseholdIdAndKindAndName(Long householdId, TransactionKind kind, String name);

    boolean existsByHouseholdIdAndKindAndNameAndIdNot(Long householdId, TransactionKind kind, String name, Long id);
}
