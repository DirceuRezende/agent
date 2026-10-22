package br.com.dirceurezende.agent.ucp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UcpDiscoveryServiceTest {

    /** Fixture no formato do perfil da Boca Rosa (UCP 2026-08-25, serviço com transporte mcp). */
    private static final String BOCA_ROSA = """
            {"ucp":{"version":"2026-08-25",
              "services":{"dev.ucp.shopping":[
                {"version":"2026-08-25","transport":"rest","endpoint":"https://totvs-sp-boca-rosa.myshopify.com/api/ucp/rest"},
                {"version":"2026-08-25","transport":"mcp","endpoint":"https://totvs-sp-boca-rosa.myshopify.com/api/ucp/mcp"}]},
              "capabilities":{"dev.ucp.shopping.catalog.search":[{"version":"2026-08-25"}],
                              "dev.ucp.shopping.cart":[{"version":"2026-08-25"}],
                              "dev.ucp.shopping.checkout":[{"version":"2026-08-25"}]}},
             "payment":{"handlers":[]}}
            """;

    @Test
    void escolheOEndpointMcpDoServicoShopping() {
        UcpProfile p = UcpDiscoveryService.parse("boca-rosa", "Boca Rosa", BOCA_ROSA);
        assertThat(p.mcpEndpoint()).isEqualTo("https://totvs-sp-boca-rosa.myshopify.com/api/ucp/mcp");
        assertThat(p.version()).isEqualTo("2026-08-25");
        assertThat(p.capabilities()).contains("dev.ucp.shopping.cart", "dev.ucp.shopping.checkout");
    }

    @Test
    void aceitaServicoComoObjetoComFilhoMcp() {
        String json = "{\"ucp\":{\"version\":\"v\",\"services\":{\"dev.ucp.shopping\":{\"mcp\":{\"endpoint\":\"https://m/mcp\"}}}}}";
        assertThat(UcpDiscoveryService.parse("k", "n", json).mcpEndpoint()).isEqualTo("https://m/mcp");
    }

    @Test
    void falhaSemTransporteMcp() {
        String json = "{\"ucp\":{\"services\":{\"dev.ucp.shopping\":[{\"transport\":\"rest\",\"endpoint\":\"https://r\"}]}}}";
        assertThatThrownBy(() -> UcpDiscoveryService.parse("k", "n", json))
                .isInstanceOf(UcpDiscoveryException.class).hasMessageContaining("mcp");
    }
}
