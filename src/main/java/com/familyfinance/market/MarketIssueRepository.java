package com.familyfinance.market;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MarketIssueRepository extends JpaRepository<MarketIssue,Long>{Optional<MarketIssue> findByHouseholdIdAndErrorCode(Long householdId,String errorCode);List<MarketIssue> findByHouseholdIdAndActiveTrue(Long householdId);}
