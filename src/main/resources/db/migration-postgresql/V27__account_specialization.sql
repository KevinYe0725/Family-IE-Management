-- Integrated V27: follows the published recurring-billing V26 migration.
-- Existing account types, identifiers, balances and relationships are unchanged.
ALTER TABLE financial_accounts ADD COLUMN wallet_provider VARCHAR(16);
ALTER TABLE financial_accounts ADD COLUMN bank_name VARCHAR(80);
ALTER TABLE financial_accounts ADD COLUMN card_last_four VARCHAR(4);
ALTER TABLE financial_accounts ADD CONSTRAINT ck_account_wallet_provider
    CHECK (wallet_provider IS NULL OR (type = 'WALLET' AND wallet_provider IN ('ALIPAY', 'WECHAT', 'OTHER')));
ALTER TABLE financial_accounts ADD CONSTRAINT ck_account_bank_details
    CHECK (type = 'BANK' OR (bank_name IS NULL AND card_last_four IS NULL));
