package com.familyfinance.household;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByIdAndHouseholdId(Long id, Long householdId);

    Optional<AppUser> findByIdAndHouseholdIdAndStatus(Long id, Long householdId, AppUserStatus status);

    boolean existsByIdAndStatus(Long id, AppUserStatus status);
}
