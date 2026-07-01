package ar.maxi.gtd.api;

import ar.maxi.gtd.service.LlmProviderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProviderController.class)
class ProviderControllerTest {

    @Autowired MockMvc mvc;
    @MockBean LlmProviderService providers;

    @Test
    void listReturnsActiveAndProviders() throws Exception {
        when(providers.describeAll()).thenReturn(Map.of(
                "active", "GROQ",
                "providers", List.of(
                        Map.of("id", "GROQ", "label", "Groq", "status", "UP"),
                        Map.of("id", "OLLAMA", "label", "Ollama", "status", "DOWN")
                )
        ));

        mvc.perform(get("/api/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value("GROQ"))
                .andExpect(jsonPath("$.providers.length()").value(2));
    }

    @Test
    void selectKnownProviderReturns200() throws Exception {
        when(providers.select("OLLAMA")).thenReturn(true);
        when(providers.describeAll()).thenReturn(Map.of("active", "OLLAMA", "providers", List.of()));

        mvc.perform(post("/api/providers/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OLLAMA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value("OLLAMA"));
    }

    @Test
    void selectUnavailableProviderReturns400() throws Exception {
        when(providers.select("OLLAMA")).thenReturn(false);

        mvc.perform(post("/api/providers/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"OLLAMA\"}"))
                .andExpect(status().isBadRequest());
    }
}
