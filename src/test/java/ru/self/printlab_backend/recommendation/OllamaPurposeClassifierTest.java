package ru.self.printlab_backend.recommendation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OllamaPurposeClassifierTest {
    final JsonMapper mapper = JsonMapper.builder().build();
    HttpServer server;
    @AfterEach void stop() { if (server != null) server.stop(0); }
    String listen(java.util.function.Function<String, String> handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            String result = handler.apply(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.setExecutor(java.util.concurrent.Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
    String reply() { return mapper.writeValueAsString(Map.of("done", true, "message", Map.of("content", mapper.writeValueAsString(Map.of(
            "category", "REINFORCED"))))); }
    @Test void sendsOnlyTextUsingSchemaWithoutThinkingAndCachesExactDescriptions() throws Exception {
        var calls = new AtomicInteger();
        String url = listen(body -> {
            calls.incrementAndGet(); var request = mapper.readTree(body);
            assertThat(request.path("think").asBoolean(true)).isFalse();
            assertThat(request.path("format").path("properties").size()).isEqualTo(1);
            assertThat(body).doesNotContain("fileName", "triangles", "vertices");
            return reply();
        });
        var classifier = new OllamaPurposeClassifier(mapper, true, url, "test-model", 2);
        assertThat(classifier.classify("Специальное изделие").category()).isEqualTo(PartPurpose.Category.REINFORCED);
        assertThat(classifier.classify("Специальное изделие").source()).isEqualTo("LOCAL_MODEL");
        assertThat(classifier.classify("").source()).isEqualTo("DEFAULT");
        assertThat(calls.get()).isEqualTo(1);
        classifier.classify("Другое изделие"); assertThat(calls.get()).isEqualTo(2);
    }
    @Test void fallsBackHonestlyOnInvalidOrOversizedResponsesAndDoesNotCacheThem() throws Exception {
        var calls = new AtomicInteger();
        String url = listen(body -> calls.incrementAndGet() == 1 ? "x".repeat(17000) : reply());
        var classifier = new OllamaPurposeClassifier(mapper, true, url, "test-model", 2);
        assertThat(classifier.classify("Изделие").source()).isEqualTo("FALLBACK");
        assertThat(classifier.classify("Изделие").source()).isEqualTo("LOCAL_MODEL");
        assertThat(calls.get()).isEqualTo(2);
        assertThatThrownBy(() -> classifier.parse(mapper.readTree("{\"done\":false}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> classifier.parse(mapper.readTree("{\"done\":true,\"message\":{\"content\":\"{}\"}}"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void hasABoundedTimeoutAndRejectsInvalidConfiguration() throws Exception {
        String url = listen(body -> { try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return reply(); });
        var classifier = new OllamaPurposeClassifier(mapper, true, url, "test-model", 1);
        long start = System.nanoTime();
        assertThat(classifier.classify("Деталь").source()).isEqualTo("FALLBACK");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1400);
        assertThatThrownBy(() -> new OllamaPurposeClassifier(mapper, true, "file:///private", "model", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> classifier.classify("x".repeat(2001))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void resolvesExplicitScenariosWithoutANeuralRequestAndDefersNegation() {
        var classifier = new OllamaPurposeClassifier(mapper, true, "http://127.0.0.1:1", "model", 1);
        assertThat(classifier.classify("Статуэтка кота для полки").category()).isEqualTo(PartPurpose.Category.DECORATIVE);
        assertThat(classifier.classify("Макет корпуса для проверки размеров").category()).isEqualTo(PartPurpose.Category.PROTOTYPE);
        assertThat(classifier.classify("Кронштейн держит груз").category()).isEqualTo(PartPurpose.Category.REINFORCED);
        assertThat(classifier.classify("Гибкий чехол").category()).isEqualTo(PartPurpose.Category.FLEXIBLE);
        assertThat(classifier.classify("Корпус датчика на улице").category()).isEqualTo(PartPurpose.Category.FUNCTIONAL);
        assertThat(classifier.classify("Держатель наушников").category()).isEqualTo(PartPurpose.Category.FUNCTIONAL);
        assertThat(classifier.classify("Держатель наушников").source()).isEqualTo("RULES");
        assertThat(OllamaPurposeClassifier.knownPurpose("Не декоративная деталь")).isNull();
    }
}
