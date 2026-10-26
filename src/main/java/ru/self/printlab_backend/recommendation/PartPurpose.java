package ru.self.printlab_backend.recommendation;

public record PartPurpose(Category category, String source) {
    public enum Category { GENERAL, DECORATIVE, PROTOTYPE, FUNCTIONAL, REINFORCED, FLEXIBLE }
    public static PartPurpose general() {
        return new PartPurpose(Category.GENERAL, "DEFAULT");
    }
}
