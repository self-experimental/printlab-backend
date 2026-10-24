package ru.self.printlab_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.self.printlab_backend.dto.experiment.ExperimentResponse;
import ru.self.printlab_backend.dto.experiment.ResearchModelResponse;
import ru.self.printlab_backend.model.ExperimentResult;
import ru.self.printlab_backend.model.ResearchModel;
import ru.self.printlab_backend.service.ExperimentService;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/experiments")
@RequiredArgsConstructor
public class ExperimentController {

    private final ExperimentService experimentService;

    @GetMapping
    public List<ExperimentResponse> findAll(@RequestParam(required = false) String modelId) {
        List<ExperimentResult> results;
        if (modelId == null || modelId.isBlank()) {
            results = experimentService.findAll();
        } else {
            results = experimentService.findByModelId(modelId);
        }

        List<ExperimentResponse> response = new ArrayList<>();
        for (ExperimentResult e : results) {
            response.add(ExperimentResponse.from(e));
        }
        return response;
    }

    @GetMapping("/models")
    public List<ResearchModelResponse> researchModels() {
        List<ResearchModel> models = experimentService.findAllResearchModels();
        List<ResearchModelResponse> response = new ArrayList<>();
        for (ResearchModel m : models) {
            response.add(ResearchModelResponse.from(m));
        }
        return response;
    }

    @PostMapping
    public ExperimentResponse save(@RequestBody ExperimentResult result) {
        return ExperimentResponse.from(experimentService.save(result));
    }
}