package ru.self.printlab_backend.dto.optimization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record OptimizationRequest(
        @NotBlank String material,
        @Positive double requiredLoadN,
        @Positive double safetyFactor,
        @PositiveOrZero double maxPrintTimeMin,
        @PositiveOrZero double maxMaterialConsumptionG,
        
        // Новые параметры из формы
        @PositiveOrZero double layerHeightMm,
        @PositiveOrZero int perimeters,
        String infillPattern,
        String printOrientation,
        String testType, // "изгиб", "сжатие"
        
        @NotNull OptimizationPriority priority
) {
}