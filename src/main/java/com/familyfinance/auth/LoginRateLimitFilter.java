package com.familyfinance.auth;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final PathPattern LOGIN_PATH = new PathPatternParser().parse("/api/auth/login");

    private final LoginRateLimiter limiter;
    private final ObjectMapper objectMapper;

    public LoginRateLimitFilter(LoginRateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!limiter.tryAcquire(request.getParameter("username"), request.getRemoteAddr())) {
            response.setStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    ApiEnvelope.error(ApiError.of("LOGIN_RATE_LIMITED", "登录暂时无法完成")));
            return;
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !LOGIN_PATH.matches(ServletRequestPathUtils.parse(request).pathWithinApplication());
    }
}
