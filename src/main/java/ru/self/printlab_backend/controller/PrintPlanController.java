package ru.self.printlab_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import ru.self.printlab_backend.dto.printplan.PrintPlanResponse;
import ru.self.printlab_backend.service.PrintPlanService;

@RestController
@RequestMapping("/api/print-plans")
@RequiredArgsConstructor
public class PrintPlanController {
    private final PrintPlanService service;
    @PostMapping(value = "/analyze-stl", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PrintPlanResponse> analyze(@RequestParam("file") MultipartFile file,
            @Valid @ModelAttribute PrintPlanRequest request,
            @RequestHeader(value = "X-PrintLab-Requested-Only", defaultValue = "false") boolean requestedOnly) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.analyze(file, request, requestedOnly));
    }
}
