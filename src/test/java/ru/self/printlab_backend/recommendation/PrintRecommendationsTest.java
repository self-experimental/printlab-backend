package ru.self.printlab_backend.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;
import ru.self.printlab_backend.slicing.PrintPlanException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static ru.self.printlab_backend.dto.printplan.PrintPlanRequest.*;

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
}
