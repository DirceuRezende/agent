package br.com.dirceurezende.agent.chat;

public record ChatRequest(String conversationId, String message, Boolean confirm) {}
