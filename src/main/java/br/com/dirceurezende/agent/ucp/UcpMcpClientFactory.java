package br.com.dirceurezende.agent.ucp;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import br.com.dirceurezende.agent.config.UcpProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.stereotype.Component;

/** Cria e cacheia um {@link McpSyncClient} por endpoint descoberto (o endpoint é dinâmico). */
@Component
public class UcpMcpClientFactory {

    private final UcpProperties properties;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public UcpMcpClientFactory(UcpProperties properties) {
        this.properties = properties;
    }

    public McpSyncClient clientFor(String endpoint, boolean trustAll) {
        return clients.computeIfAbsent(endpoint, e -> create(e, trustAll));
    }

    private McpSyncClient create(String endpoint, boolean trustAll) {
        // endpoint completo (ex.: https://loja.myshopify.com/api/ucp/mcp): base + path separados pelo transport
        java.net.URI uri = java.net.URI.create(endpoint);
        String base = uri.getScheme() + "://" + uri.getAuthority();
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/mcp" : uri.getRawPath();

        HttpClient.Builder http = HttpClient.newBuilder();
        if (trustAll) {
            http.sslContext(TrustAllSsl.context());
        }
        long timeout = properties.mcp().requestTimeoutMs();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
                .clientBuilder(http)
                .endpoint(path)
                .connectTimeout(Duration.ofMillis(timeout))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMillis(timeout))
                .initializationTimeout(Duration.ofMillis(timeout))
                .build();
        client.initialize();
        return client;
    }

    public void evict(String endpoint) {
        McpSyncClient old = clients.remove(endpoint);
        if (old != null) {
            old.close();
        }
    }
}
