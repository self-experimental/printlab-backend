package ru.self.printlab_backend.algorithm;

import ru.self.printlab_backend.dto.optimization.OptimizationRequest;
import ru.self.printlab_backend.model.ExperimentResult;

import java.util.List;

/**
 * Контракт алгоритма подбора оптимальной исследованной конфигурации.
 *
 * <p>Список {@code comparableExperiments} уже ограничен сопоставленной моделью
 * и материалом на уровне сервиса. Алгоритм не подмешивает данные других образцов.</p>
 */
public interface OptimizationAlgorithm {

    OptimizationResult select(List<ExperimentResult> comparableExperiments,
                              OptimizationRequest request);
}