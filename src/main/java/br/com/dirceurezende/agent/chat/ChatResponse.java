package br.com.dirceurezende.agent.chat;

import java.util.List;

public record ChatResponse(String conversationId, String reply, String phase, String cartId, String checkoutId,
        String checkoutStatus, String continueUrl, boolean awaitingConfirmation, String pendingAction,
        List<String> visitedNodes) {}
