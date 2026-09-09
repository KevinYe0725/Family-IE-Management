package com.familyfinance.migration;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoanRepaymentBatchMigrationTest {
 @TempDir Path temp;
 @Test void populatedV23UpgradePreservesLoanPlanReceiptsAndLedgerAndLeavesOldEventsUngrouped()throws Exception{
  Path file=StageOneDatabaseFixture.create(temp.resolve("batch-upgrade"));var before=MigrationTestSupport.migrateExistingDatabaseTo(file,"23");
  before.executeUpdate("insert into loans(id,household_id,name,loan_type,payment_account_id,payment_category_id,principal_amount,annual_rate,term_months,repayment_method,start_on,current_principal_amount,status,created_by) select 1,1,'Legacy batch boundary','OTHER',min(account_id),1,100.00,0.12,2,'EQUAL_PAYMENT','2026-01-01',90.00,'ACTIVE',1 from financial_transactions");
  before.executeUpdate("insert into loan_installments(id,household_id,loan_id,installment_no,due_on,principal_amount,interest_amount,status,precise_principal_amount,precise_interest_amount,interest_carry_amount,rounding_policy) values(1,1,1,1,'2026-02-01',45.00,0.55,'PENDING',45.000000000001,0.550000000001,0.000000000001,'FIXED_CASH_V1'),(2,1,1,2,'2026-03-01',45.00,0.25,'PENDING',44.999999999999,0.25,0.000000000001,'FIXED_CASH_V1')");
  before.executeUpdate("insert into loan_prepayments(id,household_id,loan_id,request_key,amount,interest_amount,paid_on,created_at) values(1,1,1,'old-extra-key',10.00,0.00,'2026-01-02',current_timestamp)");
  before.executeUpdate("insert into accounting_commands(household_id,request_key,request_digest,source_id) values(1,'old-extra-key','"+"a".repeat(64)+"',1)");
  var queries=List.of("select * from loans order by id","select * from loan_installments order by id","select id,loan_id,request_key,amount,interest_amount,paid_on,transaction_id,operation_kind,strategy from loan_prepayments order by id","select * from financial_transactions order by id","select * from ledger_accounts order by household_id,account_code","select * from ledger_journals order by id","select * from ledger_entries order by id","select * from ledger_sources order by household_id,source_type,source_id","select * from accounting_commands order by household_id,request_key");
  var snapshot=new ArrayList<List<List<String>>>();for(var q:queries)snapshot.add(rows(before.databaseUrl(),q));
  var after=MigrationTestSupport.migrateExistingDatabase(file);assertThat(after.version()).isEqualTo("40");
  for(int i=0;i<queries.size();i++)assertThat(rows(after.databaseUrl(),queries.get(i))).as(queries.get(i)).isEqualTo(snapshot.get(i));
  assertThat(after.queryLong("select count(*) from loan_repayment_batches")).isZero();assertThat(after.queryLong("select count(*) from loan_repayment_batch_children")).isZero();
  assertThat(after.queryLong("select count(*) from loan_prepayments where repayment_batch_id is null")).isEqualTo(1);
 }
 private List<List<String>> rows(String url,String sql)throws Exception{
  var rows=new ArrayList<List<String>>();try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement();var r=s.executeQuery(sql)){
   while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));rows.add(row);}
  }return rows;
 }
}
