package com.familyfinance.auth;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import com.familyfinance.household.AppUserStatus;

public record FamilyUserPrincipal(
        Long userId,
        String email,
        String displayName,
        String passwordHash,
        AppUserStatus status) implements UserDetails {

    public FamilyUserPrincipal(Long userId, String email, String displayName, String passwordHash) {
        this(userId, email, displayName, passwordHash, AppUserStatus.ACTIVE);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return status == AppUserStatus.ACTIVE;
    }
}
