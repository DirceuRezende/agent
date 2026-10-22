package br.com.dirceurezende.agent.graph.tools;

import java.util.List;
import java.util.Map;

import br.com.dirceurezende.agent.graph.state.FunnelPhase;
import org.springframework.ai.tool.ToolCallback;

public final class ToolsetComposer {

    private ToolsetComposer() {}

    /** Subconjunto de tools da fase; nomes que o servidor não expõe são ignorados. */
    public static List<ToolCallback> forPhase(FunnelPhase phase, Map<String, ToolCallback> ucpTools) {
        return PhaseToolPolicy.toolNames(phase).stream()
                .map(ucpTools::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
