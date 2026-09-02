package com.familyfinance.category;

import com.familyfinance.household.Household;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByHouseholdOrderById(Household household);

    List<Category> findByHouseholdIdOrderById(Long householdId);

    Optional<Category> findByIdAndHouseholdId(Long id, Long householdId);

    @EntityGraph(attributePaths = "parent")
    Page<Category> findByHouseholdId(Long householdId, Pageable pageable);

    Page<Category> findByHouseholdIdAndParentIsNull(Long householdId, Pageable pageable);

    @EntityGraph(attributePaths = "parent")
    List<Category> findByHouseholdIdAndParentIdInOrderByIdAsc(Long householdId, Collection<Long> parentIds);

    boolean existsByHouseholdIdAndParentId(Long householdId, Long parentId);

    boolean existsByHouseholdIdAndKindAndName(Long householdId, TransactionKind kind, String name);

    boolean existsByHouseholdIdAndKindAndNameAndIdNot(Long householdId, TransactionKind kind, String name, Long id);

    @Query(value = """
            select count(*) from (
                select id from budgets where household_id=:householdId and category_id=:categoryId
                union all
                select id from budget_revisions
                where household_id=:householdId
                  and (old_category_id=:categoryId or new_category_id=:categoryId)
            ) budget_refs
            """, nativeQuery = true)
    long countBudgetReferences(@Param("householdId") Long householdId, @Param("categoryId") Long categoryId);

    @Query(value = "select count(*) from recurring_rules where household_id=:householdId and category_id=:categoryId", nativeQuery = true)
    long countRecurringReferences(@Param("householdId") Long householdId, @Param("categoryId") Long categoryId);
}
