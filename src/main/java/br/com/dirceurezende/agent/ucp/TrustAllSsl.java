package br.com.dirceurezende.agent.ucp;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** SSLContext que aceita qualquer certificado. Só para ambientes de estudo (ucp.merchants.*.ssl.trust-all). */
final class TrustAllSsl {

    private TrustAllSsl() {}

    static SSLContext context() {
        try {
            TrustManager[] all = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, all, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao criar SSLContext trust-all", e);
        }
    }
}
