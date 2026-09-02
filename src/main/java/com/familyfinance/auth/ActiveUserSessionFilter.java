package com.familyfinance.auth;

import com.familyfinance.household.AppUserRepository;
import com.familyfinance.household.AppUserStatus;
import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.ObjectMapper;

public class ActiveUserSessionFilter extends OncePerRequestFilter {

    private final AppUserRepository users;
    private final ObjectMapper objectMapper;

    public ActiveUserSessionFilter(AppUserRepository users, ObjectMapper objectMapper) {
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof FamilyUserPrincipal principal
                && !users.existsByIdAndStatus(principal.userId(), AppUserStatus.ACTIVE)) {
            SecurityContextHolder.clearContext();
            if (request.getSession(false) != null) {
                request.getSession(false).invalidate();
            }
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    ApiEnvelope.error(ApiError.of("AUTH_REQUIRED", "请先登录")));
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = ServletRequestPathUtils.parse(request).pathWithinApplication().value();
        return !path.startsWith("/api/")
                || path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/csrf");
    }
}
