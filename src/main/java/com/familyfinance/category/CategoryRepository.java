package com.familyfinance.category;

import com.familyfinance.household.Household;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByHouseholdOrderById(Household household);
}
