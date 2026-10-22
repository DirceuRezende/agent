package br.com.dirceurezende.agent.graph.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Pergunta de confirmação montada dos argumentos reais do tool call pausado, nunca do texto do LLM. */
public final class PendingActionMessages {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PendingActionMessages() {}

    public static String describe(String tool, String argsJson, String fallbackId) {
        String id = idOf(argsJson, fallbackId);
        String suffix = id.isBlank() ? "" : " " + id;
        String action = switch (tool == null ? "" : tool) {
            case "cancel_cart" -> "Cancelar o carrinho" + suffix + "?";
            case "cancel_checkout" -> "Cancelar o checkout" + suffix + "?";
            case "complete_checkout" -> "Concluir a compra (checkout" + suffix + ")?";
            default -> "Executar a ação " + tool + "?";
        };
        return action + " Responda sim ou não.";
    }

    private static String idOf(String argsJson, String fallbackId) {
        try {
            if (argsJson != null && !argsJson.isBlank()) {
                JsonNode id = MAPPER.readTree(argsJson).get("id");
                if (id != null && id.isValueNode() && !id.asText().isBlank()) {
                    return id.asText();
                }
            }
        } catch (Exception ignored) {
            // cai no id do estado
        }
        return fallbackId == null ? "" : fallbackId;
    }
}
