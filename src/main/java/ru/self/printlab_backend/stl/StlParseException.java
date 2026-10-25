package ru.self.printlab_backend.stl;

public class StlParseException extends RuntimeException {
    private final StlUploadError error;

    public StlParseException(StlUploadError error) {
        super(error.message());
        this.error = error;
    }

    public StlUploadError error() { return error; }

    public StlParseException(String message) {
        super(message);
        this.error = StlUploadError.INVALID_STL;
    }

    public StlParseException(String message, Throwable cause) {
        super(message, cause);
        this.error = StlUploadError.INVALID_STL;
    }
}
