package com.familyfinance.household;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface HouseholdRepository extends JpaRepository<Household, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Household> findLockedById(Long id);
}
