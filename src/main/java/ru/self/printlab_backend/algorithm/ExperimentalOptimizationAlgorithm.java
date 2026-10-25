package ru.self.printlab_backend.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.self.printlab_backend.dto.optimization.OptimizationPriority;
import ru.self.printlab_backend.dto.optimization.OptimizationRequest;
import ru.self.printlab_backend.model.ExperimentResult;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class ExperimentalOptimizationAlgorithm implements OptimizationAlgorithm {

    @Override
    public OptimizationResult select(List<ExperimentResult> comparableExperiments,
                                     OptimizationRequest request) {
        
        List<ExperimentResult> candidates = new ArrayList<>();
        for (ExperimentResult e : comparableExperiments) {
            if (!passesAllFilters(e, request)) {
                continue;
            }
            candidates.add(e);
        }

        if (candidates.isEmpty()) {
            throw new OptimizationNotApplicableException(OptimizationError.NO_SUITABLE_CONFIGURATION);
        }

        ExperimentResult best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (ExperimentResult e : candidates) {
            double score = score(e, request.priority());
            if (best == null || score > bestScore) {
                best = e;
                bestScore = score;
            }
        }

        // Проверка по нагрузке с коэффициентом запаса
        Double capacityN = null;
        if (best.getFailureLoadN() != null && request.safetyFactor() > 0) {
            capacityN = best.getFailureLoadN() / request.safetyFactor();
        }

        return OptimizationResult.builder()
                .selectedExperimentId(best.getId())
                .selectedModelId(best.getModelId())
                .recommendedInfillPercent(best.getInfillPercent())
                .recommendedInfillPattern(best.getInfillPattern())
                .recommendedPerimeters(best.getPerimeters())
                .recommendedLayerHeightMm(best.getLayerHeightMm())
                .recommendedOrientation(best.getPrintOrientation())
                .expectedCompressiveStrengthMpa(best.getCompressiveStrengthMpa())
                .expectedPrintTimeMin(best.getPrintTimeMin())
                .expectedMaterialConsumptionG(best.getMaterialConsumptionG())
                .loadCapacityN(capacityN)
                .explanation(buildExplanation(best, request, capacityN))
                .build();
    }

    private boolean passesAllFilters(ExperimentResult e, OptimizationRequest req) {
        if (e.getInfillPercent() <= 0) return false;
        
        if (req.maxPrintTimeMin() > 0 && e.getPrintTimeMin() > req.maxPrintTimeMin()) return false;
        if (req.maxMaterialConsumptionG() > 0 && e.getMaterialConsumptionG() > req.maxMaterialConsumptionG()) return false;

        // Проверка требуемой нагрузки
        if (req.requiredLoadN() > 0) {
            if (e.getFailureLoadN() == null) return false;
            double capacity = e.getFailureLoadN() / req.safetyFactor();
            if (capacity < req.requiredLoadN()) return false;
        }

        // Проверка параметров печати (если заданы пользователем)
        if (req.layerHeightMm() > 0 && Math.abs(e.getLayerHeightMm() - req.layerHeightMm()) > 0.001) return false;
        if (req.perimeters() > 0 && e.getPerimeters() != req.perimeters()) return false;
        if (req.infillPattern() != null && !req.infillPattern().isBlank() && !req.infillPattern().equalsIgnoreCase(e.getInfillPattern())) return false;
        if (req.printOrientation() != null && !req.printOrientation().isBlank() && !req.printOrientation().equalsIgnoreCase(e.getPrintOrientation())) return false;
        if (req.testType() != null && !req.testType().isBlank() && !req.testType().equalsIgnoreCase(e.getTestType())) return false;

        return true;
    }

    private double score(ExperimentResult e, OptimizationPriority priority) {
        switch (priority) {
            case MAX_STRENGTH:
                return measuredStrength(e);
            case MIN_WEIGHT:
                return -e.getMaterialConsumptionG();
            case MIN_TIME:
                return -e.getPrintTimeMin();
            case BALANCED:
            default:
                double cost = Math.max(e.getMaterialConsumptionG(), 0.001) * Math.max(e.getPrintTimeMin(), 0.001);
                return measuredStrength(e) / Math.sqrt(cost);
        }
    }

    private double measuredStrength(ExperimentResult experiment) {
        return experiment.getFailureLoadN() != null
                ? experiment.getFailureLoadN() : experiment.getCompressiveStrengthMpa();
    }

    private String buildExplanation(ExperimentResult best, OptimizationRequest req, Double capN) {
        var number = java.text.NumberFormat.getNumberInstance(java.util.Locale.forLanguageTag("ru-RU"));
        number.setMaximumFractionDigits(2);
        StringBuilder sb = new StringBuilder();
        sb.append("Выбрано заполнение ").append(number.format(best.getInfillPercent())).append("% из испытанных вариантов");
        if (capN != null) {
            sb.append(", нагрузка с учётом запаса ").append(number.format(capN)).append(" Н")
              .append(" (коэффициент запаса ").append(number.format(req.safetyFactor())).append(")");
        }
        return sb.toString();
    }
}
