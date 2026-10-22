package br.com.dirceurezende.agent.chat;

import java.util.List;

import br.com.dirceurezende.agent.config.UcpProperties;
import br.com.dirceurezende.agent.ucp.AgentProfileController;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerTest {

    private final ShoppingGraphService service = Mockito.mock(ShoppingGraphService.class);
    private final UcpProperties props = new UcpProperties(new UcpProperties.Agent("https://t.example/.well-known/ucp"),
            new UcpProperties.Mcp(1000), "boca-rosa", java.util.Map.of());
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(service),
            new AgentProfileController(props)).build();

    @Test
    void chatDevolveOEstadoDoFunil() throws Exception {
        Mockito.when(service.chat(Mockito.any())).thenReturn(new ChatResponse("c1", "oi", "SEARCH", null, null, null,
                null, false, null, List.of("discovery", "intent", "search")));
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"quero um batom\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("c1"))
                .andExpect(jsonPath("$.phase").value("SEARCH"))
                .andExpect(jsonPath("$.visitedNodes[2]").value("search"));
    }

    @Test
    void grafoEmMermaid() throws Exception {
        Mockito.when(service.mermaid()).thenReturn("flowchart TD");
        mvc.perform(get("/api/graph")).andExpect(status().isOk());
    }

    @Test
    void perfilDoAgenteAnunciaCapabilities() throws Exception {
        mvc.perform(get("/.well-known/ucp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ucp.version").value("2026-08-25"))
                .andExpect(jsonPath("$.ucp.services['dev.ucp.shopping'][0].transport").value("mcp"))
                .andExpect(jsonPath("$.ucp.capabilities.length()").value(5));
    }
}
