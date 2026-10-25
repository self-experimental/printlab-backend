package ru.self.printlab_backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("production")
class ProductionWebIntegrationTest {
    @TempDir static Path directory;
    @Autowired WebApplicationContext context;

    @DynamicPropertySource
    static void isolateData(DynamicPropertyRegistry registry) {
        registry.add("printlab.data.dir", () -> directory.resolve("data").toString());
        registry.add("printlab.models.dir", () -> directory.resolve("uploads").toString());
    }

    @Test void servesCatalogButHidesDocumentationAndReturns404ForMissingRoutes() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/materials")).andExpect(status().isOk())
                .andExpect(jsonPath("$.materials.length()").value(8));
        for (String path : new String[] {"/v3/api-docs", "/swagger-ui.html", "/api/not-a-route"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("Страница не найдена."));
        }
    }
}
