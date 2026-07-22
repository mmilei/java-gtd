package com.gtd.api;

import com.gtd.service.LlmProviderService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
    void languageParamIsForwardedAsTranscriptionOption() throws Exception {
        when(transcriptionModel.call(any(AudioTranscriptionPrompt.class)))
                .thenReturn(new AudioTranscriptionResponse(new AudioTranscription("hola")));

        mvc.perform(multipart("/api/transcribe").file(audio(new byte[]{1, 2, 3})).param("language", "es"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("hola"));

        ArgumentCaptor<AudioTranscriptionPrompt> captor = ArgumentCaptor.forClass(AudioTranscriptionPrompt.class);
        verify(transcriptionModel).call(captor.capture());
        assertThat(captor.getValue().getOptions()).isInstanceOf(OpenAiAudioTranscriptionOptions.class);
        assertThat(((OpenAiAudioTranscriptionOptions) captor.getValue().getOptions()).getLanguage()).isEqualTo("es");
    }

    @Test
    void regionTaggedLanguageIsNormalizedToPrimarySubtag() throws Exception {
        when(transcriptionModel.call(any(AudioTranscriptionPrompt.class)))
                .thenReturn(new AudioTranscriptionResponse(new AudioTranscription("hola")));

        mvc.perform(multipart("/api/transcribe").file(audio(new byte[]{1, 2, 3})).param("language", "es-AR"))
                .andExpect(status().isOk());

        ArgumentCaptor<AudioTranscriptionPrompt> captor = ArgumentCaptor.forClass(AudioTranscriptionPrompt.class);
        verify(transcriptionModel).call(captor.capture());
        assertThat(((OpenAiAudioTranscriptionOptions) captor.getValue().getOptions()).getLanguage()).isEqualTo("es");
    }

    @Test
    void withoutLanguageParamNoOptionsAreAttached() throws Exception {
        when(transcriptionModel.call(any(AudioTranscriptionPrompt.class)))
                .thenReturn(new AudioTranscriptionResponse(new AudioTranscription("hello")));

        mvc.perform(multipart("/api/transcribe").file(audio(new byte[]{1, 2, 3})))
                .andExpect(status().isOk());

        ArgumentCaptor<AudioTranscriptionPrompt> captor = ArgumentCaptor.forClass(AudioTranscriptionPrompt.class);
        verify(transcriptionModel).call(captor.capture());
        assertThat(captor.getValue().getOptions()).isNull();
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
