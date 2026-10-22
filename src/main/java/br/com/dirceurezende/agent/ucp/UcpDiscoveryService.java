package br.com.dirceurezende.agent.ucp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.dirceurezende.agent.config.UcpProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/** Busca {@code {discovery-url}/.well-known/ucp} e extrai o endpoint MCP do serviço dev.ucp.shopping. */
@Service
public class UcpDiscoveryService {

    static final String SHOPPING_SERVICE = "dev.ucp.shopping";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UcpProperties properties;
    private final Map<String, UcpProfile> cache = new ConcurrentHashMap<>();

    public UcpDiscoveryService(UcpProperties properties) {
        this.properties = properties;
    }

    public UcpProfile discover(String merchantKey) {
        String key = merchantKey == null || merchantKey.isBlank() ? properties.defaultMerchant() : merchantKey;
        return cache.computeIfAbsent(key, this::fetch);
    }

    private UcpProfile fetch(String key) {
        UcpProperties.Merchant merchant = properties.merchant(key);
        String base = merchant.discoveryUrl().replaceAll("/+$", "");
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(merchant.timeoutMs(), 1)))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (merchant.trustAll()) {
            builder.sslContext(TrustAllSsl.context());
        }
        try (HttpClient client = builder.build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/.well-known/ucp"))
                    .timeout(Duration.ofMillis(Math.max(merchant.timeoutMs(), 1)))
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new UcpDiscoveryException("Discovery HTTP " + response.statusCode() + " em " + base);
            }
            return parse(key, merchant.name(), response.body());
        } catch (UcpDiscoveryException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UcpDiscoveryException("Discovery interrompido", e);
        } catch (Exception e) {
            throw new UcpDiscoveryException("Falha no discovery de " + base + ": " + e.getMessage(), e);
        }
    }

    /** Aceita serviço como lista de entradas ({transport, endpoint}) ou objeto com filho {@code mcp}. */
    public static UcpProfile parse(String merchantKey, String merchantName, String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode ucp = root.has("ucp") ? root.get("ucp") : root;
            String version = ucp.path("version").asText("");
            String endpoint = findMcpEndpoint(ucp.path("services").path(SHOPPING_SERVICE));
            if (endpoint == null || endpoint.isBlank()) {
                throw new UcpDiscoveryException("Perfil sem serviço " + SHOPPING_SERVICE + " com transporte mcp");
            }
            return new UcpProfile(merchantKey, merchantName, version, endpoint, capabilities(ucp.path("capabilities")));
        } catch (UcpDiscoveryException e) {
            throw e;
        } catch (Exception e) {
            throw new UcpDiscoveryException("Perfil UCP inválido: " + e.getMessage(), e);
        }
    }

    private static String findMcpEndpoint(JsonNode service) {
        if (service.isArray()) {
            for (JsonNode entry : service) {
                if ("mcp".equalsIgnoreCase(entry.path("transport").asText())) {
                    return entry.path("endpoint").asText(null);
                }
            }
            return null;
        }
        if (service.has("mcp")) {
            return service.path("mcp").path("endpoint").asText(null);
        }
        if ("mcp".equalsIgnoreCase(service.path("transport").asText())) {
            return service.path("endpoint").asText(null);
        }
        return null;
    }

    private static List<String> capabilities(JsonNode caps) {
        List<String> names = new ArrayList<>();
        if (caps.isObject()) {
            caps.fieldNames().forEachRemaining(names::add);
        } else if (caps.isArray()) {
            for (JsonNode c : caps) {
                names.add(c.isTextual() ? c.asText() : c.path("name").asText());
            }
        }
        return names;
    }
}
