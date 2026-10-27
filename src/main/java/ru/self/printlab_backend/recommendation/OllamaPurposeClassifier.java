package ru.self.printlab_backend.recommendation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Extracts grounded requirements. Print settings and explanations are resolved in Java. */
@Component
@Slf4j
public class OllamaPurposeClassifier implements PurposeClassifier {
    private static final String PROMPT = prompt();
    private static final Map<String, Class<? extends Enum<?>>> FIELDS = fields();
    private static final JsonNode QUESTIONS = questions();
    private static JsonNode questions() {
        try (var in = new org.springframework.core.io.ClassPathResource("recommendation/requirement-questions.json").getInputStream()) {
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private static Map<String, Class<? extends Enum<?>>> fields() {
        Map<String, Class<? extends Enum<?>>> fields = new LinkedHashMap<>();
        fields.put("use", PartRequirements.Use.class);
        fields.put("load", PartRequirements.Load.class);
        fields.put("loadKind", PartRequirements.LoadKind.class);
        fields.put("environment", PartRequirements.Environment.class);
        fields.put("flexibility", PartRequirements.Flexibility.class);
        fields.put("goal", PartRequirements.Goal.class);
        return Collections.unmodifiableMap(fields);
    }
    private static String prompt() {
        try {
            return new org.springframework.core.io.ClassPathResource("recommendation/requirements-prompt.txt")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private final JsonMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final String model;
    private final int timeoutSeconds;
    private final boolean enabled;
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(32, .75f, true);
    private record Cached(PartPurpose purpose, long expires) {}

    public OllamaPurposeClassifier(JsonMapper mapper,
            @Value("${printlab.recommendations.enabled:false}") boolean enabled,
            @Value("${printlab.recommendations.ollama-url:http://127.0.0.1:11434}") String baseUrl,
            @Value("${printlab.recommendations.model:qwen2.5:7b-instruct-q3_K_M}") String model,
            @Value("${printlab.recommendations.timeout-seconds:90}") int timeoutSeconds) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/chat");
        if (!Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || timeoutSeconds < 1 || timeoutSeconds > 90)
            throw new IllegalArgumentException("Invalid local recommendation service configuration");
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public PartPurpose classify(String description) {
        if (description == null || description.isBlank()) return PartPurpose.general();
        if (description.length() > 2000) throw new IllegalArgumentException("Описание детали должно быть не длиннее 2000 символов.");
        if (!enabled) return fallback(description);
        synchronized (cache) {
            Cached hit = cache.get(description);
            if (hit != null && hit.expires > System.nanoTime()) return hit.purpose;
        }
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            Map<String, JsonNode> answers = new LinkedHashMap<>();
            for (String field : FIELDS.keySet()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Interpretation deadline");
                var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload(description, field)))).build();
                var pending = client.sendAsync(request, info -> new LimitedBody());
                HttpResponse<byte[]> response;
                try { response = pending.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                finally { if (!pending.isDone()) pending.cancel(true); }
                if (response.statusCode() != 200) throw new IOException("Invalid classifier response");
                JsonNode pair = completedContent(mapper.readTree(response.body()));
                // Validate each answer before proceeding. A missing critical axis cannot silently become UNKNOWN.
                validatePair(pair, FIELDS.get(field), description);
                answers.put(field, pair);
            }
            var result = parseRequirements(mapper.valueToTree(answers), description);
            synchronized (cache) {
                cache.put(description, new Cached(result, System.nanoTime() + TimeUnit.HOURS.toNanos(1)));
                while (cache.size() > 128) cache.remove(cache.keySet().iterator().next());
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback(description);
        } catch (Exception e) {
            // Descriptions, model output and URLs are deliberately absent from logs.
            log.warn("Local purpose classifier unavailable: {}", e.getClass().getSimpleName());
            return fallback(description);
        } finally {
            // Six questions share one runner; release its RAM before starting native slicing.
            unload();
        }
    }

    private void unload() {
        // A cancelled HTTP caller must not prevent bounded cleanup of the native model process.
        boolean interrupted = Thread.interrupted();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
                            Map.of("model", model, "stream", false, "messages", List.of(), "keep_alive", 0)))).build();
            pending = client.sendAsync(request, info -> new LimitedBody());
            if (pending.get(3, TimeUnit.SECONDS).statusCode() != 200)
                log.warn("Local requirement model release was rejected");
        } catch (InterruptedException e) { interrupted = true; }
        catch (Exception e) { log.warn("Local requirement model release unavailable: {}", e.getClass().getSimpleName()); }
        finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    Map<String, Object> payload(String description, String field) {
        var type = FIELDS.get(field);
        var schema = Map.of("anyOf", List.of(
                Map.of("const", List.of("UNKNOWN", "")),
                Map.of("type", "array", "prefixItems", List.of(
                        Map.of("type", "string", "enum", Arrays.stream(type.getEnumConstants()).map(Enum::name).filter(v -> !"UNKNOWN".equals(v)).toList()),
                        Map.of("type", "string", "enum", evidenceChoices(description))), "minItems", 2, "maxItems", 2)));
        Map<String, Object> userData = new LinkedHashMap<>();
        userData.put("description", description);
        userData.put("fragments", evidenceChoices(description));
        return Map.of("model", model, "stream", false, "think", false, "keep_alive", "2m",
                "messages", List.of(Map.of("role", "system", "content", PROMPT),
                        Map.of("role", "user", "content", mapper.writeValueAsString(userData)
                                + "\n\nQUESTION: " + field + ": " + QUESTIONS.path(field).asText())),
                "format", schema,
                "options", Map.of("temperature", 0, "num_predict", 128, "num_ctx", 4096, "num_thread", 3));
    }

    /** Constrain decoding to real clauses, so even a small model cannot fabricate its evidence. */
    static List<String> evidenceChoices(String description) {
        List<String> clauses = new ArrayList<>();
        for (String part : description.split("[.!?;,\\n\\r]+")) {
            String remaining = part.strip();
            while (!remaining.isEmpty()) {
                int end = Math.min(180, remaining.length());
                if (end < remaining.length()) {
                    int space = remaining.lastIndexOf(' ', end);
                    if (space > 0) end = space;
                    if (Character.isHighSurrogate(remaining.charAt(end - 1))) end--;
                }
                String clause = remaining.substring(0, end).strip();
                if (!clause.isEmpty()) clauses.add(clause);
                remaining = remaining.substring(end).strip();
            }
        }
        // Punctuation-only input can still be rejected as having no meaningful requirements.
        return clauses.isEmpty() ? List.of(description.isBlank() ? " " : description.substring(0, Math.min(180, description.length())))
                : clauses.stream().distinct().toList();
    }

    private JsonNode completedContent(JsonNode envelope) {
        if (!envelope.path("done").asBoolean(false) || "length".equals(envelope.path("done_reason").asText()))
            throw new IllegalArgumentException("Incomplete interpretation");
        String content = envelope.path("message").path("content").asText("");
        if (content.length() > 4096) throw new IllegalArgumentException("Oversized interpretation");
        return mapper.readTree(content);
    }

    PartPurpose parse(JsonNode envelope, String description) {
        return parseRequirements(completedContent(envelope), description);
    }
    private PartPurpose parseRequirements(JsonNode body, String description) {
        if (!body.isObject() || body.size() != FIELDS.size()) throw new IllegalArgumentException("Invalid requirements");
        var goal = fact(body, "goal", PartRequirements.Goal.class, description);
        if (goal.value() == PartRequirements.Goal.UNKNOWN) goal = RequirementEvidence.explicitGoal(description);
        var requirements = new PartRequirements(
                fact(body, "use", PartRequirements.Use.class, description),
                fact(body, "load", PartRequirements.Load.class, description),
                fact(body, "loadKind", PartRequirements.LoadKind.class, description),
                fact(body, "environment", PartRequirements.Environment.class, description),
                fact(body, "flexibility", PartRequirements.Flexibility.class, description),
                goal);
        return new PartPurpose(requirements.category(), "LOCAL_MODEL", requirements);
    }

    private static <T extends Enum<T>> PartRequirements.Fact<T> fact(JsonNode body, String field, Class<T> type, String description) {
        var pair = body.path(field);
        validatePair(pair, type, description);
        T value = Enum.valueOf(type, pair.get(0).asText());
        String quote = pair.get(1).asText().strip();
        if (!RequirementEvidence.supports(field, value.name(), quote, description))
            return new PartRequirements.Fact<>(Enum.valueOf(type, "UNKNOWN"), "");
        return new PartRequirements.Fact<>(value, quote);
    }
    private static void validatePair(JsonNode pair, Class<? extends Enum<?>> type, String description) {
        if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isTextual() || !pair.get(1).isTextual()
                || Arrays.stream(type.getEnumConstants()).noneMatch(v -> v.name().equals(pair.get(0).asText())))
            throw new IllegalArgumentException("Invalid requirement");
        String quote = pair.get(1).asText().strip();
        if ("UNKNOWN".equals(pair.get(0).asText())) {
            if (!quote.isEmpty()) throw new IllegalArgumentException("Unknown requirement has evidence");
        } else if (quote.isEmpty() || quote.length() > 180
                || !description.toLowerCase(Locale.ROOT).contains(quote.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Requirement has no evidence in description");
    }
    /** Never present degraded service as a successful model classification. */
    static PartPurpose fallback(String description) {
        return new PartPurpose(PartPurpose.Category.GENERAL, "FALLBACK");
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(Long.MAX_VALUE); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 16384) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Classifier response too large"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
