package br.com.dirceurezende.agent.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import br.com.dirceurezende.agent.graph.tools.FunnelContext;
import br.com.dirceurezende.agent.graph.tools.ToolEvidence;
import br.com.dirceurezende.agent.graph.tools.ToolEvidenceExtractor;
import br.com.dirceurezende.agent.graph.tools.UcpToolCallback;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UcpToolCallbackTest {

    private static final String PROFILE = "http://localhost:8080/.well-known/ucp";
    private final List<McpSchema.CallToolRequest> requests = new ArrayList<>();

    @AfterEach
    void clear() {
        FunnelContext.clear();
    }

    private UcpToolCallback tool(String name, McpSchema.CallToolResult result) {
        McpSchema.Tool t = new McpSchema.Tool(name, null, name, Map.of("type", "object",
                "properties", Map.of("id", Map.of("type", "string"), "meta", Map.of("type", "object")),
                "required", List.of("meta", "id")), null, null, null);
        return new UcpToolCallback(t, req -> {
            requests.add(req);
            return result;
        }, PROFILE);
    }

    private static McpSchema.CallToolResult ok(String json) {
        return McpSchema.CallToolResult.builder().content(List.of(new McpSchema.TextContent(json))).isError(false).build();
    }

    @Test
    void removeMetaDoSchemaEInjetaOPerfil() {
        UcpToolCallback cb = tool("search_catalog", ok("{}"));
        assertThat(cb.getToolDefinition().inputSchema()).doesNotContain("meta").contains("\"id\"");

        cb.call("{\"query\":\"batom\"}");
        assertThat(requests.get(0).arguments()).containsEntry("meta", Map.of("ucp-agent", Map.of("profile", PROFILE)));
        assertThat(requests.get(0).arguments()).containsEntry("query", "batom");
    }

    @Test
    void forcaIdsDoEstadoERecusaIdDiferente() {
        FunnelContext.bind(new FunnelContext.Ids("cart-1", "co-1"));
        UcpToolCallback cancel = tool("cancel_checkout", ok("{\"id\":\"co-1\",\"status\":\"canceled\"}"));

        cancel.call("{}");
        assertThat(requests.get(0).arguments()).containsEntry("id", "co-1");

        String out = cancel.call("{\"id\":\"outro\"}");
        assertThat(out).contains("\"error\"").contains("invalid_argument");
        assertThat(requests).hasSize(1);

        UcpToolCallback create = tool("create_checkout", ok("{\"id\":\"co-2\"}"));
        create.call("{\"line_items\":[{\"x\":1}]}");
        assertThat(requests.get(1).arguments()).containsEntry("cart_id", "cart-1").containsEntry("line_items", List.of());
    }

    @Test
    void checkoutSemCarrinhoEErro() {
        FunnelContext.bind(new FunnelContext.Ids("", ""));
        assertThat(tool("create_checkout", ok("{}")).call("{}")).contains("\"error\"");
        assertThat(requests).isEmpty();
    }

    @Test
    void profileUnreachableViraMensagemAmigavel() {
        UcpToolCallback cb = new UcpToolCallback(
                new McpSchema.Tool("search_catalog", null, "d", Map.of("type", "object"), null, null, null),
                req -> {
                    throw new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(-32001, "profile_unreachable"));
                }, PROFILE);
        String out = cb.call("{}");
        assertThat(out).contains("profile_unreachable").contains("UCP_AGENT_PROFILE_URL");
        assertThat(ToolEvidenceExtractor.parse("search_catalog", out).error()).isTrue();
    }

    @Test
    void erroDeToolViraErroNormalizado() {
        McpSchema.CallToolResult err = McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent("falhou"))).isError(true).build();
        String out = tool("get_cart", err).call("{}");
        assertThat(out).contains("\"error\"");
    }

    @Test
    void extratorLeIdsStatusEPedido() {
        ToolEvidence cart = ToolEvidenceExtractor.parse("create_cart", "{\"cart\":{\"id\":\"c1\"}}");
        assertThat(cart.id()).isEqualTo("c1");
        ToolEvidence co = ToolEvidenceExtractor.parse("update_checkout",
                "{\"structuredContent\":{\"id\":\"k1\",\"status\":\"requires_escalation\",\"continue_url\":\"https://u\"}}");
        assertThat(co.id()).isEqualTo("k1");
        assertThat(co.status()).isEqualTo("requires_escalation");
        assertThat(co.continueUrl()).isEqualTo("https://u");
        assertThat(ToolEvidenceExtractor.parse("complete_checkout", "{\"order\":{\"id\":\"o9\"}}").orderId()).isEqualTo("o9");
        assertThat(ToolEvidenceExtractor.parse("cancel_checkout", "DENIED").error()).isTrue();
    }
}
