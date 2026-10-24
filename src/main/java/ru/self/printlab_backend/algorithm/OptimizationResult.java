package ru.self.printlab_backend.algorithm;

import lombok.Builder;

@Builder
public record OptimizationResult(
        String selectedExperimentId,
        String selectedModelId,
        double recommendedInfillPercent,
        String recommendedInfillPattern,      // <-- Добавлено
        int recommendedPerimeters,            // <-- Добавлено
        double recommendedLayerHeightMm,      // <-- Добавлено
        String recommendedOrientation,        // <-- Добавлено
        double expectedCompressiveStrengthMpa,
        double expectedPrintTimeMin,
        double expectedMaterialConsumptionG,
        Double loadCapacityN,
        String explanation
) {
}