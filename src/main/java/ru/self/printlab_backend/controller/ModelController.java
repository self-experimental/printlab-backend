package ru.self.printlab_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.self.printlab_backend.dto.model.ModelAnalysisResponse;
import ru.self.printlab_backend.service.StlAnalysisResult;
import ru.self.printlab_backend.service.StlAnalysisService;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final StlAnalysisService stlAnalysisService;

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ModelAnalysisResponse analyze(@RequestParam("file") MultipartFile file) {
        StlAnalysisResult result = stlAnalysisService.analyze(file);
        return ModelAnalysisResponse.of(result.geometry(), result.matchedModel());
    }
}