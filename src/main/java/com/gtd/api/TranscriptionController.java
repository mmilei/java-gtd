package com.gtd.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TranscriptionController {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionController.class);

    private final OpenAiAudioTranscriptionModel transcriptionModel;

    public TranscriptionController(OpenAiAudioTranscriptionModel transcriptionModel) {
        this.transcriptionModel = transcriptionModel;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, String>> transcribe(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "language", required = false) String language) {
        if (audio.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "audio file is required"));
        }

        try {
            ByteArrayResource resource = new ByteArrayResource(audio.getBytes()) {
                @Override
                public String getFilename() {
                    return "recording.webm";
                }
            };

            // A language hint steers Whisper instead of letting it auto-detect — helps short/noisy
            // clips. The frontend sends a full BCP-47 tag (e.g. "es-AR") because the same value also
            // drives the Web Speech API preview, but Whisper's API only accepts the bare ISO-639-1
            // subtag (e.g. "es") and 400s on anything with a region — so strip it here. Omitted →
            // keep auto-detect (the model's configured default), so an absent param behaves exactly
            // as before.
            AudioTranscriptionPrompt prompt = (language != null && !language.isBlank())
                ? new AudioTranscriptionPrompt(resource,
                    OpenAiAudioTranscriptionOptions.builder().language(iso639Subtag(language)).build())
                : new AudioTranscriptionPrompt(resource);

            AudioTranscriptionResponse response = transcriptionModel.call(prompt);
            String text = response.getResult().getOutput();
            return ResponseEntity.ok(Map.of("text", text));

        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "failed to read audio file"));
        } catch (Exception e) {
            log.error("transcription failed", e);
            return ResponseEntity.status(502).body(Map.of("error", "transcription failed"));
        }
    }

    /** "es-AR" -> "es"; "es" -> "es". Whisper rejects region subtags outright. */
    private static String iso639Subtag(String bcp47) {
        return bcp47.strip().split("-", 2)[0].toLowerCase(java.util.Locale.ROOT);
    }
}
