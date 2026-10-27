package ru.self.printlab_backend.recommendation;

import java.util.List;

/** Only grounded observations from the user's text, never predicted mechanical properties. */
public record PartRequirements(Fact<Use> use, Fact<Load> load, Fact<LoadKind> loadKind,
        Fact<Environment> environment, Fact<Flexibility> flexibility, Fact<Goal> goal) {
    public enum Use { UNKNOWN, FUNCTIONAL, DECORATIVE, PROTOTYPE }
    public enum Load { UNKNOWN, UNSPECIFIED, LIGHT, NORMAL, HIGH }
    public enum LoadKind { UNKNOWN, STATIC, IMPACT, CYCLIC }
    public enum Environment { UNKNOWN, INDOOR, OUTDOOR, HEAT }
    public enum Flexibility { UNKNOWN, FLEXIBLE, RIGID }
    public enum Goal { UNKNOWN, DETAIL, FIT, SPEED, ECONOMY }
    public record Fact<T extends Enum<T>>(T value, String quote) {}

    public static PartRequirements unknown() {
        return new PartRequirements(new Fact<>(Use.UNKNOWN, ""), new Fact<>(Load.UNKNOWN, ""),
                new Fact<>(LoadKind.UNKNOWN, ""), new Fact<>(Environment.UNKNOWN, ""),
                new Fact<>(Flexibility.UNKNOWN, ""), new Fact<>(Goal.UNKNOWN, ""));
    }

    public PartPurpose.Category category() {
        // Appearance, economy and draft requests must never erase a simultaneous mechanical requirement.
        if (load.value() == Load.HIGH || loadKind.value() == LoadKind.IMPACT || loadKind.value() == LoadKind.CYCLIC)
            return PartPurpose.Category.REINFORCED;
        if (flexibility.value() == Flexibility.FLEXIBLE) return PartPurpose.Category.FLEXIBLE;
        return switch (use.value()) {
            case FUNCTIONAL -> PartPurpose.Category.FUNCTIONAL;
            case DECORATIVE -> PartPurpose.Category.DECORATIVE;
            case PROTOTYPE -> PartPurpose.Category.PROTOTYPE;
            case UNKNOWN -> PartPurpose.Category.GENERAL;
        };
    }

    public boolean mechanical() {
        return load.value() == Load.HIGH || load.value() == Load.NORMAL || load.value() == Load.UNSPECIFIED || loadKind.value() != LoadKind.UNKNOWN;
    }

    public List<String> quotes() {
        return List.of(use.quote(), load.quote(), loadKind.quote(), environment.quote(), flexibility.quote(), goal.quote())
                .stream().filter(s -> !s.isBlank()).distinct().toList();
    }
}
