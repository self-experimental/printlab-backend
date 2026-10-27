package ru.self.printlab_backend.slicing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class BambuStudioEngineTest {
    @TempDir Path temp;
    JsonMapper mapper = JsonMapper.builder().build();
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC, org.junit.jupiter.api.condition.OS.LINUX})
    void stopsAnOverrunningProcessAndCleansItsTemporaryFiles() throws Exception {
        for (String kind : new String[] {"machine", "process", "filament"}) Files.createDirectory(temp.resolve(kind));
        Files.writeString(temp.resolve("machine/Bambu Lab A1 mini 0.4 nozzle.json"), "{}");
        Files.writeString(temp.resolve("process/0.20mm Standard @BBL A1M.json"), "{}");
        Files.writeString(temp.resolve("filament/Generic PLA @BBL A1M.json"), "{}");
        Path program = temp.resolve("slow-slicer"), pid = temp.resolve("pid");
        Files.writeString(program, "#!/bin/sh\necho $$ > '" + pid + "'\nexec sleep 5\n");
        Files.setPosixFilePermissions(program, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var engine = new BambuStudioEngine(mapper, new BambuProfiles(mapper, temp.toString()), program.toString(), new SlicingTimeouts(180, 45, 40));
        var catalog = new ru.self.printlab_backend.repository.MaterialCatalogRepository(mapper,
                new org.springframework.core.io.ClassPathResource("catalog/materials.json"));
        var settings = new SlicingEngine.Settings(15, 2, .2, PrintPlanRequest.SupportMode.AUTO);
        long started = System.nanoTime();
        assertThatThrownBy(() -> engine.slice(temp.resolve("model.stl"), settings, catalog.catalog().materials().getFirst(),
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1)))
                .isInstanceOfSatisfying(PrintPlanException.class, e -> assertThat(e.error()).isEqualTo(PrintPlanException.Error.SLICE_TIMEOUT));
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(4);
        long processId = Long.parseLong(Files.readString(pid).trim());
        assertThat(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }
    @Test void resolvesInheritedProfilesAndIncludesWithoutAcceptingCyclesOrTraversal() throws Exception {
        Path machines = Files.createDirectory(temp.resolve("machine"));
        Files.writeString(machines.resolve("base.json"), "{\"nozzle\":\"0.4\",\"type\":\"machine\"}");
        Files.writeString(machines.resolve("snippet.json"), "{\"machine_start_gcode\":\"G28\"}");
        Files.writeString(machines.resolve("printer.json"), "{\"inherits\":\"base\",\"include\":[\"snippet\"],\"nozzle\":\"0.6\"}");
        var profiles = new BambuProfiles(mapper, temp.toString());
        var loaded = profiles.load("machine", "printer");
        assertThat(loaded).containsEntry("nozzle", "0.6").containsEntry("machine_start_gcode", "G28").doesNotContainKeys("inherits", "include");
        loaded.put("nozzle", "9");
        assertThat(profiles.load("machine", "printer")).containsEntry("nozzle", "0.6");
        Files.writeString(machines.resolve("cycle.json"), "{\"inherits\":\"cycle\"}");
        assertThatThrownBy(() -> profiles.load("machine", "cycle")).isInstanceOf(PrintPlanException.class);
        assertThatThrownBy(() -> profiles.load("machine", "../outside")).isInstanceOf(PrintPlanException.class);
    }
    @Test void rejectsMissingOrMismatchedSlicerOutputAndReadsOnlyValidatedStats() throws Exception {
        var engine = new BambuStudioEngine(mapper, new BambuProfiles(mapper, temp.toString()), "missing", new SlicingTimeouts(180, 45, 40));
        var settings = new SlicingEngine.Settings(15, 2, .2, PrintPlanRequest.SupportMode.AUTO);
        assertThatThrownBy(() -> engine.readEstimate(temp, settings)).isInstanceOf(PrintPlanException.class);
        String good = """
                {"return_code":0,"layer_height":0.2,"wall_loops":2,"sparse_infill_density":15,
                 "sliced_plates":[{"total_predication":1200,"filaments":[{"total_used_g":4.2}],
                 "objects":[{"bbox":{"width":20,"depth":20,"height":20,"x":80,"y":80,"z":0}}]}]}
                """;
        Files.writeString(temp.resolve("result.json"), good);
        Files.writeString(temp.resolve("plate_1.gcode"), "; BambuStudio 02.08.02.61\n");
        assertThat(engine.readEstimate(temp, settings).materialG()).isEqualTo(4.2);
        for (String invalid : new String[] {good.replace("1200", "-1"), good.replace("4.2", "-1"),
                good.replace("\"wall_loops\":2", "\"wall_loops\":3"), good.replace("\"width\":20", "\"width\":200"),
                good.replace("\"return_code\":0", "\"return_code\":3")}) {
            Files.writeString(temp.resolve("result.json"), invalid);
            assertThatThrownBy(() -> engine.readEstimate(temp, settings)).isInstanceOf(PrintPlanException.class);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({org.junit.jupiter.api.condition.OS.MAC, org.junit.jupiter.api.condition.OS.LINUX})
    void enforcesTheConfiguredProcessBudgetBeforeTheLongerJobDeadline() throws Exception {
        for (String kind : new String[] {"machine", "process", "filament"}) Files.createDirectory(temp.resolve(kind));
        Files.writeString(temp.resolve("machine/Bambu Lab A1 mini 0.4 nozzle.json"), "{}");
        Files.writeString(temp.resolve("process/0.20mm Standard @BBL A1M.json"), "{}");
        Files.writeString(temp.resolve("filament/Generic PLA @BBL A1M.json"), "{}");
        Path program = temp.resolve("slow-slicer"), work = temp.resolve("work"), args = temp.resolve("args");
        Files.writeString(program, "#!/bin/sh\nprintf '%s\\n' \"$PWD\" > '" + work
                + "'\nprintf '%s\\n' \"$@\" > '" + args + "'\ncp process.json '" + temp.resolve("captured-process.json") + "'\nexec sleep 8\n");
        Files.setPosixFilePermissions(program, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var engine = new BambuStudioEngine(mapper, new BambuProfiles(mapper, temp.toString()),
                program.toString(), new SlicingTimeouts(6, 2, 1));
        var catalog = new ru.self.printlab_backend.repository.MaterialCatalogRepository(mapper,
                new org.springframework.core.io.ClassPathResource("catalog/materials.json"));
        long started = System.nanoTime();
        assertThatThrownBy(() -> engine.slice(temp.resolve("model.stl"),
                new SlicingEngine.Settings(100, 2, .28, PrintPlanRequest.SupportMode.AUTO),
                catalog.catalog().materials().getFirst(), System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6)))
                .isInstanceOfSatisfying(PrintPlanException.class, e -> assertThat(e.error()).isEqualTo(PrintPlanException.Error.SLICE_TIMEOUT));
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(1500L, 4000L);
        assertThat(Files.readString(args)).contains("--mstpp\n1\n");
        var generated = mapper.readTree(Files.readString(temp.resolve("captured-process.json")));
        assertThat(generated.path("sparse_infill_pattern").asText()).isEqualTo("zig-zag");
        assertThat(generated.path("sparse_infill_density").asText()).isEqualTo("100%");
        assertThat(generated.path("layer_height").asText()).isEqualTo("0.28");
        assertThat(Files.exists(Path.of(Files.readString(work).trim()))).isFalse();
    }
}
