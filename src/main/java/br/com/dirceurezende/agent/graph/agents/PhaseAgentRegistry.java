package br.com.dirceurezende.agent.graph.agents;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.dirceurezende.agent.config.UcpProperties;
import br.com.dirceurezende.agent.graph.prompts.PromptCatalog;
import br.com.dirceurezende.agent.graph.state.FunnelPhase;
import br.com.dirceurezende.agent.graph.tools.PhaseToolPolicy;
import br.com.dirceurezende.agent.graph.tools.ToolSource;
import br.com.dirceurezende.agent.graph.tools.ToolsetComposer;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.spring.ai.agentexecutor.AgentExecutorEx;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Um {@code AgentExecutorEx} compilado por fase, montado uma vez por endpoint descoberto. */
@Component
public class PhaseAgentRegistry {

    private static final FunnelPhase[] AGENT_PHASES = {
            FunnelPhase.SEARCH, FunnelPhase.CART, FunnelPhase.CHECKOUT, FunnelPhase.PAYMENT};

    private final ChatModel chatModel;
    private final ToolSource toolSource;
    private final BaseCheckpointSaver saver;
    private final UcpProperties properties;
    private final Map<String, Map<FunnelPhase, CompiledGraph<AgentExecutorEx.State>>> cache = new ConcurrentHashMap<>();

    public PhaseAgentRegistry(ChatModel chatModel, ToolSource toolSource,
            @Qualifier("phaseSaver") BaseCheckpointSaver saver, UcpProperties properties) {
        this.chatModel = chatModel;
        this.toolSource = toolSource;
        this.saver = saver;
        this.properties = properties;
    }

    public CompiledGraph<AgentExecutorEx.State> executor(FunnelPhase phase, String merchantKey, String endpoint) {
        return cache.computeIfAbsent(merchantKey + "|" + endpoint, k -> build(merchantKey, endpoint)).get(phase);
    }

    private Map<FunnelPhase, CompiledGraph<AgentExecutorEx.State>> build(String merchantKey, String endpoint) {
        UcpProperties.Merchant merchant = properties.merchant(merchantKey);
        Map<String, ToolCallback> all = toolSource.toolsFor(endpoint, merchant.trustAll());
        Map<FunnelPhase, CompiledGraph<AgentExecutorEx.State>> executors = new EnumMap<>(FunnelPhase.class);
        try {
            for (FunnelPhase phase : AGENT_PHASES) {
                AgentExecutorEx.Builder builder = AgentExecutorEx.builder()
                        .chatModel(chatModel)
                        .defaultSystem(PromptCatalog.promptFor(phase, merchant.name()))
                        .tools(ToolsetComposer.forPhase(phase, all));
                for (String tool : PhaseToolPolicy.sensitiveTools(phase)) {
                    builder.approvalOn(tool, (node, state) -> InterruptionMetadata.builder(node, state)
                            .reason("approval_required:" + tool).build());
                }
                executors.put(phase, builder.build(CompileConfig.builder().checkpointSaver(saver).releaseThread(false).build()));
            }
        } catch (GraphStateException e) {
            throw new IllegalStateException("Falha ao compilar os executores de fase", e);
        }
        return executors;
    }
}
