package com.stockportfolio.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(ApiTokenProperties.class)
public class ApiWebConfig implements WebMvcConfigurer {

    private final ApiTokenInterceptor apiTokenInterceptor;

    public ApiWebConfig(ApiTokenInterceptor apiTokenInterceptor) {
        this.apiTokenInterceptor = apiTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiTokenInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**"
                );
    }
}
