package ru.self.printlab_backend.dto.experiment;

import ru.self.printlab_backend.model.ModelGeometry;
import ru.self.printlab_backend.model.ResearchModel;

public record ResearchModelResponse(
        String id,
        String name,
        String material,
        ModelGeometry geometry
) {
    public static ResearchModelResponse from(ResearchModel m) {
        return new ResearchModelResponse(m.getId(), m.getName(), m.getMaterial(), m.getGeometry());
    }
}