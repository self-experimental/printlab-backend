package ru.self.printlab_backend.dto.optimization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;

public record OptimizationRequest(
        @NotBlank String material,
        @Positive @DecimalMax("1000000.0") double requiredLoadN,
        @DecimalMin("1.0") @DecimalMax("10.0") double safetyFactor,
        @PositiveOrZero @DecimalMax("1000000.0") double maxPrintTimeMin,
        @PositiveOrZero @DecimalMax("1000000.0") double maxMaterialConsumptionG,
        
        @PositiveOrZero @DecimalMax("1.0") double layerHeightMm,
        @PositiveOrZero @Max(20) int perimeters,
        String infillPattern,
        String printOrientation,
        String testType, // "изгиб", "сжатие"
        
        @NotNull OptimizationPriority priority,
        @jakarta.validation.constraints.Pattern(regexp = "[a-z0-9-]{1,100}") String materialProfileId
) {
}
