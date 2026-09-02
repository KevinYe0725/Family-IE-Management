package com.familyfinance.identity;

import com.familyfinance.auth.FamilyUserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PasswordController {

    private final RegistrationService registrationService;

    PasswordController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @PostMapping("/api/auth/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(
            @AuthenticationPrincipal FamilyUserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        registrationService.changePassword(principal.userId(), request.currentPassword(), request.newPassword());
    }

    record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }
}
