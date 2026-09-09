package com.familyfinance.budget;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetTemplateRowRepository extends JpaRepository<BudgetTemplateRow, Long> {

    List<BudgetTemplateRow> findByTemplateIdOrderById(Long templateId);
}
