package ru.self.printlab_backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import ru.self.printlab_backend.algorithm.OptimizationNotApplicableException;
import ru.self.printlab_backend.stl.StlParseException;
import ru.self.printlab_backend.stl.StlUploadError;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ru.self.printlab_backend.slicing.PrintPlanException.class)
    public ResponseEntity<Map<String, Object>> handlePrintPlan(ru.self.printlab_backend.slicing.PrintPlanException e) {
        log.warn("Print plan failed [{}]", e.error(), e.getCause());
        return ResponseEntity.status(e.error().status).body(body(HttpStatus.valueOf(e.error().status), e.error().message, e.error().name()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(body(HttpStatus.PAYLOAD_TOO_LARGE,
                StlUploadError.FILE_TOO_LARGE.message(), StlUploadError.FILE_TOO_LARGE.name()));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleMalformedRequest(Exception e) {
        if (e instanceof MethodArgumentTypeMismatchException) {
            return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST,
                    "Не удалось открыть модель по этой ссылке. Выберите исходный STL-файл и загрузите его заново.", "INVALID_MODEL_ID"));
        }
        return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST,
                "Не удалось получить файл. Выберите STL-модель и попробуйте загрузить её ещё раз.", "INVALID_REQUEST"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(415).body(body(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Не удалось передать файл. Обновите страницу и выберите STL-модель заново.", "UNSUPPORTED_REQUEST"));
    }

    @ExceptionHandler(ModelNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ModelNotFoundException e) {
        return ResponseEntity.status(404).body(body(HttpStatus.NOT_FOUND,
                "Сохранённая модель не найдена. Выберите исходный STL-файл и загрузите его заново.", "MODEL_NOT_FOUND"));
    }

    @ExceptionHandler(StlParseException.class)
    public ResponseEntity<Map<String, Object>> handleStl(StlParseException e) {
        log.warn("Ошибка разбора STL [{}]: {}", e.error().name(), e.getMessage());
        return ResponseEntity
                .badRequest()
                .body(body(HttpStatus.BAD_REQUEST, e.error().message(), e.error().name()));
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
        if (e.getParameter().getParameterType() == ru.self.printlab_backend.dto.printplan.PrintPlanRequest.class) {
            var error = ru.self.printlab_backend.slicing.PrintPlanException.Error.INVALID_PRINT_INPUT;
            return ResponseEntity.badRequest().body(body(HttpStatus.BAD_REQUEST, error.message, error.name()));
        }
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Некорректный ввод");
        log.warn("Ошибка валидации: {}", message);
        return ResponseEntity
                .badRequest()
                .body(body(HttpStatus.BAD_REQUEST,
                        "Проверьте параметры расчёта: нагрузка должна быть положительной, а коэффициент запаса — от 1 до 10.", "INVALID_CALCULATION_INPUT"));
    }

    @ExceptionHandler(OptimizationNotApplicableException.class)
    public ResponseEntity<Map<String, Object>> handleNotApplicable(OptimizationNotApplicableException e) {
        log.warn("Расчёт невозможен: {}", e.getMessage());
        return ResponseEntity
                .unprocessableEntity()
                .body(body(HttpStatus.UNPROCESSABLE_ENTITY, e.error().message(), e.error().name()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("Необработанная ошибка", e);
        return ResponseEntity
                .internalServerError()
                .body(body(HttpStatus.INTERNAL_SERVER_ERROR,
                        "На сервере произошла ошибка. Повторите попытку немного позже.", "SERVER_ERROR"));
    }

    private Map<String, Object> body(HttpStatus status, String message, String code) {
        Map<String, Object> result = body(status, message);
        result.put("code", code);
        return result;
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
