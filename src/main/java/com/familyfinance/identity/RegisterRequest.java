package com.familyfinance.identity;

public record RegisterRequest(
        String email,
        String displayName,
        String password,
        String mode,
        String householdName,
        String inviteToken) {
}
