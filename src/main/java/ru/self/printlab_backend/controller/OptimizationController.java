package ru.self.printlab_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.self.printlab_backend.dto.optimization.OptimizationByGeometryRequest;
import ru.self.printlab_backend.dto.optimization.OptimizationRequest;
import ru.self.printlab_backend.dto.optimization.OptimizationResponse;
import ru.self.printlab_backend.service.OptimizationService;
import ru.self.printlab_backend.service.StlAnalysisResult;
import ru.self.printlab_backend.service.StlAnalysisService;

@RestController
@RequestMapping("/api/optimization")
@RequiredArgsConstructor
public class OptimizationController {

    private final OptimizationService optimizationService;
    private final StlAnalysisService stlAnalysisService;

    @PostMapping(value = "/analyze-stl", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OptimizationResponse optimizeByStl(
            @RequestParam("file") MultipartFile file,
            @Valid @ModelAttribute OptimizationRequest request) { // <-- Добавлен @ModelAttribute
        StlAnalysisResult analysis = stlAnalysisService.analyze(file);
        return optimizationService.optimize(analysis.matchedModel(), request);
    }

    @PostMapping("/optimize")
    public OptimizationResponse optimize(@RequestBody @Valid OptimizationByGeometryRequest body) {
        return optimizationService.optimize(body.matchedModel(), body.request());
    }
}