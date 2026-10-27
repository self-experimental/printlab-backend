package ru.self.printlab_backend.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Replays real model responses through the actual Java parser and policy; no neural or slicer calls. */
class RecordedRecommendationEvaluationTest {
    @Test void realModelResponsesProduceTheExpectedPrintRequirements() throws Exception {
        String recording = System.getProperty("printlab.recommendation-evaluation");
        assumeTrue(recording != null, "Opt in with -Dprintlab.recommendation-evaluation=/path/to/results.json");
        var mapper = JsonMapper.builder().build();
        var cases = mapper.readTree(new ClassPathResource("recommendation/requirements-cases.json").getInputStream());
        var rows = mapper.readTree(Files.readString(Path.of(recording)));
        Map<String, tools.jackson.databind.JsonNode> byId = new HashMap<>();
        for (var row : rows) byId.put(row.path("id").asText(), row);
        var classifier = new OllamaPurposeClassifier(mapper, true, "http://127.0.0.1:1", "recording", 1);
        var catalog = new MaterialCatalogRepository(mapper, new ClassPathResource("catalog/materials.json"));
        List<String> failures = new ArrayList<>();
        String promptHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((new ClassPathResource("recommendation/requirements-prompt.txt").getContentAsString(java.nio.charset.StandardCharsets.UTF_8) + "\n"
                        + new ClassPathResource("recommendation/requirement-questions.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        int passed = 0;
        for (var example : cases) {
            String id = example.path("id").asText();
            var row = byId.get(id);
            try {
                if (row == null || !row.has("raw")) throw new IllegalArgumentException("No complete model response");
                if (!promptHash.equals(row.path("promptSha256").asText())) throw new IllegalArgumentException("Recording uses another prompt");
                var envelope = mapper.valueToTree(Map.of("done", true, "message", Map.of("content", mapper.writeValueAsString(row.path("raw")))));
                String text = example.path("text").asText();
                var purpose = classifier.parse(envelope, text);
                var advisor = new PrintRecommendations(description -> purpose, catalog);
                var settings = advisor.resolve(new PrintPlanRequest(null, null, null, null, null, null, null, null, text, null, null, null)).settings();
                var actual = Map.of("infill", settings.minimumInfillPercent(), "walls", settings.minimumPerimeters(),
                        "layer", settings.layerHeightMm(), "materialFamily", catalog.catalog().materials().stream()
                                .filter(m -> m.id().equals(settings.materialProfileId())).findFirst().orElseThrow().family());
                var expected = example.path("expectedSettings");
                if (settings.minimumInfillPercent() != expected.path("infill").asInt()
                        || settings.minimumPerimeters() != expected.path("walls").asInt()
                        || settings.layerHeightMm() != expected.path("layer").asDouble()
                        || !actual.get("materialFamily").equals(expected.path("materialFamily").asText())) {
                    failures.add(id + " actual=" + actual + " expected=" + expected);
                } else passed++;
            } catch (Exception e) { failures.add(id + " " + e.getMessage()); }
        }
        System.out.println("Recorded recommendation outcomes: " + passed + "/" + cases.size());
        failures.forEach(System.out::println);
        assertThat(failures).as("Real model → grounded requirements → print settings").isEmpty();
    }
}
