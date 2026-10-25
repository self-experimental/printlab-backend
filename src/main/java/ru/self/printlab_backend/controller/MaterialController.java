package ru.self.printlab_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.self.printlab_backend.model.material.MaterialCatalog;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;

import java.time.Duration;

@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialController {
    private final MaterialCatalogRepository repository;

    @GetMapping
    public ResponseEntity<MaterialCatalog> catalog() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .eTag(repository.etag()).body(repository.catalog());
    }
}
