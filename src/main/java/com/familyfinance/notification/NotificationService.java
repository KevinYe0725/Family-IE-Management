package com.familyfinance.notification;

import com.familyfinance.asset.*;
import com.familyfinance.budget.*;
import com.familyfinance.family.*;
import com.familyfinance.household.*;
import com.familyfinance.ledger.recurring.*;
import com.familyfinance.loan.*;
import com.familyfinance.market.*;
import com.familyfinance.transaction.*;
import com.familyfinance.shared.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service public class NotificationService {
 private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai"); private final NotificationRepository notifications; private final CurrentMembership current; private final FamilyMutationAuthorization authorization; private final HouseholdRepository households; private final BudgetRepository budgets; private final FinancialTransactionRepository transactions; private final RecurringOccurrenceRepository recurring; private final LoanInstallmentRepository installments; private final AssetRepository assets; private final AssetValuationRepository valuations; private final MarketIssueRepository marketIssues; private final Clock clock;
 NotificationService(NotificationRepository notifications,CurrentMembership current,FamilyMutationAuthorization authorization,HouseholdRepository households,BudgetRepository budgets,FinancialTransactionRepository transactions,RecurringOccurrenceRepository recurring,LoanInstallmentRepository installments,AssetRepository assets,AssetValuationRepository valuations,MarketIssueRepository marketIssues,Clock clock){this.notifications=notifications;this.current=current;this.authorization=authorization;this.households=households;this.budgets=budgets;this.transactions=transactions;this.recurring=recurring;this.installments=installments;this.assets=assets;this.valuations=valuations;this.marketIssues=marketIssues;this.clock=clock;}
 @Transactional public int generate(Authentication authentication){var access=authorization.requireAdmin(authentication);return generateForHousehold(access.household());}
 @Transactional public int generateAll(){int made=0;for(Household h:households.findAll())made+=generateForHousehold(h);return made;}
 @Transactional public int generateForHousehold(Household household){long h=household.getId(); LocalDate today=LocalDate.now(clock.withZone(SHANGHAI));int made=0;
  for(LoanInstallment i:installments.findByHouseholdIdAndStatusAndDueOnLessThanEqualOrderByDueOnAscIdAsc(h,LoanInstallmentStatus.PENDING,today))made+=open(household,i.getLoan().getAssignedUser(),NotificationType.LOAN_DUE,"贷款还款到期","请确认贷款还款","LOAN_INSTALLMENT",i.getId(),i.getDueOn().atStartOfDay(SHANGHAI).toInstant())?1:0;
  for(RecurringOccurrence i:recurring.findByHouseholdIdAndStatusAndDueOnLessThanEqualOrderByDueOnAscIdAsc(h,RecurringOccurrenceStatus.PENDING,today))made+=open(household,i.getAssignedUser(),NotificationType.RECURRING_DUE,"周期账单到期","请确认周期账单","RECURRING_OCCURRENCE",i.getId(),i.getDueOn().atStartOfDay(SHANGHAI).toInstant())?1:0;
  YearMonth month=YearMonth.from(today);for(Budget b:budgets.findAllByHouseholdIdAndPeriodMonthAndActiveTrue(h,month.toString())){long spent=spent(h,b,month); if(spent>=b.getAmountCents())made+=open(household,null,NotificationType.BUDGET_LIMIT,"预算已达上限","本月预算已用尽","BUDGET",b.getId(),clock.instant())?1:0;else if(BigInteger.valueOf(spent).multiply(BigInteger.valueOf(100)).compareTo(BigInteger.valueOf(b.getAmountCents()).multiply(BigInteger.valueOf(80)))>=0)made+=open(household,null,NotificationType.BUDGET_NEAR_LIMIT,"预算接近上限","本月预算已使用80%以上","BUDGET",b.getId(),clock.instant())?1:0;}
  for(Asset a:assets.findAllByHouseholdIdAndStatus(h,AssetStatus.ACTIVE)){var last=valuations.findFirstByAssetIdOrderByValuedOnDescFetchedAtDescIdDesc(a.getId());if(last.isEmpty()||last.get().getValuedOn().isBefore(today.minusDays(30)))made+=open(household,null,NotificationType.ASSET_VALUATION_STALE,"资产估值已过期","请更新资产估值","ASSET",a.getId(),clock.instant())?1:0;}
  for(MarketIssue issue:marketIssues.findByHouseholdIdAndActiveTrue(h))made+=open(household,null,NotificationType.MARKET_ERROR,"行情服务异常",issue.getErrorCode(),"MARKET_ISSUE",issue.getId(),clock.instant())?1:0;
  return made;
 }
 @Transactional public NotificationPage list(Authentication authentication){var c=current.require(authentication);List<NotificationResponse> items=notifications.visible(c.householdId(),c.userId()).stream().map(NotificationResponse::from).toList();return new NotificationPage(items,notifications.unread(c.householdId(),c.userId()));}
 @Transactional public NotificationResponse read(Authentication authentication,long id){var access=authorization.requireCurrent(authentication);Notification n=visible(access.context(),id);n.read(clock.instant());return NotificationResponse.from(n);}
 @Transactional public NotificationResponse resolve(Authentication authentication,long id){var access=authorization.requireCurrent(authentication);Notification n=visible(access.context(),id);n.resolve(clock.instant());return NotificationResponse.from(n);}
 @Transactional public void resolveReference(long householdId,String referenceType,long referenceId){notifications.openReference(householdId,referenceType,referenceId).forEach(n->n.resolve(clock.instant()));}
 private Notification visible(MembershipContext c,long id){Notification n=notifications.findByIdAndHouseholdId(id,c.householdId()).orElseThrow(()->new ResourceNotFoundException("提醒不存在"));if(n.getUser()!=null&&!n.getUser().getId().equals(c.userId()))throw new org.springframework.security.access.AccessDeniedException("没有权限执行此操作");return n;}
 private boolean open(Household h,AppUser user,NotificationType type,String title,String body,String ref,long refId,Instant due){if(notifications.findByTypeAndReferenceTypeAndReferenceIdAndUserId(type,ref,refId,user==null?null:user.getId()).isPresent())return false;try{notifications.saveAndFlush(new Notification(h,user,type,title,body,ref,refId,due));return true;}catch(DataIntegrityViolationException e){return false;}}
 private long spent(long h,Budget b,YearMonth month){String raw=transactions.sumBudgetExpenseCents(h,month.atDay(1),month.plusMonths(1).atDay(1),b.getScopeType().name(),b.getCategory()==null?null:b.getCategory().getId(),b.getMember()==null?null:b.getMember().getId(),false);return new BigInteger(raw).longValueExact();}
}
