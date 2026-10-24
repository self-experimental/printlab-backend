package ru.self.printlab_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import ru.self.printlab_backend.model.ModelGeometry;
import ru.self.printlab_backend.model.ResearchModel;
import ru.self.printlab_backend.stl.BinaryStlParser;
import ru.self.printlab_backend.stl.ModelIdentificationService;
import ru.self.printlab_backend.stl.StlParseException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Принимает STL, проверяет корректность и получает базовые характеристики геометрии.
 */
@Service
@RequiredArgsConstructor
public class StlAnalysisService {

    private final BinaryStlParser binaryStlParser;
    private final ModelIdentificationService modelIdentificationService;

    public StlAnalysisResult analyze(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new StlParseException("Файл STL не передан или пуст");
        }
        try (InputStream in = file.getInputStream()) {
            ModelGeometry geometry = binaryStlParser.parse(in);
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