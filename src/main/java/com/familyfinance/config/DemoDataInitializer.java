package com.familyfinance.config;

import com.familyfinance.category.Category;
import com.familyfinance.category.CategoryRepository;
import com.familyfinance.category.TransactionKind;
import com.familyfinance.family.HouseholdMembership;
import com.familyfinance.family.HouseholdMembershipRepository;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.family.MembershipStatus;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.transaction.FinancialTransaction;
import com.familyfinance.transaction.FinancialTransactionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {

    private static final Instant SEED_TIME = Instant.parse("2026-09-01T00:00:00Z");

    private final AppUserRepository users;
    private final HouseholdRepository households;
    private final FamilyMemberRepository members;
    private final CategoryRepository categories;
    private final FinancialTransactionRepository transactions;
    private final HouseholdMembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;

    public DemoDataInitializer(
            AppUserRepository users,
            HouseholdRepository households,
            FamilyMemberRepository members,
            CategoryRepository categories,
            FinancialTransactionRepository transactions,
            HouseholdMembershipRepository memberships,
            PasswordEncoder passwordEncoder) {
        this.users = users;
        this.households = households;
        this.members = members;
        this.categories = categories;
        this.transactions = transactions;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.findByUsername("demo").isPresent()) {
            return;
        }

        Household household = households.save(new Household("演示家庭", SEED_TIME));
        AppUser demo = users.save(new AppUser(
                household,
                "demo",
                "demo@local.family",
                "演示用户",
                passwordEncoder.encode("demo1234"),
                SEED_TIME));
        memberships.save(new HouseholdMembership(
                household,
                demo,
                HouseholdRole.OWNER,
                MembershipStatus.ACTIVE,
                SEED_TIME));

        List<FamilyMember> seededMembers = members.saveAll(List.of(
                new FamilyMember(household, demo, "Kevin", "爸爸", SEED_TIME),
                new FamilyMember(household, "Lily", "妈妈", SEED_TIME),
                new FamilyMember(household, "Annie", "孩子", SEED_TIME),
                new FamilyMember(household, "爷爷", "长辈", SEED_TIME),
                new FamilyMember(household, "奶奶", "长辈", SEED_TIME)));

        List<Category> seededCategories = categories.saveAll(List.of(
                new Category(household, TransactionKind.INCOME, "工资", "#3B7A72", true, SEED_TIME),
                new Category(household, TransactionKind.INCOME, "奖金", "#C49A4A", true, SEED_TIME),
                new Category(household, TransactionKind.EXPENSE, "餐饮", "#D8664B", true, SEED_TIME),
                new Category(household, TransactionKind.EXPENSE, "交通", "#17324D", true, SEED_TIME),
                new Category(household, TransactionKind.EXPENSE, "购物", "#C49A4A", true, SEED_TIME),
                new Category(household, TransactionKind.EXPENSE, "教育", "#3B7A72", true, SEED_TIME),
                new Category(household, TransactionKind.EXPENSE, "医疗", "#7A4A3B", true, SEED_TIME),
                new Category(household, TransactionKind.EXPENSE, "居家", "#4B6680", true, SEED_TIME)));

        Category salary = seededCategories.get(0);
        Category bonus = seededCategories.get(1);
        Category food = seededCategories.get(2);
        Category transport = seededCategories.get(3);
        Category shopping = seededCategories.get(4);
        Category education = seededCategories.get(5);
        Category medical = seededCategories.get(6);
        Category home = seededCategories.get(7);

        FamilyMember kevin = seededMembers.get(0);
        FamilyMember lily = seededMembers.get(1);
        FamilyMember annie = seededMembers.get(2);
        FamilyMember grandpa = seededMembers.get(3);
        FamilyMember grandma = seededMembers.get(4);

        transactions.saveAll(List.of(
                tx(household, kevin, salary, TransactionKind.INCOME, 2800000L, "2026-06-05", "公司", "杭州", "六月工资"),
                tx(household, lily, food, TransactionKind.EXPENSE, 12850L, "2026-06-08", "盒马", "杭州", "一周食材"),
                tx(household, annie, education, TransactionKind.EXPENSE, 360000L, "2026-06-15", "培训中心", "杭州", "暑期课程"),
                tx(household, kevin, salary, TransactionKind.INCOME, 2800000L, "2026-07-05", "公司", "杭州", "七月工资"),
                tx(household, grandma, medical, TransactionKind.EXPENSE, 86500L, "2026-07-13", "社区医院", "杭州", "体检配药"),
                tx(household, lily, home, TransactionKind.EXPENSE, 239900L, "2026-07-22", "宜家", "杭州", "收纳家具"),
                tx(household, kevin, salary, TransactionKind.INCOME, 2800000L, "2026-08-05", "公司", "杭州", "八月工资"),
                tx(household, lily, bonus, TransactionKind.INCOME, 600000L, "2026-08-18", "项目奖金", "杭州", "季度奖金"),
                tx(household, grandpa, transport, TransactionKind.EXPENSE, 4200L, "2026-08-20", "地铁", "杭州", "出行"),
                tx(household, kevin, salary, TransactionKind.INCOME, 2800000L, "2026-09-05", "公司", "杭州", "九月工资"),
                tx(household, lily, food, TransactionKind.EXPENSE, 15680L, "2026-09-06", "菜场", "杭州", "家庭餐饮"),
                tx(household, annie, shopping, TransactionKind.EXPENSE, 32800L, "2026-09-12", "银泰", "杭州", "开学用品")));
    }

    private static FinancialTransaction tx(
            Household household,
            FamilyMember member,
            Category category,
            TransactionKind kind,
            Long amountCents,
            String occurredOn,
            String merchant,
            String location,
            String note) {
        return new FinancialTransaction(
                household,
                member,
                category,
                kind,
                amountCents,
                LocalDate.parse(occurredOn),
                merchant,
                location,
                note,
                SEED_TIME,
                SEED_TIME);
    }
}
