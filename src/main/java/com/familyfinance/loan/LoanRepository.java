package com.familyfinance.loan;
import java.util.Optional;
import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.*;
public interface LoanRepository extends JpaRepository<Loan,Long> { Optional<Loan> findByIdAndHouseholdId(Long id,Long householdId); Page<Loan> findByHouseholdIdAndStatus(Long householdId,LoanStatus status,Pageable pageable); }
