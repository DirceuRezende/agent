package br.com.dirceurezende.agent.graph.routing;

import br.com.dirceurezende.agent.graph.state.FunnelPhase;
import br.com.dirceurezende.agent.graph.state.Intent;
import br.com.dirceurezende.agent.graph.state.ShoppingState;

/**
 * Função pura: cruza a intenção sugerida pelo LLM com a fase atual e decide o nó de destino.
 * Intent inválido para a fase cai no nó da fase atual, que explica o que falta.
 */
public final class IntentRouter {

    private IntentRouter() {}

    public static String route(ShoppingState state, Intent intent) {
        return route(state.phase(), intent, state.hasCart(), state.hasCheckout());
    }

    public static String route(FunnelPhase phase, Intent intent, boolean hasCart, boolean hasCheckout) {
        FunnelPhase current = switch (phase) {
            case DISCOVERY, DONE -> FunnelPhase.SEARCH;
            default -> phase;
        };
        FunnelPhase target = transition(current, intent == null ? Intent.CHAT : intent, hasCart, hasCheckout);
        return target.nodeId();
    }

    private static FunnelPhase transition(FunnelPhase current, Intent intent, boolean hasCart, boolean hasCheckout) {
        return switch (intent) {
            case SEARCH, BACK_TO_SEARCH -> FunnelPhase.SEARCH;
            case ADD_TO_CART -> FunnelPhase.CART;
            case VIEW_CART, CANCEL_CART -> hasCart ? FunnelPhase.CART : current;
            case GO_TO_CHECKOUT -> hasCart ? FunnelPhase.CHECKOUT : current;
            case CANCEL_CHECKOUT, UPDATE_CHECKOUT -> switch (current) {
                case CHECKOUT, PAYMENT -> hasCheckout ? current : FunnelPhase.CHECKOUT;
                default -> current;
            };
            case PAY -> switch (current) {
                case CHECKOUT, PAYMENT -> hasCheckout ? FunnelPhase.PAYMENT : current;
                default -> current;
            };
            case CHAT -> current;
        };
    }
}
