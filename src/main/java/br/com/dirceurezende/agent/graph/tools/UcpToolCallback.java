package br.com.dirceurezende.agent.graph.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Tool UCP sobre MCP: esconde {@code meta} do LLM, injeta {@code meta.ucp-agent.profile}, força os ids do funil
 * e normaliza erros em {@code {"error":{code,message,continue_url}}}.
 */
public class UcpToolCallback implements ToolCallback {

    static final String PROFILE_UNREACHABLE_HINT = "O servidor UCP não conseguiu baixar o perfil do agente "
            + "(profile_unreachable). Exponha o app por um túnel público (cloudflared/ngrok) e configure "
            + "UCP_AGENT_PROFILE_URL=https://<túnel>/.well-known/ucp.";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final McpSchema.Tool tool;
    private final Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> invoker;
    private final String profileUrl;
    private final ToolDefinition definition;

    public UcpToolCallback(McpSchema.Tool tool, Function<McpSchema.CallToolRequest, McpSchema.CallToolResult> invoker,
            String profileUrl) {
        this.tool = tool;
        this.invoker = invoker;
        this.profileUrl = profileUrl;
        this.definition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description() == null ? tool.name() : tool.description())
                .inputSchema(schemaWithoutMeta(tool.inputSchema()))
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> args = toolInput == null || toolInput.isBlank()
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(MAPPER.readValue(toolInput, MAP));
            String violation = enforceFunnelIds(args);
            if (violation != null) {
                return error("invalid_argument", violation, null);
            }
            // o servidor UCP declara "meta" como argumento da tool (e não como _meta do MCP)
            args.put("meta", Map.of("ucp-agent", Map.of("profile", profileUrl)));
            McpSchema.CallToolResult result = invoker.apply(new McpSchema.CallToolRequest(tool.name(), args));
            return render(result);
        } catch (McpError e) {
            var rpc = e.getJsonRpcError();
            return normalizeError(rpc == null ? null : rpc.code(), rpc == null ? e.getMessage() : rpc.message());
        } catch (Exception e) {
            return normalizeError(null, e.getMessage());
        }
    }

    /** Força os ids do estado do funil; id diferente vindo do LLM é erro. Devolve a violação ou null. */
    String enforceFunnelIds(Map<String, Object> args) {
        FunnelContext.Ids ids = FunnelContext.current();
        String name = tool.name();
        switch (name) {
            case "create_checkout" -> {
                if (ids.cartId().isBlank()) {
                    return "Não há carrinho ativo; crie o carrinho antes do checkout.";
                }
                Object given = args.get("cart_id");
                if (given != null && !ids.cartId().equals(given.toString())) {
                    return "cart_id diferente do carrinho ativo (" + ids.cartId() + ").";
                }
                args.put("cart_id", ids.cartId());
                args.put("line_items", List.of());
            }
            case "get_cart", "update_cart", "cancel_cart" -> {
                return forceId(args, ids.cartId(), "carrinho");
            }
            case "get_checkout", "update_checkout", "cancel_checkout", "complete_checkout" -> {
                return forceId(args, ids.checkoutId(), "checkout");
            }
            default -> { }
        }
        return null;
    }

    private String forceId(Map<String, Object> args, String activeId, String label) {
        if (activeId.isBlank()) {
            return "Não há " + label + " ativo.";
        }
        Object given = args.get("id");
        if (given != null && !activeId.equals(given.toString())) {
            return "id diferente do " + label + " ativo (" + activeId + ").";
        }
        args.put("id", activeId);
        return null;
    }

    private String render(McpSchema.CallToolResult result) throws Exception {
        String text = result.content() == null ? "" : result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
        if (Boolean.TRUE.equals(result.isError())) {
            return error("tool_error", text.isBlank() ? "A tool retornou erro." : text, null);
        }
        if (text.isBlank() && result.structuredContent() != null) {
            return MAPPER.writeValueAsString(result.structuredContent());
        }
        return text;
    }

    private String normalizeError(Integer code, String message) {
        String msg = message == null ? "" : message;
        if (msg.contains("invalid_profile_url")) {
            return error("invalid_profile_url", "O servidor UCP não recebeu uma URL de perfil válida em "
                    + "meta.ucp-agent.profile. Verifique UCP_AGENT_PROFILE_URL.", null);
        }
        if ((code != null && code == -32001) || msg.contains("profile_unreachable")) {
            return error("profile_unreachable", PROFILE_UNREACHABLE_HINT, null);
        }
        return error(code == null ? "tool_exception" : String.valueOf(code), msg.isBlank() ? "Falha ao chamar a tool." : msg, null);
    }

    static String error(String code, String message, String continueUrl) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", message);
        err.put("continue_url", continueUrl);
        try {
            return MAPPER.writeValueAsString(Map.of("error", err));
        } catch (Exception e) {
            return "{\"error\":{\"code\":\"" + code + "\"}}";
        }
    }

    @SuppressWarnings("unchecked")
    static String schemaWithoutMeta(Map<String, Object> schema) {
        try {
            Map<String, Object> copy = schema == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(MAPPER.convertValue(schema, MAP));
            if (copy.get("properties") instanceof Map<?, ?> props) {
                Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) props);
                p.remove("meta");
                copy.put("properties", p);
            } else {
                copy.putIfAbsent("type", "object");
                copy.putIfAbsent("properties", Map.of());
            }
            if (copy.get("required") instanceof List<?> req) {
                List<Object> r = new ArrayList<>(req);
                r.remove("meta");
                copy.put("required", r);
            }
            return MAPPER.writeValueAsString(copy);
        } catch (Exception e) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }
}
