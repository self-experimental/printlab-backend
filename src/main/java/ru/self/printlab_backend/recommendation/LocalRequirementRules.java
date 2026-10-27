package ru.self.printlab_backend.recommendation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import static ru.self.printlab_backend.recommendation.PartRequirements.*;

/** Versioned, auditable interpretation of explicit RU/EN requirements; no strength prediction. */
@Component
public final class LocalRequirementRules {
    private record Rule(String field, String value, Pattern pattern, boolean literal) {}
    private final List<Rule> rules;
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern NEGATIVE_PREFIX = Pattern.compile(
            "\\b(?:не|без|нет|исключены|отсутствуют|not|no|never|without)\\s+(?:(?:очень|слишком|совсем|особо|долж\\p{L}*|будет|будут|нуж\\p{L}*|предназначен\\p{L}*|треб\\p{L}*|планиру\\p{L}*|быть|very|really|be)\\s+){0,3}$", FLAGS);
    private static final Pattern NEGATIVE_COORDINATION = Pattern.compile(
            "\\b(?:без|не\\s+будет|no|without)\\s+(?:удар|вибрац|impact|shock|vibrat)\\p{L}*\\s+(?:и|или|and|or)\\s+$", FLAGS);
    private static final Pattern NEGATIVE_SUFFIX = Pattern.compile(
            "^(?:(?:вынос\\p{L}*|использ\\p{L}*|эксплуат\\p{L}*|став\\p{L}*)\\s+)?(?:(?:точно|вообще|совсем)\\s+){0,2}(?:не\\s+(?:будет|будут|нуж\\p{L}*|предусмотрен\\p{L}*|планиру\\p{L}*)|нет|исключен\\p{L}*|отсутству\\p{L}*|is\\s+not|will\\s+not|not\\s+needed)\\b", FLAGS);
    private static final Pattern TEMPERATURE = Pattern.compile("(?<![\\p{L}\\d.,-])(\\d{1,3}(?:[,.]\\d{1,2})?)\\s*°?\\s*[CС](?!\\p{L})", FLAGS);
    private static final Pattern UNCERTAIN = Pattern.compile("\\b(?:возможно|не\\s+уверен\\p{L}*|может\\s+быть|не\\s+обязательно|maybe|perhaps|not\\s+sure)\\b", FLAGS);

    public LocalRequirementRules() {
        try (var in = new ClassPathResource("recommendation/local-rules.json").getInputStream()) {
            var json = JsonMapper.builder().build().readTree(in);
            if (json.path("version").asInt() != 1) throw new IllegalArgumentException("Unsupported rule version");
            var loaded = new ArrayList<Rule>();
            for (var row : json.path("rules")) loaded.add(new Rule(row.path("field").asText(), row.path("value").asText(),
                    Pattern.compile(row.path("pattern").asText(), FLAGS), row.path("literal").asBoolean()));
            rules = List.copyOf(loaded);
        } catch (IOException e) { throw new IllegalStateException("Cannot load local requirement rules", e); }
    }

    public PartPurpose parse(String description) {
        if (description == null || description.isBlank()) return PartPurpose.general();
        if (description.length() > 2000) throw new IllegalArgumentException("Описание детали должно быть не длиннее 2000 символов.");
        Map<String, Map<String, String>> found = new HashMap<>();
        List<String> questions = new ArrayList<>();
        // Keep adversative clauses separate so negation of one property does not negate the next one.
        for (String raw : description.split("(?iu)(?<!\\d)[.,]|[.,](?!\\d)|[!?;\\n\\r]+|\\s+(?:но|but)\\s+")) {
            String clause = raw.strip();
            if (clause.isEmpty()) continue;
            if (UNCERTAIN.matcher(clause).find()) {
                questions.add("Какие из предполагаемых условий обязательны? Уточните формулировки «возможно» или «не уверен».");
                continue;
            }
            for (Rule rule : rules) {
                var matcher = rule.pattern.matcher(clause);
                while (matcher.find()) {
                    if (!rule.literal && (negated(clause, matcher.start(), matcher.end())
                            || rule.field.equals("loadKind") && NEGATIVE_COORDINATION.matcher(clause.substring(0, matcher.start())).find())) continue;
                    remember(found, rule.field, rule.value, quote(clause, matcher.start(), matcher.end()));
                }
            }
            var temp = TEMPERATURE.matcher(clause);
            while (temp.find()) {
                // A review trigger, not a safe operating-temperature limit of any material.
                if (Double.parseDouble(temp.group(1).replace(',', '.')) >= 40 && !negated(clause, temp.start(), temp.end()))
                    remember(found, "environment", "HEAT", quote(clause, temp.start(), temp.end()));
            }
        }
        var use = choose(found, "use", Use.class, Use.PROTOTYPE, Use.FUNCTIONAL, Use.DECORATIVE);
        var load = choose(found, "load", Load.class, Load.HIGH, Load.LIGHT, Load.NORMAL, Load.UNSPECIFIED);
        var kind = choose(found, "loadKind", LoadKind.class, LoadKind.IMPACT, LoadKind.CYCLIC, LoadKind.STATIC);
        var environment = choose(found, "environment", Environment.class, Environment.HEAT, Environment.OUTDOOR, Environment.INDOOR);
        var flex = choose(found, "flexibility", Flexibility.class, Flexibility.RIGID, Flexibility.FLEXIBLE);
        var goals = found.getOrDefault("goal", Map.of());
        var goal = choose(found, "goal", Goal.class, Goal.FIT, Goal.DETAIL, Goal.SPEED, Goal.ECONOMY);
        var loads = found.getOrDefault("load", Map.of());
        if (loads.containsKey("LIGHT") && loads.containsKey("HIGH"))
            questions.add("Одновременно указаны небольшая и высокая нагрузка. Какова максимальная нагрузка? Пока оставлен более требовательный стартовый вариант.");
        if (found.getOrDefault("flexibility", Map.of()).size() > 1) {
            flex = unknown(Flexibility.class);
            questions.add("Деталь должна гнуться или оставаться жёсткой? В описании указаны оба требования; уточните, к каким участкам они относятся.");
        }
        if ((goals.containsKey("SPEED") || goals.containsKey("ECONOMY")) && goals.size() > 1) {
            goal = unknown(Goal.class);
            questions.add("Что важнее: скорость, расход материала или качество поверхности и посадок? Пока выбран сбалансированный вариант.");
        }
        var requirements = new PartRequirements(use, load, kind, environment, flex, goal);
        return new PartPurpose(requirements.category(), "RULES", requirements, questions.stream().distinct().toList());
    }

    private static boolean negated(String text, int start, int end) {
        int wordEnd = end;
        while (wordEnd < text.length() && Character.isLetter(text.charAt(wordEnd))) wordEnd++;
        return NEGATIVE_PREFIX.matcher(text.substring(Math.max(0, start - 80), start)).find()
                || NEGATIVE_SUFFIX.matcher(text.substring(wordEnd).stripLeading()).find();
    }
    private static String quote(String text, int start, int end) {
        int from = Math.max(0, start - 30), to = Math.min(text.length(), Math.max(end, from + 180));
        if (to - from > 180) from = Math.max(0, to - 180);
        if (from > 0 && Character.isLowSurrogate(text.charAt(from))) from++;
        if (to < text.length() && Character.isHighSurrogate(text.charAt(to - 1))) to--;
        return text.substring(from, to).strip();
    }
    private static void remember(Map<String, Map<String, String>> found, String field, String value, String evidence) {
        found.computeIfAbsent(field, key -> new LinkedHashMap<>()).putIfAbsent(value, evidence);
    }
    @SafeVarargs private static <T extends Enum<T>> Fact<T> choose(Map<String, Map<String, String>> found,
            String field, Class<T> type, T... preference) {
        var values = found.getOrDefault(field, Map.of());
        for (T value : preference) if (values.containsKey(value.name())) return new Fact<>(value, values.get(value.name()));
        return unknown(type);
    }
    private static <T extends Enum<T>> Fact<T> unknown(Class<T> type) { return new Fact<>(Enum.valueOf(type, "UNKNOWN"), ""); }
}
