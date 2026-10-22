package br.com.dirceurezende.agent.ucp;

public class UcpDiscoveryException extends RuntimeException {

    public UcpDiscoveryException(String message) {
        super(message);
    }

    public UcpDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
