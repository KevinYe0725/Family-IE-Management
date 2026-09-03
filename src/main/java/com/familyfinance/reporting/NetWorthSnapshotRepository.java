package com.familyfinance.reporting;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NetWorthSnapshotRepository extends JpaRepository<NetWorthSnapshot, Long> {
    Optional<NetWorthSnapshot> findByHouseholdIdAndSnapshotOn(Long householdId, LocalDate snapshotOn);
    List<NetWorthSnapshot> findTop24ByHouseholdIdOrderBySnapshotOnDescIdDesc(Long householdId);
}
