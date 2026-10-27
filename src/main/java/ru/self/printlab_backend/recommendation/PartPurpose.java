package ru.self.printlab_backend.recommendation;

public record PartPurpose(Category category, String source, PartRequirements requirements, java.util.List<String> questions) {
    public PartPurpose { questions = java.util.List.copyOf(questions); }
    public PartPurpose(Category category, String source, PartRequirements requirements) {
        this(category, source, requirements, java.util.List.of());
    }
    public enum Category { GENERAL, DECORATIVE, PROTOTYPE, FUNCTIONAL, REINFORCED, FLEXIBLE }
    public PartPurpose(Category category, String source) {
        this(category, source, PartRequirements.unknown());
    }
    public static PartPurpose general() {
        return new PartPurpose(Category.GENERAL, "DEFAULT");
    }
}
