package br.com.dirceurezende.agent.graph.state;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.ai.chat.messages.Message;

/** Estado do funil. Canais String para manter a serialização trivial; "" significa vazio. */
public class ShoppingState extends MessagesState<Message> {

    public static final String PHASE = "phase";
    public static final String MERCHANT = "merchant";
    public static final String UCP_ENDPOINT = "ucp_endpoint";
    public static final String INTENT = "intent";
    public static final String CART_ID = "cart_id";
    public static final String CHECKOUT_ID = "checkout_id";
    public static final String CHECKOUT_STATUS = "checkout_status";
    public static final String CONTINUE_URL = "continue_url";
    public static final String ORDER_ID = "order_id";
    public static final String PENDING_PHASE = "pending_phase";
    public static final String PENDING_TOOL = "pending_tool";
    public static final String PENDING_ARGS = "pending_args";
    public static final String LAST_SEARCH_JSON = "last_search_json";
    /** Decisão explícita de confirmação (botão da UI): APPROVED, REJECTED ou vazio. */
    public static final String CONFIRM = "confirm";

    public static final Map<String, Channel<?>> SCHEMA = buildSchema();

    private static Map<String, Channel<?>> buildSchema() {
        Map<String, Channel<?>> schema = new HashMap<>(MessagesState.SCHEMA);
        schema.put(PHASE, Channels.base(() -> FunnelPhase.DISCOVERY.name()));
        for (String key : new String[] {MERCHANT, UCP_ENDPOINT, INTENT, CART_ID, CHECKOUT_ID, CHECKOUT_STATUS,
                CONTINUE_URL, ORDER_ID, PENDING_PHASE, PENDING_TOOL, PENDING_ARGS, LAST_SEARCH_JSON, CONFIRM}) {
            schema.put(key, Channels.base(() -> ""));
        }
        return Map.copyOf(schema);
    }

    public ShoppingState(Map<String, Object> init) {
        super(init);
    }

    public FunnelPhase phase() {
        return FunnelPhase.fromName(str(PHASE));
    }

    public String str(String key) {
        return this.<String>value(key).orElse("");
    }

    public Optional<String> pendingPhase() {
        String p = str(PENDING_PHASE);
        return p.isBlank() ? Optional.empty() : Optional.of(p);
    }

    public boolean hasCart() {
        return !str(CART_ID).isBlank();
    }

    public boolean hasCheckout() {
        return !str(CHECKOUT_ID).isBlank();
    }
}
