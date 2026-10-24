package ru.self.printlab_backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.self.printlab_backend.algorithm.OptimizationNotApplicableException;
import ru.self.printlab_backend.stl.StlParseException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StlParseException.class)
    public ResponseEntity<Map<String, Object>> handleStl(StlParseException e) {
        log.warn("Ошибка разбора STL: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(body(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        log.warn("Некорректный запрос: {}", e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(body(HttpStatus.BAD_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Некорректный ввод");
        log.warn("Ошибка валидации: {}", message);
        return ResponseEntity
                .badRequest()
                .body(body(HttpStatus.BAD_REQUEST, message));
    }

    @ExceptionHandler(OptimizationNotApplicableException.class)
    public ResponseEntity<Map<String, Object>> handleNotApplicable(OptimizationNotApplicableException e) {
        log.warn("Расчёт невозможен: {}", e.getMessage());
        return ResponseEntity
                .unprocessableEntity()
                .body(body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("Необработанная ошибка", e);
        return ResponseEntity
                .internalServerError()
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка сервера"));
    }

    private Map<String, Object> body(HttpStatus status, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", Instant.now().toString());
        map.put("status", status.value());
        map.put("error", status.getReasonPhrase());
        map.put("message", message);
        return map;
    }
}