package com.stockportfolio.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenInterceptorTest {

    @Test
    void allowsRequestsWhenTokenAuthIsDisabled() throws Exception {
        ApiTokenProperties properties = new ApiTokenProperties();
        properties.setEnabled(false);
        ApiTokenInterceptor interceptor = new ApiTokenInterceptor(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/portfolio/summary");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingBearerTokenWhenEnabled() throws Exception {
        ApiTokenProperties properties = new ApiTokenProperties();
        properties.setEnabled(true);
        properties.setToken("secret-token");
        ApiTokenInterceptor interceptor = new ApiTokenInterceptor(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/portfolio/summary");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void allowsMatchingBearerTokenWhenEnabled() throws Exception {
        ApiTokenProperties properties = new ApiTokenProperties();
        properties.setEnabled(true);
        properties.setToken("secret-token");
        ApiTokenInterceptor interceptor = new ApiTokenInterceptor(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/portfolio/summary");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void skipsCorsPreflightRequests() throws Exception {
        ApiTokenProperties properties = new ApiTokenProperties();
        properties.setEnabled(true);
        properties.setToken("secret-token");
        ApiTokenInterceptor interceptor = new ApiTokenInterceptor(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/portfolio/summary");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
