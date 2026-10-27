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

import static ru.self.printlab_backend.recommendation.PartRequirements.*;
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
        Profile profile = profile(purpose, input.description());
        var requirements = purpose.requirements();
        String family = requirements.flexibility().value() == Flexibility.FLEXIBLE || purpose.category() == PartPurpose.Category.FLEXIBLE
                ? "TPU_95A" : requirements.environment().value() == Environment.OUTDOOR || requirements.environment().value() == Environment.HEAT
                ? "PETG" : "PLA";
        String material = input.materialProfileId() != null ? input.materialProfileId()
                : materials.catalog().materials().stream().filter(m -> family.equals(m.family())).findFirst()
                .orElseThrow(() -> new PrintPlanException(PrintPlanException.Error.MATERIAL_UNSUPPORTED)).id();
        var settings = new PrintPlanRequest(material,
                input.priority() != null ? input.priority() : priority(purpose),
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

    /** Curated starting ranges, not an N-to-infill formula or a prediction of strength. */
    private static Profile profile(PartPurpose purpose, String description) {
        var r = purpose.requirements();
        Profile p = switch (purpose.category()) {
            case DECORATIVE -> new Profile(10, 2, .12, InfillPattern.GYROID, 4, 3);
            case PROTOTYPE -> new Profile(10, 2, .28, InfillPattern.RECTILINEAR, 3, 3);
            case FUNCTIONAL -> new Profile(20, 3, .20, InfillPattern.GYROID, 5, 4);
            case REINFORCED -> new Profile(35, 4, .20, InfillPattern.GYROID, 6, 5);
            case FLEXIBLE -> new Profile(15, 3, .20, InfillPattern.GYROID, 4, 4);
            case GENERAL -> new Profile(15, 2, .20, InfillPattern.GYROID, 4, 4);
        };
        // Combine requirements instead of letting a decorative/prototype label erase loads.
        if (r.load().value() == Load.LIGHT && !r.mechanical() && r.flexibility().value() != Flexibility.FLEXIBLE)
            p = new Profile(10, 2, .20, InfillPattern.GYROID, 4, 3);
        if (r.mechanical() || r.flexibility().value() == Flexibility.RIGID || !quantities(description).isEmpty())
            p = new Profile(Math.max(20, p.fill), Math.max(3, p.walls), .20, InfillPattern.GYROID,
                    Math.max(5, p.top), Math.max(4, p.bottom));
        if (r.loadKind().value() == LoadKind.IMPACT || r.loadKind().value() == LoadKind.CYCLIC)
            p = new Profile(Math.max(30, p.fill), Math.max(5, p.walls), .20, InfillPattern.GYROID, 6, 5);
        if (r.goal().value() == Goal.DETAIL || r.goal().value() == Goal.FIT)
            p = new Profile(p.fill, p.walls, .12, p.pattern, p.top, p.bottom);
        else if (r.goal().value() == Goal.SPEED && !r.mechanical() && quantities(description).isEmpty())
            p = new Profile(p.fill, p.walls, .28, p.pattern, p.top, p.bottom);
        return p;
    }

    private static Priority priority(PartPurpose p) {
        return switch (p.requirements().goal().value()) {
            case ECONOMY -> Priority.MIN_MATERIAL;
            case SPEED -> Priority.MIN_TIME;
            default -> p.category() == PartPurpose.Category.PROTOTYPE ? Priority.MIN_TIME : Priority.BALANCED;
        };
    }

    // Preserve the stated units. A mass in kg is not silently turned into a calculated allowable force.
    private static final java.util.regex.Pattern QUANTITY = java.util.regex.Pattern.compile(
            "(?iu)(?<![\\p{L}\\d.,-])(\\d{1,7}(?:[,.]\\d{1,3})?)\\s*(кН|kN|Н|N|кг|kg)(?![\\p{L}])");
    static List<String> quantities(String description) {
        return QUANTITY.matcher(description).results().limit(3).map(java.util.regex.MatchResult::group).toList();
    }
    private static final java.util.regex.Pattern TEMPERATURE = java.util.regex.Pattern.compile(
            "(?iu)(?<![\\p{L}\\d.,-])(\\d{1,3}(?:[,.]\\d{1,2})?)\\s*°?\\s*[CС](?![\\p{L}])");
    private static java.util.OptionalDouble temperature(String description) {
        return TEMPERATURE.matcher(description).results().mapToDouble(m -> Double.parseDouble(m.group(1).replace(',', '.'))).max();
    }

    public PrintPlanResponse.Recommendation explain(Resolved resolved, PrintPlanRequest original,
                                                   PrintPlanResponse.Candidate selected, MaterialProfile material) {
        PartPurpose purpose = resolved.purpose();
        var r = purpose.requirements();
        Profile suggested = profile(purpose, original.description());
        boolean hasQuantity = !quantities(original.description()).isEmpty();
        boolean mechanical = r.mechanical() || hasQuantity || purpose.category() == PartPurpose.Category.REINFORCED;
        var statedTemperature = temperature(original.description());
        Double hdt = material.hdtAt045MpaC();
        boolean thermalReview = r.environment().value() == Environment.HEAT && statedTemperature.isPresent()
                && hdt != null && statedTemperature.getAsDouble() >= hdt;
        String title = r.load().value() == Load.LIGHT && !mechanical ? "Деталь с небольшой нагрузкой" : switch (purpose.category()) {
            case DECORATIVE -> "Декоративная деталь";
            case PROTOTYPE -> "Прототип для примерки";
            case FUNCTIONAL -> "Деталь для повседневного использования";
            case REINFORCED -> "Деталь с повышенными требованиями к жёсткости";
            case FLEXIBLE -> "Гибкая деталь";
            case GENERAL -> mechanical ? "Рабочая деталь — условия требуют уточнения" : "Универсальная печать";
        };
        String summary = "Сравнены варианты печати с учётом описанных требований. Время и расход рассчитаны слайсером; параметры служат отправной точкой для пробной печати.";
        if (original.description().isBlank()) summary = "Подобран базовый вариант по вашим настройкам. Добавьте назначение детали, чтобы учесть условия использования.";
        else if ("FALLBACK".equals(purpose.source())) summary = "Не удалось надёжно разобрать описание. Ниже — базовый подбор без подтверждённого понимания назначения; уточните условия использования или задайте параметры вручную.";
        else if (r.quotes().isEmpty()) summary = "Не удалось уверенно определить требования. Уточните, что делает деталь, какие усилия и условия ей предстоят; пока показан базовый вариант.";
        if (mechanical) summary += " Допустимая нагрузка этим подбором не подтверждается.";
        if (thermalReview) summary = "Для заявленного нагрева материал требует отдельной проверки. " + summary;
        List<Item> items = new ArrayList<>();
        if (!r.quotes().isEmpty()) items.add(new Item("Как поняли задачу", interpretation(r)));
        String pattern = switch (InfillPattern.valueOf(selected.infillPattern())) {
            case GYROID -> "Гироид";
            case RECTILINEAR -> "Прямолинейное";
            case GRID -> "Сетка";
            case CUBIC -> "Кубическое";
        };
        items.add(new Item("Заполнение", selected.infillPercent() + "% · " + pattern + ". "
                + (original.minimumInfillPercent() != null ? "Учтён заданный вами минимум заполнения."
                : r.load().value() == Load.LIGHT && !mechanical ? "При небольшой нагрузке начинаем с умеренного расхода пластика."
                : mechanical ? "Заполнение поддерживает верхние слои и влияет на жёсткость; оно не заменяет расчёт конструкции."
                : "Умеренное заполнение поддерживает верхние слои без сплошной заливки детали.")
                + (original.infillPattern() != null ? " Рисунок задан вами вручную." : "")));
        items.add(new Item("Стенки", "Стенок: " + selected.perimeters() + ". "
                + (original.minimumPerimeters() != null ? "Учтён заданный вами минимум стенок."
                : mechanical ? "Для рабочей детали усиливаем оболочку: увеличение числа стенок часто полезнее простого повышения заполнения."
                : "Оболочка закрывает деталь; при лёгком применении достаточно начать с небольшого числа стенок.")));
        items.add(new Item("Высота слоя", number(selected.layerHeightMm()) + " мм. "
                + (original.layerHeightMm() != null ? "Учтён заданный вами предел высоты слоя. Сравнивались варианты с этим слоем и более тонким."
                : r.goal().value() == Goal.FIT ? "Тонкий слой улучшает детализацию по высоте. Точность посадок зависит также от геометрии, усадки и калибровки — проверьте пробной печатью."
                : r.goal().value() == Goal.DETAIL ? "Тонкий слой выбран ради мелких деталей и поверхности."
                : r.goal().value() == Goal.SPEED || purpose.category() == PartPurpose.Category.PROTOTYPE ? "Приоритетом служит скорость; фактический слой выбран по сравнению вариантов слайсером."
                : "Баланс качества поверхности и времени печати.")));
        items.add(new Item("Верх и низ", "Верхних слоёв: " + selected.topLayers() + ", нижних: " + selected.bottomLayers()
                + ". Сплошные слои закрывают оболочку. При другой высоте слоя проверьте их суммарную толщину."));
        String materialReason = original.materialProfileId() != null ? "Сохранён ваш выбор материала."
                : "FALLBACK".equals(purpose.source()) ? "Использован базовый материал; описание не удалось разобрать. Проверьте материал вручную."
                : r.flexibility().value() == Flexibility.FLEXIBLE || purpose.category() == PartPurpose.Category.FLEXIBLE ? "TPU выбран из-за требуемой гибкости."
                : r.environment().value() == Environment.OUTDOOR ? "PETG выбран как отправная точка для уличного применения; стойкость зависит от конкретного филамента и условий."
                : r.environment().value() == Environment.HEAT ? "PETG — только стартовый вариант: пригодность при заявленной температуре требует отдельной проверки."
                : "PLA выбран для обычной печати без заявленных специальных условий.";
        items.add(new Item("Материал", material.name() + ". " + materialReason));
        items.add(new Item("Поддержки", selected.supportMode() == SupportMode.NONE
                ? "Отключены по вашему выбору; проверьте нависающие участки перед печатью."
                : "Слайсер определяет необходимость поддержек по геометрии модели. Описание детали не меняет её ориентацию печати."));
        List<String> checks = new ArrayList<>();
        if (hasQuantity) checks.add("В описании указано: " + String.join(", ", quantities(original.description()))
                + ". Это требование пользователя, а не вычисленная несущая способность. Число не преобразуется в процент заполнения.");
        if (mechanical) checks.add("Направление усилия относительно слоёв, места крепления и плечо нагрузки могут изменить результат сильнее настроек заполнения. Проверьте ориентацию и испытайте образец.");
        if (r.environment().value() == Environment.HEAT) checks.add("Проверьте рабочую температуру по паспорту филамента. HDT — характеристика испытаний материала, а не гарантированная температура эксплуатации детали.");
        if (thermalReview) checks.add("Указано " + number(statedTemperature.getAsDouble()) + " °C; это достигает или превышает HDT "
                + number(hdt) + " °C из паспорта выбранного материала. Пригодность этого материала для заданного нагрева не подтверждена.");
        if (r.environment().value() == Environment.OUTDOOR && "PLA".equals(material.family())) checks.add("Выбранный PLA может не подойти для солнца и улицы. Рассмотрите подходящий PETG и проверьте его паспорт.");
        if (r.flexibility().value() == Flexibility.FLEXIBLE && !"TPU_95A".equals(material.family())) checks.add("Требуется гибкость, но выбран материал без профиля TPU. Уточните материал.");
        if (r.flexibility().value() == Flexibility.RIGID && "TPU_95A".equals(material.family())) checks.add("Требуется жёсткость, но выбран гибкий TPU. Уточните материал.");
        if (selected.infillPercent() < suggested.fill || selected.perimeters() < suggested.walls)
            checks.add("Ваши ручные ограничения допускают более лёгкий вариант, чем ориентир по описанию (" + suggested.fill + "% и " + suggested.walls + " стенки). Ручной выбор сохранён; проверьте это расхождение.");
        if (!checks.isEmpty()) items.add(new Item("Проверьте перед печатью", String.join(" ", checks)));
        List<String> questions = new ArrayList<>();
        if (mechanical) questions.add("Где деталь закреплена и куда приложена нагрузка?");
        if (mechanical && r.loadKind().value() == LoadKind.UNKNOWN) questions.add("Нагрузка постоянная, повторяющаяся или ударная?");
        if (r.environment().value() == Environment.HEAT) questions.add(statedTemperature.isPresent()
                ? "Как долго деталь будет находиться под нагревом и нагрузкой?" : "Каковы максимальная температура и длительность нагрева?");
        if (purpose.category() == PartPurpose.Category.GENERAL && !original.description().isBlank()) questions.add("Для чего нужна деталь и что важнее: жёсткость, внешний вид, точность или скорость?");
        if (!questions.isEmpty()) items.add(new Item("Условия, которые нужно уточнить", String.join(" ", questions)));
        return new PrintPlanResponse.Recommendation(purpose.source(), title, summary, items);
    }

    private static String number(double value) {
        NumberFormat number = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
        number.setMaximumFractionDigits(2);
        return number.format(value);
    }

    private static String interpretation(PartRequirements r) {
        List<String> parts = new ArrayList<>();
        describe(parts, r.use(), switch (r.use().value()) { case FUNCTIONAL -> "Рабочая деталь"; case DECORATIVE -> "Декоративное назначение"; case PROTOTYPE -> "Проверка прототипа"; default -> ""; });
        describe(parts, r.load(), switch (r.load().value()) { case LIGHT -> "Небольшая нагрузка"; case NORMAL -> "Обычная рабочая нагрузка"; case HIGH -> "Повышенная нагрузка"; default -> ""; });
        describe(parts, r.loadKind(), switch (r.loadKind().value()) { case STATIC -> "Постоянное усилие"; case IMPACT -> "Удары"; case CYCLIC -> "Повторяющиеся усилия или вибрация"; default -> ""; });
        describe(parts, r.environment(), switch (r.environment().value()) { case INDOOR -> "В помещении"; case OUTDOOR -> "Уличное применение"; case HEAT -> "Нагрев"; default -> ""; });
        describe(parts, r.flexibility(), switch (r.flexibility().value()) { case FLEXIBLE -> "Нужна гибкость"; case RIGID -> "Нужна жёсткость"; default -> ""; });
        describe(parts, r.goal(), switch (r.goal().value()) { case DETAIL -> "Важны детали и поверхность"; case FIT -> "Важна точность посадки"; case SPEED -> "Важна скорость"; case ECONOMY -> "Важна экономия материала"; default -> ""; });
        return String.join("; ", parts) + ".";
    }
    private static void describe(List<String> parts, Fact<?> fact, String label) {
        if (!label.isEmpty() && !fact.quote().isBlank()) parts.add(label);
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
