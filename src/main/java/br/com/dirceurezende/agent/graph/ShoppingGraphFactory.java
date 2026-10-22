package br.com.dirceurezende.agent.graph;

import java.util.Map;

import br.com.dirceurezende.agent.chat.ConfirmIntentClassifier;
import br.com.dirceurezende.agent.config.UcpProperties;
import br.com.dirceurezende.agent.graph.agents.PhaseAgentRegistry;
import br.com.dirceurezende.agent.graph.nodes.DiscoveryNode;
import br.com.dirceurezende.agent.graph.nodes.IntentNode;
import br.com.dirceurezende.agent.graph.nodes.PhaseExecutorNode;
import br.com.dirceurezende.agent.graph.routing.EntryRouter;
import br.com.dirceurezende.agent.graph.routing.IntentRouter;
import br.com.dirceurezende.agent.graph.state.FunnelPhase;
import br.com.dirceurezende.agent.graph.state.Intent;
import br.com.dirceurezende.agent.graph.state.ShoppingState;
import br.com.dirceurezende.agent.ucp.UcpDiscoveryService;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Monta o grafo do funil: discovery → intent → search | cart | checkout | payment → END. */
@Component
public class ShoppingGraphFactory {

    private final UcpDiscoveryService discovery;
    private final ChatClient chatClient;
    private final PhaseAgentRegistry registry;
    private final ConfirmIntentClassifier classifier;
    private final BaseCheckpointSaver saver;
    private final UcpProperties properties;

    public ShoppingGraphFactory(UcpDiscoveryService discovery, ChatClient chatClient, PhaseAgentRegistry registry,
            ConfirmIntentClassifier classifier, @Qualifier("funnelSaver") BaseCheckpointSaver saver,
            UcpProperties properties) {
        this.discovery = discovery;
        this.chatClient = chatClient;
        this.registry = registry;
        this.classifier = classifier;
        this.saver = saver;
        this.properties = properties;
    }

    public CompiledGraph<ShoppingState> build() {
        try {
            StateGraph<ShoppingState> graph = new StateGraph<>(ShoppingState.SCHEMA,
                    new SpringAIJacksonStateSerializer<>(ShoppingState::new));

            graph.addNode("discovery", new DiscoveryNode(discovery, properties.defaultMerchant()));
            graph.addNode("intent", new IntentNode(chatClient));
            Map<String, String> phaseNodes = Map.of(
                    "search", "search", "cart", "cart", "checkout", "checkout", "payment", "payment");
            for (FunnelPhase phase : new FunnelPhase[] {
                    FunnelPhase.SEARCH, FunnelPhase.CART, FunnelPhase.CHECKOUT, FunnelPhase.PAYMENT}) {
                graph.addNode(phase.nodeId(), new PhaseExecutorNode(phase, registry, classifier));
                graph.addEdge(phase.nodeId(), StateGraph.END);
            }

            graph.addEdge(StateGraph.START, "discovery");
            graph.addConditionalEdges("discovery",
                    AsyncEdgeAction.edge_async(EntryRouter::route),
                    Map.of("intent", "intent", "search", "search", "cart", "cart", "checkout", "checkout",
                            "payment", "payment", EntryRouter.END, StateGraph.END));
            graph.addConditionalEdges("intent",
                    AsyncEdgeAction.edge_async(s -> IntentRouter.route(s, Intent.fromName(s.str(ShoppingState.INTENT)))),
                    phaseNodes);

            return graph.compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false).build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("Falha ao montar o grafo do funil", e);
        }
    }
}
