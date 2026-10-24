package ru.self.printlab_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.self.printlab_backend.model.ExperimentResult;
import ru.self.printlab_backend.model.ResearchModel;
import ru.self.printlab_backend.repository.ExperimentRepository;

import java.util.List;
import java.util.Optional;

/**
 * Предоставляет результаты проведённых экспериментов.
 */
@Service
@RequiredArgsConstructor
public class ExperimentService {

    private final ExperimentRepository experimentRepository;

    public List<ExperimentResult> findAll() {
        return experimentRepository.findAllExperiments();
    }

    public List<ExperimentResult> findByModelId(String modelId) {
        return experimentRepository.findExperimentsByModelId(modelId);
    }

    public List<ResearchModel> findAllResearchModels() {
        return experimentRepository.findAllResearchModels();
    }

    public Optional<ResearchModel> findResearchModel(String id) {
        return experimentRepository.findResearchModelById(id);
    }

    public ExperimentResult save(ExperimentResult result) {
        experimentRepository.saveExperiment(result);
        return result;
    }
}