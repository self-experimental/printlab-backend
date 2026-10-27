package ru.self.printlab_backend.recommendation;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;
import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static ru.self.printlab_backend.recommendation.PartRequirements.*;

class LocalRequirementRulesTest {
    final LocalRequirementRules rules = new LocalRequirementRules();
    final MaterialCatalogRepository materials = new MaterialCatalogRepository(JsonMapper.builder().build(), new ClassPathResource("catalog/materials.json"));
    final PrintRecommendations recommendations = new PrintRecommendations(new LocalPurposeClassifier(rules, text -> Optional.empty()), materials);
    PrintPlanRequest input(String text) { return new PrintPlanRequest(null, null, null, null, null, null, null, null, text, null, null, null); }

    @TestFactory Stream<DynamicTest> publicCorpusChecksInterpretationAndActualPolicyWithoutAnyModel() throws Exception {
        try (var in = new ClassPathResource("recommendation/local-requirements-cases.json").getInputStream()) {
            var tests = new ArrayList<DynamicTest>();
            for (var row : JsonMapper.builder().build().readTree(in)) tests.add(DynamicTest.dynamicTest(row.path("id").asText(), () -> {
                var text = row.path("text").asText();
                var resolved = recommendations.resolve(input(text));
                var r = resolved.purpose().requirements();
                var facts = Map.of("use", r.use(), "load", r.load(), "loadKind", r.loadKind(), "environment", r.environment(), "flexibility", r.flexibility(), "goal", r.goal());
                facts.forEach((key, fact) -> {
                    var expected = row.path("expected").path(key);
                    if (expected.isArray()) assertThat(expected.valueStream().map(v -> v.asText()).toList()).contains(fact.value().name());
                    else assertThat(fact.value().name()).as(key).isEqualTo(expected.asText());
                    if (!fact.value().name().equals("UNKNOWN")) assertThat(text).contains(fact.quote());
                    assertThat(fact.quote()).hasSizeLessThanOrEqualTo(180);
                });
                var settings = resolved.settings();
                var expected = row.path("expectedSettings");
                assertThat(settings.minimumInfillPercent()).isEqualTo(expected.path("infill").asInt());
                assertThat(settings.minimumPerimeters()).isEqualTo(expected.path("walls").asInt());
                assertThat(settings.layerHeightMm()).isEqualTo(expected.path("layer").asDouble());
                assertThat(materials.catalog().materials().stream().filter(m -> m.id().equals(settings.materialProfileId())).findFirst().orElseThrow().family())
                        .isEqualTo(expected.path("materialFamily").asText());
                assertThat(resolved.purpose().source()).isEqualTo("RULES");
                assertThat(recommendations.resolve(input(text))).isEqualTo(resolved);
            }));
            return tests.stream();
        }
    }

    @Test void negationsDoNotBecomePositiveRequirements() {
        for (String text : List.of("Корпус без нагрева, без ударов и вибрации, не гибкий.",
                "Корпус. Не нужна высокая нагрузка. Нагрева не будет. Удары исключены. Не гибкий.",
                "Enclosure, no heating, no impacts, not flexible.")) {
            var r = rules.parse(text).requirements();
            assertThat(r.environment().value()).isNotEqualTo(Environment.HEAT);
            assertThat(r.load().value()).isNotEqualTo(Load.HIGH);
            assertThat(r.loadKind().value()).isEqualTo(LoadKind.UNKNOWN);
            assertThat(r.flexibility().value()).isEqualTo(Flexibility.RIGID);
        }
        assertThat(rules.parse("Корпус без высокой нагрузки, но на улице под дождём.").requirements().environment().value()).isEqualTo(Environment.OUTDOOR);
        assertThat(rules.parse("Скульптура не должна быть гибкой.").requirements().flexibility().value()).isNotEqualTo(Flexibility.FLEXIBLE);
    }

    @Test void loadSeverityAcceptsCommonWordOrdersButDoesNotInferItFromABareNumber() {
        for (String text : List.of("Большая нагрузка", "Нагрузка большая", "Нагрузка будет очень высокой"))
            assertThat(rules.parse(text).requirements().load().value()).isEqualTo(Load.HIGH);
        for (String text : List.of("Небольшая нагрузка", "Нагрузка минимальная", "Нагрузка будет небольшой"))
            assertThat(rules.parse(text).requirements().load().value()).isEqualTo(Load.LIGHT);
        assertThat(rules.parse("Нагрузка будет умеренной").requirements().load().value()).isEqualTo(Load.NORMAL);
        assertThat(rules.parse("Нагрузка не будет высокой").requirements().load().value()).isNotEqualTo(Load.HIGH);
        assertThat(rules.parse("Деталь должна выдержать 1500 Н").requirements().load().value()).isEqualTo(Load.UNKNOWN);
    }

    @Test void contradictionsAndUncertaintyAskQuestionsInsteadOfChoosingAnArbitraryInterpretation() {
        var load = rules.parse("Небольшая нагрузка, но также высокая нагрузка.");
        assertThat(load.requirements().load().value()).isEqualTo(Load.HIGH);
        assertThat(load.questions()).anyMatch(q -> q.contains("максимальная нагрузка"));
        var flexible = rules.parse("Держатель должен быть гибким и жёстким.");
        assertThat(flexible.requirements().flexibility().value()).isEqualTo(Flexibility.UNKNOWN);
        assertThat(flexible.questions()).isNotEmpty();
        var uncertain = rules.parse("Держатель. Возможно будет нагрев и высокая нагрузка.");
        assertThat(uncertain.requirements().environment().value()).isEqualTo(Environment.UNKNOWN);
        assertThat(uncertain.requirements().load().value()).isEqualTo(Load.UNKNOWN);
        assertThat(uncertain.questions()).isNotEmpty();
        var balanced = recommendations.resolve(input("Макет для быстрой проверки размеров."));
        assertThat(balanced.purpose().questions()).isNotEmpty();
        assertThat(balanced.settings().priority()).isEqualTo(PrintPlanRequest.Priority.BALANCED);
        assertThat(balanced.settings().layerHeightMm()).isEqualTo(.20);
    }

    @Test void decimalTemperatureAndUnitsAreKeptWithoutInventingHeatFromElectronicsOrLoadFromWeather() {
        assertThat(rules.parse("Корпус при 65,5 °C.").requirements().environment().value()).isEqualTo(Environment.HEAT);
        var weather = rules.parse("Корпус электроники постоянно под дождём.").requirements();
        assertThat(weather.environment().value()).isEqualTo(Environment.OUTDOOR);
        assertThat(weather.loadKind().value()).isEqualTo(LoadKind.UNKNOWN);
        assertThat(recommendations.resolve(input("Деталь под 15 Н.")).settings().minimumInfillPercent())
                .isEqualTo(recommendations.resolve(input("Деталь под 1500 Н.")).settings().minimumInfillPercent());
    }

    @Test void optionalAiCanOnlyAddAQuestionAndIsNeverCalledForKnownConditionsOrConflicts() {
        var calls = new AtomicInteger();
        var classifier = new LocalPurposeClassifier(rules, text -> { calls.incrementAndGet(); return Optional.of("Где будет использоваться деталь?"); });
        for (String text : List.of("", "Подставка, нужно совсем слабо", "Огромная нагрузка 1500 Н", "Деталь 20 kg", "Гибкая и жёсткая деталь", "Возможно нагрев")) classifier.classify(text);
        assertThat(calls).hasValue(0);
        var helped = classifier.classify("Нужна нормальная штука.");
        assertThat(calls).hasValue(1);
        assertThat(helped.requirements()).isEqualTo(PartRequirements.unknown());
        assertThat(helped.questions()).containsExactly("Где будет использоваться деталь?");
        var assisted = new PrintRecommendations(classifier, materials).resolve(input("Нужна нормальная штука.")).settings();
        assertThat(assisted).isEqualTo(recommendations.resolve(input("Нужна нормальная штука.")).settings());
    }
}
