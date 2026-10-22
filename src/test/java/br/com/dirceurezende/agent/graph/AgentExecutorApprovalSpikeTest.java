package br.com.dirceurezende.agent.graph;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.InterruptionMetadata;
import org.bsc.langgraph4j.agent.AgentEx;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.spring.ai.agentexecutor.AgentExecutorEx;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

/** Spike: AgentExecutorEx + approvalOn para e retoma executando o tool call exato. */
class AgentExecutorApprovalSpikeTest {

    record Args(String id) {}

    static final ThreadLocal<String> TL = new ThreadLocal<>();
    static volatile String seenInTool;

    private final AtomicInteger executions = new AtomicInteger();

    private CompiledGraph<AgentExecutorEx.State> executor(BaseCheckpointSaver saver) throws Exception {
        ToolCallback cancel = FunctionToolCallback.<Args, String>builder("cancel_checkout", a -> {
            executions.incrementAndGet();
            seenInTool = TL.get();
            return "{\"id\":\"" + a.id() + "\",\"status\":\"canceled\"}";
        }).description("cancela checkout").inputType(Args.class).build();

        FakeChatModel model = new FakeChatModel(msgs -> {
            Message last = msgs.get(msgs.size() - 1);
            if (last instanceof ToolResponseMessage) {
                return FakeChatModel.text("feito");
            }
            if (last.getText() != null && last.getText().contains("DENIED")) {
                return FakeChatModel.text("ok, não cancelei");
            }
            return FakeChatModel.toolCall("cancel_checkout", "{\"id\":\"abc\"}");
        });

        return AgentExecutorEx.builder()
                .chatModel(model)
                .defaultSystem("teste")
                .tool(cancel)
                .approvalOn("cancel_checkout", (node, state) -> InterruptionMetadata.builder(node, state)
                        .reason("approval").build())
                .build(CompileConfig.builder().checkpointSaver(saver).releaseThread(false).build());
    }

    @Test
    void paraEmApprovalEExecutaToolCallExatoAoAprovar() throws Exception {
        var saver = new MemorySaver();
        var exec = executor(saver);
        var cfg = RunnableConfig.builder().threadId("t1").emitterExecutor(Runnable::run).build();

        exec.invoke(Map.of("messages", List.of(new UserMessage("cancela"))), cfg);

        var snap = exec.getState(cfg);
        assertThat(snap.next()).isNotBlank();
        assertThat(snap.next()).contains("approval");
        assertThat(executions.get()).isZero();
        assertThat(snap.state().toolExecutionRequests$getFirst().get().arguments()).contains("abc");

        TL.set("bound");
        var out = exec.invoke(GraphInput.resume(Map.of(AgentEx.APPROVAL_RESULT, "APPROVED")), cfg);
        TL.remove();
        System.out.println("TL-SEEN: " + seenInTool);
        assertThat(executions.get()).isEqualTo(1);
        assertThat(out.get().lastMessage().get().getText()).isEqualTo("feito");
    }

    @Test
    void rejeitarNaoExecutaTool() throws Exception {
        var exec = executor(new MemorySaver());
        var cfg = RunnableConfig.builder().threadId("t2").build();
        exec.invoke(Map.of("messages", List.of(new UserMessage("cancela"))), cfg);
        var out = exec.invoke(GraphInput.resume(Map.of(AgentEx.APPROVAL_RESULT, "REJECTED")), cfg);
        assertThat(executions.get()).isZero();
        System.out.println("REJECTED-LAST: " + out.get().lastMessage().get());
    }
}
