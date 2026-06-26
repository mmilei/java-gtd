package ar.maxi.gtd.api;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TranscriptionController {

    private final OpenAiAudioTranscriptionModel transcriptionModel;

    public TranscriptionController(OpenAiAudioTranscriptionModel transcriptionModel) {
        this.transcriptionModel = transcriptionModel;
    }

    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, String>> transcribe(@RequestParam("audio") MultipartFile audio) {
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

            AudioTranscriptionResponse response = transcriptionModel.call(new AudioTranscriptionPrompt(resource));
            String text = response.getResult().getOutput();
            return ResponseEntity.ok(Map.of("text", text));

        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "failed to read audio file"));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "transcription failed: " + e.getMessage()));
        }
    }
}
