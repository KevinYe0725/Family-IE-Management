package com.familyfinance.auth;

import com.familyfinance.shared.ApiEnvelope;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    @GetMapping("/api/csrf")
    ApiEnvelope<CsrfResponse> csrf(CsrfToken token) {
        return ApiEnvelope.data(new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken()));
    }

    @GetMapping("/api/session")
    ApiEnvelope<SessionResponse> session(@AuthenticationPrincipal FamilyUserPrincipal principal) {
        return ApiEnvelope.data(new SessionResponse(principal.userId(), principal.householdId(), principal.username()));
    }

    record CsrfResponse(String headerName, String parameterName, String token) {
    }

    record SessionResponse(Long userId, Long householdId, String username) {
    }
}
