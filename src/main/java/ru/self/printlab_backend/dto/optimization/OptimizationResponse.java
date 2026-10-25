package ru.self.printlab_backend.dto.optimization;

import lombok.Builder;
import java.util.List;
import ru.self.printlab_backend.dto.experiment.ExperimentResponse;

@Builder
public record OptimizationResponse(
        String matchedModelId,
        String matchedModelName,
        String selectedExperimentId,
        
        double recommendedInfillPercent,
        String recommendedInfillPattern,
        int recommendedPerimeters,
        double recommendedLayerHeightMm,
        String recommendedOrientation,
        
        double expectedCompressiveStrengthMpa,
        double expectedPrintTimeMin,
        double expectedMaterialConsumptionG,
        
        Double loadCapacityN,
        List<String> warnings,
        String explanation,
        List<ExperimentResponse> experiments
) {
}
