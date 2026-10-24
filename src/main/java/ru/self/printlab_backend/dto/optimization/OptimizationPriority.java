package ru.self.printlab_backend.dto.optimization;

/**
 * Приоритет оптимизации, который выбирает пользователь в форме.
 */
public enum OptimizationPriority {
    MAX_STRENGTH,
    MIN_WEIGHT,
    MIN_TIME,
    BALANCED
}