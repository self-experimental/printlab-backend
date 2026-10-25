package ru.self.printlab_backend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MaterialCatalogRepositoryTest {
    @Test void rejectsMissingSourcesAndInvalidUnitsOrGeometryConditions() throws Exception {
        String original;
        try (var stream = new ClassPathResource("catalog/materials.json").getInputStream()) {
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String bad : List.of(
                original.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
                original.replace("https://", "http://"),
                original.replace("\"unit\": \"MPa\"", "\"unit\": \"N\""),
                original.replace("\"densityGcm3\": 1.24", "\"densityGcm3\": -1"),
                original.replace("\"infillPercent\": 100", "\"infillPercent\": 150"),
                original.replace("\"verifiedOn\": \"2026-10-24\"", "\"verifiedOn\": \"2020-01-01\""),
                original.replace("\"sha256\": \"68c8298b", "\"sha256\": \"invalid"),
                original.replace("\"max\": 220", "\"max\": 100"))) {
            assertThatThrownBy(() -> new MaterialCatalogRepository(JsonMapper.builder().build(),
                    new ByteArrayResource(bad.getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void readsConfiguredCatalogOnceAndFailsExplicitlyOnMissingFile() throws Exception {
        var path = Files.createTempFile("material-catalog-", ".json");
        try {
            try (var stream = new ClassPathResource("catalog/materials.json").getInputStream()) {
                Files.write(path, stream.readAllBytes());
            }
            var repository = new MaterialCatalogRepository(JsonMapper.builder().build(), new FileSystemResource(path));
            Files.writeString(path, "invalid replacement");
            assertThat(repository.catalog().materials()).hasSize(8);
            assertThatThrownBy(() -> new MaterialCatalogRepository(JsonMapper.builder().build(), new FileSystemResource(path)))
                    .isInstanceOf(IllegalStateException.class);
            Files.delete(path);
            assertThatThrownBy(() -> new MaterialCatalogRepository(JsonMapper.builder().build(), new FileSystemResource(path)))
                    .isInstanceOf(IllegalStateException.class);
        } finally { Files.deleteIfExists(path); }
    }
}
