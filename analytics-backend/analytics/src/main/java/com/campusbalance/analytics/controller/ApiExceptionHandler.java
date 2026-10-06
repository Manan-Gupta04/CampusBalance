package com.campusbalance.analytics.controller;

import com.campusbalance.analytics.service.ApiException;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns bad input and service errors into a short plain-text message with the right status,
 * so the frontend can show it to the user as-is.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleUnreadable(HttpMessageNotReadableException ex) {
        // Name the offending field when Jackson can tell us (e.g. a blank number input sent as null)
        if (ex.getCause() instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            String field = jme.getPath().get(jme.getPath().size() - 1).getFieldName();
            if (field != null) {
                return ResponseEntity.badRequest().body("Missing or invalid value for " + field);
            }
        }
        return ResponseEntity.badRequest().body("Request body is missing or malformed");
    }
}
