package com.familyfinance.reporting;

import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.shared.ResourceNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NetWorthSnapshotService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final HouseholdRepository households;
    private final NetWorthSnapshotRepository snapshots;
    private final NetWorthService netWorth;
    private final Clock clock;

    public NetWorthSnapshotService(HouseholdRepository households, NetWorthSnapshotRepository snapshots,
            NetWorthService netWorth, Clock clock) {
        this.households = households;
        this.snapshots = snapshots;
        this.netWorth = netWorth;
        this.clock = clock;
    }

    @Transactional
    public NetWorthSnapshot generate(long householdId, LocalDate snapshotOn) {
        Household household = households.findLockedById(householdId)
                .orElseThrow(() -> new ResourceNotFoundException("家庭不存在"));
        NetWorthResult result = netWorth.calculate(householdId, snapshotOn);
        NetWorthSnapshot snapshot = snapshots.findByHouseholdIdAndSnapshotOn(householdId, snapshotOn)
                .orElseGet(() -> snapshots.save(new NetWorthSnapshot(household, snapshotOn,
                        result.assetCents(), result.liabilityCents(), result.netWorthCents())));
        snapshot.update(result.assetCents(), result.liabilityCents(), result.netWorthCents());
        snapshots.flush();
        return snapshot;
    }

    @Transactional
    @Scheduled(cron = "0 50 23 * * *", zone = "Asia/Shanghai")
    public void generateDaily() {
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        for (Household household : households.findAll()) {
            try {
                generate(household.getId(), today);
            } catch (DataIntegrityViolationException ignored) {
                // A concurrent daily run may have already persisted the same natural key.
            }
        }
    }

    @Transactional(readOnly = true)
    public List<NetWorthSnapshot> history(long householdId) {
        return snapshots.findTop24ByHouseholdIdOrderBySnapshotOnDescIdDesc(householdId);
    }
}
