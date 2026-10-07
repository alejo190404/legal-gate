package com.legalgate.intake.api;

import com.legalgate.intake.clients.LawyerInChargeException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        return Map.of(
                "error", "validation_failed",
                "message", "Request payload failed validation.",
                "fields", fields
        );
    }

    /** The settings screen lists exactly what must be reassigned before the lawyer can go. */
    @ExceptionHandler(LawyerInChargeException.class)
    public ResponseEntity<Map<String, Object>> handleLawyerInCharge(LawyerInChargeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "lawyer_still_in_charge",
                "message", exception.getMessage(),
                "blockers", exception.blockers()
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
                "error", exception.getReason() == null ? "request_failed" : exception.getReason(),
                "message", exception.getReason() == null ? "Request failed." : exception.getReason()
        ));
    }
}
