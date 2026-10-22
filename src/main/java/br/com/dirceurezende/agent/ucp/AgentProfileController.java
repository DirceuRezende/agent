package br.com.dirceurezende.agent.ucp;

import java.util.List;
import java.util.Map;

import br.com.dirceurezende.agent.config.UcpProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Perfil do agente que o servidor UCP baixa a cada chamada (meta.ucp-agent.profile). */
@RestController
public class AgentProfileController {

    private static final String VERSION = "2026-08-25";

    private final UcpProperties properties;

    public AgentProfileController(UcpProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/ucp")
    public Map<String, Object> profile() {
        return Map.of("ucp", Map.of(
                "version", VERSION,
                "services", Map.of("dev.ucp.shopping", List.of(Map.of(
                        "version", VERSION,
                        "transport", "mcp"))),
                "capabilities", List.of(
                        Map.of("name", "dev.ucp.shopping.checkout", "version", VERSION),
                        Map.of("name", "dev.ucp.shopping.cart", "version", VERSION),
                        Map.of("name", "dev.ucp.shopping.catalog", "version", VERSION),
                        Map.of("name", "dev.ucp.shopping.fulfillment", "version", VERSION),
                        Map.of("name", "dev.ucp.shopping.order", "version", VERSION)),
                "payment_handlers", Map.of(),
                "agent", Map.of("profile_url", properties.agent().profileUrl())));
    }
}
