package com.familyfinance.loan;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LoanPrepaymentRepository extends JpaRepository<LoanPrepayment,Long> { Optional<LoanPrepayment> findByHouseholdIdAndLoanIdAndRequestKey(Long householdId,Long loanId,String requestKey); }
