package ru.self.printlab_backend.slicing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import ru.self.printlab_backend.model.material.MaterialProfile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static ru.self.printlab_backend.slicing.PrintPlanException.Error.*;

@Slf4j
@Component
public class BambuStudioEngine implements SlicingEngine {
    private final JsonMapper mapper;
    private final BambuProfiles profiles;
    private final Path executable;
    private final SlicingTimeouts timeouts;

    public BambuStudioEngine(JsonMapper mapper, BambuProfiles profiles,
            @Value("${printlab.bambu.executable:/Applications/BambuStudio.app/Contents/MacOS/BambuStudio}") String executable,
            SlicingTimeouts timeouts) {
        this.mapper = mapper;
        this.profiles = profiles;
        this.executable = Path.of(executable);
        this.timeouts = timeouts;
    }

    @Override public Estimate slice(Path stl, Settings settings, MaterialProfile material, long deadlineNanos) {
        if (!Files.isExecutable(executable)) throw new PrintPlanException(SLICER_UNAVAILABLE);
        Path directory = null;
        Process process = null;
        long started = System.nanoTime();
        try {
            directory = Files.createTempDirectory("printlab-slice-");
            var machine = profiles.load("machine", "Bambu Lab A1 mini 0.4 nozzle");
            var print = profiles.load("process", "0.20mm Standard @BBL A1M");
            String family = switch (material.family()) {
                case "PLA" -> "PLA";
                case "PETG" -> "PETG";
                case "TPU_95A" -> "TPU";
                default -> throw new PrintPlanException(MATERIAL_UNSUPPORTED);
            };
            var filament = profiles.load("filament", "Generic " + family + " @BBL A1M");
            print.put("layer_height", Double.toString(settings.layerHeightMm()));
            print.put("sparse_infill_density", settings.infillPercent() + "%");
            print.put("wall_loops", Integer.toString(settings.perimeters()));
            print.put("sparse_infill_pattern", settings.infillPercent() == 100 ? "rectilinear" : settings.infillPattern().name().toLowerCase(Locale.ROOT));
            print.put("top_shell_layers", Integer.toString(settings.topLayers()));
            print.put("bottom_shell_layers", Integer.toString(settings.bottomLayers()));
            // Do not let an inherited minimum thickness silently override the requested layer counts.
            print.put("top_shell_thickness", "0");
            print.put("bottom_shell_thickness", "0");
            print.put("enable_support", settings.supportMode() == PrintPlanRequest.SupportMode.AUTO ? "1" : "0");
            print.put("support_type", "normal(auto)");
            double nozzle = (material.printing().nozzleTemperatureC().min() + material.printing().nozzleTemperatureC().max()) / 2;
            double bed = Math.min(80, (material.printing().bedTemperatureC().min() + material.printing().bedTemperatureC().max()) / 2);
            filament.put("filament_density", List.of(Double.toString(material.densityGcm3())));
            filament.put("nozzle_temperature", List.of(Double.toString(nozzle)));
            filament.put("nozzle_temperature_initial_layer", List.of(Double.toString(nozzle)));
            filament.put("textured_plate_temp", List.of(Double.toString(bed)));
            filament.put("textured_plate_temp_initial_layer", List.of(Double.toString(bed)));
            for (String key : List.of("outer_wall_speed", "inner_wall_speed", "sparse_infill_speed", "internal_solid_infill_speed",
                    "top_surface_speed", "gap_infill_speed", "initial_layer_speed", "initial_layer_infill_speed", "support_speed", "support_interface_speed")) {
                Object raw = print.get(key);
                if (raw instanceof List<?> values && !values.isEmpty()) {
                    try { print.put(key, List.of(Double.toString(Math.min(Double.parseDouble(values.getFirst().toString()), material.printing().maxPrintSpeedMmS())))); }
                    catch (NumberFormatException ignored) { /* Keep the vendor's relative speeds. */ }
                }
            }
            Path machineFile = directory.resolve("machine.json"), printFile = directory.resolve("process.json"), filamentFile = directory.resolve("filament.json");
            Files.write(machineFile, mapper.writeValueAsBytes(machine));
            Files.write(printFile, mapper.writeValueAsBytes(print));
            Files.write(filamentFile, mapper.writeValueAsBytes(filament));
            long remainingMillis = Math.min(TimeUnit.SECONDS.toMillis(timeouts.processSeconds()),
                    TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
            if (remainingMillis <= 0) throw new PrintPlanException(SLICE_TIMEOUT);
            long nativeSeconds = Math.min(timeouts.nativeSeconds(), Math.max(1, (remainingMillis - 1000) / 1000));
            List<String> command = List.of(executable.toAbsolutePath().toString(), "--datadir", directory.resolve("config").toString(),
                    "--debug", "2", "--load-settings", machineFile + ";" + printFile, "--load-filaments", filamentFile.toString(),
                    "--curr-bed-type", "Textured PEI Plate", "--arrange", "1", "--ensure-on-bed", "--slice", "0", "--mstpp", Long.toString(nativeSeconds),
                    "--mtcpp", "100000", "--outputdir", directory.toString(), stl.toAbsolutePath().toString());
            process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("slicer.log").toFile()).start();
            if (!process.waitFor(remainingMillis, TimeUnit.MILLISECONDS)) {
                log.warn("Slicer timeout: budgetMs={}, elapsedMs={}, settings={}, diagnostic={}",
                        remainingMillis, elapsedMillis(started), settings, diagnostic(directory));
                throw new PrintPlanException(SLICE_TIMEOUT);
            }
            if (process.exitValue() != 0) {
                log.warn("Slicer failed: exit={}, elapsedMs={}, settings={}, diagnostic={}",
                        process.exitValue(), elapsedMillis(started), settings, diagnostic(directory));
                throw new PrintPlanException(SLICE_FAILED);
            }
            var estimate = readEstimate(directory, settings);
            log.info("Slicer completed: elapsedMs={}, settings={}", elapsedMillis(started), settings);
            return estimate;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrintPlanException(SLICE_TIMEOUT, e);
        } catch (IOException e) { throw new PrintPlanException(SLICE_FAILED, e); }
        finally {
            if (process != null && process.isAlive()) {
                try { process.descendants().forEach(ProcessHandle::destroyForcibly); }
                catch (RuntimeException e) { log.warn("Cannot enumerate slicer descendants; terminating the direct process", e); }
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            deleteTree(directory);
        }
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /** Keep only a bounded, single-line diagnostic in server logs, never the response or STL. */
    private static String diagnostic(Path directory) {
        try (var channel = java.nio.channels.FileChannel.open(directory.resolve("slicer.log"))) {
            int length = (int) Math.min(2048, channel.size());
            var buffer = java.nio.ByteBuffer.allocate(length);
            channel.position(channel.size() - length);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { /* Bounded tail only. */ }
            return new String(buffer.array(), 0, buffer.position(), java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("\\p{Cntrl}", " ");
        } catch (IOException e) { return "unavailable"; }
    }

    Estimate readEstimate(Path directory, Settings expected) throws IOException {
        Path result = directory.resolve("result.json");
        if (!Files.isRegularFile(result) || Files.size(result) > 1_048_576) throw new PrintPlanException(SLICE_FAILED);
        JsonNode root = mapper.readTree(Files.readAllBytes(result));
        if (root.path("return_code").asInt(-1) != 0 || root.path("sliced_plates").size() != 1) throw new PrintPlanException(SLICE_FAILED);
        if (Math.abs(root.path("layer_height").asDouble(-1) - expected.layerHeightMm()) > 0.00001
                || root.path("wall_loops").asInt(-1) != expected.perimeters()
                || Math.abs(root.path("sparse_infill_density").asDouble(-1) - expected.infillPercent()) > .001) throw new PrintPlanException(SLICE_FAILED);
        var plate = root.path("sliced_plates").get(0);
        double seconds = plate.path("total_predication").asDouble(-1), grams = 0;
        for (var filament : plate.path("filaments")) grams += filament.path("total_used_g").asDouble(-1);
        if (!positive(seconds) || !positive(grams) || plate.path("objects").isEmpty()) throw new PrintPlanException(SLICE_FAILED);
        for (var object : plate.path("objects")) {
            var box = object.path("bbox");
            String[] dimensions = {"width", "depth", "height"}, axes = {"x", "y", "z"};
            for (int i = 0; i < 3; i++) {
                double size = box.path(dimensions[i]).asDouble(-1), origin = box.path(axes[i]).asDouble(-1);
                if (!positive(size) || !Double.isFinite(origin) || origin < -.01 || origin + size > 180.01) throw new PrintPlanException(MODEL_DOES_NOT_FIT);
            }
        }
        String version;
        try (var in = Files.newInputStream(directory.resolve("plate_1.gcode"))) {
            String header = new String(in.readNBytes(2048), java.nio.charset.StandardCharsets.UTF_8);
            var matcher = java.util.regex.Pattern.compile("; BambuStudio ([0-9.]+)").matcher(header);
            if (!matcher.find()) throw new PrintPlanException(SLICE_FAILED);
            version = matcher.group(1);
        }
        return new Estimate(seconds / 60, grams, version, !plate.path("warning_message").asText("").isBlank());
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
    public static void deleteTree(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) { log.warn("Cannot clean temporary slicing directory", e); }
    }
}
