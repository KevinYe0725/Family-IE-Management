package com.familyfinance.config;

import com.familyfinance.auth.FamilyUserPrincipal;
import com.familyfinance.family.CurrentMembership;
import com.familyfinance.family.MembershipContext;
import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            CurrentMembership currentMembership) throws Exception {
        http
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.migrateSession()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**", "/static/**").permitAll()
                        .requestMatchers("/api/csrf", "/api/auth/login").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            FamilyUserPrincipal principal = (FamilyUserPrincipal) authentication.getPrincipal();
                            try {
                                MembershipContext membership = currentMembership.require(authentication);
                                writeJson(response, HttpServletResponse.SC_OK, objectMapper, ApiEnvelope.data(
                                        new LoginResponse(
                                                principal.userId(),
                                                membership.householdId(),
                                                principal.email(),
                                                principal.displayName(),
                                                membership.role(),
                                                "demo@local.family".equals(principal.email())
                                                        ? "demo"
                                                        : principal.email())));
                            } catch (AccessDeniedException exception) {
                                SecurityContextHolder.clearContext();
                                if (request.getSession(false) != null) {
                                    request.getSession(false).invalidate();
                                }
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN, objectMapper,
                                        ApiEnvelope.error(ApiError.of("FORBIDDEN", "没有权限执行此操作")));
                            }
                        })
                        .failureHandler((request, response, exception) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, objectMapper,
                                        ApiEnvelope.error(ApiError.of("LOGIN_FAILED", "用户名或密码错误")))))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, objectMapper,
                                        ApiEnvelope.error(ApiError.of("AUTH_REQUIRED", "请先登录"))))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJson(response, HttpServletResponse.SC_FORBIDDEN, objectMapper,
                                        ApiEnvelope.error(ApiError.of("FORBIDDEN", "没有权限执行此操作")))));

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void writeJson(
            HttpServletResponse response,
            int status,
            ObjectMapper objectMapper,
            ApiEnvelope<?> body) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }

    private record LoginResponse(
            Long userId,
            Long householdId,
            String email,
            String displayName,
            com.familyfinance.family.HouseholdRole role,
            String username) {
    }
}
