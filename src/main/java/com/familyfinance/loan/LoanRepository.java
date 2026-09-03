package com.familyfinance.loan;
import java.util.Optional;
import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.*;
public interface LoanRepository extends JpaRepository<Loan,Long> { Optional<Loan> findByIdAndHouseholdId(Long id,Long householdId); Page<Loan> findByHouseholdIdAndStatus(Long householdId,LoanStatus status,Pageable pageable);
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) @EntityGraph(attributePaths={"installments","installments.confirmedTransaction","paymentAccount","paymentCategory","member","assignedUser"}) Optional<Loan> findLockedByIdAndHouseholdId(Long id,Long householdId);
}
