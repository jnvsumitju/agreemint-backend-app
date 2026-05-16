package com.agreemint.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI agreemintOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Crixaa API")
                        .version("1.0.0")
                        .description("Multi-tenant agreement template management API. " +
                                "Provides endpoints for authentication, template CRUD, " +
                                "PDF generation, organization management, marketplace, and real-time collaboration.")
                        .contact(new Contact()
                                .name("Crixaa")
                                .url("https://agreemint.com")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT access token. Obtain via POST /api/auth/login or /api/auth/register."))
                        // Public /api/v1/* endpoints authenticate via X-Api-Key; per-endpoint
                        // scope is enforced server-side (see PublicApiController @PreAuthorize).
                        .addSecuritySchemes("ApiKeyAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")
                                .description("Org-scoped API key. Create one at Settings → Developer. "
                                        + "Format: ak_live_<40 hex chars>.")));
    }
}
