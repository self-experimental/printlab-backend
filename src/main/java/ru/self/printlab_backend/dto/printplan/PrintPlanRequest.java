package ru.self.printlab_backend.dto.printplan;

import jakarta.validation.constraints.*;

public record PrintPlanRequest(
        @Pattern(regexp = "[a-z0-9-]{1,100}") String materialProfileId,
        Priority priority,
        @DecimalMin("0.08") @DecimalMax("0.28") Double layerHeightMm,
        @Min(1) @Max(100) Integer minimumInfillPercent,
        @Min(2) @Max(6) Integer minimumPerimeters,
        SupportMode supportMode,
        @PositiveOrZero @DecimalMax("1000000") Double maxPrintTimeMin,
        @PositiveOrZero @DecimalMax("1000000") Double maxMaterialConsumptionG,
        @Size(max = 2000) String description,
        InfillPattern infillPattern,
        @Min(3) @Max(8) Integer topLayers,
        @Min(3) @Max(8) Integer bottomLayers) {
    public PrintPlanRequest {
        description = description == null ? "" : description.strip();
    }
    // Keep the existing Java callers and HTTP field names compatible.
    public PrintPlanRequest(String material, Priority priority, double layer, int fill, int walls,
                            SupportMode supports, double time, double mass) {
        this(material, priority, layer, fill, walls, supports, time, mass, "", null, null, null);
    }
    public enum Priority { MIN_MATERIAL, MIN_TIME, BALANCED }
    public enum SupportMode { AUTO, NONE }
    public enum InfillPattern { GYROID, RECTILINEAR, GRID, CUBIC }
}
