package br.com.dirceurezende.agent.chat;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ShoppingGraphService service;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(ShoppingGraphService service) {
        this.service = service;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return service.chat(request);
    }

    /** SSE: um evento "node" por nó visitado, depois "message" e "done". */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        streams.submit(() -> {
            try {
                ChatResponse response = service.chat(request, (node, phase) -> send(emitter, "node",
                        Map.of("node", node, "phase", phase)));
                send(emitter, "message", response);
                send(emitter, "done", Map.of("conversationId", response.conversationId()));
                emitter.complete();
            } catch (Exception e) {
                send(emitter, "error", Map.of("message", String.valueOf(e.getMessage())));
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/chat/{id}")
    public ShoppingGraphService.ConversationView conversation(@PathVariable String id) {
        return service.conversation(id);
    }

    @GetMapping(value = "/graph", produces = MediaType.TEXT_PLAIN_VALUE)
    public String graph() {
        return service.mermaid();
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
