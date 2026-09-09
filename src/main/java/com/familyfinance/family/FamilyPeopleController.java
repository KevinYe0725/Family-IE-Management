package com.familyfinance.family;

import com.familyfinance.shared.ApiEnvelope;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only directory. A ledger person is not a grant of access to the household. */
@RestController
public class FamilyPeopleController {
    private final CurrentMembership current;
    private final JdbcTemplate jdbc;

    public FamilyPeopleController(CurrentMembership current, JdbcTemplate jdbc) {
        this.current = current;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/family/people")
    @Transactional(readOnly = true)
    public ApiEnvelope<List<Person>> people(Authentication authentication) {
        long household = current.require(authentication).householdId();
        // Preserve the ledger name and ID; merge only by explicit user link, never by name.
        String query = """
                select f.id member_id, m.id membership_id, f.name, f.role_label relationship,
                       u.display_name, u.email, m.role, m.status membership_status, u.status user_status
                from family_members f
                left join household_memberships m on m.household_id=f.household_id and m.user_id=f.linked_user_id
                left join app_users u on u.id=m.user_id and u.household_id=f.household_id
                where f.household_id=?
                union all
                select null member_id, m.id membership_id, u.display_name name, null relationship,
                       u.display_name, u.email, m.role, m.status membership_status, u.status user_status
                from household_memberships m
                join app_users u on u.id=m.user_id and u.household_id=m.household_id
                where m.household_id=? and not exists (
                    select 1 from family_members f where f.household_id=m.household_id and f.linked_user_id=m.user_id
                )
                order by member_id, membership_id
                """;
        return ApiEnvelope.data(jdbc.query(query, (row, index) -> {
            Long membershipId = row.getObject("membership_id", Long.class);
            String loginStatus = membershipId == null ? "NONE"
                    : "ACTIVE".equals(row.getString("membership_status")) && "ACTIVE".equals(row.getString("user_status"))
                    ? "AVAILABLE" : "SUSPENDED";
            String relationship = row.getString("relationship");
            // Registration historically stored permission names in role_label, not family relationships.
            if (List.of("所有者", "管理员", "成员").contains(relationship == null ? "" : relationship)) relationship = null;
            return new Person(row.getObject("member_id", Long.class), membershipId, row.getString("name"),
                    relationship, row.getString("display_name"), row.getString("email"), row.getString("role"), loginStatus);
        }, household, household));
    }

    public record Person(Long memberId, Long membershipId, String name, String relationship,
                         String accountDisplayName, String email, String role, String loginStatus) {}
}
