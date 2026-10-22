package br.com.dirceurezende.agent.chat;

import br.com.dirceurezende.agent.chat.ConfirmIntentClassifier.Decision;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmIntentClassifierTest {

    private final ConfirmIntentClassifier classifier = new ConfirmIntentClassifier();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "sim|APPROVED", "Sim, pode cancelar|APPROVED", "sim, cancela|APPROVED", "confirmo|APPROVED",
            "ok!|APPROVED", "não|REJECTED", "Não, obrigado|REJECTED", "nao pode|REJECTED", "melhor não|REJECTED",
            "deixa pra lá|REJECTED", "hmm talvez|OTHER", "qual o prazo de entrega?|OTHER", "|OTHER"})
    void classifica(String message, Decision expected) {
        assertThat(classifier.classify(message)).isEqualTo(expected);
    }
}
