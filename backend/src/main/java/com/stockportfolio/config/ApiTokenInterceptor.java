package com.stockportfolio.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiTokenInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiTokenProperties properties;

    public ApiTokenInterceptor(ApiTokenProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!properties.isEnabled() || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        if (!properties.hasToken()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "API token auth is enabled but no token is configured.");
            return false;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing bearer token.");
            return false;
        }

        String token = authorization.substring(BEARER_PREFIX.length());
        if (!properties.getToken().equals(token)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid bearer token.");
            return false;
        }

        return true;
    }
}
