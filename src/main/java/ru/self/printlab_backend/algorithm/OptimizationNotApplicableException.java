package ru.self.printlab_backend.algorithm;

/**
 * Расчёт невозможен: нет подходящих экспериментов, не выполнены ограничения
 * или не подтверждена требуемая нагрузка.
 */
public class OptimizationNotApplicableException extends RuntimeException {
    private final OptimizationError error;

    public OptimizationNotApplicableException(OptimizationError error) {
        super(error.message());
        this.error = error;
    }

    public OptimizationError error() { return error; }

    public OptimizationNotApplicableException(String message) {
        super(message);
        this.error = OptimizationError.NO_SUITABLE_CONFIGURATION;
    }
}
