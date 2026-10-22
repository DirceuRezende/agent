package br.com.dirceurezende.agent.graph.tools;

/** Fato extraído do resultado de uma tool. Só isso move o funil, nunca o texto do LLM. */
public record ToolEvidence(String tool, boolean error, String id, String status, String continueUrl,
        String orderId, String raw) {

    public boolean ok() {
        return !error;
    }
}
