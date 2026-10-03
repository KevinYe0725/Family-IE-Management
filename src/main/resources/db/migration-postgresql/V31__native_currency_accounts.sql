alter table financial_accounts drop constraint ck_financial_accounts_currency;
alter table financial_accounts add constraint ck_financial_accounts_currency check(currency in ('CNY','HKD','USD'));
alter table financial_accounts add constraint ck_wallet_currency check(wallet_provider is null or wallet_provider not in ('ALIPAY','WECHAT') or currency='CNY');
alter table investment_accounts drop constraint ck_investment_accounts_currency;
alter table investment_accounts add constraint ck_investment_accounts_currency check(currency in ('CNY','HKD','USD'));
