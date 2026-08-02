package com.stockportfolio.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI stockPortfolioOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Investment Research & Portfolio Platform API")
                        .version("0.1.0")
                        .description("REST API for the Investment Research & Portfolio Platform, used by the React frontend and native SwiftUI iPhone/iPad app."))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("opaque")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
