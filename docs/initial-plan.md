# Plano: Agente de compras UCP com grafo determinístico (Spring AI + LangGraph4j + MongoDB)

## Context

O projeto `br.com.dirceurezende.agent` é só o esqueleto do Spring Initializr: Java 25, Boot 4.1.1, Spring AI 2.0.1, com os starters mongodb, webmvc, mcp-client, openai e springdoc. Ainda não tem código de domínio. Objetivo: um agente conversacional que fala com um servidor **UCP** (Universal Commerce Protocol, transporte MCP) e conduz o usuário por um funil determinístico **discovery → intent → search → cart → checkout → payment**. O funil permite voltar ou cancelar (cancelar carrinho, cancelar checkout, voltar para a busca), guarda o checkpoint do grafo no **MongoDB** e oferece uma **página de chat** para testar. O merchant de exemplo é a Boca Rosa.

Mesmos princípios do exemplo de referência: a fase fica no estado do grafo (não é decidida pelo LLM), as transições dependem de evidência de tool (nunca de texto do LLM), cada fase tem o próprio subconjunto de tools, e ações sensíveis passam por confirmação humana (HITL), executando exatamente o que foi confirmado.

## Fatos verificados

- **Boca Rosa**: `https://bocarosa.com.br/.well-known/ucp` → UCP `2026-08-25`, serviço `dev.ucp.shopping` com transporte `mcp` em `https://totvs-sp-boca-rosa.myshopify.com/api/ucp/mcp` (Streamable HTTP, JSON-RPC). Capabilities: catalog.search/lookup, cart, checkout, fulfillment, discount, order, buyer_consent, permalink (`https://bocarosa.com.br/buy`). Payment handlers: Google Pay, `shopify.card` e Shop Pay.
- **Tools MCP** (`tools/list` testado): `search_catalog`, `lookup_catalog`, `get_product`, `create_cart`, `get_cart`, `update_cart`, `cancel_cart`, `create_checkout` (aceita `cart_id` + `line_items: []`), `get_checkout`, `update_checkout`, `complete_checkout`, `cancel_checkout`, `get_order`. **Todas exigem `meta.ucp-agent.profile`** (URI do perfil do agente). Os preços vêm em minor units. O status do checkout vai de `incomplete` para `requires_escalation`, `ready_for_complete` ou `canceled`.
- **O servidor baixa o perfil do agente**: uma chamada com um perfil inacessível devolve `-32001 profile_unreachable`. **Decisão do usuário: servir o perfil no próprio app em localhost por enquanto.** Com `localhost`, a Shopify vai recusar as chamadas. Por isso a URL anunciada fica numa property (`ucp.agent.profile-url`) e basta apontá-la para um túnel (cloudflared/ngrok) sem mexer no código. Isso está documentado no README e no erro amigável.
- **Pagamento**: `complete_checkout` exige `payment.instruments[]` com credencial tokenizada de um handler (Shopify card vault ou Google Pay). Não há tokenização disponível para um agente local. O fluxo de payment então: (a) se o checkout está `ready_for_complete` e existe instrumento, ele propõe `complete_checkout` com HITL; (b) senão, ou em `requires_escalation`, entrega o `continue_url`/permalink para o comprador concluir na loja. Tokenização real fica fora de escopo.
- **LangGraph4j 1.9.6** (`org.bsc.langgraph4j`) é compilado contra Spring AI 2.0.1, a mesma versão do projeto. Artefatos: `langgraph4j-core`, `langgraph4j-spring-ai` e `langgraph4j-mongodb-saver`.
  - `MongoDBSaver.builder().mongoClient(client).databaseName(..).stateSerializer(..).build()` usa `mongodb-driver-sync`, reaproveita o `MongoClient` do Boot e não fecha esse client. Coleções: `lg4j_thread` e `lg4j_checkpoint`. O `registerInterruption` é real: grava `is_interrupted` e o motivo.
  - `SpringAIJacksonStateSerializer` grava `Message`s do Spring AI em JSON, legível no Mongo.
  - `InterruptibleAction.interrupt(nodeId, state, config)` → `Optional<InterruptionMetadata>`; `GraphInput.resume(Map)`.

## Design

### D1 — Estado (`ShoppingState extends MessagesState<Message>`)
Canais String (serialização trivial, compatível com checkpoints antigos): `PHASE` (default `DISCOVERY`), `MERCHANT`, `UCP_ENDPOINT`, `INTENT`, `CART_ID`, `CHECKOUT_ID`, `CHECKOUT_STATUS`, `CONTINUE_URL`, `ORDER_ID`, `PENDING_PHASE` (fase cujo executor está pausado em aprovação), `PENDING_TOOL`, `PENDING_ARGS` (só para exibir; a execução usa o tool call pausado no subgrafo), `LAST_SEARCH_JSON` (resumo compacto dos últimos resultados, ids de variantes para o LLM referenciar).
Enum `FunnelPhase { DISCOVERY, SEARCH, CART, CHECKOUT, PAYMENT, DONE }` com `fromName` e fallback.

### D2 — Topologia
```
START → discovery ─▶ intent ─[IntentRouter]─▶ search | cart | checkout | payment ──▶ END
                       (PENDING_PHASE presente → pula o intent e vai direto ao nó da fase pendente, em modo resume)

cada nó de fase = PhaseExecutorNode → AgentExecutorEx compilado da fase
     (agent ⇄ action_dispatcher ⇄ tools; approval_<tool> interrompe antes de tools sensíveis)
```
- **discovery** (determinístico, sem LLM): na primeira vez na thread, ou quando falta `UCP_ENDPOINT`, faz GET em `{discovery-url}/.well-known/ucp` (timeout-ms, trust-all), escolhe o serviço `dev.ucp.shopping` com transporte `mcp` e a versão suportada, e grava o endpoint e as capabilities. `UcpDiscoveryService` faz cache por merchant.
- **intent** (agent LLM com saída estruturada `entity(IntentDecision.class)`): classifica a mensagem em `SEARCH | ADD_TO_CART | VIEW_CART | CANCEL_CART | BACK_TO_SEARCH | GO_TO_CHECKOUT | CANCEL_CHECKOUT | UPDATE_CHECKOUT | PAY | CHAT`. O LLM só sugere. `IntentRouter` (função pura) cruza a sugestão com a fase atual numa **tabela de transições permitidas** e decide o nó de destino. Um intent inválido para a fase (ex.: PAY em SEARCH) cai no nó da fase atual, que explica o que falta. Se houver `PENDING_PHASE`, o entry router manda direto para o nó dessa fase, que retoma a aprovação.
- **search / cart / checkout / payment**: um `PhaseExecutorNode` parametrizado por fase. Cada fase tem seu **`AgentExecutorEx`** (`langgraph4j-springai-agentexecutor`) montado uma vez por `PhaseAgentRegistry`:
  ```java
  AgentExecutorEx.builder()
      .chatModel(chatModel)
      .defaultSystem(PromptCatalog.promptFor(phase))
      .tools(ToolsetComposer.forPhase(phase, ucpTools))      // subset da fase
      .approvalOn("complete_checkout", ApprovalMetadata::of) // uma por tool sensível
      .build(CompileConfig.builder().checkpointSaver(mongoSaver).build());
  ```
  - **Por que não é um subgrafo nativo** (`addNode(id, compiledGraph)`): o `AgentExecutorEx.Builder.build()` fixa `schema(State.SCHEMA)` e o tipo `AgentExecutorEx.State`, então o estado do funil não cabe nele. O nó pai invoca o executor como **grafo filho com thread próprio** (`threadId = "{conversationId}:{phase}"`), checkpointado no mesmo `MongoDBSaver`. O histórico da fase fica no thread filho, e o contexto do funil (cartId, checkoutId, status, últimos resultados) vai em um `SystemMessage` de contexto montado pelo nó a cada turno.
  - **Turno normal**: `executor.invoke(Map.of("messages", contexto + userMessage), childConfig)`. Ao terminar, o nó extrai do estado filho os `ToolResponseMessage`s deste turno (`ToolEvidenceExtractor`). O `PhaseTransitionGuard` (função pura) calcula a nova fase e os ids, e a última `AssistantMessage` é copiada para `messages` do pai.
  - **Aprovação pendente**: se o executor parar num nó `approval_<tool>` (`childSnapshot.next()`), o nó grava `PENDING_PHASE`, `PENDING_TOOL` e `PENDING_ARGS` (do `toolExecutionRequests$getFirst()`). A resposta ao usuário é montada **a partir dos argumentos reais do tool call** (`PendingActionMessages`, ex.: "Cancelar o checkout gid://…/Checkout/abc?"), não do texto do LLM.
  - **Resume**: `executor.invoke(GraphInput.resume(Map.of(AgentEx.APPROVAL_RESULT, APPROVED|REJECTED)), childConfig)`. Se APPROVED, o executor roda **exatamente o tool call pausado**. Se REJECTED, o próprio executor injeta "DENIED" e o LLM responde. Depois o guard roda e a pendência é limpa.

### D3 — Tabela fase → tools → evidência

| Nó | Tools | Evidência → fase |
|---|---|---|
| search | `search_catalog`, `lookup_catalog`, `get_product` | (fica em SEARCH) |
| cart | leituras do catálogo + `create_cart`, `update_cart`, `get_cart`, `cancel_cart` | `cart.id` → CART; `cancel_cart` ok → SEARCH (limpa CART_ID) |
| checkout | `get_cart`, `create_checkout` (força `cart_id`), `get_checkout`, `update_checkout`, `cancel_checkout` | `checkout.id` → CHECKOUT; status `ready_for_complete` ou `requires_escalation` → PAYMENT (grava `CONTINUE_URL`); `canceled` → CART |
| payment | `get_checkout`, `update_checkout`, `cancel_checkout`, `complete_checkout`[sensível] | `order.id` → DONE; `canceled` → CART |

"Voltar para search" vindo de CART/CHECKOUT muda só a fase e mantém o carrinho, sem chamar tools. "Cancelar checkout" chama `cancel_checkout` e volta para CART. "Cancelar carrinho" cancela antes o checkout ativo, se houver, e depois o carrinho. As duas remoções são **sensíveis** (HITL), igual a `complete_checkout`.

### D4 — Camada de tools sobre MCP
- `UcpMcpClientFactory`: cria um `McpSyncClient` programaticamente para o endpoint descoberto (`HttpClientStreamableHttpTransport`, `HttpClient` com SSLContext trust-all quando `ssl.trust-all=true`, `requestTimeout` próprio). Faz cache por endpoint. O starter mcp-client fica sem conexões estáticas, porque o endpoint é dinâmico.
- `UcpToolCallback` (decorator de `SyncMcpToolCallback`):
  1. **remove `meta` do inputSchema** exibido ao LLM e **injeta** `meta.ucp-agent.profile` em toda chamada;
  2. normaliza erros (JSON-RPC error / exceção) em `{"error":{code,message,continue_url}}`. O caso `profile_unreachable` vira uma mensagem que explica como configurar o túnel.
  - A evidência **não** precisa de decorator: os resultados das tools já ficam como `ToolResponseMessage` no estado do executor, e o `ToolEvidenceExtractor` faz o parse (`id`, `status`, `continue_url`, `order`).
- Os `create_checkout` sempre recebem `cart_id` do estado (o decorator força `cart_id` e `line_items: []`), e `cancel_*`/`complete_checkout` usam o id do estado. Se o LLM mandar outro id, o decorator responde com erro.
- `PhaseToolPolicy` + `ToolsetComposer` montam o toolset de cada fase a partir dos nomes reais e definem quais tools levam `approvalOn`.

### D5 — HITL (aprovação nativa do AgentExecutorEx)
Tools sensíveis: `cancel_cart`, `cancel_checkout`, `complete_checkout`. O nó `approval_<tool>` do executor interrompe antes de executar, e o `MongoDBSaver` persiste o checkpoint do thread filho. No turno seguinte, o pai vê `PENDING_PHASE` e chama o nó da fase em modo resume. O `ConfirmIntentClassifier` (heurística PT-BR determinística, com negação checada antes de afirmação) traduz a mensagem em `APPROVED`/`REJECTED`, e o botão da UI manda `confirm=true/false` direto. Uma resposta ambígua (OTHER) mantém a pendência e repete a pergunta, sem chamar o LLM.

### D6 — Persistência
`MongoDBSaver` (bean, com `MongoClient` do Boot e database `ucp_agent`) + `SpringAIJacksonStateSerializer<ShoppingState>` no grafo pai (o executor usa o default `SpringAIJacksonStateSerializer<AgentExecutorEx.State>`). Threads: `conversationId` (funil) e `conversationId:{phase}` (executor de cada fase). O histórico do chat sai do próprio checkpoint (`getState`). `compose.yaml` com `mongo:8` e a dependência `spring-boot-docker-compose` (sobe sozinho no `spring-boot:run`).

### D7 — API + UI
- `POST /api/chat` `{conversationId?, message, confirm?}` → `ChatResponse(conversationId, reply, phase, cartId, checkoutId, checkoutStatus, continueUrl, awaitingConfirmation, pendingAction, visitedNodes)`.
- `POST /api/chat/stream` (SSE): roda `graph.stream(...)` e emite um evento `node` por nó visitado (com a fase) e depois `message` e `done`. Streaming token a token fica para depois.
- `GET /api/chat/{id}` traz o histórico e o estado. `GET /api/graph` traz o diagrama Mermaid (`getGraph(MERMAID)`).
- `GET /.well-known/ucp` serve o **perfil do agente**: `ucp.version`, `services` (`dev.ucp.shopping`/mcp), `capabilities` (checkout, cart, catalog, fulfillment, order) e `payment_handlers: {}`.
- `src/main/resources/static/index.html` + `app.js` + `style.css`: chat com SSE, badge da fase, trilha dos nós visitados, painel cart/checkout (ids, status, link continue_url), botões **Confirmar/Cancelar** quando há confirmação pendente, botão "nova conversa" e diagrama Mermaid do grafo (mermaid via cdnjs).

## Configuração (`application.yaml`)
```yaml
spring.ai.openai: { api-key: ${OPENAI_API_KEY}, chat.options.model: gpt-4.1-mini }
spring.mongodb.uri: mongodb://localhost:27017/ucp_agent
ucp:
  agent.profile-url: ${UCP_AGENT_PROFILE_URL:http://localhost:8080/.well-known/ucp}
  mcp.request-timeout-ms: 15000
  default-merchant: boca-rosa
  merchants:
    boca-rosa: { name: Boca Rosa, discovery-url: https://bocarosa.com.br, ssl.trust-all: true, timeout-ms: 800 }
```
`timeout-ms` (800) vale para o fetch de discovery. As chamadas MCP de checkout usam `mcp.request-timeout-ms`, porque 800 ms é curto demais para elas.

## Arquivos (sob `src/main/java/br/com/dirceurezende/agent/`)
- `config/` — `UcpProperties` (@ConfigurationProperties), `CheckpointConfig` (MongoDBSaver), `ChatClientConfig`
- `ucp/` — `UcpDiscoveryService`, `UcpProfile` (records), `UcpMcpClientFactory`, `AgentProfileController`
- `graph/state/` — `ShoppingState`, `FunnelPhase`, `Intent`, `IntentDecision`
- `graph/nodes/` — `DiscoveryNode`, `IntentNode`, `PhaseExecutorNode`
- `graph/agents/` — `PhaseAgentRegistry` (um `CompiledGraph<AgentExecutorEx.State>` por fase), `PendingActionMessages`
- `graph/routing/` — `EntryRouter`, `IntentRouter`, `PhaseTransitionGuard`
- `graph/tools/` — `PhaseToolPolicy`, `ToolsetComposer`, `UcpToolCallback`, `ToolEvidenceExtractor`
- `graph/prompts/PromptCatalog`
- `graph/ShoppingGraphFactory` (monta o `StateGraph` e compila com o saver)
- `chat/` — `ShoppingGraphService` (invoke/stream/resume), `ConfirmIntentClassifier`, `ChatController`, `ChatResponse`, `ChatRequest`
- `resources/static/{index.html,app.js,style.css}`, `compose.yaml`, `README.md`
- `pom.xml`: `langgraph4j-bom:1.9.6` (import) + `langgraph4j-core`, `langgraph4j-spring-ai`, `langgraph4j-springai-agentexecutor`, `langgraph4j-mongodb-saver`, `spring-boot-docker-compose`, testcontainers-mongodb (test)

## Fases de implementação
- **Primeiro passo**: copiar este plano para `docs/initial-plan.md` no projeto.
0. **Spike** (`AgentExecutorApprovalSpikeTest`, Testcontainers Mongo + `ChatModel` fake que devolve um tool call): `AgentExecutorEx` com `approvalOn` + `MongoDBSaver` → para em `approval_<tool>`, `getState().next()` aponta para o nó de aprovação, `resume(APPROVAL_RESULT=APPROVED)` executa o tool call exato, `REJECTED` gera o "DENIED" e o processo retoma depois de recriar o saver (simulando restart). O teste fica como regressão. **Plano B** se falhar: um `PhaseAgentNode` próprio com `ChatClient` e decorator de confirmação.
1. **Primitivas puras**: estado, enums, `EntryRouter`/`IntentRouter` (tabela completa), `PhaseTransitionGuard`, `ConfirmIntentClassifier`, `PromptCatalog`, `ToolEvidenceExtractor` + testes unitários.
2. **UCP**: discovery (com teste contra um JSON fixture do perfil da Boca Rosa), client factory, `UcpToolCallback` (injeção de meta, remoção do schema, ids forçados do estado, normalização de erro), policy + testes com delegates fake.
3. **Grafo**: `PhaseAgentRegistry`, `PhaseExecutorNode`, factory e service (invoke/resume), com teste de integração usando `ChatModel` fake e cobrindo a jornada SEARCH→CART→CHECKOUT→PAYMENT, os cancelamentos e os desfechos APPROVED/REJECTED/ambíguo.
4. **API + perfil + UI**: controllers, SSE, página estática, teste MockMvc.
5. **README**: como rodar, túnel para o perfil, limitações (pagamento, perfil localhost).

## Verificação
1. `./mvnw test` com unitários + integração (Testcontainers Mongo; precisa do Docker que já existe no WSL).
2. `docker compose up -d` + `OPENAI_API_KEY=... ./mvnw spring-boot:run` → abrir `http://localhost:8080`.
3. Testar a discovery real: na primeira mensagem a UI mostra o nó `discovery` e o endpoint MCP da Boca Rosa.
4. Com perfil em localhost: a busca devolve o erro amigável `profile_unreachable`, o que é esperado e confirma que a cadeia funciona até o servidor. Com `UCP_AGENT_PROFILE_URL=https://<túnel>/.well-known/ucp`: "quero um batom" → resultados; "adiciona o primeiro" → fase CART; "fechar pedido" → CHECKOUT; informar e-mail/endereço → PAYMENT com `continue_url`; "cancela o checkout" → confirmação → CART; "volta pra busca" → SEARCH mantendo o carrinho.
5. Reiniciar o app no meio de uma confirmação pendente: a conversa retoma do checkpoint no Mongo (`lg4j_checkpoint`/`lg4j_thread.is_interrupted`).

## Fora de escopo
Tokenização real de cartão/Google Pay, identity linking OAuth (Shop accounts), assinatura HTTP de requests, múltiplos merchants na UI (a config já suporta), streaming token a token, Langfuse/evals.
