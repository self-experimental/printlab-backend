package ru.self.printlab_backend.slicing;

public class PrintPlanException extends RuntimeException {
    public enum Error {
        INVALID_PRINT_INPUT(400, "Проверьте описание детали (до 2000 символов) и указанные вручную параметры печати."),
        SLICER_UNAVAILABLE(503, "Расчёт печати временно недоступен. Слайсер на сервере ещё не настроен."),
        SLICER_BUSY(429, "Сейчас выполняется другой расчёт. Дождитесь его завершения и попробуйте ещё раз."),
        SLICE_TIMEOUT(504, "Не удалось завершить расчёт за отведённое время. Измените параметры печати или повторите позже."),
        SLICE_FAILED(422, "Не удалось подготовить модель к печати. Проверьте её в Bambu Studio и экспортируйте STL заново."),
        MATERIAL_UNSUPPORTED(422, "Для этого материала пока нет подходящего профиля A1 mini. Выберите PLA, PETG или TPU 95A."),
        MODEL_DOES_NOT_FIT(422, "В этой ориентации модель не помещается в область печати A1 mini — 180 × 180 × 180 мм. Уменьшите или поверните её в редакторе и экспортируйте STL заново."),
        NO_PRINTABLE_VOLUME(422, "У модели нет замкнутого объёма для печати. Проверьте оболочку в редакторе и экспортируйте STL заново."),
        NO_PRINT_PLAN(422, "Рассчитанные варианты не вписываются в ограничения времени или расхода. Увеличьте ограничения или измените параметры печати.");
        public final int status;
        public final String message;
        Error(int status, String message) { this.status = status; this.message = message; }
    }
    private final Error error;
    public PrintPlanException(Error error) { super(error.message); this.error = error; }
    public PrintPlanException(Error error, Throwable cause) { super(error.message, cause); this.error = error; }
    public Error error() { return error; }
}
