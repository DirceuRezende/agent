package br.com.dirceurezende.agent.ucp;

import java.util.List;

/** Resultado do discovery: o essencial do /.well-known/ucp do merchant. */
public record UcpProfile(String merchantKey, String merchantName, String version, String mcpEndpoint,
        List<String> capabilities) {}
