package br.com.dirceurezende.agent.graph;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.agent.AgentEx;
import org.bsc.langgraph4j.checkpoint.MongoDBSaver;
import org.bsc.langgraph4j.spring.ai.agentexecutor.AgentExecutorEx;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Spike com Mongo real: a aprovação pendente sobrevive à recriação do saver (simula restart). */
@Testcontainers(disabledWithoutDocker = true)
class MongoApprovalRestartTest {

    record Args(String id) {}

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8");

    private final AtomicInteger executions = new AtomicInteger();

    private CompiledGraph<AgentExecutorEx.State> executor(MongoClient client) throws Exception {
        var saver = MongoDBSaver.builder().mongoClient(client).databaseName("spike")
                .stateSerializer(new SpringAIJacksonStateSerializer<>(AgentExecutorEx.State::new)).build();
        var cancel = FunctionToolCallback.<Args, String>builder("cancel_checkout", a -> {
            executions.incrementAndGet();
            return "{\"id\":\"" + a.id() + "\",\"status\":\"canceled\"}";
        }).description("cancela checkout").inputType(Args.class).build();
        var model = new FakeChatModel(msgs -> {
            Message last = msgs.get(msgs.size() - 1);
            return last instanceof ToolResponseMessage ? FakeChatModel.text("feito")
                    : FakeChatModel.toolCall("cancel_checkout", "{\"id\":\"abc\"}");
        });
        return AgentExecutorEx.builder().chatModel(model).defaultSystem("teste").tool(cancel)
                .approvalOn("cancel_checkout", (node, state) -> InterruptionMetadata.builder(node, state)
                        .reason("approval").build())
                .build(CompileConfig.builder().checkpointSaver(saver).releaseThread(false).build());
    }

    @Test
    void retomaAprovacaoDepoisDeRecriarOSaver() throws Exception {
        var cfg = RunnableConfig.builder().threadId("restart").emitterExecutor(Runnable::run).build();
        try (MongoClient first = MongoClients.create(mongo.getConnectionString())) {
            executor(first).invoke(Map.of("messages", List.of(new UserMessage("cancela"))), cfg);
        }
        try (MongoClient second = MongoClients.create(mongo.getConnectionString())) {
            var exec = executor(second);
            assertThat(exec.getState(cfg).next()).startsWith("approval");
            assertThat(executions.get()).isZero();
            exec.invoke(GraphInput.resume(Map.of(AgentEx.APPROVAL_RESULT, "APPROVED")), cfg);
            assertThat(executions.get()).isEqualTo(1);
        }
    }
}
