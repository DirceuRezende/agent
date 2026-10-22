package br.com.dirceurezende.agent.graph.nodes;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import br.com.dirceurezende.agent.graph.prompts.PromptCatalog;
import br.com.dirceurezende.agent.graph.state.Intent;
import br.com.dirceurezende.agent.graph.state.IntentDecision;
import br.com.dirceurezende.agent.graph.state.ShoppingState;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;

/** LLM com saída estruturada. Só sugere a intenção; quem decide o destino é o IntentRouter. */
public class IntentNode implements AsyncNodeActionWithConfig<ShoppingState> {

    private static final Logger log = LoggerFactory.getLogger(IntentNode.class);

    private final ChatClient chatClient;

    public IntentNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(ShoppingState state, RunnableConfig config) {
        Intent intent;
        try {
            String user = state.messages().stream().filter(UserMessage.class::isInstance)
                    .reduce((a, b) -> b).map(m -> m.getText()).orElse("");
            IntentDecision decision = chatClient.prompt()
                    .system(PromptCatalog.INTENT)
                    .user("Fase atual: %s. Carrinho: %s. Checkout: %s.\nMensagem: %s".formatted(
                            state.phase(), state.hasCart() ? "sim" : "não", state.hasCheckout() ? "sim" : "não", user))
                    .call()
                    .entity(IntentDecision.class);
            intent = decision == null || decision.intent() == null ? Intent.CHAT : decision.intent();
        } catch (Exception e) {
            log.warn("Falha ao classificar a intenção, seguindo como CHAT: {}", e.getMessage());
            intent = Intent.CHAT;
        }
        return CompletableFuture.completedFuture(Map.of(ShoppingState.INTENT, intent.name()));
    }
}
