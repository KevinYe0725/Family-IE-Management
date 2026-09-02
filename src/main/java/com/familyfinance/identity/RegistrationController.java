package com.familyfinance.identity;

import com.familyfinance.shared.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegistrationController {

    private final RegistrationService registrationService;
    private final RegistrationRateLimiter rateLimiter;

    RegistrationController(RegistrationService registrationService, RegistrationRateLimiter rateLimiter) {
        this.registrationService = registrationService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<RegisterResponse> register(@RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        String key = normalizedEmail(request.email()) + "|" + servletRequest.getRemoteAddr();
        if (!rateLimiter.allows(key)) {
            throw new RegistrationRateLimitedException();
        }
        try {
            RegisterResponse response = registrationService.register(registrationService.validateCreate(request));
            rateLimiter.recordSuccess(key);
            return ApiEnvelope.data(response);
        } catch (RuntimeException exception) {
            rateLimiter.recordFailure(key);
            throw exception;
        }
    }

    private static String normalizedEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
