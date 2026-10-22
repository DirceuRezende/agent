package br.com.dirceurezende.agent.graph.tools;

/**
 * Ids do funil visíveis às tools durante um turno. Fica em ThreadLocal: o nó de fase roda o executor com um
 * executor direto (mesma thread), então as tools enxergam o contexto do turno corrente.
 */
public final class FunnelContext {

    public record Ids(String cartId, String checkoutId) {}

    private static final ThreadLocal<Ids> CURRENT = new ThreadLocal<>();

    private FunnelContext() {}

    public static void bind(Ids ids) {
        CURRENT.set(ids);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Ids current() {
        Ids ids = CURRENT.get();
        return ids == null ? new Ids("", "") : ids;
    }
}
