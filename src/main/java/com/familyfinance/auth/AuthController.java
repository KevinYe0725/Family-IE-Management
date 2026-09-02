package com.familyfinance.auth;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.MembershipContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final CurrentMembership currentMembership;

    public AuthController(CurrentMembership currentMembership) {
        this.currentMembership = currentMembership;
    }

    @GetMapping("/api/csrf")
    ApiEnvelope<CsrfResponse> csrf(CsrfToken token) {
        return ApiEnvelope.data(new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @GetMapping("/api/session")
    ApiEnvelope<SessionResponse> session(
            @AuthenticationPrincipal FamilyUserPrincipal principal,
            org.springframework.security.core.Authentication authentication) {
        MembershipContext membership = currentMembership.require(authentication);
        return ApiEnvelope.data(new SessionResponse(
                principal.userId(),
                membership.householdId(),
                principal.email(),
                principal.displayName(),
                membership.role(),
                legacyUsername(principal.email())));
    }

    record CsrfResponse(String headerName, String parameterName, String token) {
    }

    record SessionResponse(
            Long userId,
            Long householdId,
            String email,
            String displayName,
            com.familyfinance.family.HouseholdRole role,
            String username) {
    }

    private static String legacyUsername(String email) {
        return "demo@local.family".equals(email) ? "demo" : email;
    }
}
