package com.familyfinance.identity;

import com.familyfinance.family.HouseholdMembership;
import com.familyfinance.family.HouseholdMembershipRepository;
import com.familyfinance.family.HouseholdRole;
import com.familyfinance.family.MembershipStatus;
import com.familyfinance.family.FamilyInvite;
import com.familyfinance.family.InviteService;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.FamilyMember;
import com.familyfinance.household.FamilyMemberRepository;
import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceConflictException;
import java.nio.charset.StandardCharsets;
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

    private static final int MAX_EMAIL_INPUT_CODE_UNITS = 512;
    private static final int MAX_DISPLAY_NAME_INPUT_CODE_UNITS = 128;
    private static final int MAX_HOUSEHOLD_NAME_INPUT_CODE_UNITS = 1_024;
    private static final int MAX_INVITE_TOKEN_CODE_UNITS = 512;
    private static final int MIN_PASSWORD_CODE_POINTS = 8;
    private static final int MAX_PASSWORD_CODE_POINTS = 72;
    private static final int MAX_BCRYPT_PASSWORD_UTF8_BYTES = 72;
    private static final int MAX_PASSWORD_CODE_UNITS = MAX_PASSWORD_CODE_POINTS * 2;

    private final AppUserRepository users;
    private final HouseholdRepository households;
    private final HouseholdMembershipRepository memberships;
    private final FamilyMemberRepository members;
    private final RegistrationDefaults defaults;
    private final InviteService invites;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    RegistrationService(
            AppUserRepository users,
            HouseholdRepository households,
            HouseholdMembershipRepository memberships,
            FamilyMemberRepository members,
            RegistrationDefaults defaults,
            InviteService invites,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.users = users;
        this.households = households;
        this.memberships = memberships;
        this.members = members;
        this.defaults = defaults;
        this.invites = invites;
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
    public RegisterResponse join(JoinRegistration command) {
        Instant now = clock.instant();
        FamilyInvite invite = invites.lockValidInvite(command.inviteToken(), now);
        if (users.findByEmail(command.email()).isPresent()) {
            throw registrationFailed();
        }
        AppUser user;
        try {
            user = users.saveAndFlush(new AppUser(invite.getHousehold(), command.email(), command.email(),
                    command.displayName(), passwordEncoder.encode(command.password()), now));
        } catch (DataIntegrityViolationException exception) {
            throw registrationFailed();
        }
        memberships.save(new HouseholdMembership(invite.getHousehold(), user, invite.getRole(), MembershipStatus.ACTIVE, now));
        members.save(new FamilyMember(invite.getHousehold(), user, command.displayName(), roleLabel(invite.getRole()), now));
        return new RegisterResponse(user.getEmail(), user.getDisplayName(), invite.getHousehold().getName(), invite.getRole());
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
        String email = normalizeEmail(request.email(), fields);
        if (email.isEmpty() || email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            fields.put("email", "邮箱格式不正确");
        }
        String displayName = normalize(request.displayName(), "displayName", MAX_DISPLAY_NAME_INPUT_CODE_UNITS, fields);
        if (displayName.isEmpty() || displayName.length() > 40) {
            fields.put("displayName", "显示姓名长度应为 1 到 40 个字符");
        }
        String householdName = normalize(request.householdName(), "householdName", MAX_HOUSEHOLD_NAME_INPUT_CODE_UNITS, fields);
        validatePassword(fields, "password", request.password());
        if (request.inviteToken() != null && request.inviteToken().length() > MAX_INVITE_TOKEN_CODE_UNITS) {
            fields.put("inviteToken", "邀请码长度不能超过 512 个字符");
        }
        if (!"CREATE".equals(request.mode())) {
            fields.put("mode", "当前暂不支持的注册方式");
        }
        if (householdName.isEmpty() || householdName.length() > 255) fields.put("householdName", "家庭名称不能为空且不能超过 255 个字符");
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
        return new CreateRegistration(email, displayName, request.password(), householdName);
    }

    public JoinRegistration validateJoin(RegisterRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        String email = normalizeEmail(request.email(), fields);
        if (email.isEmpty() || email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) fields.put("email", "邮箱格式不正确");
        String displayName = normalize(request.displayName(), "displayName", MAX_DISPLAY_NAME_INPUT_CODE_UNITS, fields);
        if (displayName.isEmpty() || displayName.length() > 40) fields.put("displayName", "显示姓名长度应为 1 到 40 个字符");
        validatePassword(fields, "password", request.password());
        String inviteToken = normalize(request.inviteToken(), "inviteToken", MAX_INVITE_TOKEN_CODE_UNITS, fields);
        if (inviteToken.isEmpty()) fields.put("inviteToken", "邀请码不能为空");
        if (!"JOIN".equals(request.mode())) fields.put("mode", "当前暂不支持的注册方式");
        if (!fields.isEmpty()) throw new RequestValidationException(fields);
        return new JoinRegistration(email, displayName, request.password(), inviteToken);
    }

    private static String roleLabel(HouseholdRole role) {
        return switch (role) { case OWNER -> "所有者"; case ADMIN -> "管理员"; case MEMBER -> "成员"; };
    }

    private void validatePassword(String field, String password) {
        Map<String, String> fields = new LinkedHashMap<>();
        validatePassword(fields, field, password);
        if (!fields.isEmpty()) {
            throw new RequestValidationException(fields);
        }
    }

    private static void validatePassword(Map<String, String> fields, String field, String password) {
        if (password == null || password.length() > MAX_PASSWORD_CODE_UNITS) {
            fields.put(field, "密码长度应为 8 到 72 个字符");
            return;
        }
        int codePoints = password.codePointCount(0, password.length());
        if (codePoints < MIN_PASSWORD_CODE_POINTS || codePoints > MAX_PASSWORD_CODE_POINTS) {
            fields.put(field, "密码长度应为 8 到 72 个字符");
            return;
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_PASSWORD_UTF8_BYTES) {
            fields.put(field, "密码不能超过 BCrypt 的 72 字节限制");
        }
    }

    private static String normalizeEmail(String value, Map<String, String> fields) {
        return normalize(value, "email", MAX_EMAIL_INPUT_CODE_UNITS, fields).toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value, String field, int maxCodeUnits, Map<String, String> fields) {
        if (value == null) {
            return "";
        }
        if (value.length() > maxCodeUnits) {
            fields.put(field, "字段内容过长");
            return "";
        }
        return value.trim();
    }

    private static ResourceConflictException registrationFailed() {
        return new ResourceConflictException("REGISTRATION_FAILED", "注册暂时无法完成");
    }

    public record CreateRegistration(String email, String displayName, String password, String householdName) {
    }
    public record JoinRegistration(String email, String displayName, String password, String inviteToken) {}
}
