package ru.self.printlab_backend.recommendation;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class OllamaClarificationAdvisorTest {
    final JsonMapper mapper = JsonMapper.builder().build();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    volatile String content = "{\"topic\":\"LOAD\"}";
    volatile String envelopeOverride;
    volatile int delayMillis;
    HttpServer server;
    ExecutorService executor;
    String url;

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/chat", exchange -> {
            try (exchange) {
                var request = mapper.readTree(exchange.getRequestBody());
                requests.add(request);
                boolean unload = request.path("messages").isEmpty();
                if (!unload && delayMillis > 0) {
                    try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                var body = (!unload && envelopeOverride != null ? envelopeOverride : mapper.writeValueAsString(Map.of("done", true, "message", Map.of("content", content)))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stopServer() { server.stop(0); executor.shutdownNow(); }
    OllamaClarificationAdvisor advisor(boolean enabled, int seconds) { return new OllamaClarificationAdvisor(mapper, enabled, url, "qwen3:1.7b", seconds); }

    @Test void acceptsOnlyAnEnumeratedTopicAndCachesSuccessWithoutAnotherModelCall() {
        var advisor = advisor(true, 5);
        assertThat(advisor.suggest("Нужна штука для моего проекта")).contains("Будет ли деталь держать груз или работать под усилием? Опишите максимальную нагрузку.");
        assertThat(advisor.suggest("Нужна штука для моего проекта")).isPresent();
        assertThat(requests).hasSize(2);
        var request = requests.getFirst();
        assertThat(request.path("messages").get(1).path("content").asText()).isEqualTo("Нужна штука для моего проекта");
        assertThat(request.path("format").path("additionalProperties").asBoolean()).isFalse();
        assertThat(request.path("options").path("num_predict").asInt()).isEqualTo(24);
        assertThat(request.path("options").path("temperature").asInt()).isZero();
        assertThat(requests.getLast().path("keep_alive").asInt()).isZero();
        assertThat(requests.getLast().path("messages").isEmpty()).isTrue();
    }

    @Test void rejectsGeneratedSettingsUnknownTopicsAndFreeTextWithoutCachingThem() {
        var advisor = advisor(true, 5);
        for (String invalid : List.of("{\"topic\":\"LOAD\",\"infill\":100}", "{\"topic\":\"1500N_SAFE\"}", "Set 100% infill", "[\"LOAD\"]", "{}")) {
            content = invalid;
            assertThat(advisor.suggest("Неясная задача")).isEmpty();
        }
        assertThat(requests).hasSize(10);
        content = "{\"topic\":\"ENVIRONMENT\"}";
        assertThat(advisor.suggest("Неясная задача")).isPresent();
        assertThat(requests).hasSize(12);
    }

    @Test void refusesOversizedAndIncompleteEnvelopes() {
        for (String invalid : List.of(" ".repeat(5000), "{\"done\":false,\"message\":{\"content\":\"{}\"}}", "{\"done\":true,\"done_reason\":\"length\",\"message\":{\"content\":\"{}\"}}")) {
            envelopeOverride = invalid;
            assertThat(advisor(true, 5).suggest("Задача")).isEmpty();
        }
        assertThat(requests).hasSize(6);
    }

    @Test void timeoutIsBoundedAndReleasesTheModel() {
        delayMillis = 3000;
        long started = System.nanoTime();
        assertThat(advisor(true, 1).suggest("Неясная задача")).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));
        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().path("messages").isEmpty()).isTrue();
    }

    @Test void disabledServiceDoesNotConnectAndConfigurationCannotRedirectToAnArbitraryProtocol() {
        assertThat(advisor(false, 5).suggest("Деталь")).isEmpty();
        assertThat(requests).isEmpty();
        assertThatThrownBy(() -> new OllamaClarificationAdvisor(mapper, true, "file:///etc", "model", 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OllamaClarificationAdvisor(mapper, true, "http://user:password@localhost", "model", 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
