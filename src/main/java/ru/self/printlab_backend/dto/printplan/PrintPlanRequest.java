package ru.self.printlab_backend.dto.printplan;

import jakarta.validation.constraints.*;

public record PrintPlanRequest(
        @NotBlank @Pattern(regexp = "[a-z0-9-]{1,100}") String materialProfileId,
        @NotNull Priority priority,
        @DecimalMin("0.12") @DecimalMax("0.28") double layerHeightMm,
        @Min(5) @Max(80) int minimumInfillPercent,
        @Min(2) @Max(6) int minimumPerimeters,
        @NotNull SupportMode supportMode,
        @PositiveOrZero @DecimalMax("1000000") double maxPrintTimeMin,
        @PositiveOrZero @DecimalMax("1000000") double maxMaterialConsumptionG) {
    public enum Priority { MIN_MATERIAL, MIN_TIME, BALANCED }
    public enum SupportMode { AUTO, NONE }
}
