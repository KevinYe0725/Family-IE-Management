package com.familyfinance.budget;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class BudgetUpgradeMigrationTest {
 @Test void upgradesBankSchemaAndArchivesLegacyTotalHistoryWithoutLosingAmounts() throws Exception {
  String url="jdbc:h2:mem:budget-upgrade-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
  Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").target("41").load().migrate();
  try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
   s.executeUpdate("insert into households(id,name,created_at,status) values(999,'migration',CURRENT_TIMESTAMP,'ACTIVE')");
   s.executeUpdate("insert into app_users(id,household_id,username,email,display_name,password_hash,created_at,status) values(999,999,'migration','migration@test.local','migration','unused',CURRENT_TIMESTAMP,'ACTIVE')");
   s.executeUpdate("insert into budgets(id,household_id,period_month,scope_type,amount_cents,version,active) values(999,999,'2026-09','TOTAL',1505000,1,true)");
   s.executeUpdate("insert into budget_revisions(household_id,budget_id,old_amount_cents,new_amount_cents,changed_by,changed_at,old_period_month,new_period_month,old_scope_type,new_scope_type,old_active,new_active) values(999,999,1400000,1505000,999,CURRENT_TIMESTAMP,'2026-09','2026-09','TOTAL','TOTAL',true,true)");
   Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration").load().migrate();
   s.executeUpdate("insert into categories(id,household_id,kind,name,color,is_default,created_at) values(999,999,'EXPENSE','category','#445566',false,CURRENT_TIMESTAMP)");
   s.executeUpdate("insert into family_members(id,household_id,name,role_label,created_at) values(998,999,'A','',CURRENT_TIMESTAMP),(999,999,'B','',CURRENT_TIMESTAMP)");
   s.executeUpdate("insert into budgets(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) values(999,'2026-09','CATEGORY_MEMBER',999,998,10000,1,true)");
   s.executeUpdate("insert into budgets(household_id,period_month,scope_type,category_id,member_id,amount_cents,version,active) values(999,'2026-09','CATEGORY_MEMBER',999,999,10000,1,true)");
   try(var r=s.executeQuery("select amount_cents from budget_monthly_totals where household_id=999")){assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isEqualTo(1505000);}
   try(var r=s.executeQuery("select amount_cents from legacy_budget_totals where id=999")){assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isEqualTo(1505000);}
   try(var r=s.executeQuery("select old_amount_cents,new_amount_cents from legacy_budget_total_revisions where budget_id=999")){assertThat(r.next()).isTrue();assertThat(r.getLong(1)).isEqualTo(1400000);assertThat(r.getLong(2)).isEqualTo(1505000);}
  }
 }
}
