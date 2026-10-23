package dev.kawa;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI kawaOpenApi(ObjectProvider<BuildProperties> build) {
        // build-info only exists after a Maven build; fall back when running straight from an IDE
        BuildProperties props = build.getIfAvailable();
        return new OpenAPI().info(new Info()
                .title("Kawa API")
                .version(props == null ? "dev" : props.getVersion())
                .description("A durable job queue on PostgreSQL: enqueue jobs, claim them with a lease, "
                        + "and ack or nack them. Errors use RFC 9457 problem details.")
                .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
