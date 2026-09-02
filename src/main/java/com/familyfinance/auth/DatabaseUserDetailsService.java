package com.familyfinance.auth;

import com.familyfinance.family.CurrentMembership;
import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import java.util.Locale;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final CurrentMembership currentMembership;

    public DatabaseUserDetailsService(AppUserRepository users, CurrentMembership currentMembership) {
        this.users = users;
        this.currentMembership = currentMembership;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) {
        AppUser user = users.findByEmail(normalizeLogin(login))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));
        FamilyUserPrincipal principal = new FamilyUserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getPasswordHash(),
                user.getStatus());
        requireActiveMembership(principal);
        return principal;
    }

    private void requireActiveMembership(FamilyUserPrincipal principal) {
        try {
            currentMembership.require(new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities()));
        } catch (AccessDeniedException exception) {
            throw new UsernameNotFoundException("Invalid username or password");
        }
    }

    static String normalizeLogin(String login) {
        if ("demo".equals(login)) {
            return "demo@local.family";
        }
        return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    }
}
