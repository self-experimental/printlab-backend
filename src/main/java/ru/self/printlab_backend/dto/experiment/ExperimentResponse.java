package ru.self.printlab_backend.dto.experiment;

import ru.self.printlab_backend.model.ExperimentResult;

public record ExperimentResponse(
        String id,
        String modelId,
        String material,
        double infillPercent,
        String infillPattern,
        int perimeters,
        double layerHeightMm,
        String printOrientation,
        double compressiveStrengthMpa,
        double printTimeMin,
        double materialConsumptionG,
        Double failureLoadN,
        String testType
) {
    public static ExperimentResponse from(ExperimentResult e) {
        return new ExperimentResponse(
                e.getId(),
                e.getModelId(),
                e.getMaterial(),
                e.getInfillPercent(),
                e.getInfillPattern(),
                e.getPerimeters(),
                e.getLayerHeightMm(),
                e.getPrintOrientation(),
                e.getCompressiveStrengthMpa(),
                e.getPrintTimeMin(),
                e.getMaterialConsumptionG(),
                e.getFailureLoadN(),
                e.getTestType()
        );
    }
}