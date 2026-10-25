package ru.self.printlab_backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class OptimizationIntegrationTest {
    @TempDir static Path models;
    @Autowired WebApplicationContext context;
    @Autowired ru.self.printlab_backend.stl.ModelIdentificationService identification;
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("printlab.models.dir", () -> models.toString());
    }
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    static byte[] cube(double side) {
        double[][] points = {{0,0,0},{side,0,0},{side,side,0},{0,side,0},
                {0,0,side},{side,0,side},{side,side,side},{0,side,side}};
        int[][] faces = {{0,2,1},{0,3,2},{4,5,6},{4,6,7},{0,1,5},{0,5,4},
                {3,7,6},{3,6,2},{0,4,7},{0,7,3},{1,2,6},{1,6,5}};
        ByteBuffer buffer = ByteBuffer.allocate(84 + 50 * faces.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(80);
        buffer.putInt(faces.length);
        for (int[] face : faces) {
            buffer.putFloat(0).putFloat(0).putFloat(0);
            for (int vertex : face) for (double coordinate : points[vertex]) buffer.putFloat((float) coordinate);
            buffer.putShort((short) 0);
        }
        return buffer.array();
    }

    private MockMultipartHttpServletRequestBuilder request(double side, String load) {
        return multipart("/api/optimization/analyze-stl")
                .file(new MockMultipartFile("file", "cube.stl", "application/octet-stream", cube(side)))
                .param("material", "PLA").param("requiredLoadN", load).param("safetyFactor", "2")
                .param("maxPrintTimeMin", "0").param("maxMaterialConsumptionG", "0")
                .param("layerHeightMm", "0.2").param("perimeters", "0")
                .param("testType", "изгиб").param("priority", "MIN_WEIGHT");
    }

    private MockMultipartHttpServletRequestBuilder request(double side, String load, String field, String value) {
        var builder = request(side, load);
        builder.with(req -> { req.setParameter(field, value); return req; });
        return builder;
    }

    @Test void calculatesDifferentResultsForDifferentLoadsWithoutSavingFiles() throws Exception {
        for (String[] values : new String[][] {{"30","3.0","exp-001"},{"40","15.0","exp-002"},{"45","30.0","exp-003"}}) {
            mvc.perform(request(20, values[0])).andExpect(status().isOk())
                    .andExpect(jsonPath("$.matchedModelName").value("Куб 20 мм"))
                    .andExpect(jsonPath("$.selectedExperimentId").value(values[2]))
                    .andExpect(jsonPath("$.recommendedInfillPercent").value(Double.parseDouble(values[1])))
                    .andExpect(jsonPath("$.experiments.length()").value(3))
                    .andExpect(jsonPath("$.warnings[0]").isString());
        }
        try (var files = Files.list(models)) { assertEquals(0, files.count()); }
    }

    @Test void usesMeasuredLoadWhenCompressiveStrengthIsNotRecorded() throws Exception {
        mvc.perform(request(20, "30", "priority", "MAX_STRENGTH"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.selectedExperimentId").value("exp-003"))
                .andExpect(jsonPath("$.loadCapacityN").value(46.58));
    }

    @Test void explainsUnsupportedGeometryMissingDataAndUnsatisfiedConstraints() throws Exception {
        mvc.perform(request(10, "40")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_SUPPORTED"));
        mvc.perform(request(20, "100")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_SUITABLE_CONFIGURATION"));
        mvc.perform(request(20, "40", "material", "PETG"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_EXPERIMENTS"));
        mvc.perform(request(20, "40", "testType", "сжатие"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_EXPERIMENTS"));
        mvc.perform(request(20, "40", "maxPrintTimeMin", "30"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("NO_SUITABLE_CONFIGURATION"));
        mvc.perform(request(20, "40", "perimeters", "3"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test void validatesRequestsAndDoesNotTrustBrowserValidation() throws Exception {
        mvc.perform(request(20, "-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CALCULATION_INPUT"));
        mvc.perform(request(20, "40", "safetyFactor", "0.5"))
                .andExpect(status().isBadRequest());
        mvc.perform(request(20, "40", "priority", "unknown"))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/optimization/analyze-stl"))
                .andExpect(status().isBadRequest());
    }

    @Test void equalDimensionsDoNotMatchADifferentVolumeOrSurface() {
        var geometry = ru.self.printlab_backend.model.ModelGeometry.builder()
                .boundingBoxXmm(20).boundingBoxYmm(20).boundingBoxZmm(20)
                .volumeMm3(4000).surfaceAreaMm2(2400).triangleCount(12).build();
        assertTrue(identification.identify(geometry).isEmpty());
        geometry.setVolumeMm3(8000);
        geometry.setSurfaceAreaMm2(1200);
        assertTrue(identification.identify(geometry).isEmpty());
    }
}
