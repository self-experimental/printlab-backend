package ru.self.printlab_backend.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import ru.self.printlab_backend.dto.printplan.PrintPlanResponse;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;
import ru.self.printlab_backend.slicing.PrintPlanException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static ru.self.printlab_backend.dto.printplan.PrintPlanRequest.*;
import static ru.self.printlab_backend.recommendation.PartRequirements.*;

class PrintRecommendationsTest {
    final MaterialCatalogRepository materials = new MaterialCatalogRepository(JsonMapper.builder().build(), new ClassPathResource("catalog/materials.json"));
    PrintPlanRequest automatic(String description) { return new PrintPlanRequest(null, null, null, null, null, null, null, null, description, null, null, null); }
    PrintRecommendations advisor(PartPurpose.Category category) {
        return new PrintRecommendations(description -> new PartPurpose(category, "LOCAL_MODEL"), materials);
    }
    @Test void changesNumericProfilesForPurposeAndKeepsThePrinterCompatible() {
        var decorative = advisor(PartPurpose.Category.DECORATIVE).resolve(automatic("Статуэтка")).settings();
        var reinforced = advisor(PartPurpose.Category.REINFORCED).resolve(automatic("Кронштейн")).settings();
        assertThat(decorative.minimumInfillPercent()).isEqualTo(10);
        assertThat(decorative.layerHeightMm()).isEqualTo(.12);
        assertThat(reinforced.minimumInfillPercent()).isEqualTo(35);
        assertThat(reinforced.minimumPerimeters()).isEqualTo(4);
        assertThat(reinforced.topLayers()).isEqualTo(6);
        assertThat(advisor(PartPurpose.Category.FLEXIBLE).resolve(automatic("Чехол")).settings().materialProfileId()).isEqualTo("prusament-tpu-95a");
        assertThat(advisor(PartPurpose.Category.PROTOTYPE).resolve(automatic("Макет")).settings().priority()).isEqualTo(Priority.MIN_TIME);
    }
    @Test void respectsEveryExplicitManualSettingRatherThanReplacingItWithTheSuggestedProfile() {
        var input = new PrintPlanRequest("prusament-petg", Priority.MIN_MATERIAL, .28, 50, 6, SupportMode.NONE, 30d, 20d,
                "Кронштейн", InfillPattern.CUBIC, 8, 7);
        assertThat(advisor(PartPurpose.Category.REINFORCED).resolve(input).settings()).isEqualTo(input);
    }
    @Test void preservesManualMaterialEvenWhenThePurposeSuggestsFlexibleFilament() {
        var advisor = advisor(PartPurpose.Category.FLEXIBLE);
        assertThat(advisor.resolve(automatic("Гибкая деталь")).settings().materialProfileId()).isEqualTo("prusament-tpu-95a");
        var input = new PrintPlanRequest("prusament-pla", null, null, null, null, null, null, null, "Гибкая деталь", null, null, null);
        assertThat(advisor.resolve(input).settings().materialProfileId()).isEqualTo("prusament-pla");
    }
    @Test void validatesDescriptionAndLimitsBeforeCallingTheModel() {
        var advisor = new PrintRecommendations(description -> { throw new AssertionError("Do not classify invalid input"); }, materials);
        assertThatThrownBy(() -> advisor.resolve(automatic("x".repeat(2001)))).isInstanceOf(PrintPlanException.class);
        assertThatThrownBy(() -> advisor.resolve(automatic("bad\u0000text"))).isInstanceOf(PrintPlanException.class);
        assertThatThrownBy(() -> advisor.resolve(new PrintPlanRequest(null, null, .18, null, null, null, null, null, "", null, null, null))).isInstanceOf(PrintPlanException.class);
        assertThatThrownBy(() -> advisor.resolve(new PrintPlanRequest(null, null, null, 81, null, null, Double.NaN, null, "", null, null, null))).isInstanceOf(PrintPlanException.class);
    }

    PartRequirements requirements(Use use, Load load, LoadKind kind, Environment environment, Flexibility flex, Goal goal) {
        return new PartRequirements(new Fact<>(use, "назначение"), new Fact<>(load, "нагрузка"),
                new Fact<>(kind, "усилия"), new Fact<>(environment, "среда"), new Fact<>(flex, "материал"), new Fact<>(goal, "цель"));
    }
    PrintRecommendations advisor(PartRequirements r) {
        return new PrintRecommendations(description -> new PartPurpose(r.category(), "LOCAL_MODEL", r), materials);
    }
    PrintPlanResponse.Recommendation explain(PrintRecommendations advisor, PrintPlanRequest input) {
        var resolved = advisor.resolve(input);
        var s = resolved.settings();
        var material = materials.catalog().materials().stream().filter(m -> m.id().equals(s.materialProfileId())).findFirst().orElseThrow();
        var candidate = new PrintPlanResponse.Candidate("selected", s.minimumInfillPercent(), s.minimumPerimeters(),
                s.layerHeightMm(), s.infillPattern().name(), 20, 10, true, s.topLayers(), s.bottomLayers(), s.supportMode());
        return advisor.explain(resolved, input, candidate, material);
    }
    String text(PrintPlanResponse.Recommendation advice) { return advice.summary() + advice.items().toString(); }

    @Test void distinguishesTheActualUserPairAndShowsStatedForceWithoutClaimingCalculatedStrength() {
        var light = advisor(requirements(Use.FUNCTIONAL, Load.LIGHT, LoadKind.UNKNOWN, Environment.UNKNOWN, Flexibility.UNKNOWN, Goal.UNKNOWN));
        var high = advisor(requirements(Use.UNKNOWN, Load.HIGH, LoadKind.UNKNOWN, Environment.UNKNOWN, Flexibility.UNKNOWN, Goal.UNKNOWN));
        var first = automatic("плата для микросхемы, просто подставочка, нужно совсем слабо");
        var second = automatic("огромная нагрузка будет, около 1500 Н");
        assertThat(light.resolve(first).settings().minimumInfillPercent()).isEqualTo(10);
        assertThat(light.resolve(first).settings().minimumPerimeters()).isEqualTo(2);
        assertThat(high.resolve(second).settings().minimumInfillPercent()).isEqualTo(35);
        assertThat(high.resolve(second).settings().minimumPerimeters()).isEqualTo(4);
        assertThat(text(explain(light, first))).contains("небольшой нагрузке");
        assertThat(text(explain(high, second))).contains("1500 Н", "не вычисленная несущая способность", "Где деталь закреплена");
    }
    @Test void combinesMechanicalRequirementsWithDraftEconomyAndAppearanceWithoutWeakeningTheShell() {
        for (Use use : new Use[]{Use.PROTOTYPE, Use.DECORATIVE}) {
            for (Goal goal : new Goal[]{Goal.SPEED, Goal.ECONOMY, Goal.DETAIL}) {
                var advisor = advisor(requirements(use, Load.HIGH, LoadKind.IMPACT, Environment.UNKNOWN, Flexibility.UNKNOWN, goal));
                var settings = advisor.resolve(automatic("Красивый макет, большая нагрузка и удары")).settings();
                assertThat(settings.minimumInfillPercent()).isGreaterThanOrEqualTo(35);
                assertThat(settings.minimumPerimeters()).isGreaterThanOrEqualTo(5);
                assertThat(settings.layerHeightMm()).isLessThanOrEqualTo(.20);
                assertThat(settings.infillPattern()).isEqualTo(InfillPattern.GYROID);
            }
        }
    }
    @Test void choosesSupportedMaterialsFromGroundedEnvironmentAndReportsManualConflicts() {
        var outside = advisor(requirements(Use.FUNCTIONAL, Load.UNKNOWN, LoadKind.UNKNOWN, Environment.OUTDOOR, Flexibility.UNKNOWN, Goal.UNKNOWN));
        assertThat(outside.resolve(automatic("Корпус на улице")).settings().materialProfileId()).isEqualTo("prusament-petg");
        var manual = new PrintPlanRequest("prusament-pla", null, null, 5, 2, null, null, null, "Корпус на улице", null, null, null);
        assertThat(outside.resolve(manual).settings().materialProfileId()).isEqualTo("prusament-pla");
        assertThat(text(explain(outside, manual))).contains("Выбранный PLA", "Ручной выбор сохранён", "20%");
        var hot = advisor(requirements(Use.FUNCTIONAL, Load.UNKNOWN, LoadKind.UNKNOWN, Environment.HEAT, Flexibility.UNKNOWN, Goal.UNKNOWN));
        var hotAdvice = explain(hot, automatic("90 °C"));
        assertThat(text(hotAdvice)).contains("рабочую температуру", "а не гарантированная температура", "Указано 90 °C", "HDT");
        assertThat(hotAdvice.summary()).contains("материал требует отдельной проверки");
    }
    @Test void doesNotInventMagnitudeThresholdsOrConvertMassIntoForce() {
        var unknown = advisor(PartPurpose.Category.GENERAL);
        assertThat(unknown.resolve(automatic("Деталь для 15 Н")).settings().minimumInfillPercent())
                .isEqualTo(unknown.resolve(automatic("Деталь для 1500 Н")).settings().minimumInfillPercent());
        assertThat(PrintRecommendations.quantities("Нагрузка 1,5 кН, 20 kg или 1500 Н, температура 90 C"))
                .containsExactly("1,5 кН", "20 kg", "1500 Н");
        assertThat(PrintRecommendations.quantities("PLA, размер 90 мм, температура 80 °C")).isEmpty();
        assertThat(text(explain(unknown, automatic("Деталь для 20 кг")))).contains("20 кг", "не преобразуется в процент заполнения");
    }
    @Test void constantLoadCannotReceiveAnUnloadedDraftProfile() {
        var advisor = advisor(requirements(Use.PROTOTYPE, Load.UNKNOWN, LoadKind.STATIC, Environment.UNKNOWN, Flexibility.UNKNOWN, Goal.SPEED));
        var settings = advisor.resolve(automatic("Макет, но уже под постоянной нагрузкой")).settings();
        assertThat(settings.minimumInfillPercent()).isGreaterThanOrEqualTo(20);
        assertThat(settings.minimumPerimeters()).isGreaterThanOrEqualTo(3);
        assertThat(settings.layerHeightMm()).isEqualTo(.20);
    }
    @Test void explainsActualManualChoicesInsteadOfAttributingThemToTheModel() {
        var advisor = advisor(requirements(Use.FUNCTIONAL, Load.LIGHT, LoadKind.UNKNOWN, Environment.UNKNOWN, Flexibility.UNKNOWN, Goal.DETAIL));
        var request = new PrintPlanRequest("prusament-pla", Priority.MIN_TIME, .28, 80, 6, SupportMode.AUTO, null, null,
                "Лёгкая красивая подставка", InfillPattern.GRID, 8, 8);
        String explanation = text(explain(advisor, request));
        assertThat(explanation).contains("80%", "Стенок: 6", "заданный вами предел", "Рисунок задан вами вручную");
        assertThat(explanation).doesNotContain("Тонкий слой выбран");
    }
    @Test void reportsFallbackAndUnknownRequirementsWithoutPretendingTheyWereUnderstood() {
        var fallback = new PrintRecommendations(description -> new PartPurpose(PartPurpose.Category.GENERAL, "FALLBACK"), materials);
        assertThat(explain(fallback, automatic("Что-то хорошее")).summary()).contains("Не удалось надёжно разобрать");
        assertThat(explain(advisor(PartPurpose.Category.GENERAL), automatic("Что-то хорошее")).summary()).contains("Не удалось уверенно");
        assertThat(explain(advisor(PartPurpose.Category.GENERAL), automatic("")).summary()).contains("Добавьте назначение");
    }
}
