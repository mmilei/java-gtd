package com.gtd.api;

import com.gtd.service.LlmAction;
import com.gtd.service.LlmProviderService;
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
    void listReturnsPerActionShape() throws Exception {
        List<Map<String, Object>> providerList = List.of(
                Map.of("id", "GROQ", "label", "Groq", "status", "UP"),
                Map.of("id", "OLLAMA", "label", "Ollama", "status", "DOWN"));
        when(providers.describeAll()).thenReturn(Map.of(
                "actions", List.of(
                        Map.of("action", "TRIAGE", "active", "GROQ", "providers", providerList),
                        Map.of("action", "ENRICHMENT", "active", "OLLAMA", "providers", providerList),
                        Map.of("action", "RESOLVER", "active", "GROQ", "providers", providerList)
                )
        ));

        mvc.perform(get("/api/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions.length()").value(3))
                .andExpect(jsonPath("$.actions[0].action").value("TRIAGE"))
                .andExpect(jsonPath("$.actions[1].active").value("OLLAMA"))
                .andExpect(jsonPath("$.actions[0].providers.length()").value(2));
    }

    @Test
    void selectKnownProviderReturns200() throws Exception {
        when(providers.select(LlmAction.TRIAGE, "OLLAMA")).thenReturn(true);

        mvc.perform(post("/api/providers/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"TRIAGE\",\"provider\":\"OLLAMA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("TRIAGE"))
                .andExpect(jsonPath("$.active").value("OLLAMA"));
    }

    @Test
    void selectUnavailableProviderReturns400() throws Exception {
        when(providers.select(LlmAction.TRIAGE, "OLLAMA")).thenReturn(false);

        mvc.perform(post("/api/providers/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"TRIAGE\",\"provider\":\"OLLAMA\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void selectUnknownActionReturns400() throws Exception {
        mvc.perform(post("/api/providers/select")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"BOGUS\",\"provider\":\"GROQ\"}"))
                .andExpect(status().isBadRequest());
    }
}
