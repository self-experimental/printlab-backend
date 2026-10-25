package ru.self.printlab_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.self.printlab_backend.algorithm.OptimizationAlgorithm;
import ru.self.printlab_backend.algorithm.OptimizationNotApplicableException;
import ru.self.printlab_backend.algorithm.OptimizationResult;
import ru.self.printlab_backend.algorithm.OptimizationError;
import ru.self.printlab_backend.dto.experiment.ExperimentResponse;
import ru.self.printlab_backend.dto.optimization.OptimizationRequest;
import ru.self.printlab_backend.dto.optimization.OptimizationResponse;
import ru.self.printlab_backend.model.ExperimentResult;
import ru.self.printlab_backend.model.ResearchModel;
import ru.self.printlab_backend.repository.ExperimentRepository;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OptimizationService {

    private final ExperimentRepository experimentRepository;
    private final OptimizationAlgorithm optimizationAlgorithm;
    private final MaterialCatalogRepository materialCatalogRepository;

    public OptimizationResponse optimize(ResearchModel matchedModel, OptimizationRequest request) {
        var profile = request.materialProfileId() == null ? null : materialCatalogRepository.catalog().materials()
                .stream().filter(m -> m.id().equals(request.materialProfileId()) && m.family().equals(request.material()))
                .findFirst().orElseThrow(() -> new OptimizationNotApplicableException(OptimizationError.INVALID_MATERIAL_PROFILE));
        if (matchedModel == null) {
            throw new OptimizationNotApplicableException(OptimizationError.MODEL_NOT_SUPPORTED);
        }

        List<ExperimentResult> comparable = experimentRepository
                .findExperimentsByModelIdAndMaterial(matchedModel.getId(), request.material())
                .stream()
                .filter(e -> request.testType() == null || request.testType().isBlank()
                        || request.testType().equalsIgnoreCase(e.getTestType()))
                .toList();

        if (comparable.isEmpty()) {
            throw new OptimizationNotApplicableException(OptimizationError.NO_EXPERIMENTS);
        }

        comparable = comparable.stream()
                .filter(e -> request.layerHeightMm() == 0 || Math.abs(e.getLayerHeightMm() - request.layerHeightMm()) <= 0.001)
                .filter(e -> request.perimeters() == 0 || request.perimeters() == e.getPerimeters())
                .filter(e -> request.infillPattern() == null || request.infillPattern().isBlank()
                        || request.infillPattern().equalsIgnoreCase(e.getInfillPattern()))
                .filter(e -> request.printOrientation() == null || request.printOrientation().isBlank()
                        || request.printOrientation().equalsIgnoreCase(e.getPrintOrientation()))
                .toList();

        OptimizationResult result = optimizationAlgorithm.select(comparable, request);

        List<String> warnings = new ArrayList<>();
        if (profile != null) warnings.add("В испытаниях образца марка пластика не зафиксирована. Паспорт «"
                + profile.name() + "» доступен для справки; его свойства не использованы для пересчёта нагрузки.");
        warnings.add("Подбор основан на испытаниях образца «" + matchedModel.getName()
                + "» с близкими размерами и объёмом. Для детали другой формы эти результаты требуют отдельной проверки.");
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
                .experiments(comparable.stream().map(ExperimentResponse::from).toList())
                .build();
    }
}
