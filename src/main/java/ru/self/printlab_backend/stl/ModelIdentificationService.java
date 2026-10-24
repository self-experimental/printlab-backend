package ru.self.printlab_backend.stl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.self.printlab_backend.model.ModelGeometry;
import ru.self.printlab_backend.model.ResearchModel;
import ru.self.printlab_backend.repository.ExperimentRepository;

import java.util.List;
import java.util.Optional;

/**
 * Сопоставляет загруженную модель с уже исследованными образцами по геометрии.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelIdentificationService {

    private static final double TOLERANCE_RATIO = 0.15;

    private final ExperimentRepository experimentRepository;

    public Optional<ResearchModel> identify(ModelGeometry geometry) {
        List<ResearchModel> models = experimentRepository.findAllResearchModels();

        ResearchModel best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (ResearchModel m : models) {
            if (m.getGeometry() == null) {
                continue;
            }
            double distance = geometryDistance(geometry, m.getGeometry());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = m;
            }
        }

        if (best == null) {
            return Optional.empty();
        }
        if (bestDistance >= TOLERANCE_RATIO) {
            return Optional.empty();
        }
        return Optional.of(best);
    }

    private double geometryDistance(ModelGeometry a, ModelGeometry b) {
        double dv = relativeDiff(a.getVolumeMm3(), b.getVolumeMm3());
        double dx = relativeDiff(a.getBoundingBoxXmm(), b.getBoundingBoxXmm());
        double dy = relativeDiff(a.getBoundingBoxYmm(), b.getBoundingBoxYmm());
        double dz = relativeDiff(a.getBoundingBoxZmm(), b.getBoundingBoxZmm());
        return (dv + dx + dy + dz) / 4.0;
    }

    private double relativeDiff(double a, double b) {
        double denom = Math.max(Math.abs(a), Math.abs(b));
        if (denom == 0) {
            return 0;
        }
        return Math.abs(a - b) / denom;
    }
}