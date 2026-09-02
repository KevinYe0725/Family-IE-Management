package com.familyfinance.family;

import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InviteService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CurrentMembership currentMembership;
    private final FamilyPermissionService permissions;
    private final FamilyInviteRepository invites;
    private final AppUserRepository users;
    private final Clock clock;

    InviteService(CurrentMembership currentMembership, FamilyPermissionService permissions, FamilyInviteRepository invites,
            AppUserRepository users, Clock clock) {
        this.currentMembership = currentMembership;
        this.permissions = permissions;
        this.invites = invites;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public CreatedInvite create(Authentication authentication, Integer maxUses, HouseholdRole requestedRole) {
        MembershipContext context = currentMembership.require(authentication);
        HouseholdRole role = requestedRole == null ? HouseholdRole.MEMBER : requestedRole;
        if (role == HouseholdRole.OWNER) validation("role", "邀请不能授予所有者角色");
        if (role == HouseholdRole.ADMIN) permissions.requireOwner(context); else permissions.requireAdmin(context);
        int uses = maxUses == null ? 5 : maxUses;
        if (uses < 1 || uses > 100) validation("maxUses", "邀请码使用次数必须为 1 到 100");
        AppUser user = users.getReferenceById(context.userId());
        Instant now = clock.instant();
        String token = newToken();
        FamilyInvite invite = invites.save(new FamilyInvite(
                user.getHousehold(), sha256(token), role, now.plus(java.time.Duration.ofDays(7)), uses, user, now));
        return new CreatedInvite(invite.getId(), token, role, invite.getExpiresAt(), uses, 0);
    }

    @Transactional(readOnly = true)
    public List<InviteView> list(Authentication authentication) {
        MembershipContext context = currentMembership.require(authentication);
        permissions.requireAdmin(context);
        return invites.findByHouseholdIdOrderByIdDesc(context.householdId()).stream().map(InviteView::from).toList();
    }

    @Transactional
    public void revoke(Authentication authentication, long id) {
        MembershipContext context = currentMembership.require(authentication);
        FamilyInvite invite = invites.findByIdAndHouseholdId(id, context.householdId())
                .orElseThrow(() -> new ResourceNotFoundException("邀请不存在"));
        if (invite.getRole() == HouseholdRole.ADMIN) permissions.requireOwner(context); else permissions.requireAdmin(context);
        invite.revoke(clock.instant());
    }

    public FamilyInvite lockValidInvite(String rawToken, Instant now) {
        FamilyInvite invite = invites.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new InviteStateException("INVITE_INVALID", "邀请码无效"));
        invite.consume(now);
        return invite;
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte byteValue : digest) result.append(String.format("%02x", byteValue));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String newToken() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void validation(String field, String message) {
        throw new RequestValidationException(Map.of(field, message));
    }

    public record CreatedInvite(Long id, String token, HouseholdRole role, Instant expiresAt, int maxUses, int usedCount) {}
    public record InviteView(Long id, HouseholdRole role, Instant expiresAt, int maxUses, int usedCount, Instant revokedAt, Instant createdAt) {
        static InviteView from(FamilyInvite invite) { return new InviteView(invite.getId(), invite.getRole(), invite.getExpiresAt(), invite.getMaxUses(), invite.getUsedCount(), invite.getRevokedAt(), invite.getCreatedAt()); }
    }
}
