package com.gtd.api;

import com.gtd.service.LlmProviderService;
import com.gtd.util.LlmErrorClassifier;
import com.gtd.util.LlmErrorClassifier.LlmError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final LlmProviderService llmProviders;

    public GlobalExceptionHandler(LlmProviderService llmProviders) {
        this.llmProviders = llmProviders;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadArgument(IllegalArgumentException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "Invalid argument";
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /**
     * LLM provider errors (rate limit, context length, provider outage) surface as
     * TransientAiException (5xx) / NonTransientAiException (4xx, including 429) from
     * Spring AI's retry layer. Classify them into an actionable message and include
     * the provider list so the client can offer switching providers.
     */
    @ExceptionHandler({NonTransientAiException.class, TransientAiException.class})
    public ResponseEntity<Map<String, Object>> handleLlmProviderError(RuntimeException e) {
        log.error("LLM provider error", e);
        LlmError classified = LlmErrorClassifier.classify(e.getMessage());
        return ResponseEntity.status(classified.httpStatus()).body(Map.of(
            "error", classified.kind().name(),
            "message", classified.message(),
            "providers", llmProviders.describeAll()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
    }
}
