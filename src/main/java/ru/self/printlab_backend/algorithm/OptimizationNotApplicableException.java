package ru.self.printlab_backend.algorithm;

/**
 * Расчёт невозможен: нет подходящих экспериментов, не выполнены ограничения
 * или не подтверждена требуемая нагрузка.
 */
public class OptimizationNotApplicableException extends RuntimeException {

    public OptimizationNotApplicableException(String message) {
        super(message);
    }
}