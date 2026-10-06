package com.rammendez.warehouse.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.rammendez.warehouse.common.ApiExceptionHandler;
import com.rammendez.warehouse.security.SecurityRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import javax.crypto.spec.SecretKeySpec;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecretKeySpec jwtKey(@Value("${security.jwt.secret}") String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKeySpec key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKeySpec key, @Value("${security.jwt.issuer}") String issuer) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http, SecurityRepository repository, ObjectMapper mapper)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(
                auth ->
                        auth.requestMatchers(
                                        "/swagger-ui/**",
                                        "/swagger-ui.html",
                                        "/v3/api-docs/**",
                                        "/actuator/health")
                                .permitAll()
                                .requestMatchers(
                                        HttpMethod.POST,
                                        "/api/v1/auth/login",
                                        "/api/v1/auth/refresh",
                                        "/api/v1/contact")
                                .permitAll()
                                .requestMatchers("/actuator/**")
                                .hasAuthority("PERM_AUDIT_READ")
                                .anyRequest()
                                .authenticated());
        var resolver = new DefaultBearerTokenResolver();
        org.springframework.security.web.AuthenticationEntryPoint entry =
                (request, response, error) -> writeSecurityProblemResponse(request, response, 401, mapper);
        org.springframework.security.web.access.AccessDeniedHandler denied =
                (request, response, error) -> writeSecurityProblemResponse(request, response, 403, mapper);
        http.oauth2ResourceServer(
                oauth ->
                        oauth.bearerTokenResolver(
                                        request -> {
                                            String path = request.getServletPath();
                                            if (path.isEmpty()) {
                                                path =
                                                        request.getRequestURI()
                                                                .substring(
                                                                        request.getContextPath()
                                                                                .length());
                                            }
                                            if (request.getMethod().equals("POST")
                                                    && (path.equals("/api/v1/auth/login")
                                                            || path.equals("/api/v1/auth/refresh")
                                                            || path.equals("/api/v1/contact"))) {
                                                return null;
                                            }
                                            return resolver.resolve(request);
                                        })
                                .authenticationEntryPoint(entry)
                                .accessDeniedHandler(denied)
                                .jwt(
                                        jwt ->
                                                jwt.jwtAuthenticationConverter(
                                                        token -> {
                                                            long userId;
                                                            try {
                                                                userId =
                                                                        Long.parseLong(
                                                                                token.getSubject());
                                                            } catch (NumberFormatException ex) {
                                                                throw createInvalidTokenException();
                                                            }
                                                            var user =
                                                                    repository
                                                                            .findUserById(userId)
                                                                            .orElseThrow(
                                                                                    SecurityConfig
                                                                                            ::createInvalidTokenException);
                                                            Number version = token.getClaim("av");
                                                            if (!user.enabled()
                                                                    || user.locked()
                                                                    || version == null
                                                                    || version.longValue()
                                                                            != user.authVersion()) {
                                                                throw createInvalidTokenException();
                                                            }
                                                            var authorities =
                                                                    new ArrayList<
                                                                            SimpleGrantedAuthority>();
                                                            repository
                                                                    .findUserRoleCodes(userId)
                                                                    .forEach(
                                                                            role ->
                                                                                    authorities.add(
                                                                                            new SimpleGrantedAuthority(
                                                                                                    role)));
                                                            repository
                                                                    .findUserPermissionCodes(userId)
                                                                    .forEach(
                                                                            permission ->
                                                                                    authorities.add(
                                                                                            new SimpleGrantedAuthority(
                                                                                                    permission)));
                                                            return new JwtAuthenticationToken(
                                                                    token,
                                                                    authorities,
                                                                    user.username());
                                                        })));
        http.exceptionHandling(
                errors -> errors.authenticationEntryPoint(entry).accessDeniedHandler(denied));
        return http.build();
    }

    private static OAuth2AuthenticationException createInvalidTokenException() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
    }

    private static void writeSecurityProblemResponse(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            int status,
            ObjectMapper mapper)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter()
                .write(
                        mapper.writeValueAsString(
                                ApiExceptionHandler.createProblemDetailWithRequestMetadata(
                                        org.springframework.http.HttpStatus.valueOf(status),
                                        "Authentication or permission denied",
                                        request)));
    }
}
