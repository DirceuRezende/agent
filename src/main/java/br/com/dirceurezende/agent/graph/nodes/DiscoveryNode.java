package br.com.dirceurezende.agent.graph.nodes;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import br.com.dirceurezende.agent.graph.state.FunnelPhase;
import br.com.dirceurezende.agent.graph.state.ShoppingState;
import br.com.dirceurezende.agent.ucp.UcpDiscoveryService;
import br.com.dirceurezende.agent.ucp.UcpProfile;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.springframework.ai.chat.messages.AssistantMessage;

/** Determinístico, sem LLM: descobre o endpoint MCP do merchant quando a thread ainda não o tem. */
public class DiscoveryNode implements AsyncNodeActionWithConfig<ShoppingState> {

    private final UcpDiscoveryService discovery;
    private final String defaultMerchant;

    public DiscoveryNode(UcpDiscoveryService discovery, String defaultMerchant) {
        this.discovery = discovery;
        this.defaultMerchant = defaultMerchant;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(ShoppingState state, RunnableConfig config) {
        if (!state.str(ShoppingState.UCP_ENDPOINT).isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String merchant = state.str(ShoppingState.MERCHANT).isBlank() ? defaultMerchant : state.str(ShoppingState.MERCHANT);
        Map<String, Object> update = new HashMap<>();
        try {
            UcpProfile profile = discovery.discover(merchant);
            update.put(ShoppingState.MERCHANT, merchant);
            update.put(ShoppingState.UCP_ENDPOINT, profile.mcpEndpoint());
            if (state.phase() == FunnelPhase.DISCOVERY) {
                update.put(ShoppingState.PHASE, FunnelPhase.SEARCH.name());
            }
        } catch (Exception e) {
            update.put("messages", AssistantMessage.builder()
                    .content("Não consegui descobrir o servidor UCP da loja: " + e.getMessage()).build());
        }
        return CompletableFuture.completedFuture(update);
    }
}
