package br.com.dirceurezende.agent.graph.routing;

import java.util.List;

import br.com.dirceurezende.agent.graph.state.FunnelPhase;
import br.com.dirceurezende.agent.graph.state.ShoppingState;
import br.com.dirceurezende.agent.graph.tools.ToolEvidence;

/** Função pura: nova fase e ids a partir da evidência das tools do turno. */
public final class PhaseTransitionGuard {

    static final int SEARCH_SUMMARY_LIMIT = 3000;

    private PhaseTransitionGuard() {}

    public record Transition(FunnelPhase phase, String cartId, String checkoutId, String checkoutStatus,
            String continueUrl, String orderId, String lastSearchJson) {}

    public static Transition apply(FunnelPhase node, ShoppingState before, List<ToolEvidence> evidence) {
        String cartId = before.str(ShoppingState.CART_ID);
        String checkoutId = before.str(ShoppingState.CHECKOUT_ID);
        String status = before.str(ShoppingState.CHECKOUT_STATUS);
        String continueUrl = before.str(ShoppingState.CONTINUE_URL);
        String orderId = before.str(ShoppingState.ORDER_ID);
        String search = before.str(ShoppingState.LAST_SEARCH_JSON);
        FunnelPhase forced = null;

        for (ToolEvidence e : evidence) {
            if (e.error()) {
                continue;
            }
            switch (e.tool()) {
                case "search_catalog", "lookup_catalog", "get_product" ->
                    search = e.raw().length() > SEARCH_SUMMARY_LIMIT
                            ? e.raw().substring(0, SEARCH_SUMMARY_LIMIT) : e.raw();
                case "create_cart", "update_cart", "get_cart" -> {
                    if (!e.id().isBlank()) {
                        cartId = e.id();
                    }
                }
                case "cancel_cart" -> {
                    cartId = "";
                    checkoutId = "";
                    status = "";
                    continueUrl = "";
                    forced = FunnelPhase.SEARCH;
                }
                case "create_checkout", "get_checkout", "update_checkout" -> {
                    if (!e.id().isBlank()) {
                        checkoutId = e.id();
                    }
                    if (!e.status().isBlank()) {
                        status = e.status();
                    }
                    if (!e.continueUrl().isBlank()) {
                        continueUrl = e.continueUrl();
                    }
                    if ("canceled".equals(e.status())) {
                        checkoutId = "";
                        status = "";
                        forced = FunnelPhase.CART;
                    }
                }
                case "cancel_checkout" -> {
                    checkoutId = "";
                    status = "";
                    continueUrl = "";
                    forced = FunnelPhase.CART;
                }
                case "complete_checkout" -> {
                    if (!e.orderId().isBlank()) {
                        orderId = e.orderId();
                        forced = FunnelPhase.DONE;
                    } else if ("canceled".equals(e.status())) {
                        checkoutId = "";
                        status = "";
                        forced = FunnelPhase.CART;
                    }
                }
                default -> { }
            }
        }

        FunnelPhase phase = forced != null ? forced : natural(node, cartId, checkoutId, status);
        if (phase == FunnelPhase.SEARCH && node == FunnelPhase.SEARCH && before.phase() == FunnelPhase.DONE) {
            cartId = "";
            checkoutId = "";
            status = "";
            continueUrl = "";
            orderId = "";
        }
        return new Transition(phase, cartId, checkoutId, status, continueUrl, orderId, search);
    }

    private static FunnelPhase natural(FunnelPhase node, String cartId, String checkoutId, String status) {
        return switch (node) {
            case SEARCH, DISCOVERY -> FunnelPhase.SEARCH;
            case CART -> cartId.isBlank() ? FunnelPhase.SEARCH : FunnelPhase.CART;
            case CHECKOUT -> {
                if (checkoutId.isBlank()) {
                    yield cartId.isBlank() ? FunnelPhase.SEARCH : FunnelPhase.CART;
                }
                yield "ready_for_complete".equals(status) || "requires_escalation".equals(status)
                        ? FunnelPhase.PAYMENT : FunnelPhase.CHECKOUT;
            }
            case PAYMENT -> checkoutId.isBlank()
                    ? (cartId.isBlank() ? FunnelPhase.SEARCH : FunnelPhase.CART) : FunnelPhase.PAYMENT;
            case DONE -> FunnelPhase.DONE;
        };
    }
}
