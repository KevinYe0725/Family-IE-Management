package com.familyfinance.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NetWorthSnapshotServiceTest {

    @Test
    void sameHouseholdAndDateUpdatesOneSnapshotInsteadOfCreatingAnother() {
        HouseholdRepository households = mock(HouseholdRepository.class);
        NetWorthSnapshotRepository snapshots = mock(NetWorthSnapshotRepository.class);
        NetWorthService netWorth = mock(NetWorthService.class);
        Household household = mock(Household.class);
        when(household.getId()).thenReturn(7L);
        when(households.findLockedById(7L)).thenReturn(Optional.of(household));
        NetWorthSnapshot existing = new NetWorthSnapshot(household, LocalDate.of(2026, 9, 3), 1L, 2L, -1L);
        when(snapshots.findByHouseholdIdAndSnapshotOn(7L, LocalDate.of(2026, 9, 3))).thenReturn(Optional.of(existing));
        when(netWorth.calculate(7L, LocalDate.of(2026, 9, 3))).thenReturn(new NetWorthResult(1_200_000L, 400_000L,
                800_000L, List.of(), 333, List.of(), new BudgetSummary(0, 0L, 0L, 0, 0),
                new InvestmentSummary(0L, 0, 0, false, false, false)));

        NetWorthSnapshotService service = new NetWorthSnapshotService(households, snapshots, netWorth,
                Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC));
        NetWorthSnapshot snapshot = service.generate(7L, LocalDate.of(2026, 9, 3));

        assertThat(snapshot.getAssetCents()).isEqualTo(1_200_000L);
        assertThat(snapshot.getLiabilityCents()).isEqualTo(400_000L);
        assertThat(snapshot.getNetWorthCents()).isEqualTo(800_000L);
        verify(snapshots).flush();
    }
}
