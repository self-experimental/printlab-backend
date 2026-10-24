package ru.self.printlab_backend.service;

import ru.self.printlab_backend.model.ModelGeometry;
import ru.self.printlab_backend.model.ResearchModel;

/**
 * Результат анализа STL: геометрия и (опционально) сопоставленный образец.
 */
public record StlAnalysisResult(ModelGeometry geometry, ResearchModel matchedModel) {
}