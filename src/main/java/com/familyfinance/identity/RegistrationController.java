package com.familyfinance.identity;

import com.familyfinance.shared.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
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
        if (!rateLimiter.tryAcquire(request.email(), servletRequest.getRemoteAddr())) {
            throw new RegistrationRateLimitedException();
        }
        RegisterResponse response = "JOIN".equals(request.mode())
                ? registrationService.join(registrationService.validateJoin(request))
                : registrationService.register(registrationService.validateCreate(request));
        return ApiEnvelope.data(response);
    }
}
