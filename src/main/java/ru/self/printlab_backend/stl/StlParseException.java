package ru.self.printlab_backend.stl;

public class StlParseException extends RuntimeException {

    public StlParseException(String message) {
        super(message);
    }

    public StlParseException(String message, Throwable cause) {
        super(message, cause);
    }
}