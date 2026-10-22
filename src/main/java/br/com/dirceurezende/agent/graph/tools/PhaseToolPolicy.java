package br.com.dirceurezende.agent.graph.tools;

import java.util.List;
import java.util.Set;

import br.com.dirceurezende.agent.graph.state.FunnelPhase;

/** Quais tools cada fase enxerga e quais exigem confirmação humana (HITL). */
public final class PhaseToolPolicy {

    public static final Set<String> SENSITIVE = Set.of("cancel_cart", "cancel_checkout", "complete_checkout");

    private static final List<String> CATALOG = List.of("search_catalog", "lookup_catalog", "get_product");

    private PhaseToolPolicy() {}

    public static List<String> toolNames(FunnelPhase phase) {
        return switch (phase) {
            case SEARCH -> CATALOG;
            case CART -> List.of("search_catalog", "lookup_catalog", "get_product", "create_cart", "update_cart",
                    "get_cart", "cancel_cart", "cancel_checkout");
            case CHECKOUT -> List.of("get_cart", "create_checkout", "get_checkout", "update_checkout",
                    "cancel_checkout");
            case PAYMENT -> List.of("get_checkout", "update_checkout", "cancel_checkout", "complete_checkout");
            case DISCOVERY, DONE -> List.of();
        };
    }

    public static List<String> sensitiveTools(FunnelPhase phase) {
        return toolNames(phase).stream().filter(SENSITIVE::contains).toList();
    }
}
