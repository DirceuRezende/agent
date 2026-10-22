package br.com.dirceurezende.agent.config;

import br.com.dirceurezende.agent.graph.state.ShoppingState;
import com.mongodb.client.MongoClient;
import org.bsc.langgraph4j.checkpoint.MongoDBSaver;
import org.bsc.langgraph4j.spring.ai.agentexecutor.AgentExecutorEx;
import org.bsc.langgraph4j.spring.ai.serializer.jackson.SpringAIJacksonStateSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dois savers sobre o mesmo database/coleções (lg4j_thread, lg4j_checkpoint): cada um com o serializer do seu tipo
 * de estado. Os threads não colidem: {@code conversationId} (funil) e {@code conversationId:fase} (executores).
 */
@Configuration
public class CheckpointConfig {

    @Bean(destroyMethod = "")
    MongoDBSaver funnelSaver(MongoClient mongoClient, @Value("${ucp.checkpoint.database:ucp_agent}") String database) {
        return MongoDBSaver.builder()
                .mongoClient(mongoClient)
                .databaseName(database)
                .stateSerializer(new SpringAIJacksonStateSerializer<>(ShoppingState::new))
                .build();
    }

    @Bean(destroyMethod = "")
    MongoDBSaver phaseSaver(MongoClient mongoClient, @Value("${ucp.checkpoint.database:ucp_agent}") String database) {
        return MongoDBSaver.builder()
                .mongoClient(mongoClient)
                .databaseName(database)
                .stateSerializer(new SpringAIJacksonStateSerializer<>(AgentExecutorEx.State::new))
                .build();
    }
}
