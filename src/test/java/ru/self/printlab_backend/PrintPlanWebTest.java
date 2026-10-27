package ru.self.printlab_backend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.self.printlab_backend.controller.PrintPlanController;
import ru.self.printlab_backend.exception.GlobalExceptionHandler;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;
import ru.self.printlab_backend.service.PrintPlanService;
import ru.self.printlab_backend.slicing.SlicingEngine;
import ru.self.printlab_backend.stl.*;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PrintPlanWebTest {
    @Test void validatesPrintInputsAndReturnsFriendlyCodesWithoutNativeDiagnostics() throws Exception {
        var mapper = JsonMapper.builder().build();
        SlicingEngine engine = (file, settings, material, deadline) -> new SlicingEngine.Estimate(20, 4, "test", false);
        var service = new PrintPlanService(new MaterialCatalogRepository(mapper, new ClassPathResource("catalog/materials.json")),
                new StlFileParser(new BinaryStlParser()), engine, mapper, new ru.self.printlab_backend.slicing.SlicingTimeouts(180, 45, 40), new ru.self.printlab_backend.recommendation.PrintRecommendations(
                        description -> ru.self.printlab_backend.recommendation.PartPurpose.general(), new MaterialCatalogRepository(mapper, new ClassPathResource("catalog/materials.json"))));
        var mvc = MockMvcBuilders.standaloneSetup(new PrintPlanController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String quality : new String[] {"0.2", "0.16", "0.08", "0.28", "0.07", "0.29", "NaN"}) {
            var request = multipart("/api/print-plans/analyze-stl").file("file", OptimizationIntegrationTest.cube(31))
                    .param("materialProfileId", "prusament-pla").param("priority", "MIN_TIME").param("layerHeightMm", quality)
                    .param("minimumInfillPercent", "15").param("minimumPerimeters", "2").param("supportMode", "AUTO")
                    .param("maxPrintTimeMin", "0").param("maxMaterialConsumptionG", "0");
            var response = mvc.perform(request);
            if (java.util.Set.of("0.2", "0.16", "0.08", "0.28").contains(quality)) response.andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.candidates").isArray())
                    .andExpect(jsonPath("$.plannedCandidateCount").isNumber())
                    .andExpect(jsonPath("$.candidates[0].infillPattern").value("GYROID"));
            else response.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRINT_INPUT"));
        }
        mvc.perform(multipart("/api/print-plans/analyze-stl").file("file", OptimizationIntegrationTest.cube(31))
                .header("X-PrintLab-Requested-Only", "true")
                .param("layerHeightMm", "0.27").param("minimumInfillPercent", "55"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.plannedCandidateCount").value(1))
                .andExpect(jsonPath("$.candidates[0].layerHeightMm").value(.27))
                .andExpect(jsonPath("$.candidates[0].infillPercent").value(55));
        mvc.perform(multipart("/api/print-plans/analyze-stl").file("file", OptimizationIntegrationTest.cube(31))
                .param("description", "Держатель наушников"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recommendation.items.length()").value(6))
                .andExpect(jsonPath("$.materialName").value("Prusament PLA"))
                .andExpect(jsonPath("$.candidates[0].topLayers").value(4));
        mvc.perform(multipart("/api/print-plans/analyze-stl").file("file", OptimizationIntegrationTest.cube(31))
                .param("description", "x".repeat(2001)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRINT_INPUT"));
    }
}
