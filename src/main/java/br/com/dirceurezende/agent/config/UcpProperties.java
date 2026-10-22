package br.com.dirceurezende.agent.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ucp")
public record UcpProperties(Agent agent, Mcp mcp, String defaultMerchant, Map<String, Merchant> merchants) {

    public record Agent(String profileUrl) {}

    public record Mcp(long requestTimeoutMs) {}

    public record Ssl(boolean trustAll) {}

    public record Merchant(String name, String discoveryUrl, Ssl ssl, long timeoutMs) {

        public boolean trustAll() {
            return ssl != null && ssl.trustAll();
        }
    }

    public Merchant merchant(String key) {
        Merchant m = merchants == null ? null : merchants.get(key == null || key.isBlank() ? defaultMerchant : key);
        if (m == null) {
            throw new IllegalArgumentException("Merchant não configurado: " + key);
        }
        return m;
    }
}
