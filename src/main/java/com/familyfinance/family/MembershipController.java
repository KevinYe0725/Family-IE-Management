package com.familyfinance.family;

import com.familyfinance.household.Household;
import com.familyfinance.household.HouseholdRepository;
import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.RequestValidationException;
import com.familyfinance.shared.ResourceNotFoundException;
import com.familyfinance.shared.ResourceConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MembershipController {
    private final FamilyManagementService family;
    MembershipController(FamilyManagementService family) { this.family = family; }

    @GetMapping("/api/family") ApiEnvelope<FamilyView> get(Authentication authentication) { return ApiEnvelope.data(family.get(authentication)); }
    @PatchMapping("/api/family") ApiEnvelope<FamilyView> rename(Authentication authentication, @RequestBody RenameRequest request) { return ApiEnvelope.data(family.rename(authentication, request)); }
    @DeleteMapping("/api/family") @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(Authentication authentication, @RequestBody ArchiveRequest request, HttpServletRequest servletRequest) {
        family.archive(authentication, request);
        if (servletRequest.getSession(false) != null) servletRequest.getSession(false).invalidate();
    }
    @GetMapping("/api/family/memberships") ApiEnvelope<List<MembershipView>> memberships(Authentication authentication) { return ApiEnvelope.data(family.list(authentication)); }
    @PatchMapping("/api/family/memberships/{id}") ApiEnvelope<MembershipView> updateRole(Authentication authentication, @PathVariable long id, @RequestBody RoleRequest request) { return ApiEnvelope.data(family.updateRole(authentication, id, request)); }
    @PostMapping("/api/family/transfer-ownership") @ResponseStatus(HttpStatus.NO_CONTENT)
    void transfer(Authentication authentication, @RequestBody TransferRequest request) { family.transferOwnership(authentication, request); }

    record RenameRequest(String name) {}
    record ArchiveRequest(String confirmName) {}
    record RoleRequest(HouseholdRole role) {}
    record TransferRequest(Long membershipId) {}
}

@Service
class FamilyManagementService {
    private final CurrentMembership currentMembership;
    private final FamilyPermissionService permissions;
    private final HouseholdRepository households;
    private final HouseholdMembershipRepository memberships;
    private final FamilyInviteRepository invites;
    private final Clock clock;
    private final FamilyLockService locks;

    FamilyManagementService(CurrentMembership currentMembership, FamilyPermissionService permissions, HouseholdRepository households,
            HouseholdMembershipRepository memberships, FamilyInviteRepository invites, Clock clock, FamilyLockService locks) {
        this.currentMembership = currentMembership; this.permissions = permissions; this.households = households;
        this.memberships = memberships; this.invites = invites; this.clock = clock;
        this.locks = locks;
    }

    @Transactional(readOnly = true)
    FamilyView get(Authentication authentication) {
        MembershipContext context = currentMembership.require(authentication);
        Household household = households.findById(context.householdId()).orElseThrow(() -> new ResourceNotFoundException("家庭不存在"));
        return FamilyView.from(household);
    }

    @Transactional
    FamilyView rename(Authentication authentication, MembershipController.RenameRequest request) {
        MembershipContext context = currentMembership.require(authentication); permissions.requireOwner(context);
        String name = requireName(request == null ? null : request.name(), "name");
        Household household = locks.lockActiveHousehold(context.householdId());
        household.rename(name); return FamilyView.from(household);
    }

    @Transactional
    void archive(Authentication authentication, MembershipController.ArchiveRequest request) {
        MembershipContext context = currentMembership.require(authentication); permissions.requireOwner(context);
        Household household = locks.lockHousehold(context.householdId());
        if (request == null || !household.getName().equals(request.confirmName())) validation("confirmName", "家庭名称确认不匹配");
        Instant now = clock.instant();
        List<HouseholdMembership> householdMemberships = memberships.findByHouseholdId(household.getId());
        long owners = householdMemberships.stream().filter(m -> m.getStatus() == MembershipStatus.ACTIVE && m.getRole() == HouseholdRole.OWNER).count();
        if (owners != 1) throw new IllegalStateException("家庭必须恰有一名所有者");
        householdMemberships.forEach(HouseholdMembership::suspend);
        invites.findByHouseholdId(household.getId()).forEach(invite -> invite.revoke(now));
        household.archive(now);
    }

    @Transactional(readOnly = true)
    List<MembershipView> list(Authentication authentication) {
        MembershipContext context = currentMembership.require(authentication);
        return memberships.findByHouseholdIdOrderById(context.householdId()).stream().map(MembershipView::from).toList();
    }

    @Transactional
    MembershipView updateRole(Authentication authentication, long id, MembershipController.RoleRequest request) {
        MembershipContext context = currentMembership.require(authentication); permissions.requireOwner(context);
        if (request == null || request.role() == null || request.role() == HouseholdRole.OWNER) validation("role", "角色不允许这样调整");
        HouseholdMembership target = memberships.findByIdAndHouseholdId(id, context.householdId())
                .orElseThrow(() -> new ResourceNotFoundException("成员身份不存在"));
        if (target.getStatus() != MembershipStatus.ACTIVE || target.getRole() == HouseholdRole.OWNER) validation("role", "不能调整所有者或停用成员");
        target.changeRole(request.role()); return MembershipView.from(target);
    }

    @Transactional
    void transferOwnership(Authentication authentication, MembershipController.TransferRequest request) {
        MembershipContext context = currentMembership.require(authentication); permissions.requireOwner(context);
        if (request == null || request.membershipId() == null) validation("membershipId", "必须选择新所有者");
        List<HouseholdMembership> locked = memberships.findByHouseholdId(context.householdId());
        List<HouseholdMembership> currentOwners = locked.stream().filter(m -> m.getUser().getId().equals(context.userId()) && m.getRole() == HouseholdRole.OWNER && m.getStatus() == MembershipStatus.ACTIVE).toList();
        if (currentOwners.size() != 1) throw new ResourceConflictException("OWNER_INVARIANT", "家庭所有者状态无效");
        HouseholdMembership current = currentOwners.get(0);
        HouseholdMembership target = locked.stream().filter(m -> m.getId().equals(request.membershipId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("成员身份不存在"));
        if (target.getId().equals(current.getId()) || target.getStatus() != MembershipStatus.ACTIVE) validation("membershipId", "新所有者必须是另一名有效成员");
        HouseholdRole targetRole = target.getRole(); current.changeRole(targetRole); target.changeRole(HouseholdRole.OWNER);
        long owners = locked.stream().filter(m -> m.getStatus() == MembershipStatus.ACTIVE && m.getRole() == HouseholdRole.OWNER).count();
        if (owners != 1) throw new IllegalStateException("家庭必须恰有一名所有者");
    }

    private static String requireName(String value, String field) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 255) validation(field, "家庭名称不能为空且不能超过 255 个字符");
        return name;
    }
    private static void validation(String field, String message) { throw new RequestValidationException(Map.of(field, message)); }
}

record FamilyView(Long id, String name, String status, Instant archivedAt) {
    static FamilyView from(Household household) { return new FamilyView(household.getId(), household.getName(), household.getStatus(), household.getArchivedAt()); }
}
record MembershipView(Long id, Long userId, String email, String displayName, HouseholdRole role, MembershipStatus status) {
    static MembershipView from(HouseholdMembership membership) { return new MembershipView(membership.getId(), membership.getUser().getId(), membership.getUser().getEmail(), membership.getUser().getDisplayName(), membership.getRole(), membership.getStatus()); }
}
