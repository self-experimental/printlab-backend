package ru.self.printlab_backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;
import ru.self.printlab_backend.model.material.MaterialProfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class MaterialCatalogIntegrationTest {
    @Autowired WebApplicationContext context;
    @Autowired MaterialCatalogRepository repository;

    @Test void servesVersionedCatalogWithConditionalCaching() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(get("/api/materials")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=3600"))
                .andExpect(header().string("ETag", repository.etag()))
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.materials.length()").value(8))
                .andExpect(jsonPath("$.materials[0].source.sha256").isString());
        mvc.perform(get("/api/materials").header("If-None-Match", repository.etag()))
                .andExpect(status().isNotModified()).andExpect(content().string(""));
    }

    @Test void preservesScopeAndUnknownsRatherThanInventingMeasurements() {
        var materials = repository.catalog().materials();
        assertThat(materials.stream().flatMap(m -> m.testSeries().stream())
                .flatMap(s -> s.measurements().stream()).count()).isEqualTo(39);
        var abs = materials.stream().filter(m -> m.family().equals("ABS")).findFirst().orElseThrow();
        assertThat(abs.testSeries().getFirst().specimenType()).isEqualTo(MaterialProfile.SpecimenType.INJECTION_MOLDED);
        assertThat(abs.testSeries().getFirst().conditions()).isNull();
        assertThat(abs.testSeries().getFirst().measurements().getFirst().uncertainty()).isNull();
        var tpu = materials.stream().filter(m -> m.family().equals("TPU_95A")).findFirst().orElseThrow();
        assertThat(tpu.densityGcm3()).isEqualTo(1.25);
        assertThat(tpu.hdtAt045MpaC()).isNull();
        assertThat(tpu.testSeries().getFirst().measurements()).hasSize(1);
        assertThat(tpu.testSeries().getFirst().conditions().infillPrintSpeedMmS()).isEqualTo(36);
        assertThat(tpu.printing().maxPrintSpeedMmS()).isEqualTo(30);
        var pla = materials.getFirst().testSeries().getFirst().measurements();
        assertThat(pla.stream().filter(m -> m.property() == MaterialProfile.Property.TENSILE_MODULUS)
                .map(MaterialProfile.Measurement::value)).containsExactly(2300.0, 2400.0);
    }

    @Test void catalogDoesNotSubstituteMaterialStrengthForPartLoad() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(multipart("/api/optimization/analyze-stl")
                        .file("file", OptimizationIntegrationTest.cube(20))
                        .param("material", "ASA").param("materialProfileId", "prusament-asa")
                        .param("requiredLoadN", "40").param("safetyFactor", "2")
                        .param("priority", "MIN_WEIGHT").param("maxPrintTimeMin", "0")
                        .param("maxMaterialConsumptionG", "0").param("layerHeightMm", "0").param("perimeters", "0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_EXPERIMENTS"));
        mvc.perform(multipart("/api/optimization/analyze-stl")
                        .file("file", OptimizationIntegrationTest.cube(20))
                        .param("material", "PLA").param("materialProfileId", "prusament-petg")
                        .param("requiredLoadN", "40").param("safetyFactor", "2")
                        .param("priority", "MIN_WEIGHT").param("maxPrintTimeMin", "0")
                        .param("maxMaterialConsumptionG", "0").param("layerHeightMm", "0").param("perimeters", "0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_MATERIAL_PROFILE"));
    }
}
