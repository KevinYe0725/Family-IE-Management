package com.familyfinance.identity;

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
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

    private final AppUserRepository users;
    private final HouseholdRepository households;
    private final HouseholdMembershipRepository memberships;
    private final FamilyMemberRepository members;
    private final RegistrationDefaults defaults;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    RegistrationService(
            AppUserRepository users,
            HouseholdRepository households,
            HouseholdMembershipRepository memberships,
            FamilyMemberRepository members,
            RegistrationDefaults defaults,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.users = users;
        this.households = households;
        this.memberships = memberships;
        this.members = members;
        this.defaults = defaults;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public RegisterResponse register(CreateRegistration command) {
        if (users.findByEmail(command.email()).isPresent()) {
            throw registrationFailed();
        }
        Instant now = clock.instant();
        Household household = households.saveAndFlush(new Household(command.householdName(), now));
        AppUser user;
        try {
            user = users.saveAndFlush(new AppUser(
                    household,
                    command.email(),
                    command.email(),
                    command.displayName(),
                    passwordEncoder.encode(command.password()),
                    now));
        } catch (DataIntegrityViolationException exception) {
            throw registrationFailed();
        }
        memberships.save(new HouseholdMembership(household, user, HouseholdRole.OWNER, MembershipStatus.ACTIVE, now));
        members.save(new FamilyMember(household, user, command.displayName(), "所有者", now));
        defaults.createFor(household, now);
        return new RegisterResponse(user.getEmail(), user.getDisplayName(), household.getName(), HouseholdRole.OWNER);
    }

    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword) {
        validatePassword("newPassword", newPassword);
        AppUser user = users.findById(userId).orElseThrow(PasswordChangeException::new);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())
                || passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new PasswordChangeException();
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
    }

    public CreateRegistration validateCreate(RegisterRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        String email = normalizeEmail(request.email());
        if (email.isEmpty() || email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            fields.put("email", "邮箱格式不正确");
        }
        String displayName = normalize(request.displayName());
        if (displayName.isEmpty() || displayName.length() > 40) {
            fields.put("displayName", "显示姓名长度应为 1 到 40 个字符");
        }
        String householdName = normalize(request.householdName());
        if (householdName.isEmpty() || householdName.length() > 255) {
            fields.put("householdName", "家庭名称不能为空且不能超过 255 个字符");
        }
        validatePassword(fields, "password", request.password());
        if (!"CREATE".equals(request.mode())) {
            fields.put("mode", "当前暂不支持的注册方式");
        }
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
        return new CreateRegistration(email, displayName, request.password(), householdName);
    }

    private void validatePassword(String field, String password) {
        Map<String, String> fields = new LinkedHashMap<>();
        validatePassword(fields, field, password);
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
    }

    private static void validatePassword(Map<String, String> fields, String field, String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            fields.put(field, "密码长度应为 8 到 72 个字符");
        }
    }

    private static String normalizeEmail(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static ResourceConflictException registrationFailed() {
        return new ResourceConflictException("REGISTRATION_FAILED", "注册暂时无法完成");
    }

    public record CreateRegistration(String email, String displayName, String password, String householdName) {
    }
}
