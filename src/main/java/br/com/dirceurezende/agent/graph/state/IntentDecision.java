package br.com.dirceurezende.agent.graph.state;

/** Saída estruturada do nó intent. O LLM só sugere; o roteamento é do {@code IntentRouter}. */
public record IntentDecision(Intent intent, String reason) {}
