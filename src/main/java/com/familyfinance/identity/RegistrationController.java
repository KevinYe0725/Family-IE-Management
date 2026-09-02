package com.familyfinance.identity;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.RequestValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegistrationController {

    private static final int MAX_REGISTRATION_BODY_BYTES = 4_096;

    private final RegistrationService registrationService;
    private final RegistrationRateLimiter rateLimiter;

    RegistrationController(RegistrationService registrationService, RegistrationRateLimiter rateLimiter) {
        this.registrationService = registrationService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    ApiEnvelope<RegisterResponse> register(@RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        if (servletRequest.getContentLengthLong() > MAX_REGISTRATION_BODY_BYTES) {
            throw new RequestValidationException(Map.of("request", "请求内容过大"));
        }
        if (!rateLimiter.tryAcquire(request.email(), servletRequest.getRemoteAddr())) {
            throw new RegistrationRateLimitedException();
        }
        RegisterResponse response = registrationService.register(registrationService.validateCreate(request));
        return ApiEnvelope.data(response);
    }
}
