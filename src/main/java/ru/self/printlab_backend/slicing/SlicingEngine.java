package ru.self.printlab_backend.slicing;

import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import ru.self.printlab_backend.model.material.MaterialProfile;
import java.nio.file.Path;

public interface SlicingEngine {
    record Settings(int infillPercent, int perimeters, double layerHeightMm, PrintPlanRequest.SupportMode supportMode) {}
    record Estimate(double printTimeMin, double materialG, String version, boolean hasWarnings) {}
    Estimate slice(Path stl, Settings settings, MaterialProfile material, long deadlineNanos);
}
