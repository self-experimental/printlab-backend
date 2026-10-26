package ru.self.printlab_backend.recommendation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.self.printlab_backend.dto.printplan.PrintPlanRequest;
import ru.self.printlab_backend.dto.printplan.PrintPlanResponse;
import ru.self.printlab_backend.dto.printplan.PrintPlanResponse.Item;
import ru.self.printlab_backend.model.material.MaterialProfile;
import ru.self.printlab_backend.repository.MaterialCatalogRepository;
import ru.self.printlab_backend.slicing.PrintPlanException;

import java.text.NumberFormat;
import java.util.*;

import static ru.self.printlab_backend.slicing.PrintPlanException.Error.INVALID_PRINT_INPUT;
import static ru.self.printlab_backend.dto.printplan.PrintPlanRequest.*;

@Component
@RequiredArgsConstructor
public class PrintRecommendations {
    private final PurposeClassifier classifier;
    private final MaterialCatalogRepository materials;
    public record Resolved(PrintPlanRequest settings, PartPurpose purpose) {}
    private record Profile(int fill, int walls, double layer, InfillPattern pattern, int top, int bottom) {}

    public Resolved resolve(PrintPlanRequest input) {
        validate(input);
        if (input.materialProfileId() != null && materials.catalog().materials().stream()
                .noneMatch(material -> material.id().equals(input.materialProfileId()) && Set.of("PLA", "PETG", "TPU_95A").contains(material.family())))
            throw new PrintPlanException(PrintPlanException.Error.MATERIAL_UNSUPPORTED);
        PartPurpose purpose = classifier.classify(input.description());
        Profile profile = switch (purpose.category()) {
            case DECORATIVE -> new Profile(10, 2, .12, InfillPattern.GYROID, 4, 3);
            case PROTOTYPE -> new Profile(10, 2, .28, InfillPattern.RECTILINEAR, 3, 3);
            case FUNCTIONAL -> new Profile(20, 3, .20, InfillPattern.GYROID, 5, 4);
            case REINFORCED -> new Profile(35, 4, .20, InfillPattern.GYROID, 6, 5);
            case FLEXIBLE -> new Profile(15, 3, .20, InfillPattern.GYROID, 4, 4);
            case GENERAL -> new Profile(15, 2, .20, InfillPattern.GYROID, 4, 4);
        };
        String family = purpose.category() == PartPurpose.Category.FLEXIBLE ? "TPU_95A" : "PLA";
        String material = input.materialProfileId() != null ? input.materialProfileId()
                : materials.catalog().materials().stream().filter(m -> family.equals(m.family())).findFirst()
                .orElseThrow(() -> new PrintPlanException(PrintPlanException.Error.MATERIAL_UNSUPPORTED)).id();
        var settings = new PrintPlanRequest(material,
                input.priority() != null ? input.priority() : purpose.category() == PartPurpose.Category.PROTOTYPE ? Priority.MIN_TIME : Priority.BALANCED,
                input.layerHeightMm() != null ? input.layerHeightMm() : profile.layer,
                input.minimumInfillPercent() != null ? input.minimumInfillPercent() : profile.fill,
                input.minimumPerimeters() != null ? input.minimumPerimeters() : profile.walls,
                input.supportMode() != null ? input.supportMode() : SupportMode.AUTO,
                input.maxPrintTimeMin() != null ? input.maxPrintTimeMin() : 0,
                input.maxMaterialConsumptionG() != null ? input.maxMaterialConsumptionG() : 0,
                input.description(), input.infillPattern() != null ? input.infillPattern() : profile.pattern,
                input.topLayers() != null ? input.topLayers() : profile.top,
                input.bottomLayers() != null ? input.bottomLayers() : profile.bottom);
        return new Resolved(settings, purpose);
    }

    public PrintPlanResponse.Recommendation explain(Resolved resolved, PrintPlanRequest original,
                                                   PrintPlanResponse.Candidate selected, MaterialProfile material) {
        PartPurpose purpose = resolved.purpose();
        String title = switch (purpose.category()) {
            case DECORATIVE -> "Декоративная деталь";
            case PROTOTYPE -> "Прототип для примерки";
            case FUNCTIONAL -> "Деталь для повседневного использования";
            case REINFORCED -> "Деталь с повышенными требованиями к жёсткости";
            case FLEXIBLE -> "Гибкая деталь";
            case GENERAL -> "Универсальная печать";
        };
        String summary = switch (purpose.category()) {
            case DECORATIVE -> "Для декоративной детали приоритетом служит качество поверхности при умеренном расходе материала.";
            case PROTOTYPE -> "Для прототипа приоритетом служит быстрая проверка формы и размеров.";
            case FUNCTIONAL -> "Для повседневного применения учитываем целостность оболочки и экономичность печати.";
            case REINFORCED -> "Для описанного применения важны оболочка и внутреннее заполнение. Это рекомендация по печати, а не подтверждение допустимой нагрузки.";
            case FLEXIBLE -> "Для гибкой детали выбран профиль с умеренным заполнением; итоговая гибкость зависит от материала и формы.";
            case GENERAL -> original.description().isBlank() ? "Подобран базовый профиль с учётом заданных параметров и ограничений."
                    : "Назначение описано без подробных условий: выбран универсальный профиль. Уточните применение, если деталь должна работать под нагрузкой или при нагреве.";
        };
        if ("FALLBACK".equals(purpose.source())) summary = "Сервис разбора описания сейчас недоступен; использован универсальный профиль. " + summary;
        if (original.minimumInfillPercent() != null || original.minimumPerimeters() != null || original.layerHeightMm() != null
                || original.infillPattern() != null || original.topLayers() != null || original.bottomLayers() != null)
            summary = "Учтены ваши ручные настройки. " + summary;
        NumberFormat number = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
        number.setMaximumFractionDigits(2);
        String pattern = switch (InfillPattern.valueOf(selected.infillPattern())) {
            case GYROID -> "Гироид";
            case RECTILINEAR -> "Прямолинейное";
            case GRID -> "Сетка";
            case CUBIC -> "Кубическое";
        };
        List<Item> items = new ArrayList<>();
        items.add(new Item("Заполнение", selected.infillPercent() + "% · " + pattern + ". "
                + (original.minimumInfillPercent() != null || original.infillPattern() != null
                ? "Учтён ваш минимум заполнения и выбранный рисунок." : "Плотность и рисунок выбраны по назначению; время и расход проверены слайсером.")));
        items.add(new Item("Стенки", "Стенок: " + selected.perimeters() + ". "
                + (original.minimumPerimeters() != null ? "Сохранён заданный вами минимум стенок." : "Оболочка подобрана под выбранный сценарий использования.")));
        items.add(new Item("Высота слоя", number.format(selected.layerHeightMm()) + " мм. "
                + (original.layerHeightMm() != null ? "Слой не превышает выбранный вами предел качества." : "Выбран компромисс детализации поверхности и времени печати.")));
        items.add(new Item("Верх и низ", "Верхних слоёв: " + selected.topLayers() + ", нижних: " + selected.bottomLayers()
                + ". " + (original.topLayers() != null || original.bottomLayers() != null
                ? "Учтены заданные значения." : "Сплошные слои закрывают оболочку детали.")));
        items.add(new Item("Материал", material.name() + ". " + (original.materialProfileId() != null
                ? "Оставлен выбранный вами материал." : "Выбран базовый совместимый материал. Для нагрева, улицы или химической среды уточните материал вручную.")));
        items.add(new Item("Поддержки", selected.supportMode() == SupportMode.NONE
                ? "Отключены по вашему выбору; проверьте нависающие участки перед печатью."
                : "Слайсер определяет необходимость поддержек по геометрии модели."));
        if (purpose.category() == PartPurpose.Category.REINFORCED) items.add(new Item("Условия использования",
                "Для постоянной нагрузки или ударов проверьте ориентацию слоёв и испытайте напечатанный образец в предполагаемых условиях."));
        if (purpose.category() == PartPurpose.Category.FLEXIBLE && !"TPU_95A".equals(material.family()))
            items.add(new Item("Гибкость", "Выбранный вами материал не TPU. Проверьте, обеспечит ли он нужную гибкость; при необходимости смените материал."));
        return new PrintPlanResponse.Recommendation(purpose.source(), title, summary, items);
    }

    private static void validate(PrintPlanRequest r) {
        if (r.description().length() > 2000 || r.description().codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                || r.layerHeightMm() != null && !List.of(.12, .20, .28).contains(r.layerHeightMm())
                || r.minimumInfillPercent() != null && (r.minimumInfillPercent() < 5 || r.minimumInfillPercent() > 80)
                || r.minimumPerimeters() != null && (r.minimumPerimeters() < 2 || r.minimumPerimeters() > 6)
                || r.topLayers() != null && (r.topLayers() < 3 || r.topLayers() > 8)
                || r.bottomLayers() != null && (r.bottomLayers() < 3 || r.bottomLayers() > 8)
                || invalidLimit(r.maxPrintTimeMin()) || invalidLimit(r.maxMaterialConsumptionG())) throw new PrintPlanException(INVALID_PRINT_INPUT);
    }
    private static boolean invalidLimit(Double value) { return value != null && (!Double.isFinite(value) || value < 0 || value > 1000000); }
}
