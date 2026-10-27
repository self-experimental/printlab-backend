package ru.self.printlab_backend.recommendation;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.regex.Pattern;

/** Abstention guards, never a keyword-to-profile classifier. An unsupported claim becomes UNKNOWN. */
final class RequirementEvidence {
    private static final JsonNode RULES = rules();
    private static JsonNode rules() {
        try (var in = new ClassPathResource("recommendation/requirement-evidence.json").getInputStream()) {
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    static boolean supports(String field, String value, String quote, String description) {
        if ("UNKNOWN".equals(value)) return true;
        if ("use".equals(field) && "FUNCTIONAL".equals(value)) {
            var words = Pattern.compile("(?U)[\\p{L}]+", Pattern.UNICODE_CHARACTER_CLASS).matcher(quote).results().map(m -> m.group()).toList();
            if (!words.isEmpty() && words.stream().allMatch(word -> matches(RULES.path("genericUseWords").asText(), word, true))) return false;
        }
        String rule = RULES.path(field).path(value).asText("");
        // The supporting clause may be selected poorly even if the requested condition is elsewhere in the text.
        return rule.isEmpty() || matches(rule, description, false);
    }
    /** Recover only an explicit, unambiguous print preference; never invent mechanical conditions. */
    static PartRequirements.Fact<PartRequirements.Goal> explicitGoal(String description) {
        java.util.Map<PartRequirements.Goal, String> found = new java.util.EnumMap<>(PartRequirements.Goal.class);
        for (String clause : OllamaPurposeClassifier.evidenceChoices(description)) {
            if (matches("(?U)\\b(не|нет|без|not|no|never)\\b", clause, false)) continue;
            for (var goal : PartRequirements.Goal.values()) {
                if (goal == PartRequirements.Goal.UNKNOWN) continue;
                if (matches(RULES.path("goalRecovery").path(goal.name()).asText(), clause, false)) found.putIfAbsent(goal, clause);
            }
        }
        if (found.size() != 1) return new PartRequirements.Fact<>(PartRequirements.Goal.UNKNOWN, "");
        var result = found.entrySet().iterator().next();
        return new PartRequirements.Fact<>(result.getKey(), result.getValue());
    }
    private static boolean matches(String expression, String text, boolean entire) {
        var matcher = Pattern.compile(expression, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        return entire ? matcher.matches() : matcher.find();
    }
    private RequirementEvidence() {}
}
