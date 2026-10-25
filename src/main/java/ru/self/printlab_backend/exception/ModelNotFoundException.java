package ru.self.printlab_backend.exception;

public class ModelNotFoundException extends RuntimeException {
    public ModelNotFoundException() { super("Сохранённая модель не найдена"); }
}
