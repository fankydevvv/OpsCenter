package com.opscenter.shared.infrastructure.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document served by springdoc at {@code /v3/api-docs} and browsable at
 * {@code /swagger-ui.html}.
 * <p>
 * 04-API remains the contract; the generated document is the live, always-current view of what the
 * code actually exposes and lets a developer try endpoints with a JWT (the {@code bearerAuth}
 * scheme adds the "Authorize" button).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI opscenterOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OpsCenter API")
                        .version("v1")
                        .description("Incident operations platform - base path /api/v1. "
                                + "Errors use {timestamp, requestId, status, code, message, fieldErrors[]}."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
