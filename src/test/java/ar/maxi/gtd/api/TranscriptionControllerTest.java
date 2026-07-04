package ar.maxi.gtd.api;

import ar.maxi.gtd.service.LlmProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TranscriptionController.class)
class TranscriptionControllerTest {

    @Autowired MockMvc mvc;
    @MockBean OpenAiAudioTranscriptionModel transcriptionModel;
    // required by GlobalExceptionHandler, which the @WebMvcTest slice also instantiates
    @MockBean LlmProviderService llmProviders;

    private static MockMultipartFile audio(byte[] content) {
        return new MockMultipartFile("audio", "recording.webm", "audio/webm", content);
    }

    @Test
    void emptyAudioIsRejected() throws Exception {
        mvc.perform(multipart("/api/transcribe").file(audio(new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("audio file is required"));
    }

    @Test
    void providerFailureReturnsMaskedError() throws Exception {
        when(transcriptionModel.call(any(AudioTranscriptionPrompt.class)))
                .thenThrow(new RuntimeException("api key leaked-secret-detail rejected"));

        mvc.perform(multipart("/api/transcribe").file(audio(new byte[]{1, 2, 3})))
                .andExpect(status().is(502))
                .andExpect(jsonPath("$.error").value("transcription failed"));
    }
}
