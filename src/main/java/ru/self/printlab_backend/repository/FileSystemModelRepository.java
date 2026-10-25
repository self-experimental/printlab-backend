package ru.self.printlab_backend.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import ru.self.printlab_backend.dto.model.UploadedModel;
import ru.self.printlab_backend.exception.ModelNotFoundException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Publishes a complete file + metadata directory with a single atomic rename. */
@Slf4j
@Repository
public class FileSystemModelRepository {
    private final Path root;
    private final JsonMapper mapper;

    public FileSystemModelRepository(JsonMapper mapper,
            @Value("${printlab.models.dir:./data/uploads}") String directory) {
        this.mapper = mapper;
        root = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось открыть хранилище моделей", e);
        }
    }

    public UploadedModel save(byte[] bytes, UploadedModel model) {
        Path staging = null;
        try {
            staging = Files.createTempDirectory(root, ".upload-");
            Files.write(staging.resolve("model.stl"), bytes);
            Files.write(staging.resolve("metadata.json"), mapper.writeValueAsBytes(model));
            Files.move(staging, root.resolve(model.id().toString()), StandardCopyOption.ATOMIC_MOVE);
            return model;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить модель", e);
        } finally {
            if (staging != null) cleanup(staging);
        }
    }

    public UploadedModel find(UUID id) {
        Path directory = root.resolve(id.toString());
        try {
            byte[] metadata = Files.readAllBytes(directory.resolve("metadata.json"));
            if (!Files.isRegularFile(directory.resolve("model.stl"))) {
                throw new IllegalStateException("Отсутствует файл сохранённой модели");
            }
            UploadedModel model = mapper.readValue(metadata, UploadedModel.class);
            if (!id.equals(model.id())) throw new IllegalStateException("Повреждены метаданные модели");
            return model;
        } catch (NoSuchFileException e) {
            throw new ModelNotFoundException();
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать модель", e);
        }
    }

    private void cleanup(Path staging) {
        try {
            Files.deleteIfExists(staging.resolve("metadata.json"));
            Files.deleteIfExists(staging.resolve("model.stl"));
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            log.warn("Не удалось удалить временные файлы загрузки", e);
        }
    }
}
