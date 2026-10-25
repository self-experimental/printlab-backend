package ru.self.printlab_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.self.printlab_backend.dto.model.ModelAnalysisResponse;
import ru.self.printlab_backend.dto.model.UploadedModel;
import ru.self.printlab_backend.service.ModelUploadService;
import ru.self.printlab_backend.service.StlAnalysisResult;
import ru.self.printlab_backend.service.StlAnalysisService;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final StlAnalysisService stlAnalysisService;
    private final ModelUploadService modelUploadService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadedModel> upload(@RequestParam("file") MultipartFile file) {
        UploadedModel model = modelUploadService.upload(file);
        return ResponseEntity.created(URI.create("/api/models/" + model.id())).body(model);
    }

    @GetMapping("/{id}")
    public UploadedModel get(@PathVariable UUID id) {
        return modelUploadService.find(id);
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ModelAnalysisResponse analyze(@RequestParam("file") MultipartFile file) {
        StlAnalysisResult result = stlAnalysisService.analyze(file);
        return ModelAnalysisResponse.of(result.geometry(), result.matchedModel());
    }
}
