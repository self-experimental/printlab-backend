package ru.self.printlab_backend.algorithm;

public enum OptimizationError {
    INVALID_MATERIAL_PROFILE("Список материалов обновился. Обновите страницу и выберите материал ещё раз."),
    MODEL_NOT_SUPPORTED("Для этой модели пока нет подходящих испытаний. Сейчас подбор доступен для исследованных образцов; другую модель можно открыть и посмотреть в 3D."),
    NO_EXPERIMENTS("Для выбранного материала и типа испытания пока нет данных. Выберите другие условия или добавьте результаты испытаний."),
    NO_SUITABLE_CONFIGURATION("Ни один испытанный вариант не подходит под заданные условия. Проверьте нагрузку, запас прочности и ограничения печати.");

    private final String message;
    OptimizationError(String message) { this.message = message; }
    public String message() { return message; }
}
