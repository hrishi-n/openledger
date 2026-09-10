package dev.openledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openLedgerApi() {
        return new OpenAPI().info(new Info()
            .title("openledger")
            .version("0.1.0")
            .description("Append-only double-entry ledger service. "
                + "Pass X-Tenant-Id to scope requests; pass Idempotency-Key on writes.")
            .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
