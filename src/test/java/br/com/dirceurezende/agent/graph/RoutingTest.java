package br.com.dirceurezende.agent.graph;

import java.util.List;
import java.util.Map;

import br.com.dirceurezende.agent.graph.routing.EntryRouter;
import br.com.dirceurezende.agent.graph.routing.IntentRouter;
import br.com.dirceurezende.agent.graph.routing.PhaseTransitionGuard;
import br.com.dirceurezende.agent.graph.state.FunnelPhase;
import br.com.dirceurezende.agent.graph.state.Intent;
import br.com.dirceurezende.agent.graph.state.ShoppingState;
import br.com.dirceurezende.agent.graph.tools.ToolEvidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingTest {

    private static ShoppingState state(String... kv) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return new ShoppingState(m);
    }

    private static String route(FunnelPhase p, Intent i, boolean cart, boolean checkout) {
        return IntentRouter.route(p, i, cart, checkout);
    }

    @Test
    void intentRouterTabelaDeTransicoes() {
        assertThat(route(FunnelPhase.SEARCH, Intent.SEARCH, false, false)).isEqualTo("search");
        assertThat(route(FunnelPhase.SEARCH, Intent.ADD_TO_CART, false, false)).isEqualTo("cart");
        assertThat(route(FunnelPhase.SEARCH, Intent.PAY, false, false)).isEqualTo("search");
        assertThat(route(FunnelPhase.SEARCH, Intent.VIEW_CART, false, false)).isEqualTo("search");
        assertThat(route(FunnelPhase.SEARCH, Intent.VIEW_CART, true, false)).isEqualTo("cart");
        assertThat(route(FunnelPhase.SEARCH, Intent.GO_TO_CHECKOUT, true, false)).isEqualTo("checkout");
        assertThat(route(FunnelPhase.SEARCH, Intent.GO_TO_CHECKOUT, false, false)).isEqualTo("search");
        assertThat(route(FunnelPhase.CART, Intent.BACK_TO_SEARCH, true, false)).isEqualTo("search");
        assertThat(route(FunnelPhase.CART, Intent.CANCEL_CART, true, false)).isEqualTo("cart");
        assertThat(route(FunnelPhase.CART, Intent.CANCEL_CHECKOUT, true, false)).isEqualTo("cart");
        assertThat(route(FunnelPhase.CART, Intent.GO_TO_CHECKOUT, true, false)).isEqualTo("checkout");
        assertThat(route(FunnelPhase.CHECKOUT, Intent.UPDATE_CHECKOUT, true, true)).isEqualTo("checkout");
        assertThat(route(FunnelPhase.CHECKOUT, Intent.PAY, true, true)).isEqualTo("payment");
        assertThat(route(FunnelPhase.CHECKOUT, Intent.CANCEL_CART, true, true)).isEqualTo("cart");
        assertThat(route(FunnelPhase.PAYMENT, Intent.CANCEL_CHECKOUT, true, true)).isEqualTo("payment");
        assertThat(route(FunnelPhase.PAYMENT, Intent.CHAT, true, true)).isEqualTo("payment");
        assertThat(route(FunnelPhase.PAYMENT, Intent.BACK_TO_SEARCH, true, true)).isEqualTo("search");
        assertThat(route(FunnelPhase.DONE, Intent.CHAT, false, false)).isEqualTo("search");
        assertThat(route(FunnelPhase.DISCOVERY, Intent.PAY, false, false)).isEqualTo("search");
    }

    @Test
    void entryRouterPulaOIntentComPendencia() {
        assertThat(EntryRouter.route(state(ShoppingState.UCP_ENDPOINT, ""))).isEqualTo(EntryRouter.END);
        assertThat(EntryRouter.route(state(ShoppingState.UCP_ENDPOINT, "https://x"))).isEqualTo("intent");
        assertThat(EntryRouter.route(state(ShoppingState.UCP_ENDPOINT, "https://x",
                ShoppingState.PENDING_PHASE, "PAYMENT"))).isEqualTo("payment");
    }

    private static ToolEvidence ev(String tool, String id, String status, String url, String order) {
        return new ToolEvidence(tool, false, id, status, url, order, "{}");
    }

    @Test
    void guardMoveAFasePelaEvidencia() {
        var base = state(ShoppingState.CART_ID, "cart-1");
        var t = PhaseTransitionGuard.apply(FunnelPhase.CHECKOUT, base,
                List.of(ev("create_checkout", "co-1", "incomplete", "", "")));
        assertThat(t.phase()).isEqualTo(FunnelPhase.CHECKOUT);
        assertThat(t.checkoutId()).isEqualTo("co-1");

        t = PhaseTransitionGuard.apply(FunnelPhase.CHECKOUT, base,
                List.of(ev("update_checkout", "co-1", "ready_for_complete", "https://c", "")));
        assertThat(t.phase()).isEqualTo(FunnelPhase.PAYMENT);
        assertThat(t.continueUrl()).isEqualTo("https://c");

        var withCheckout = state(ShoppingState.CART_ID, "cart-1", ShoppingState.CHECKOUT_ID, "co-1");
        t = PhaseTransitionGuard.apply(FunnelPhase.PAYMENT, withCheckout, List.of(ev("cancel_checkout", "co-1", "canceled", "", "")));
        assertThat(t.phase()).isEqualTo(FunnelPhase.CART);
        assertThat(t.checkoutId()).isEmpty();
        assertThat(t.cartId()).isEqualTo("cart-1");

        t = PhaseTransitionGuard.apply(FunnelPhase.PAYMENT, withCheckout, List.of(ev("complete_checkout", "co-1", "completed", "", "o-1")));
        assertThat(t.phase()).isEqualTo(FunnelPhase.DONE);
        assertThat(t.orderId()).isEqualTo("o-1");

        t = PhaseTransitionGuard.apply(FunnelPhase.CART, withCheckout, List.of(ev("cancel_cart", "cart-1", "canceled", "", "")));
        assertThat(t.phase()).isEqualTo(FunnelPhase.SEARCH);
        assertThat(t.cartId()).isEmpty();
        assertThat(t.checkoutId()).isEmpty();
    }

    @Test
    void erroDeToolNaoMoveOFunil() {
        var t = PhaseTransitionGuard.apply(FunnelPhase.CART, state(),
                List.of(new ToolEvidence("create_cart", true, "", "", "", "", "{\"error\":{}}")));
        assertThat(t.phase()).isEqualTo(FunnelPhase.SEARCH);
        assertThat(t.cartId()).isEmpty();
    }

    @Test
    void faseEnumTemFallback() {
        assertThat(FunnelPhase.fromName("xyz")).isEqualTo(FunnelPhase.DISCOVERY);
        assertThat(FunnelPhase.fromName("cart")).isEqualTo(FunnelPhase.CART);
        assertThat(Map.of()).isEmpty();
    }
}
