package br.com.dirceurezende.agent.ucp;

import java.util.LinkedHashMap;
import java.util.Map;

import br.com.dirceurezende.agent.config.UcpProperties;
import br.com.dirceurezende.agent.graph.tools.ToolSource;
import br.com.dirceurezende.agent.graph.tools.UcpToolCallback;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/** Lista as tools do servidor UCP e as embrulha em {@link UcpToolCallback}. */
@Component
public class UcpToolRegistry implements ToolSource {

    private final UcpMcpClientFactory clientFactory;
    private final UcpProperties properties;

    public UcpToolRegistry(UcpMcpClientFactory clientFactory, UcpProperties properties) {
        this.clientFactory = clientFactory;
        this.properties = properties;
    }

    @Override
    public Map<String, ToolCallback> toolsFor(String endpoint, boolean trustAll) {
        McpSyncClient client = clientFactory.clientFor(endpoint, trustAll);
        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        client.listTools().tools().forEach(t -> tools.put(t.name(),
                new UcpToolCallback(t, client::callTool, properties.agent().profileUrl())));
        return tools;
    }
}
