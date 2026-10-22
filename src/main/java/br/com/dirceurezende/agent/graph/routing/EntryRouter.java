package br.com.dirceurezende.agent.graph.routing;

import br.com.dirceurezende.agent.graph.state.FunnelPhase;
import br.com.dirceurezende.agent.graph.state.ShoppingState;

/** Depois do discovery: pendência de aprovação pula o intent e vai direto à fase pausada. */
public final class EntryRouter {

    public static final String END = "__end__";

    private EntryRouter() {}

    public static String route(ShoppingState state) {
        if (state.str(ShoppingState.UCP_ENDPOINT).isBlank()) {
            return END;
        }
        return state.pendingPhase()
                .map(FunnelPhase::fromName)
                .filter(p -> p != FunnelPhase.DISCOVERY && p != FunnelPhase.DONE)
                .map(FunnelPhase::nodeId)
                .orElse("intent");
    }
}
