package ru.self.printlab_backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;
import ru.self.printlab_backend.service.PrintPlanService;
import ru.self.printlab_backend.slicing.*;
import ru.self.printlab_backend.stl.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;


class PrintPlanServiceTest {
    JsonMapper mapper = JsonMapper.builder().build();
    static class FakeEngine implements SlicingEngine {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Path lastPath;
        java.util.function.Function<Settings, Estimate> calculation = s -> new Estimate(
                100 * s.layerHeightMm() + s.perimeters(), s.infillPercent() + s.perimeters(), "test-engine", false);
        @Override public Estimate slice(Path stl, Settings settings, ru.self.printlab_backend.model.material.MaterialProfile material, long deadline) {
            calls.incrementAndGet(); lastPath = stl; return calculation.apply(settings);
        }
    }
    FakeEngine engine;
    PrintPlanService service;
    PrintPlanRequest request(String material, PrintPlanRequest.Priority priority, double time, double mass) {
        return new PrintPlanRequest(material, priority, .2, 15, 2, PrintPlanRequest.SupportMode.AUTO, time, mass);
    }
    MockMultipartFile file(double side) { return new MockMultipartFile("file", "part.stl", "model/stl", OptimizationIntegrationTest.cube(side)); }
    @BeforeEach void setup() {
        engine = new FakeEngine();
        service = new PrintPlanService(new MaterialCatalogRepository(mapper, new ClassPathResource("catalog/materials.json")),
                new StlFileParser(new BinaryStlParser()), engine, mapper, new SlicingTimeouts(180, 45, 40), new ru.self.printlab_backend.recommendation.PrintRecommendations(
                        description -> ru.self.printlab_backend.recommendation.PartPurpose.general(), new MaterialCatalogRepository(mapper, new ClassPathResource("catalog/materials.json"))));
    }
    @Test void calculatesUnseenGeometryAndChoosesByActualCandidateMetrics() {
        var part = file(31);
        var result = service.analyze(part, request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 0, 0));
        assertThat(result.candidates()).hasSize(6);
        var selected = result.candidates().stream().filter(c -> c.id().equals(result.selectedCandidateId())).findFirst().orElseThrow();
        assertThat(selected.layerHeightMm()).isEqualTo(.16);
        assertThat(selected.estimatedPrintTimeMin()).isEqualTo(18);
        assertThat(result.recommendation().items()).extracting(i -> i.title()).contains("Заполнение", "Стенки", "Материал");
        assertThat(mapper.writeValueAsString(result)).doesNotContain("loadCapacityN", "compressiveStrength");
        assertThat(engine.calls.get()).isEqualTo(6);
        for (int fill : new int[] {1, 5, 95, 100}) {
            for (double layer : new double[] {.08, .16, .24, .28}) {
                var custom = new PrintPlanRequest("prusament-pla", PrintPlanRequest.Priority.MIN_TIME,
                        layer, fill, 2, PrintPlanRequest.SupportMode.AUTO, 0, 0);
                var candidates = service.analyze(part, custom).candidates();
                assertThat(candidates).doesNotHaveDuplicates();
                boolean requested = false;
                for (var candidate : candidates) {
                    assertThat(candidate.infillPercent()).isBetween(1, 100);
                    assertThat(candidate.layerHeightMm()).isBetween(.08, .28);
                    if (candidate.infillPercent() == fill && candidate.layerHeightMm() == layer && candidate.perimeters() == 2) {
                        requested = true;
                    }
                }
                assertThat(requested).isTrue();
            }
        }
    }
    @Test void cachesExactRequestsButRecalculatesChangedParametersAndDeletesFiles() {
        AtomicReference<Path> stl = new AtomicReference<>();
        engine.calculation = settings -> {
            Path p = engine.lastPath; stl.set(p); assertThat(Files.isRegularFile(p)).isTrue();
            return new SlicingEngine.Estimate(20, 4, "test", false);
        };
        var request = request("prusament-pla", PrintPlanRequest.Priority.MIN_MATERIAL, 0, 0);
        var first = service.analyze(file(31), request);
        assertThat(Files.exists(stl.get())).isFalse();
        assertThat(service.analyze(file(31), request)).isSameAs(first);
        assertThat(engine.calls.get()).isEqualTo(6);
        service.analyze(file(31), request("prusament-pla", PrintPlanRequest.Priority.BALANCED, 0, 0));
        assertThat(engine.calls.get()).isEqualTo(12);
    }
    @Test void rejectsUnsupportedMaterialsInvalidVolumeAndOversizedPartsBeforeSlicing() {
        assertThatThrownBy(() -> service.analyze(file(20), request("prusament-asa", PrintPlanRequest.Priority.MIN_TIME, 0, 0)))
                .isInstanceOfSatisfying(PrintPlanException.class, e -> assertThat(e.error()).isEqualTo(PrintPlanException.Error.MATERIAL_UNSUPPORTED));
        assertThatThrownBy(() -> service.analyze(file(181), request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 0, 0)))
                .isInstanceOfSatisfying(PrintPlanException.class, e -> assertThat(e.error()).isEqualTo(PrintPlanException.Error.MODEL_DOES_NOT_FIT));
        var flat = new MockMultipartFile("file", "flat.stl", "model/stl", "solid x facet normal 0 0 1 outer loop vertex 0 0 0 vertex 1 0 0 vertex 0 1 0 endloop endfacet endsolid x".getBytes());
        assertThatThrownBy(() -> service.analyze(flat, request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 0, 0)))
                .isInstanceOfSatisfying(PrintPlanException.class, e -> assertThat(e.error()).isEqualTo(PrintPlanException.Error.NO_PRINTABLE_VOLUME));
        assertThat(engine.calls.get()).isZero();
    }
    @Test void appliesLimitsAndDoesNotCacheFailures() {
        assertThatThrownBy(() -> service.analyze(file(31), request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 1, 0)))
                .isInstanceOfSatisfying(PrintPlanException.class, e -> assertThat(e.error()).isEqualTo(PrintPlanException.Error.NO_PRINT_PLAN));
        engine.calculation = settings -> { throw new PrintPlanException(PrintPlanException.Error.SLICE_TIMEOUT); };
        assertThatThrownBy(() -> service.analyze(file(31), request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 0, 0)))
                .isInstanceOf(PrintPlanException.class);
        engine.calculation = settings -> new SlicingEngine.Estimate(20, 4, "test", false);
        assertThat(service.analyze(file(31), request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 0, 0)).candidates()).hasSize(6);
    }
    @Test void preservesSuccessfulVariantsWhenOneTimesOutAndCachesThePartialResult() {
        engine.calculation = settings -> {
            if (settings.infillPercent() == 15 && settings.perimeters() == 3)
                throw new PrintPlanException(PrintPlanException.Error.SLICE_TIMEOUT);
            return new SlicingEngine.Estimate(20, 4, "test", false);
        };
        var request = request("prusament-pla", PrintPlanRequest.Priority.BALANCED, 0, 0);
        var result = service.analyze(file(31), request);
        assertThat(result.plannedCandidateCount()).isEqualTo(6);
        assertThat(result.candidates()).hasSize(5).noneMatch(c -> c.id().equals("variant-3"));
        assertThat(result.candidates()).anyMatch(c -> c.id().equals("variant-6"));
        assertThat(result.explanation()).contains("не уложившиеся в лимит");
        assertThat(engine.calls.get()).isEqualTo(6);
        assertThat(service.analyze(file(31), request)).isSameAs(result);
        assertThat(engine.calls.get()).isEqualTo(6);
        assertThat(Files.exists(engine.lastPath)).isFalse();
    }
    @Test void doesNotClaimThatNoConfigurationFitsWhenSomeWereNotCalculated() {
        engine.calculation = settings -> {
            if (settings.infillPercent() == 35) throw new PrintPlanException(PrintPlanException.Error.SLICE_TIMEOUT);
            return new SlicingEngine.Estimate(20, 4, "test", false);
        };
        assertThatThrownBy(() -> service.analyze(file(31), request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 1, 0)))
                .isInstanceOfSatisfying(PrintPlanException.class, e -> assertThat(e.error()).isEqualTo(PrintPlanException.Error.SLICE_TIMEOUT));
    }
    @Test void doesNotHideInvalidSlicerOutputBehindPartialSuccess() {
        engine.calculation = settings -> {
            if (settings.infillPercent() == 35) throw new PrintPlanException(PrintPlanException.Error.SLICE_FAILED);
            return new SlicingEngine.Estimate(20, 4, "test", false);
        };
        assertThatThrownBy(() -> service.analyze(file(31), request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 0, 0)))
                .isInstanceOfSatisfying(PrintPlanException.class, e -> assertThat(e.error()).isEqualTo(PrintPlanException.Error.SLICE_FAILED));
    }
    @Test void permitsOnlyOneCalculationAtATimeAndReleasesSlot() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        engine.calculation = settings -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return new SlicingEngine.Estimate(20, 4, "test", false);
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> service.analyze(file(31), request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 0, 0)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> service.analyze(file(32), request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 0, 0)))
                        .isInstanceOfSatisfying(PrintPlanException.class, e -> assertThat(e.error()).isEqualTo(PrintPlanException.Error.SLICER_BUSY));
            } finally { release.countDown(); }
            assertThat(running.get(5, TimeUnit.SECONDS).candidates()).hasSize(6);
            assertThat(service.analyze(file(32), request("prusament-pla", PrintPlanRequest.Priority.MIN_TIME, 0, 0)).candidates()).hasSize(6);
        }
    }
}
