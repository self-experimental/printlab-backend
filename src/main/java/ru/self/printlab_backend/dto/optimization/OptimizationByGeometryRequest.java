package ru.self.printlab_backend.dto.optimization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import ru.self.printlab_backend.model.ResearchModel;

/**
 * Запрос оптимизации по уже сопоставленной модели (например, после /api/models/analyze).
 * Модель должна быть из списка исследованных образцов: без неё рекомендация
 * будет относиться к другому образцу.
 */
public record OptimizationByGeometryRequest(
        @NotNull @Valid ResearchModel matchedModel,
        @NotNull @Valid OptimizationRequest request
) {
}