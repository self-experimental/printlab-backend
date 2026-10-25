package ru.self.printlab_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import ru.self.printlab_backend.dto.model.UploadedModel;
import ru.self.printlab_backend.repository.FileSystemModelRepository;
import ru.self.printlab_backend.stl.StlFileParser;
import ru.self.printlab_backend.stl.StlLimits;
import ru.self.printlab_backend.stl.StlParseException;
import ru.self.printlab_backend.stl.StlUploadError;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModelUploadService {
    private final StlFileParser parser;
    private final FileSystemModelRepository repository;

    public UploadedModel upload(MultipartFile file) {
        if (file.isEmpty()) throw new StlParseException(StlUploadError.EMPTY_FILE);
        if (file.getSize() > StlLimits.MAX_BYTES) throw new MaxUploadSizeExceededException(StlLimits.MAX_BYTES);
        String name = file.getOriginalFilename();
        if (name == null) throw new StlParseException(StlUploadError.INVALID_FILE_NAME);
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.length() > 255 || name.chars().anyMatch(Character::isISOControl)) {
            throw new StlParseException(StlUploadError.INVALID_FILE_NAME);
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".stl")) {
            throw new StlParseException(StlUploadError.WRONG_FILE_TYPE);
        }
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(StlLimits.MAX_BYTES + 1);
            if (bytes.length > StlLimits.MAX_BYTES) throw new MaxUploadSizeExceededException(StlLimits.MAX_BYTES);
            var geometry = parser.parse(bytes);
            var model = new UploadedModel(UUID.randomUUID(), name, bytes.length,
                    geometry.getTriangleCount(), Instant.now());
            return repository.save(bytes, model);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать загруженный файл", e);
        }
    }

    public UploadedModel find(UUID id) { return repository.find(id); }
}
