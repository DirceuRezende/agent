package br.com.dirceurezende.agent.graph.state;

public enum Intent {
    SEARCH, ADD_TO_CART, VIEW_CART, CANCEL_CART, BACK_TO_SEARCH,
    GO_TO_CHECKOUT, CANCEL_CHECKOUT, UPDATE_CHECKOUT, PAY, CHAT;

    public static Intent fromName(String name) {
        if (name == null) {
            return CHAT;
        }
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CHAT;
        }
    }
}
