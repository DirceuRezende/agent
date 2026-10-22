# Agente de compras UCP

Agente conversacional (Spring AI + LangGraph4j + MongoDB) que fala com um servidor **UCP** (Universal Commerce
Protocol, transporte MCP) e conduz o usuário por um funil determinístico
**discovery → intent → search → cart → checkout → payment**. O merchant de exemplo é a Boca Rosa.
O desenho completo está em [`docs/initial-plan.md`](docs/initial-plan.md).

Princípios: a fase vive no estado do grafo (não é decidida pelo LLM); as transições dependem de evidência de tool;
cada fase tem o próprio subconjunto de tools; ações sensíveis (`cancel_cart`, `cancel_checkout`,
`complete_checkout`) passam por confirmação humana e executam exatamente o tool call confirmado.

## Como rodar

Requisitos: Java 25, Docker (para o MongoDB) e uma chave da OpenAI.

```bash
export OPENAI_API_KEY=...
sh mvnw spring-boot:run      # sobe o mongo:8 do compose.yaml sozinho (spring-boot-docker-compose)
# abra http://localhost:8080
```

Sem Docker, aponte `spring.mongodb.uri` para um MongoDB existente.

Endpoints: `POST /api/chat`, `POST /api/chat/stream` (SSE), `GET /api/chat/{id}`, `GET /api/graph` (Mermaid) e
`GET /.well-known/ucp` (perfil do agente). Swagger em `/swagger-ui.html`.

## Perfil do agente e túnel

Todas as tools UCP exigem `meta.ucp-agent.profile`, e o **servidor baixa esse perfil a cada chamada**. Por padrão ele é
servido pelo próprio app em `http://localhost:8080/.well-known/ucp`, que a Shopify não alcança: as chamadas voltam com
`-32001 profile_unreachable` (o agente mostra uma mensagem explicando). Isso é esperado e confirma que a cadeia
funciona até o servidor. Para chamadas reais, exponha o app por um túnel:

```bash
cloudflared tunnel --url http://localhost:8080
UCP_AGENT_PROFILE_URL=https://<túnel>/.well-known/ucp sh mvnw spring-boot:run
```

## Testes

```bash
sh mvnw test
```

Unitários e de integração (ChatModel e tools UCP fake, `MemorySaver`) rodam sem rede. Os testes com Testcontainers
(`AgentApplicationTests`, `MongoApprovalRestartTest`, que cobre retomar a aprovação depois de recriar o saver) só
rodam com Docker disponível; sem ele são ignorados.

## Detalhes de implementação que importam

- **`releaseThread(false)`** em todo `CompileConfig`: o default do LangGraph4j 1.9.6 descarta os checkpoints quando a
  execução termina, e a conversa perderia o estado entre turnos.
- Cada fase é um `AgentExecutorEx` rodando como grafo filho com thread `{conversationId}:{fase}`, no mesmo MongoDB
  (`lg4j_thread`, `lg4j_checkpoint`). Dois `MongoDBSaver`s (um por tipo de estado) compartilham as coleções.
- O executor filho roda com `emitterExecutor(Runnable::run)`: as tools executam na thread do turno e enxergam o
  `FunnelContext` (ids de carrinho/checkout do estado, que `UcpToolCallback` força nos argumentos).
- Além das tools do plano, a fase `cart` recebe `cancel_checkout` (sensível) para cancelar um checkout ativo antes do
  carrinho.

## Limitações

- **Pagamento**: não há tokenização de cartão/Google Pay para um agente local. Em `ready_for_complete` com instrumento
  o agente propõe `complete_checkout` (com confirmação); caso contrário entrega o `continue_url` para o comprador
  concluir na loja.
- O formato de `.well-known/ucp` é lido de forma tolerante (serviço como lista ou objeto); os nomes dos argumentos de
  id das tools (`id`, `cart_id`) seguem o plano e não foram verificados contra o schema real do servidor.
- Fora de escopo: identity linking OAuth, assinatura HTTP, múltiplos merchants na UI, streaming token a token.
