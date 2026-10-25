package ru.self.printlab_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.self.printlab_backend.model.ModelGeometry;
import ru.self.printlab_backend.model.ResearchModel;
import ru.self.printlab_backend.stl.StlFileParser;
import ru.self.printlab_backend.stl.ModelIdentificationService;
import ru.self.printlab_backend.stl.StlParseException;
import ru.self.printlab_backend.stl.StlUploadError;
import ru.self.printlab_backend.stl.StlLimits;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Принимает STL, проверяет корректность и получает базовые характеристики геометрии.
 */
@Service
@RequiredArgsConstructor
public class StlAnalysisService {

    private final StlFileParser stlFileParser;
    private final ModelIdentificationService modelIdentificationService;

    public StlAnalysisResult analyze(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new StlParseException(StlUploadError.EMPTY_FILE);
        }
        if (file.getSize() > StlLimits.MAX_BYTES) throw new MaxUploadSizeExceededException(StlLimits.MAX_BYTES);
        try (InputStream in = file.getInputStream()) {
            ModelGeometry geometry = stlFileParser.parse(in);
            Optional<ResearchModel> matched = modelIdentificationService.identify(geometry);
            ResearchModel model = null;
            if (matched.isPresent()) {
                model = matched.get();
            }
            return new StlAnalysisResult(geometry, model);
        } catch (IOException e) {
            throw new StlParseException("Не удалось прочитать загруженный файл", e);
        }
    }
}
