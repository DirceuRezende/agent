package br.com.dirceurezende.agent.graph.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

public final class ToolEvidenceExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> READ_ONLY_TEXT = Set.of("search_catalog", "lookup_catalog", "get_product");

    private ToolEvidenceExtractor() {}

    /** Evidências das tools executadas depois da última mensagem do usuário (o turno corrente). */
    public static List<ToolEvidence> ofTurn(List<Message> messages) {
        int lastUser = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                lastUser = i;
            }
        }
        List<ToolEvidence> out = new ArrayList<>();
        for (int i = lastUser + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    out.add(parse(r.name(), r.responseData()));
                }
            }
        }
        return out;
    }

    public static ToolEvidence parse(String tool, String data) {
        JsonNode root = readJson(data);
        if (root == null || !root.isObject()) {
            // Texto não-JSON (ex.: "DENIED" do HITL) só vale como resultado para as tools de leitura do catálogo.
            return new ToolEvidence(tool, !READ_ONLY_TEXT.contains(tool), "", "", "", "", data);
        }
        if (root.has("error")) {
            return new ToolEvidence(tool, true, "", "", text(root.path("error"), "continue_url"), "", data);
        }
        JsonNode body = unwrap(root);
        String id = firstText(body, "id");
        if (id.isBlank()) {
            id = firstText(body.path("cart"), "id");
        }
        if (id.isBlank()) {
            id = firstText(body.path("checkout"), "id");
        }
        String status = firstText(body, "status");
        if (status.isBlank()) {
            status = firstText(body.path("checkout"), "status");
        }
        String continueUrl = firstText(body, "continue_url");
        String orderId = firstText(body.path("order"), "id");
        if (orderId.isBlank()) {
            orderId = firstText(body, "order_id");
        }
        return new ToolEvidence(tool, false, id, status, continueUrl, orderId, data);
    }

    private static JsonNode unwrap(JsonNode root) {
        JsonNode structured = root.path("structuredContent");
        return structured.isObject() ? structured : root;
    }

    private static String text(JsonNode node, String field) {
        return firstText(node, field);
    }

    private static String firstText(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? "" : v.asText();
    }

    private static JsonNode readJson(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(data);
            // Spring AI pode devolver o resultado como string JSON embrulhada em aspas.
            if (node != null && node.isTextual()) {
                return MAPPER.readTree(node.asText());
            }
            return node;
        } catch (Exception e) {
            return null;
        }
    }
}
