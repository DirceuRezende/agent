package br.com.dirceurezende.agent.graph.tools;

import java.util.Map;

import org.springframework.ai.tool.ToolCallback;

/** Origem das tools UCP de um endpoint (MCP em produção, fake nos testes). */
@FunctionalInterface
public interface ToolSource {

    Map<String, ToolCallback> toolsFor(String endpoint, boolean trustAll);
}
