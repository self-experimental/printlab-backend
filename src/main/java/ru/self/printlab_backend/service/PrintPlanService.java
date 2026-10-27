package ru.self.printlab_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import ru.self.printlab_backend.dto.printplan.PrintPlanResponse;
import ru.self.printlab_backend.dto.printplan.PrintPlanResponse.Candidate;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;
import ru.self.printlab_backend.recommendation.PrintRecommendations;
import ru.self.printlab_backend.slicing.*;
import ru.self.printlab_backend.stl.*;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import static ru.self.printlab_backend.slicing.PrintPlanException.Error.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PrintPlanService {
    private final MaterialCatalogRepository materials;
    private final StlFileParser parser;
    private final SlicingEngine engine;
    private final JsonMapper mapper;
    private final SlicingTimeouts timeouts;
    private final PrintRecommendations recommendations;
    private final Semaphore slot = new Semaphore(1);
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(32, .75f, true);
    private record Cached(PrintPlanResponse response, long expires) {}

    public PrintPlanResponse analyze(MultipartFile file, PrintPlanRequest original) {
        if (file == null || file.isEmpty()) throw new StlParseException(StlUploadError.EMPTY_FILE);
        if (file.getSize() > StlLimits.MAX_BYTES) throw new StlParseException(StlUploadError.FILE_TOO_LARGE);
        try {
            byte[] bytes = file.getBytes();
            String key = key(bytes, original);
            PrintPlanResponse hit = cached(key);
            if (hit != null) return hit;
            if (!slot.tryAcquire()) throw new PrintPlanException(SLICER_BUSY);
            Path directory = null;
            long started = System.nanoTime();
            String jobId = UUID.randomUUID().toString();
            try {
                hit = cached(key);
                if (hit != null) return hit;
                var geometry = parser.parse(bytes);
                log.info("Print plan started: id={}, bytes={}, boundsMm={}x{}x{}, budgetSeconds={}",
                        jobId, bytes.length, geometry.getBoundingBoxXmm(), geometry.getBoundingBoxYmm(),
                        geometry.getBoundingBoxZmm(), timeouts.jobSeconds());
                if (!(geometry.getVolumeMm3() > 0) || geometry.getBoundingBoxXmm() <= 0
                        || geometry.getBoundingBoxYmm() <= 0 || geometry.getBoundingBoxZmm() <= 0) throw new PrintPlanException(NO_PRINTABLE_VOLUME);
                if (geometry.getBoundingBoxXmm() > 180 || geometry.getBoundingBoxYmm() > 180 || geometry.getBoundingBoxZmm() > 180)
                    throw new PrintPlanException(MODEL_DOES_NOT_FIT);
                var resolved = recommendations.resolve(original);
                var request = resolved.settings();
                var material = materials.catalog().materials().stream().filter(m -> m.id().equals(request.materialProfileId()))
                        .findFirst().orElseThrow(() -> new PrintPlanException(MATERIAL_UNSUPPORTED));
                if (!Set.of("PLA", "PETG", "TPU_95A").contains(material.family()) || Boolean.TRUE.equals(material.printing().hardenedNozzleRequired())
                        || material.printing().bedTemperatureC().min() > 80 || material.printing().nozzleTemperatureC().min() > 300)
                    throw new PrintPlanException(MATERIAL_UNSUPPORTED);
                directory = Files.createTempDirectory("printlab-plan-");
                Path stl = directory.resolve("model.stl");
                Files.write(stl, bytes);
                long deadline = started + TimeUnit.SECONDS.toNanos(timeouts.jobSeconds());
                List<Candidate> candidates = new ArrayList<>();
                List<String> warnings = new ArrayList<>();
                String version = "";
                int index = 0;
                var planned = configurations(request);
                for (var settings : planned) {
                    if (System.nanoTime() >= deadline) break;
                    index++;
                    SlicingEngine.Estimate estimate;
                    try {
                        estimate = engine.slice(stl, settings, material, deadline);
                    } catch (PrintPlanException e) {
                        if (e.error() != SLICE_TIMEOUT) throw e;
                        log.warn("Print plan variant timed out: id={}, variant={}, settings={}", jobId, index, settings);
                        continue;
                    }
                    if (!Double.isFinite(estimate.printTimeMin()) || estimate.printTimeMin() <= 0
                            || !Double.isFinite(estimate.materialG()) || estimate.materialG() <= 0) throw new PrintPlanException(SLICE_FAILED);
                    version = estimate.version();
                    candidates.add(new Candidate("variant-" + index, settings.infillPercent(), settings.perimeters(), settings.layerHeightMm(),
                            settings.infillPercent() == 100 ? "RECTILINEAR" : settings.infillPattern().name(),
                            estimate.printTimeMin(), estimate.materialG(),
                            (request.maxPrintTimeMin() == 0 || estimate.printTimeMin() <= request.maxPrintTimeMin())
                                    && (request.maxMaterialConsumptionG() == 0 || estimate.materialG() <= request.maxMaterialConsumptionG()),
                            settings.topLayers(), settings.bottomLayers(), settings.supportMode()));
                    if (estimate.hasWarnings() && warnings.isEmpty()) warnings.add("Слайсер обнаружил особенности модели. Перед печатью проверьте её в Bambu Studio.");
                }
                if (candidates.isEmpty()) throw new PrintPlanException(SLICE_TIMEOUT);
                boolean partial = candidates.size() < planned.size();
                var feasible = candidates.stream().filter(Candidate::meetsConstraints).toList();
                if (feasible.isEmpty()) throw new PrintPlanException(partial ? SLICE_TIMEOUT : NO_PRINT_PLAN);
                double minTime = feasible.stream().mapToDouble(Candidate::estimatedPrintTimeMin).min().orElseThrow();
                double minMass = feasible.stream().mapToDouble(Candidate::estimatedMaterialG).min().orElseThrow();
                var selected = feasible.stream().min(Comparator.comparingDouble(c -> switch (request.priority()) {
                    case MIN_MATERIAL -> c.estimatedMaterialG();
                    case MIN_TIME -> c.estimatedPrintTimeMin();
                    case BALANCED -> c.estimatedPrintTimeMin() / minTime + c.estimatedMaterialG() / minMass;
                })).orElseThrow();
                if (request.supportMode() == PrintPlanRequest.SupportMode.NONE) warnings.add("Поддержки отключены. Нависающие части могут потребовать поддержек.");
                if (material.family().equals("TPU_95A")) warnings.add("Поддержки из TPU могут быть сложны в удалении. Перед печатью проверьте их расположение.");
                var advice = recommendations.explain(resolved, original, selected, material);
                var response = new PrintPlanResponse("Bambu Lab A1 mini", .4, "Текстурированная PEI-пластина", material.name(), version,
                        selected.id(), planned.size(), List.copyOf(candidates), List.copyOf(warnings),
                        advice.summary() + (partial ? " Варианты, не уложившиеся в лимит времени, в сравнении не участвуют." : ""), advice);
                synchronized (cache) {
                    cache.put(key, new Cached(response, System.nanoTime() + TimeUnit.MINUTES.toNanos(15)));
                    while (cache.size() > 32) cache.remove(cache.keySet().iterator().next());
                }
                log.info("Print plan completed: id={}, elapsedMs={}, variants={}, planned={}", jobId,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), candidates.size(), planned.size());
                return response;
            } catch (PrintPlanException e) {
                log.warn("Print plan failed: id={}, elapsedMs={}, code={}", jobId,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), e.error());
                throw e;
            } finally { BambuStudioEngine.deleteTree(directory); slot.release(); }
        } catch (IOException e) { throw new PrintPlanException(SLICE_FAILED, e); }
    }

    private PrintPlanResponse cached(String key) {
        synchronized (cache) {
            Cached value = cache.get(key);
            if (value == null) return null;
            if (value.expires() < System.nanoTime()) { cache.remove(key); return null; }
            return value.response();
        }
    }
    private String key(byte[] bytes, PrintPlanRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            digest.update(mapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static List<SlicingEngine.Settings> configurations(PrintPlanRequest request) {
        double layer = request.layerHeightMm();
        double finer = Math.max(.08, Math.round((layer - .04) * 100) / 100.0);
        if (layer == .28) {
            finer = .20;
        }
        int fill = request.minimumInfillPercent();
        int walls = request.minimumPerimeters();
        int denser = Math.min(100, fill + 10);
        int densest = Math.min(100, fill + 20);
        List<SlicingEngine.Settings> planned = List.of(
                settings(request, fill, walls, layer),
                settings(request, fill, walls, finer),
                settings(request, fill, walls + 1, layer),
                settings(request, denser, walls, layer),
                settings(request, denser, walls + 1, finer),
                settings(request, densest, walls + 1, layer));
        List<SlicingEngine.Settings> unique = new ArrayList<>();
        for (SlicingEngine.Settings candidate : planned) {
            if (!unique.contains(candidate)) {
                unique.add(candidate);
            }
        }
        return List.copyOf(unique);
    }
    private static SlicingEngine.Settings settings(PrintPlanRequest r, int fill, int walls, double layer) {
        return new SlicingEngine.Settings(Math.min(100, fill), walls, layer, r.supportMode(), r.infillPattern(), r.topLayers(), r.bottomLayers());
    }
}
