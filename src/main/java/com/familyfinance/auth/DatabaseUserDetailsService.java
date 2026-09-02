package com.familyfinance.auth;

import com.familyfinance.household.AppUser;
import com.familyfinance.household.AppUserRepository;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public DatabaseUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String login) {
        AppUser user = users.findByEmail(normalizeLogin(login))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));
        return new FamilyUserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getPasswordHash());
    }

    private static String normalizeLogin(String login) {
        if ("demo".equals(login)) {
            return "demo@local.family";
        }
        return login.trim().toLowerCase(Locale.ROOT);
    }
}
