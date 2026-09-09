package com.familyfinance.loan;
import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable; import org.springframework.data.jpa.repository.*;
public interface LoanRepository extends JpaRepository<Loan,Long> { Optional<Loan> findByIdAndHouseholdId(Long id,Long householdId); Page<Loan> findByHouseholdIdAndStatus(Long householdId,LoanStatus status,Pageable pageable);
 List<Loan> findAllByHouseholdIdAndStatus(Long householdId, LoanStatus status);
 List<Loan> findAllByHouseholdIdAndAccountingOnLessThanEqual(Long householdId,java.time.LocalDate day);
 List<Loan> findAllByHouseholdIdAndLinkedAsset_IdOrderByIdAsc(Long householdId,Long linkedAssetId);
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @Query("select l from Loan l where l.household.id=:household and l.linkedAsset.id=:asset order by l.id")
 List<Loan> findAllCurrentLinked(long household,long asset);
 boolean existsByHouseholdIdAndLinkedAsset_Id(Long householdId, Long linkedAssetId);
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE) @EntityGraph(attributePaths={"paymentAccount","paymentCategory","member","assignedUser","disbursementAccount","linkedAsset","ownContributionAccount"}) Optional<Loan> findLockedByIdAndHouseholdId(Long id,Long householdId);
}
