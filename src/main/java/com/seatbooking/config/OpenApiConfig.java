package com.seatbooking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at /swagger-ui.html. The Authorize button takes the access_token from POST /auth/token. */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";
    private static final String HEALTH_WILDCARD_PATH = "/health/**";
    private static final String ACTUATOR_TAG = "Actuator";

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Seat Booking API")
                        .version("v1")
                        .description("Race-free assigned-seat reservation. Get a token from POST /auth/token, "
                                + "then click Authorize and paste the access_token."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /** Without this, springdoc uses its own ObjectMapper and documents camelCase instead of the real snake_case. */
    @Bean
    public ModelResolver modelResolver(ObjectMapper objectMapper) {
        return new ModelResolver(objectMapper);
    }

    /** Actuator documents probes as an untryable wildcard (/health/**); list the two real probe URLs instead. */
    @Bean
    public OpenApiCustomizer healthProbePaths() {
        return openApi -> {
            if (openApi.getPaths().remove(HEALTH_WILDCARD_PATH) == null) {
                return;
            }
            openApi.getPaths()
                    .addPathItem("/health/liveness", probe("Liveness: UP while the process is running"))
                    .addPathItem("/health/readiness", probe("Readiness: also checks the database; 503 when it is down"));
        };
    }

    private static PathItem probe(String summary) {
        return new PathItem().get(new Operation()
                .tags(List.of(ACTUATOR_TAG))
                .summary(summary)
                .security(List.of())
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse().description("UP"))
                        .addApiResponse("503", new ApiResponse().description("DOWN"))));
    }
}
