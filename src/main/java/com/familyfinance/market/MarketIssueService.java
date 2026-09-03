package com.familyfinance.market;
import com.familyfinance.household.*;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class MarketIssueService {private final MarketIssueRepository issues;private final HouseholdRepository households;private final Clock clock;MarketIssueService(MarketIssueRepository issues,HouseholdRepository households,Clock clock){this.issues=issues;this.households=households;this.clock=clock;}@Transactional public void record(long householdId,String code){var current=issues.findByHouseholdIdAndErrorCode(householdId,code).orElse(null);if(current!=null){current.reopen(clock.instant());return;}households.findById(householdId).ifPresent(h->issues.save(new MarketIssue(h,code,clock.instant())));}@Transactional public void clear(long householdId){issues.findByHouseholdIdAndActiveTrue(householdId).forEach(i->i.resolve(clock.instant()));}}
