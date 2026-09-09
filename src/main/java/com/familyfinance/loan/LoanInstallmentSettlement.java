package com.familyfinance.loan;

import com.familyfinance.category.*;
import com.familyfinance.family.FamilyMutationAuthorization.LockedFamilyAccess;
import com.familyfinance.household.*;
import com.familyfinance.ledger.FinancialAccount;
import com.familyfinance.notification.NotificationService;
import com.familyfinance.shared.*;
import com.familyfinance.transaction.*;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Caller owns authorization, household/root locks, payment order and the whole-request receipt. */
@Service
public class LoanInstallmentSettlement {
    private final FinancialTransactionRepository transactions;
    private final LoanInstallmentRepository installments;
    private final LoanAccountingService accounting;
    private final NotificationService notifications;
    private final FamilyMemberRepository members;
    private final CategoryRepository categories;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public LoanInstallmentSettlement(FinancialTransactionRepository transactions,LoanInstallmentRepository installments,
            LoanAccountingService accounting,NotificationService notifications,FamilyMemberRepository members,
            CategoryRepository categories,JdbcTemplate jdbc,Clock clock) {
        this.transactions=transactions;this.installments=installments;this.accounting=accounting;
        this.notifications=notifications;this.members=members;this.categories=categories;this.jdbc=jdbc;this.clock=clock;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public FinancialTransaction settleAuthorized(LockedFamilyAccess access,Loan loan,LoanInstallment installment,
            FinancialAccount account,LocalDate paidOn,String childKey) {
        return settleAuthorized(access,loan,installment,account,paidOn,childKey,LoanSettlementFunding.cash());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public FinancialTransaction settleAuthorized(LockedFamilyAccess access,Loan loan,LoanInstallment installment,
            FinancialAccount account,LocalDate paidOn,String childKey,LoanSettlementFunding funding) {
        long h=access.context().householdId();
        if(loan.getHousehold().getId()!=h||installment.getLoan().getId().longValue()!=loan.getId()
                ||account.getHousehold().getId()!=h||installment.getStatus()!=LoanInstallmentStatus.PENDING)
            throw new ResourceConflictException("LOAN_PLAN_CHANGED","待还期次已变化，请重新预览");
        var principal=installment.getPrincipalAmount();var interest=installment.getInterestAmount();
        var tx=FinancialTransaction.loanPayment(access.household(),account,access.membership().getUser(),
                member(loan,true),category(loan),DecimalMoney.toCents(principal.add(interest)),paidOn,installment.getId(),clock.instant());
        tx.loanSplit(DecimalMoney.toCents(principal),DecimalMoney.toCents(interest));funding.mark(tx);transactions.saveAndFlush(tx);
        accounting.pay(loan,tx,principal,interest,childKey);
        installment.confirm(tx);loan.applyPrincipalPayment(principal,clock.instant());accounting.requireBalance(loan);
        notifications.resolveReference(h,"LOAN_INSTALLMENT",installment.getId());installments.flush();
        return tx;
    }
    long memberId(Loan loan,boolean current) {
        long h=loan.getHousehold().getId();
        String lock=current?" for update":"";
        var ids=loan.getMember()!=null
                ?jdbc.queryForList("select id from family_members where household_id=? and id=?"+lock,Long.class,h,loan.getMember().getId())
                :loan.getAssignedUser()==null?java.util.List.<Long>of()
                :jdbc.queryForList("select id from family_members where household_id=? and linked_user_id=? order by id"+lock,Long.class,h,loan.getAssignedUser().getId());
        if(ids.isEmpty())throw stale();return ids.get(0);
    }
    FamilyMember member(Loan loan,boolean current){return members.getReferenceById(memberId(loan,current));}
    Category category(Loan loan){return categories.findByIdAndHouseholdId(loan.getPaymentCategory().getId(),loan.getHousehold().getId()).filter(c->c.getKind()==TransactionKind.EXPENSE).orElseThrow(LoanInstallmentSettlement::stale);}
    private static ResourceConflictException stale(){return new ResourceConflictException("STALE_REFERENCE","贷款关联的分类或记账成员已失效");}
}
