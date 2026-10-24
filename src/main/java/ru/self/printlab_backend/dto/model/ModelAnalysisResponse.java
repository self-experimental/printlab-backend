package ru.self.printlab_backend.dto.model;

import lombok.Builder;
import ru.self.printlab_backend.model.ModelGeometry;
import ru.self.printlab_backend.model.ResearchModel;

@Builder
public record ModelAnalysisResponse(
        ModelGeometry geometry,
        String matchedModelId,
        String matchedModelName,
        boolean matched
) {
    public static ModelAnalysisResponse of(ModelGeometry geometry, ResearchModel model) {
        boolean matched = model != null;
        String matchedModelId = null;
        String matchedModelName = null;
        if (model != null) {
            matchedModelId = model.getId();
            matchedModelName = model.getName();
        }
        return ModelAnalysisResponse
                .builder()
                .geometry(geometry)
                .matched(matched)
                .matchedModelId(matchedModelId)
                .matchedModelName(matchedModelName)
                .build();
    }
}