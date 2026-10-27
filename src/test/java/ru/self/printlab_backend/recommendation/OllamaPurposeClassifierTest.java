package ru.self.printlab_backend.recommendation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OllamaPurposeClassifierTest {
    final JsonMapper mapper = JsonMapper.builder().build();
    HttpServer server;
    final AtomicInteger releases = new AtomicInteger();
    @AfterEach void stop() { if (server != null) server.stop(0); }
    String listen(java.util.function.Function<String, String> handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String result;
            if (mapper.readTree(requestBody).path("messages").size() == 0) { releases.incrementAndGet(); result = "{\"done\":true}"; }
            else result = handler.apply(requestBody);
            // The test oracle returns exactly the requested axis from a complete fixture.
            try {
                var request = mapper.readTree(requestBody);
                String field = request.path("messages").get(1).path("content").asText().split("QUESTION: ", 2)[1].split(":", 2)[0];
                var envelope = mapper.readTree(result);
                var content = mapper.readTree(envelope.path("message").path("content").asText());
                if (content.isObject() && content.has(field)) result = mapper.writeValueAsString(Map.of("done", true,
                        "done_reason", "stop", "message", Map.of("content", mapper.writeValueAsString(content.path(field)))));
            } catch (Exception ignored) { /* Invalid transport fixtures must remain invalid. */ }
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.setExecutor(java.util.concurrent.Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
    Map<String, Object> facts() {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String name : new String[]{"use", "load", "loadKind", "environment", "flexibility", "goal"}) fields.put(name, java.util.List.of("UNKNOWN", ""));
        return fields;
    }
    void putFact(Map<String, Object> fields, String name, String value, String quote) {
        fields.put(name, java.util.List.of(value, quote));
    }
    String reply(Map<String, Object> fields) {
        return mapper.writeValueAsString(Map.of("done", true, "done_reason", "stop", "message", Map.of("content", mapper.writeValueAsString(fields))));
    }
    String high() {
        var fields = facts(); putFact(fields, "load", "HIGH", "огромная нагрузка"); return reply(fields);
    }
    @Test void sendsAllDescriptionsToModelWithGroundedSchemaAndCachesExactDescriptions() throws Exception {
        var calls = new AtomicInteger();
        String url = listen(body -> {
            calls.incrementAndGet(); var request = mapper.readTree(body);
            assertThat(request.path("think").asBoolean(true)).isFalse();
            assertThat(request.path("format").path("anyOf").size()).isEqualTo(2);
            assertThat(request.path("options").path("temperature").asInt()).isZero();
            assertThat(body).doesNotContain("fileName", "triangles", "vertices");
            return high();
        });
        var classifier = new OllamaPurposeClassifier(mapper, true, url, "test-model", 2);
        String text = "Кронштейн: огромная нагрузка будет, около 1500 Н";
        assertThat(classifier.classify(text).category()).isEqualTo(PartPurpose.Category.REINFORCED);
        assertThat(classifier.classify(text).source()).isEqualTo("LOCAL_MODEL");
        assertThat(classifier.classify("").source()).isEqualTo("DEFAULT");
        assertThat(calls.get()).isEqualTo(6);
        assertThat(releases.get()).isEqualTo(1);
        classifier.classify("Не декоративный кронштейн, огромная нагрузка"); assertThat(calls.get()).isEqualTo(12);
    }
    @Test void keepsGroundedLightDutyAndDoesNotBlanketDowngradeModelAnswers() throws Exception {
        String text = "плата для микросхемы, просто подставочка, нужно совсем слабо";
        var fields = facts();
        putFact(fields, "use", "FUNCTIONAL", "подставочка");
        putFact(fields, "load", "LIGHT", "совсем слабо");
        String url = listen(body -> reply(fields));
        var purpose = new OllamaPurposeClassifier(mapper, true, url, "test", 2).classify(text);
        assertThat(purpose.requirements().load().value()).isEqualTo(PartRequirements.Load.LIGHT);
        assertThat(purpose.requirements().quotes()).containsExactly("подставочка", "совсем слабо");
        putFact(fields, "use", "DECORATIVE", "Статуэтка");
        putFact(fields, "load", "UNKNOWN", "");
        assertThat(new OllamaPurposeClassifier(mapper, true, url, "test", 2).classify("Статуэтка").category()).isEqualTo(PartPurpose.Category.DECORATIVE);
    }
    @Test void rejectsInventedEvidenceExtraFieldsAndInvalidEnums() {
        var classifier = new OllamaPurposeClassifier(mapper, true, "http://127.0.0.1:1", "test", 1);
        var fields = facts();
        putFact(fields, "environment", "HEAT", "горячая среда");
        assertThatThrownBy(() -> classifier.parse(mapper.readTree(reply(fields)), "Корпус для платы")).isInstanceOf(IllegalArgumentException.class);
        putFact(fields, "environment", "HEAT", "");
        assertThatThrownBy(() -> classifier.parse(mapper.readTree(reply(fields)), "Корпус")).isInstanceOf(IllegalArgumentException.class);
        putFact(fields, "environment", "VACUUM", "Корпус");
        assertThatThrownBy(() -> classifier.parse(mapper.readTree(reply(fields)), "Корпус")).isInstanceOf(IllegalArgumentException.class);
        var extraFields = facts(); extraFields.put("infillPercent", 100);
        String extra = reply(extraFields);
        assertThatThrownBy(() -> classifier.parse(mapper.readTree(extra), "Корпус")).isInstanceOf(IllegalArgumentException.class);
        putFact(fields, "environment", "UNKNOWN", "Корпус");
        assertThatThrownBy(() -> classifier.parse(mapper.readTree(reply(fields)), "Корпус")).isInstanceOf(IllegalArgumentException.class);
        fields.put("environment", java.util.List.of("HEAT", "Корпус", "extra"));
        assertThatThrownBy(() -> classifier.parse(mapper.readTree(reply(fields)), "Корпус")).isInstanceOf(IllegalArgumentException.class);
        assertThat(classifier.parse(mapper.readTree(reply(facts())), "Непонятно").requirements()).isEqualTo(PartRequirements.unknown());
    }
    @Test void fallsBackHonestlyOnInvalidOrOversizedResponsesAndDoesNotCacheThem() throws Exception {
        var calls = new AtomicInteger();
        String url = listen(body -> calls.incrementAndGet() == 1 ? "x".repeat(17000) : high());
        var classifier = new OllamaPurposeClassifier(mapper, true, url, "test-model", 2);
        String text = "огромная нагрузка";
        assertThat(classifier.classify(text).source()).isEqualTo("FALLBACK");
        assertThat(classifier.classify(text).source()).isEqualTo("LOCAL_MODEL");
        assertThat(calls.get()).isEqualTo(7);
        assertThat(releases.get()).isEqualTo(2);
        assertThatThrownBy(() -> classifier.parse(mapper.readTree("{\"done\":false}"), text)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> classifier.parse(mapper.readTree("{\"done\":true,\"message\":{\"content\":\"{}\"}}"), text)).isInstanceOf(IllegalArgumentException.class);
        String cut = mapper.writeValueAsString(Map.of("done", true, "done_reason", "length", "message", Map.of("content", mapper.writeValueAsString(facts()))));
        assertThatThrownBy(() -> classifier.parse(mapper.readTree(cut), text)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void hasABoundedTimeoutAndRejectsInvalidConfiguration() throws Exception {
        String url = listen(body -> { try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return high(); });
        var classifier = new OllamaPurposeClassifier(mapper, true, url, "test-model", 1);
        long start = System.nanoTime();
        assertThat(classifier.classify("Деталь").source()).isEqualTo("FALLBACK");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1400);
        assertThatThrownBy(() -> new OllamaPurposeClassifier(mapper, true, "file:///private", "model", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> classifier.classify("x".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new OllamaPurposeClassifier(mapper, false, url, "test", 1).classify("Деталь").source()).isEqualTo("FALLBACK");
    }
    @Test void constrainsQuotesToOriginalClausesAndBoundsLongUnpunctuatedText() {
        String text = "Подставка для платы, усилий почти нет. Дома";
        assertThat(OllamaPurposeClassifier.evidenceChoices(text)).containsExactly("Подставка для платы", "усилий почти нет", "Дома");
        var classifier = new OllamaPurposeClassifier(mapper, true, "http://127.0.0.1:1", "test", 1);
        var choices = mapper.valueToTree(classifier.payload(text, "use")).path("format").path("anyOf").get(1).path("prefixItems").get(1).path("enum");
        assertThat(choices.size()).isEqualTo(3);
        assertThat(choices.get(0).asText()).isEqualTo("Подставка для платы");
        String longText = "a".repeat(179) + "😀" + "b".repeat(200);
        var chunks = OllamaPurposeClassifier.evidenceChoices(longText);
        assertThat(chunks).allMatch(s -> s.length() <= 180 && longText.contains(s));
        assertThat(String.join("", chunks)).isEqualTo(longText);
    }
    @Test void sharesOneDeadlineAcrossAllSixQuestionsAndReleasesTheModelOnTimeout() throws Exception {
        var calls = new AtomicInteger();
        String url = listen(body -> {
            calls.incrementAndGet();
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return high();
        });
        var classifier = new OllamaPurposeClassifier(mapper, true, url, "test", 1);
        long start = System.nanoTime();
        assertThat(classifier.classify("огромная нагрузка").source()).isEqualTo("FALLBACK");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1400);
        assertThat(calls.get()).isLessThan(6);
        assertThat(releases.get()).isEqualTo(1);
    }
    @Test void rejectsUnsupportedInferencesWithoutDroppingOtherGroundedRequirements() {
        var classifier = new OllamaPurposeClassifier(mapper, true, "http://127.0.0.1:1", "test", 1);
        var fields = facts();
        putFact(fields, "use", "FUNCTIONAL", "просто подставочка");
        putFact(fields, "load", "LIGHT", "нужно совсем слабо");
        putFact(fields, "goal", "DETAIL", "просто подставочка");
        var light = classifier.parse(mapper.readTree(reply(fields)), "просто подставочка, нужно совсем слабо");
        assertThat(light.requirements().goal().value()).isEqualTo(PartRequirements.Goal.UNKNOWN);
        assertThat(light.requirements().load().value()).isEqualTo(PartRequirements.Load.LIGHT);
        putFact(fields, "use", "UNKNOWN", ""); putFact(fields, "load", "UNKNOWN", ""); putFact(fields, "goal", "UNKNOWN", "");
        putFact(fields, "flexibility", "FLEXIBLE", "Гладкая поверхность");
        assertThat(classifier.parse(mapper.readTree(reply(fields)), "Гладкая поверхность").requirements().flexibility().value())
                .isEqualTo(PartRequirements.Flexibility.UNKNOWN);
        putFact(fields, "flexibility", "UNKNOWN", ""); putFact(fields, "loadKind", "STATIC", "будет нагрузка");
        assertThat(classifier.parse(mapper.readTree(reply(fields)), "будет нагрузка").requirements().loadKind().value())
                .isEqualTo(PartRequirements.LoadKind.UNKNOWN);
        putFact(fields, "loadKind", "UNKNOWN", ""); putFact(fields, "use", "FUNCTIONAL", "Нужна нормальная штука");
        assertThat(classifier.parse(mapper.readTree(reply(fields)), "Нужна нормальная штука").requirements().use().value())
                .isEqualTo(PartRequirements.Use.UNKNOWN);
    }
}
