package br.com.dirceurezende.agent.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.dirceurezende.agent.chat.ChatRequest;
import br.com.dirceurezende.agent.chat.ChatResponse;
import br.com.dirceurezende.agent.chat.ConfirmIntentClassifier;
import br.com.dirceurezende.agent.chat.ShoppingGraphService;
import br.com.dirceurezende.agent.config.UcpProperties;
import br.com.dirceurezende.agent.graph.agents.PhaseAgentRegistry;
import br.com.dirceurezende.agent.graph.tools.ToolSource;
import br.com.dirceurezende.agent.graph.tools.UcpToolCallback;
import br.com.dirceurezende.agent.ucp.UcpDiscoveryService;
import br.com.dirceurezende.agent.ucp.UcpProfile;
import io.modelcontextprotocol.spec.McpSchema;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

/** Jornada completa do funil com ChatModel fake e tools UCP fake (sem rede, sem Mongo). */
class FunnelJourneyTest {

    private final List<String> toolLog = new ArrayList<>();
    private final List<Map<String, Object>> toolArgs = new ArrayList<>();
    private ShoppingGraphService service;

    @BeforeEach
    void setUp() {
        UcpProperties props = new UcpProperties(new UcpProperties.Agent("http://localhost:8080/.well-known/ucp"),
                new UcpProperties.Mcp(1000), "boca-rosa",
                Map.of("boca-rosa", new UcpProperties.Merchant("Boca Rosa", "https://example.test", null, 800)));
        UcpDiscoveryService discovery = Mockito.mock(UcpDiscoveryService.class);
        Mockito.when(discovery.discover(Mockito.anyString())).thenReturn(
                new UcpProfile("boca-rosa", "Boca Rosa", "2026-08-25", "https://shop.test/api/ucp/mcp", List.of()));

        FakeChatModel model = new FakeChatModel(this::script);
        ToolSource tools = (endpoint, trustAll) -> fakeTools();
        MemorySaver funnelSaver = new MemorySaver();
        PhaseAgentRegistry registry = new PhaseAgentRegistry(model, tools, new MemorySaver(), props);
        service = new ShoppingGraphService(new ShoppingGraphFactory(discovery, ChatClient.builder(model).build(),
                registry, new ConfirmIntentClassifier(), funnelSaver, props));
    }

    @Test
    void jornadaCompletaComCancelamentoEVolta() {
        ChatResponse r = say("c1", "quero um batom");
        assertThat(r.phase()).isEqualTo("SEARCH");
        assertThat(r.visitedNodes()).contains("discovery", "intent", "search");
        assertThat(toolLog).containsExactly("search_catalog");

        r = say("c1", "adiciona o primeiro");
        assertThat(r.phase()).isEqualTo("CART");
        assertThat(r.cartId()).isEqualTo("cart-1");

        r = say("c1", "fechar pedido");
        assertThat(r.phase()).isEqualTo("CHECKOUT");
        assertThat(r.checkoutId()).isEqualTo("co-1");
        // create_checkout recebeu cart_id do estado e line_items vazio, mesmo sem o LLM informar
        Map<String, Object> createArgs = toolArgs.get(toolLog.indexOf("create_checkout"));
        assertThat(createArgs).containsEntry("cart_id", "cart-1").containsEntry("line_items", List.of());

        r = say("c1", "meu email é a@b.com");
        assertThat(r.phase()).isEqualTo("PAYMENT");
        assertThat(r.continueUrl()).isEqualTo("https://shop.test/continue/co-1");

        // cancelar o checkout é sensível: para e pergunta com o id real
        r = say("c1", "cancela o checkout");
        assertThat(r.awaitingConfirmation()).isTrue();
        assertThat(r.reply()).contains("Cancelar o checkout co-1");
        assertThat(toolLog).doesNotContain("cancel_checkout");

        // resposta ambígua mantém a pendência, sem executar nada
        r = say("c1", "hmm, talvez");
        assertThat(r.awaitingConfirmation()).isTrue();
        assertThat(toolLog).doesNotContain("cancel_checkout");

        r = say("c1", "sim");
        assertThat(r.awaitingConfirmation()).isFalse();
        assertThat(toolLog).contains("cancel_checkout");
        assertThat(r.phase()).isEqualTo("CART");
        assertThat(r.checkoutId()).isNull();
        assertThat(r.cartId()).isEqualTo("cart-1");

        // voltar para a busca mantém o carrinho
        r = say("c1", "volta pra busca");
        assertThat(r.phase()).isEqualTo("SEARCH");
        assertThat(r.cartId()).isEqualTo("cart-1");
    }

    @Test
    void rejeitarNaoExecutaATool() {
        say("c2", "quero um batom");
        say("c2", "adiciona o primeiro");
        say("c2", "fechar pedido");
        ChatResponse r = say("c2", "cancela o checkout");
        assertThat(r.awaitingConfirmation()).isTrue();

        r = service.chat(new ChatRequest("c2", null, false));
        assertThat(r.awaitingConfirmation()).isFalse();
        assertThat(toolLog).doesNotContain("cancel_checkout");
        assertThat(r.checkoutId()).isEqualTo("co-1");
    }

    @Test
    void botaoConfirmarExecutaATool() {
        say("c3", "quero um batom");
        say("c3", "adiciona o primeiro");
        say("c3", "fechar pedido");
        say("c3", "cancela o checkout");

        ChatResponse r = service.chat(new ChatRequest("c3", null, true));
        assertThat(toolLog).contains("cancel_checkout");
        assertThat(r.phase()).isEqualTo("CART");
    }

    private ChatResponse say(String conversationId, String text) {
        return service.chat(new ChatRequest(conversationId, text, null));
    }

    // ---- fakes ----

    private AssistantMessage script(List<Message> msgs) {
        String system = msgs.stream().filter(SystemMessage.class::isInstance).map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b);
        String lastUser = msgs.stream().filter(UserMessage.class::isInstance).reduce((a, b) -> b)
                .map(Message::getText).orElse("");
        if (system.contains("Classifique a mensagem")) {
            return FakeChatModel.text("{\"intent\":\"" + intentFor(lastUser) + "\",\"reason\":\"fake\"}");
        }
        if (msgs.get(msgs.size() - 1) instanceof ToolResponseMessage) {
            return FakeChatModel.text("pronto");
        }
        if (lastUser.contains("batom")) {
            return FakeChatModel.toolCall("search_catalog", "{\"query\":\"batom\"}");
        }
        if (lastUser.contains("adiciona")) {
            return FakeChatModel.toolCall("create_cart", "{\"line_items\":[{\"variant_id\":\"v1\",\"quantity\":1}]}");
        }
        if (lastUser.contains("fechar")) {
            return FakeChatModel.toolCall("create_checkout", "{}");
        }
        if (lastUser.contains("email")) {
            return FakeChatModel.toolCall("update_checkout", "{\"buyer\":{\"email\":\"a@b.com\"}}");
        }
        if (lastUser.contains("cancela o checkout")) {
            return FakeChatModel.toolCall("cancel_checkout", "{}");
        }
        return FakeChatModel.text("ok");
    }

    private static String intentFor(String text) {
        if (text.contains("cancela o checkout")) return "CANCEL_CHECKOUT";
        if (text.contains("cancela o carrinho")) return "CANCEL_CART";
        if (text.contains("volta")) return "BACK_TO_SEARCH";
        if (text.contains("fechar")) return "GO_TO_CHECKOUT";
        if (text.contains("email")) return "UPDATE_CHECKOUT";
        if (text.contains("adiciona")) return "ADD_TO_CART";
        return "SEARCH";
    }

    private Map<String, ToolCallback> fakeTools() {
        Map<String, String> results = new LinkedHashMap<>();
        results.put("search_catalog", "{\"products\":[{\"title\":\"Batom\",\"variant_id\":\"v1\",\"price\":4990}]}");
        results.put("lookup_catalog", "{}");
        results.put("get_product", "{}");
        results.put("create_cart", "{\"id\":\"cart-1\"}");
        results.put("update_cart", "{\"id\":\"cart-1\"}");
        results.put("get_cart", "{\"id\":\"cart-1\"}");
        results.put("cancel_cart", "{\"id\":\"cart-1\",\"status\":\"canceled\"}");
        results.put("create_checkout", "{\"id\":\"co-1\",\"status\":\"incomplete\"}");
        results.put("get_checkout", "{\"id\":\"co-1\",\"status\":\"incomplete\"}");
        results.put("update_checkout", "{\"id\":\"co-1\",\"status\":\"requires_escalation\","
                + "\"continue_url\":\"https://shop.test/continue/co-1\"}");
        results.put("cancel_checkout", "{\"id\":\"co-1\",\"status\":\"canceled\"}");
        results.put("complete_checkout", "{\"id\":\"co-1\",\"status\":\"completed\",\"order\":{\"id\":\"o-1\"}}");

        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        results.forEach((name, json) -> {
            McpSchema.Tool tool = new McpSchema.Tool(name, null, name, Map.of("type", "object", "properties",
                    Map.of("id", Map.of("type", "string"), "meta", Map.of("type", "object"))), null, null, null);
            tools.put(name, new UcpToolCallback(tool, req -> {
                toolLog.add(req.name());
                toolArgs.add(req.arguments());
                return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(json)))
                        .isError(false).build();
            }, "http://localhost:8080/.well-known/ucp"));
        });
        return tools;
    }
}
