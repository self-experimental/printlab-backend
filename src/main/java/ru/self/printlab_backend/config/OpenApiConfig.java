package ru.self.printlab_backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI printlabOpenApi() {
        return new OpenAPI().info(new Info()
                .title("PrintLab Backend API")
                .description("REST API для анализа STL и подбора параметров 3D-печати")
                .version("0.0.1"));
    }
}