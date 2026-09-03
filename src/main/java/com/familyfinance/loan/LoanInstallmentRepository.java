package com.familyfinance.loan;

import java.util.Optional;
import java.time.LocalDate;
import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface LoanInstallmentRepository extends JpaRepository<LoanInstallment, Long> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @EntityGraph(attributePaths={"loan","loan.installments","loan.paymentAccount","loan.paymentCategory","loan.member","loan.assignedUser","confirmedTransaction"})
 Optional<LoanInstallment> findLockedByIdAndHouseholdId(Long id, Long householdId);
 List<LoanInstallment> findByHouseholdIdAndStatusAndDueOnLessThanEqualOrderByDueOnAscIdAsc(Long householdId, LoanInstallmentStatus status, LocalDate dueOn);
}
