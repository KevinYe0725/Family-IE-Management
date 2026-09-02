package com.familyfinance.ledger;

import com.familyfinance.household.Household;
import org.springframework.stereotype.Component;

@Component
public class DefaultFinancialAccountFactory {

    private final FinancialAccountRepository accounts;

    public DefaultFinancialAccountFactory(FinancialAccountRepository accounts) {
        this.accounts = accounts;
    }

    public FinancialAccount createFor(Household household) {
        return accounts.save(new FinancialAccount(
                household,
                FinancialAccount.DEFAULT_NAME,
                AccountType.CASH,
                FinancialAccount.STAGE_TWO_CURRENCY,
                0L));
    }
}
