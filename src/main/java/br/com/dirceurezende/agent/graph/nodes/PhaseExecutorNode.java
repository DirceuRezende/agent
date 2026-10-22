package br.com.dirceurezende.agent.graph.nodes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import br.com.dirceurezende.agent.chat.ConfirmIntentClassifier;
import br.com.dirceurezende.agent.graph.agents.PendingActionMessages;
import br.com.dirceurezende.agent.graph.agents.PhaseAgentRegistry;
import br.com.dirceurezende.agent.graph.routing.PhaseTransitionGuard;
import br.com.dirceurezende.agent.graph.state.FunnelPhase;
import br.com.dirceurezende.agent.graph.state.ShoppingState;
import br.com.dirceurezende.agent.graph.tools.FunnelContext;
import br.com.dirceurezende.agent.graph.tools.ToolEvidence;
import br.com.dirceurezende.agent.graph.tools.ToolEvidenceExtractor;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.agent.AgentEx;
import org.bsc.langgraph4j.spring.ai.agentexecutor.AgentExecutorEx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Nó de uma fase: roda o AgentExecutorEx da fase como grafo filho (thread {@code conversationId:fase}) e traduz o
 * resultado em atualizações do estado do funil, pela evidência das tools.
 */
public class PhaseExecutorNode implements AsyncNodeActionWithConfig<ShoppingState> {

    private static final Logger log = LoggerFactory.getLogger(PhaseExecutorNode.class);

    private final FunnelPhase phase;
    private final PhaseAgentRegistry registry;
    private final ConfirmIntentClassifier classifier;

    public PhaseExecutorNode(FunnelPhase phase, PhaseAgentRegistry registry, ConfirmIntentClassifier classifier) {
        this.phase = phase;
        this.registry = registry;
        this.classifier = classifier;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(ShoppingState state, RunnableConfig config) {
        return CompletableFuture.completedFuture(run(state, config));
    }

    private Map<String, Object> run(ShoppingState state, RunnableConfig config) {
        String conversationId = config.threadId().orElse("default");
        // executor direto: as tools rodam na thread do turno e enxergam o FunnelContext
        RunnableConfig child = RunnableConfig.builder()
                .threadId(conversationId + ":" + phase.nodeId())
                .emitterExecutor(Runnable::run)
                .build();
        FunnelContext.bind(new FunnelContext.Ids(state.str(ShoppingState.CART_ID), state.str(ShoppingState.CHECKOUT_ID)));
        try {
            CompiledGraph<AgentExecutorEx.State> executor = registry.executor(
                    phase, state.str(ShoppingState.MERCHANT), state.str(ShoppingState.UCP_ENDPOINT));
            boolean resuming = state.pendingPhase().filter(phase.name()::equals).isPresent();
            return resuming ? resume(executor, child, state) : turn(executor, child, state);
        } catch (Exception e) {
            log.error("Falha na fase {}", phase, e);
            Map<String, Object> out = new HashMap<>();
            out.put("messages", assistant("Tive um problema ao processar seu pedido (" + rootCause(e)
                    + "). Pode tentar de novo?"));
            out.put(ShoppingState.CONFIRM, "");
            return out;
        } finally {
            FunnelContext.clear();
        }
    }

    private static String rootCause(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 600; t = t.getCause() == t ? null : t.getCause()) {
            String m = t.getMessage();
            if (m != null && !m.isBlank() && !sb.toString().contains(m)) {
                sb.append(sb.isEmpty() ? "" : " <- ").append(m);
            }
        }
        return sb.toString();
    }

    private Map<String, Object> turn(CompiledGraph<AgentExecutorEx.State> executor, RunnableConfig child,
            ShoppingState state) {
        // uma única mensagem de sistema (a do defaultSystem): o contexto vai junto da mensagem do usuário
        List<Message> input = List.of(new UserMessage(context(state) + "\n\nMensagem do usuário: " + lastUserText(state)));
        Optional<AgentExecutorEx.State> out = executor.invoke(Map.of("messages", input), child);
        return afterRun(executor, child, state, out.orElse(null));
    }

    private Map<String, Object> resume(CompiledGraph<AgentExecutorEx.State> executor, RunnableConfig child,
            ShoppingState state) {
        String explicit = state.str(ShoppingState.CONFIRM);
        ConfirmIntentClassifier.Decision decision = explicit.isBlank()
                ? classifier.classify(lastUserText(state))
                : ConfirmIntentClassifier.Decision.valueOf(explicit);
        if (decision == ConfirmIntentClassifier.Decision.OTHER) {
            Map<String, Object> out = new HashMap<>();
            out.put("messages", assistant("Não entendi. " + PendingActionMessages.describe(
                    state.str(ShoppingState.PENDING_TOOL), state.str(ShoppingState.PENDING_ARGS), activeId(state))));
            out.put(ShoppingState.CONFIRM, "");
            return out;
        }
        Optional<AgentExecutorEx.State> out = executor.invoke(
                GraphInput.resume(Map.of(AgentEx.APPROVAL_RESULT, decision.name())), child);
        return afterRun(executor, child, state, out.orElse(null));
    }

    private Map<String, Object> afterRun(CompiledGraph<AgentExecutorEx.State> executor, RunnableConfig child,
            ShoppingState state, AgentExecutorEx.State result) {
        Map<String, Object> out = new HashMap<>();
        out.put(ShoppingState.CONFIRM, "");

        AgentExecutorEx.State pending = pendingState(executor, child);
        AgentExecutorEx.State view = pending != null ? pending : result;
        List<ToolEvidence> evidence = view == null ? List.of() : ToolEvidenceExtractor.ofTurn(view.messages());
        PhaseTransitionGuard.Transition t = PhaseTransitionGuard.apply(phase, state, evidence);
        out.put(ShoppingState.CART_ID, t.cartId());
        out.put(ShoppingState.CHECKOUT_ID, t.checkoutId());
        out.put(ShoppingState.CHECKOUT_STATUS, t.checkoutStatus());
        out.put(ShoppingState.CONTINUE_URL, t.continueUrl());
        out.put(ShoppingState.ORDER_ID, t.orderId());
        out.put(ShoppingState.LAST_SEARCH_JSON, t.lastSearchJson());

        if (pending != null) {
            var call = pending.toolExecutionRequests$getFirst().orElse(null);
            String tool = call == null ? "" : call.name();
            String args = call == null ? "" : call.arguments();
            out.put(ShoppingState.PHASE, phase.name());
            out.put(ShoppingState.PENDING_PHASE, phase.name());
            out.put(ShoppingState.PENDING_TOOL, tool);
            out.put(ShoppingState.PENDING_ARGS, args);
            out.put("messages", assistant(PendingActionMessages.describe(
                    tool, args, activeIdFor(tool, t.cartId(), t.checkoutId()))));
        } else {
            out.put(ShoppingState.PHASE, t.phase().name());
            out.put(ShoppingState.PENDING_PHASE, "");
            out.put(ShoppingState.PENDING_TOOL, "");
            out.put(ShoppingState.PENDING_ARGS, "");
            out.put("messages", assistant(lastAssistantText(result)));
        }
        return out;
    }

    /** Estado do executor se ele parou num nó approval_*; null caso contrário. */
    private AgentExecutorEx.State pendingState(CompiledGraph<AgentExecutorEx.State> executor, RunnableConfig child) {
        try {
            var snapshot = executor.getState(child);
            String next = snapshot.next();
            return next != null && next.startsWith("approval") ? snapshot.state() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String context(ShoppingState state) {
        return """
                Contexto do funil (fonte de verdade, não invente ids):
                - fase: %s
                - carrinho: %s
                - checkout: %s (status: %s)
                - continue_url: %s
                - últimos resultados de busca: %s""".formatted(
                phase, orNone(state.str(ShoppingState.CART_ID)), orNone(state.str(ShoppingState.CHECKOUT_ID)),
                orNone(state.str(ShoppingState.CHECKOUT_STATUS)), orNone(state.str(ShoppingState.CONTINUE_URL)),
                orNone(state.str(ShoppingState.LAST_SEARCH_JSON)));
    }

    private static String orNone(String s) {
        return s == null || s.isBlank() ? "nenhum" : s;
    }

    private static String lastUserText(ShoppingState state) {
        return state.messages().stream().filter(UserMessage.class::isInstance)
                .reduce((a, b) -> b).map(Message::getText).orElse("");
    }

    private static String lastAssistantText(AgentExecutorEx.State result) {
        if (result == null) {
            return "";
        }
        return result.lastMessage().filter(AssistantMessage.class::isInstance)
                .map(Message::getText).orElse("");
    }

    private static String activeId(ShoppingState state) {
        return activeIdFor(state.str(ShoppingState.PENDING_TOOL),
                state.str(ShoppingState.CART_ID), state.str(ShoppingState.CHECKOUT_ID));
    }

    private static String activeIdFor(String tool, String cartId, String checkoutId) {
        return "cancel_cart".equals(tool) ? cartId : checkoutId;
    }

    private static AssistantMessage assistant(String text) {
        return AssistantMessage.builder().content(text == null ? "" : text).build();
    }
}
