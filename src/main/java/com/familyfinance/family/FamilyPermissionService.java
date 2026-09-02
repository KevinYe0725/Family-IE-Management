package com.familyfinance.family;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class FamilyPermissionService {
    public void requireAdmin(MembershipContext context) {
        if (context.role() != HouseholdRole.OWNER && context.role() != HouseholdRole.ADMIN) forbidden();
    }
    public void requireOwner(MembershipContext context) {
        if (context.role() != HouseholdRole.OWNER) forbidden();
    }
    public boolean canMutateTransaction(MembershipContext context, Long createdByUserId) {
        return context.role() != HouseholdRole.MEMBER || context.userId().equals(createdByUserId);
    }
    public boolean canConfirmAssignedOccurrence(MembershipContext context, Long assigneeUserId) {
        return assigneeUserId == null || context.userId().equals(assigneeUserId);
    }
    private static void forbidden() { throw new AccessDeniedException("没有权限执行此操作"); }
}
