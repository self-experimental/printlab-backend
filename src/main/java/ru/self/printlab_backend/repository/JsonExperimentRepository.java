package ru.self.printlab_backend.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import ru.self.printlab_backend.model.ExperimentResult;
import ru.self.printlab_backend.model.ResearchModel;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * MVP-реализация на JSON-файлах без БД. Позже заменяется на PostgreSQL + JPA.
 */
@Slf4j
@Repository
public class JsonExperimentRepository implements ExperimentRepository {

    private final JsonMapper jsonMapper;
    private final File experimentsFile;
    private final File modelsFile;

    private List<ExperimentResult> experiments = Collections.emptyList();
    private List<ResearchModel> researchModels = Collections.emptyList();

    public JsonExperimentRepository(JsonMapper jsonMapper,
                                    @Value("${printlab.data.dir:./data}") String dataDir) {
        this.jsonMapper = jsonMapper;
        File dir = new File(dataDir);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("Не удалось создать каталог данных: {}", dir.getAbsolutePath());
        }
        this.experimentsFile = new File(dir, "experiments.json");
        this.modelsFile = new File(dir, "research-models.json");
        load();
    }

    private void load() {
        experiments = readList(
                experimentsFile,
                new TypeReference<List<ExperimentResult>>() {});
        researchModels = readList(
                modelsFile,
                new TypeReference<List<ResearchModel>>() {});
        log.info("Загружено экспериментов: {}, исследованных моделей: {}",
                experiments.size(), researchModels.size());
    }

    private <T> List<T> readList(File file, TypeReference<List<T>> type) {
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(
                    jsonMapper.readValue(file, type));
        } catch (RuntimeException e) {
            log.error("Ошибка чтения {}: {}", file.getAbsolutePath(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private void persistExperiments() {
        try {
            jsonMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(experimentsFile, experiments);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Не удалось сохранить эксперименты", e);
        }
    }

    @Override
    public List<ExperimentResult> findAllExperiments() {
        return List.copyOf(experiments);
    }

    @Override
    public List<ExperimentResult> findExperimentsByModelId(String modelId) {
        List<ExperimentResult> result = new ArrayList<>();
        for (ExperimentResult e : experiments) {
            if (modelId.equals(e.getModelId())) {
                result.add(e);
            }
        }
        return result;
    }

    @Override
    public List<ExperimentResult> findExperimentsByModelIdAndMaterial(String modelId, String material) {
        List<ExperimentResult> result = new ArrayList<>();
        for (ExperimentResult e : experiments) {
            if (!modelId.equals(e.getModelId())) {
                continue;
            }
            if (material == null || material.isBlank()) {
                result.add(e);
                continue;
            }
            if (material.equalsIgnoreCase(e.getMaterial())) {
                result.add(e);
            }
        }
        return result;
    }

    @Override
    public List<ResearchModel> findAllResearchModels() {
        return List.copyOf(researchModels);
    }

    @Override
    public Optional<ResearchModel> findResearchModelById(String id) {
        for (ResearchModel m : researchModels) {
            if (id.equals(m.getId())) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized void saveExperiment(ExperimentResult result) {
        List<ExperimentResult> updated = new ArrayList<>(experiments);
        updated.add(result);
        experiments = updated;
        persistExperiments();
    }
}