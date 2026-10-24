package ru.self.printlab_backend.repository;

import ru.self.printlab_backend.model.ExperimentResult;
import ru.self.printlab_backend.model.ResearchModel;

import java.util.List;
import java.util.Optional;

/**
 * Хранилище экспериментальных данных и исследованных моделей.
 */
public interface ExperimentRepository {

    List<ExperimentResult> findAllExperiments();

    List<ExperimentResult> findExperimentsByModelId(String modelId);

    List<ExperimentResult> findExperimentsByModelIdAndMaterial(String modelId, String material);

    List<ResearchModel> findAllResearchModels();

    Optional<ResearchModel> findResearchModelById(String id);

    void saveExperiment(ExperimentResult result);
}