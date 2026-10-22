package br.com.dirceurezende.agent.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

import br.com.dirceurezende.agent.graph.ShoppingGraphFactory;
import br.com.dirceurezende.agent.graph.agents.PendingActionMessages;
import br.com.dirceurezende.agent.graph.state.ShoppingState;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

/** Fachada sobre o grafo do funil: um turno de chat (invoke/stream), histórico e diagrama. */
@Service
public class ShoppingGraphService {

    public record HistoryItem(String role, String text) {}

    public record ConversationView(ChatResponse state, List<HistoryItem> history) {}

    private final CompiledGraph<ShoppingState> graph;

    public ShoppingGraphService(ShoppingGraphFactory factory) {
        this.graph = factory.build();
    }

    public ChatResponse chat(ChatRequest request) {
        return chat(request, (node, phase) -> { });
    }

    /** Roda um turno; {@code onNode} recebe cada nó visitado (e a fase do estado naquele ponto). */
    public ChatResponse chat(ChatRequest request, BiConsumer<String, String> onNode) {
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString() : request.conversationId();
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();

        Map<String, Object> input = new HashMap<>();
        String text = request.message() == null ? "" : request.message();
        if (request.confirm() != null) {
            input.put(ShoppingState.CONFIRM, request.confirm() ? "APPROVED" : "REJECTED");
            if (text.isBlank()) {
                text = request.confirm() ? "sim" : "não";
            }
        }
        input.put("messages", List.of(new UserMessage(text)));

        List<String> visited = new ArrayList<>();
        ShoppingState last = null;
        for (NodeOutput<ShoppingState> out : graph.stream(GraphInput.args(input), config)) {
            if (out.isSTART() || out.isEND()) {
                if (out.isEND()) {
                    last = out.state();
                }
                continue;
            }
            visited.add(out.node());
            last = out.state();
            onNode.accept(out.node(), last.str(ShoppingState.PHASE));
        }
        if (last == null) {
            last = graph.getState(config).state();
        }
        return toResponse(conversationId, last, visited);
    }

    public ConversationView conversation(String conversationId) {
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        ShoppingState state = graph.getState(config).state();
        List<HistoryItem> history = new ArrayList<>();
        for (Message m : state.messages()) {
            if (m instanceof UserMessage) {
                history.add(new HistoryItem("user", m.getText()));
            } else if (m instanceof AssistantMessage a && a.getText() != null && !a.getText().isBlank()) {
                history.add(new HistoryItem("assistant", a.getText()));
            }
        }
        return new ConversationView(toResponse(conversationId, state, List.of()), history);
    }

    public String mermaid() {
        return graph.getGraph(GraphRepresentation.Type.MERMAID).content();
    }

    private ChatResponse toResponse(String conversationId, ShoppingState s, List<String> visited) {
        String reply = s.messages().stream().filter(AssistantMessage.class::isInstance)
                .reduce((a, b) -> b).map(Message::getText).orElse("");
        boolean awaiting = s.pendingPhase().isPresent();
        String pendingAction = awaiting ? PendingActionMessages.describe(s.str(ShoppingState.PENDING_TOOL),
                s.str(ShoppingState.PENDING_ARGS),
                "cancel_cart".equals(s.str(ShoppingState.PENDING_TOOL))
                        ? s.str(ShoppingState.CART_ID) : s.str(ShoppingState.CHECKOUT_ID)) : null;
        return new ChatResponse(conversationId, reply, s.phase().name(), blankToNull(s.str(ShoppingState.CART_ID)),
                blankToNull(s.str(ShoppingState.CHECKOUT_ID)), blankToNull(s.str(ShoppingState.CHECKOUT_STATUS)),
                blankToNull(s.str(ShoppingState.CONTINUE_URL)), awaiting, pendingAction, visited);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
