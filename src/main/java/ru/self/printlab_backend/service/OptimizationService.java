package ru.self.printlab_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.self.printlab_backend.algorithm.OptimizationAlgorithm;
import ru.self.printlab_backend.algorithm.OptimizationNotApplicableException;
import ru.self.printlab_backend.algorithm.OptimizationResult;
import ru.self.printlab_backend.dto.optimization.OptimizationRequest;
import ru.self.printlab_backend.dto.optimization.OptimizationResponse;
import ru.self.printlab_backend.model.ExperimentResult;
import ru.self.printlab_backend.model.ResearchModel;
import ru.self.printlab_backend.repository.ExperimentRepository;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OptimizationService {

    private final ExperimentRepository experimentRepository;
    private final OptimizationAlgorithm optimizationAlgorithm;

    public OptimizationResponse optimize(ResearchModel matchedModel, OptimizationRequest request) {
        if (matchedModel == null) {
            throw new OptimizationNotApplicableException("Модель не сопоставлена с исследованными образцами");
        }

        List<ExperimentResult> comparable = experimentRepository
                .findExperimentsByModelIdAndMaterial(matchedModel.getId(), request.material());

        if (comparable.isEmpty()) {
            throw new OptimizationNotApplicableException(
                    "Нет экспериментов для модели " + matchedModel.getId() + " и материала " + request.material());
        }

        OptimizationResult result = optimizationAlgorithm.select(comparable, request);

        List<String> warnings = new ArrayList<>();
        if (result.loadCapacityN() == null && request.requiredLoadN() > 0) {
            warnings.add("Допустимая нагрузка не подтверждена: в данных нет измеренной силы.");
        }

        return OptimizationResponse.builder()
                .matchedModelId(matchedModel.getId())
                .matchedModelName(matchedModel.getName())
                .selectedExperimentId(result.selectedExperimentId())
                .recommendedInfillPercent(result.recommendedInfillPercent())
                .recommendedInfillPattern(result.recommendedInfillPattern())
                .recommendedPerimeters(result.recommendedPerimeters())
                .recommendedLayerHeightMm(result.recommendedLayerHeightMm())
                .recommendedOrientation(result.recommendedOrientation())
                .expectedCompressiveStrengthMpa(result.expectedCompressiveStrengthMpa())
                .expectedPrintTimeMin(result.expectedPrintTimeMin())
                .expectedMaterialConsumptionG(result.expectedMaterialConsumptionG())
                .loadCapacityN(result.loadCapacityN())
                .warnings(warnings)
                .explanation(result.explanation())
                .build();
    }
}