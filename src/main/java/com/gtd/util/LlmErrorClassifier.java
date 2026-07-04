package com.gtd.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies raw LLM provider errors into actionable categories. Spring AI's default
 * ResponseErrorHandler wraps any HTTP error from the provider as
 * "&lt;status&gt; - &lt;raw JSON body&gt;" inside a {@code TransientAiException} /
 * {@code NonTransientAiException}; provider error codes such as
 * {@code rate_limit_exceeded} appear literally in that body, so substring matching
 * is enough — no JSON parsing needed.
 */
public final class LlmErrorClassifier {

    public enum Kind { RATE_LIMIT, CONTEXT_LENGTH_EXCEEDED, PROVIDER_ERROR }

    public record LlmError(Kind kind, int httpStatus, String message) {}

    private static final Pattern STATUS_PREFIX = Pattern.compile("^\\s*\\d{3}\\s*-\\s*");

    private LlmErrorClassifier() {}

    public static LlmError classify(String rawMessage) {
        String body = stripStatusPrefix(rawMessage);
        if (body.contains("rate_limit_exceeded") || body.contains("rate limit")) {
            return new LlmError(Kind.RATE_LIMIT, 429,
                "The LLM provider's usage limit was reached. Wait a moment and retry, or switch to another provider from the provider menu.");
        }
        if (body.contains("context_length_exceeded") || body.contains("context length")) {
            return new LlmError(Kind.CONTEXT_LENGTH_EXCEEDED, 413,
                "The message is too long for the current model. Shorten it, or switch to a provider with a larger context window.");
        }
        return new LlmError(Kind.PROVIDER_ERROR, 502,
            "The LLM provider returned an error. Retry, or switch to another provider from the provider menu.");
    }

    private static String stripStatusPrefix(String rawMessage) {
        if (rawMessage == null) return "";
        Matcher m = STATUS_PREFIX.matcher(rawMessage);
        return m.find() ? rawMessage.substring(m.end()) : rawMessage;
    }
}
