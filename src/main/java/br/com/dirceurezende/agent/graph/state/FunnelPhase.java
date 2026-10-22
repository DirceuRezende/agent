package br.com.dirceurezende.agent.graph.state;

public enum FunnelPhase {
    DISCOVERY, SEARCH, CART, CHECKOUT, PAYMENT, DONE;

    public static FunnelPhase fromName(String name) {
        if (name == null) {
            return DISCOVERY;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DISCOVERY;
        }
    }

    /** Id do nó do grafo que executa a fase. */
    public String nodeId() {
        return name().toLowerCase();
    }
}
