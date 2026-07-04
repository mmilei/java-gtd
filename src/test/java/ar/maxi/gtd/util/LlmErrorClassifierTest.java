package ar.maxi.gtd.util;

import ar.maxi.gtd.util.LlmErrorClassifier.LlmError;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmErrorClassifierTest {

    @Test
    void shouldClassifyRateLimitErrors() {
        String raw = "429 - {\"error\":{\"message\":\"Rate limit reached for model llama-3.3-70b-versatile\",\"type\":\"tokens\",\"code\":\"rate_limit_exceeded\"}}";

        LlmError result = LlmErrorClassifier.classify(raw);

        assertThat(result.kind()).isEqualTo(LlmErrorClassifier.Kind.RATE_LIMIT);
        assertThat(result.httpStatus()).isEqualTo(429);
        assertThat(result.message()).contains("provider menu");
    }

    @Test
    void shouldClassifyContextLengthErrors() {
        String raw = "400 - {\"error\":{\"message\":\"Please reduce the length of the messages or completion.\",\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}";

        LlmError result = LlmErrorClassifier.classify(raw);

        assertThat(result.kind()).isEqualTo(LlmErrorClassifier.Kind.CONTEXT_LENGTH_EXCEEDED);
        assertThat(result.httpStatus()).isEqualTo(413);
        assertThat(result.message()).contains("too long");
    }

    @Test
    void shouldFallBackToGenericProviderError() {
        String raw = "503 - {\"error\":{\"message\":\"Service unavailable\",\"type\":\"server_error\"}}";

        LlmError result = LlmErrorClassifier.classify(raw);

        assertThat(result.kind()).isEqualTo(LlmErrorClassifier.Kind.PROVIDER_ERROR);
        assertThat(result.httpStatus()).isEqualTo(502);
    }

    @Test
    void shouldHandleNullAndPrefixlessMessages() {
        assertThat(LlmErrorClassifier.classify(null).kind())
            .isEqualTo(LlmErrorClassifier.Kind.PROVIDER_ERROR);
        assertThat(LlmErrorClassifier.classify("connection reset").kind())
            .isEqualTo(LlmErrorClassifier.Kind.PROVIDER_ERROR);
        // no "NNN - " prefix, code still detected in the body
        assertThat(LlmErrorClassifier.classify("rate_limit_exceeded").kind())
            .isEqualTo(LlmErrorClassifier.Kind.RATE_LIMIT);
    }
}
