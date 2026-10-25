package ru.self.printlab_backend.dto.model;

import java.time.Instant;
import java.util.UUID;

public record UploadedModel(UUID id, String fileName, long sizeBytes, int triangleCount, Instant uploadedAt) {}
