package ru.self.printlab_backend.recommendation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/** AI may ask a question; it cannot modify any setting or requirement accepted by the rules. */
@Component
@RequiredArgsConstructor
public final class LocalPurposeClassifier implements PurposeClassifier {
    private final LocalRequirementRules rules;
    private final ClarificationAdvisor advisor;

    @Override public PartPurpose classify(String description) {
        var result = rules.parse(description);
        if (description == null || description.isBlank() || !result.requirements().quotes().isEmpty() || !result.questions().isEmpty() || !PrintRecommendations.quantities(description).isEmpty()) return result;
        var suggestion = advisor.suggest(description);
        if (suggestion.isEmpty()) return result;
        var questions = new ArrayList<>(result.questions());
        questions.add(suggestion.orElseThrow());
        return new PartPurpose(result.category(), result.source(), result.requirements(), questions);
    }
}
