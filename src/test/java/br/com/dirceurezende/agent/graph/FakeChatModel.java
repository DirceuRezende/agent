package br.com.dirceurezende.agent.graph;

import java.util.List;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** ChatModel de teste: a resposta é decidida por uma função sobre as mensagens do prompt. */
public class FakeChatModel implements ChatModel {

    private final Function<List<Message>, AssistantMessage> script;

    public FakeChatModel(Function<List<Message>, AssistantMessage> script) {
        this.script = script;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        AssistantMessage out = script.apply(prompt.getInstructions());
        return ChatResponse.builder().generations(List.of(new Generation(out))).build();
    }

    public static AssistantMessage text(String text) {
        return AssistantMessage.builder().content(text).build();
    }

    public static AssistantMessage toolCall(String name, String argsJson) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, argsJson)))
                .build();
    }
}
