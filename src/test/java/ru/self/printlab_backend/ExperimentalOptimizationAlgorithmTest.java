package ru.self.printlab_backend;

import org.junit.jupiter.api.Test;
import ru.self.printlab_backend.algorithm.ExperimentalOptimizationAlgorithm;
import ru.self.printlab_backend.algorithm.OptimizationNotApplicableException;
import ru.self.printlab_backend.algorithm.OptimizationResult;
import ru.self.printlab_backend.dto.optimization.OptimizationPriority;
import ru.self.printlab_backend.dto.optimization.OptimizationRequest;
import ru.self.printlab_backend.model.ExperimentResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExperimentalOptimizationAlgorithmTest {

    private final ExperimentalOptimizationAlgorithm algorithm = new ExperimentalOptimizationAlgorithm();

    private OptimizationRequest createRequest(double reqLoadN, double safety, OptimizationPriority priority) {
        return new OptimizationRequest("PLA", reqLoadN, safety, 0, 0, 0, 0, null, null, null, priority);
    }

    @Test
    void picksBestExperimentByPriority() {
        ExperimentResult light = ExperimentResult.builder()
                .id("light").infillPercent(20).compressiveStrengthMpa(18).failureLoadN(500.0)
                .printTimeMin(40).materialConsumptionG(8).build();
        ExperimentResult heavy = ExperimentResult.builder()
                .id("heavy").infillPercent(80).compressiveStrengthMpa(45).failureLoadN(1200.0)
                .printTimeMin(120).materialConsumptionG(30).build();

        // Приоритет МАКС ПРОЧНОСТЬ
        OptimizationResult res1 = algorithm.select(List.of(light, heavy), createRequest(0, 1, OptimizationPriority.MAX_STRENGTH));
        assertEquals("heavy", res1.selectedExperimentId());

        // Приоритет МИН ВЕС
        OptimizationResult res2 = algorithm.select(List.of(light, heavy), createRequest(0, 1, OptimizationPriority.MIN_WEIGHT));
        assertEquals("light", res2.selectedExperimentId());
    }

    @Test
    void checksLoadWithSafetyFactor() {
        ExperimentResult weak = ExperimentResult.builder()
                .id("weak").infillPercent(20).compressiveStrengthMpa(18).failureLoadN(500.0) // 500 Н
                .printTimeMin(40).materialConsumptionG(8).build();
        ExperimentResult strong = ExperimentResult.builder()
                .id("strong").infillPercent(80).compressiveStrengthMpa(45).failureLoadN(1200.0) // 1200 Н
                .printTimeMin(120).materialConsumptionG(30).build();

        // Требуем 600 Н с запасом 1.5 (нужно 900 Н) -> должен выбрать strong
        OptimizationResult res = algorithm.select(List.of(weak, strong), createRequest(600, 1.5, OptimizationPriority.MIN_TIME));
        assertEquals("strong", res.selectedExperimentId());
        assertEquals(800.0, res.loadCapacityN(), 0.01); // 1200 / 1.5 = 800
    }

    @Test
    void throwsWhenNoExperimentFitsLoad() {
        ExperimentResult weak = ExperimentResult.builder()
                .id("weak").infillPercent(20).compressiveStrengthMpa(18).failureLoadN(500.0)
                .printTimeMin(40).materialConsumptionG(8).build();

        // Требуем 1000 Н -> должно быть исключение
        assertThrows(OptimizationNotApplicableException.class, () -> 
            algorithm.select(List.of(weak), createRequest(1000, 1.5, OptimizationPriority.BALANCED))
        );
    }

    @Test
    void filtersByPrintParameters() {
        ExperimentResult layer02 = ExperimentResult.builder()
                .id("l02").infillPercent(20).layerHeightMm(0.2).perimeters(2)
                .printTimeMin(40).materialConsumptionG(8).build();
        ExperimentResult layer03 = ExperimentResult.builder()
                .id("l03").infillPercent(20).layerHeightMm(0.3).perimeters(3)
                .printTimeMin(30).materialConsumptionG(7).build();

        // Ищем конкретно слой 0.3
        OptimizationRequest req = new OptimizationRequest("PLA", 0, 1, 0, 0, 0.3, 3, null, null, null, OptimizationPriority.BALANCED);
        
        OptimizationResult res = algorithm.select(List.of(layer02, layer03), req);
        assertEquals("l03", res.selectedExperimentId());
    }
}