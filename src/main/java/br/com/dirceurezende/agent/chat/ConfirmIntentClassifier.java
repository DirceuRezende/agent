package br.com.dirceurezende.agent.chat;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Heurística determinística PT-BR: negação é checada antes de afirmação. Verbos como "cancela" ficam de fora
 * de propósito: em "sim, cancela" eles confirmam a ação pendente.
 */
@Component
public class ConfirmIntentClassifier {

    public enum Decision { APPROVED, REJECTED, OTHER }

    private static final List<Pattern> NEGATIVE = List.of(
            p("nao"), p("n"), p("desiste"), p("desisti"), p("deixa pra la"), p("deixa quieto"),
            p("negativo"), p("recuso"), p("nem pensar"), p("espera"), p("aguarda"));

    private static final List<Pattern> POSITIVE = List.of(
            p("sim"), p("s"), p("confirmo"), p("confirma"), p("confirmar"), p("pode"), p("pode ser"), p("pode sim"),
            p("ok"), p("okay"), p("claro"), p("isso"), p("isso mesmo"), p("com certeza"), p("positivo"), p("manda ver"),
            p("vai"), p("bora"), p("aprovo"), p("aprovado"), p("beleza"), p("fechado"), p("yes"));

    private static Pattern p(String phrase) {
        return Pattern.compile("(^|\\s)" + Pattern.quote(phrase) + "(\\s|$)");
    }

    public Decision classify(String message) {
        String text = normalize(message);
        if (text.isBlank()) {
            return Decision.OTHER;
        }
        boolean negative = NEGATIVE.stream().anyMatch(pt -> pt.matcher(text).find());
        if (negative) {
            return Decision.REJECTED;
        }
        boolean positive = POSITIVE.stream().anyMatch(pt -> pt.matcher(text).find());
        return positive ? Decision.APPROVED : Decision.OTHER;
    }

    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }
}
