(() => {
  const $ = (id) => document.getElementById(id);
  const messages = $('messages'), form = $('form'), input = $('input');
  const KEY = 'ucp-agent-conversation';
  let conversationId = null;
  try { conversationId = localStorage.getItem(KEY); } catch (e) { /* storage indisponível */ }
  let busy = false;

  function add(role, text) {
    const div = document.createElement('div');
    div.className = 'msg ' + role;
    div.textContent = text;
    messages.appendChild(div);
    messages.scrollTop = messages.scrollHeight;
  }

  function setBusy(value) {
    busy = value;
    form.querySelector('button').disabled = value;
    $('confirm-yes').disabled = value;
    $('confirm-no').disabled = value;
  }

  function render(state) {
    $('phase').textContent = state.phase;
    $('cart').textContent = state.cartId || '—';
    $('checkout').textContent = state.checkoutId || '—';
    $('status').textContent = state.checkoutStatus || '—';
    const link = $('continue');
    link.textContent = '';
    if (state.continueUrl) {
      const a = document.createElement('a');
      a.href = state.continueUrl; a.target = '_blank'; a.rel = 'noopener'; a.textContent = 'abrir na loja';
      link.appendChild(a);
    } else { link.textContent = '—'; }
    $('confirm').hidden = !state.awaitingConfirmation;
    $('confirm-text').textContent = state.pendingAction || '';
  }

  function trail(node, phase) {
    const s = document.createElement('span');
    s.textContent = phase ? `${node} (${phase})` : node;
    $('trail').appendChild(s);
  }

  async function send(body) {
    if (busy) return;
    setBusy(true);
    $('trail').textContent = '';
    try {
      const res = await fetch('/api/chat/stream', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ conversationId, ...body })
      });
      if (!res.ok || !res.body) throw new Error('HTTP ' + res.status);
      const reader = res.body.getReader(), decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          handle(buffer.slice(0, idx));
          buffer = buffer.slice(idx + 2);
        }
      }
    } catch (e) {
      add('error', 'Falha na requisição: ' + e.message);
    } finally { setBusy(false); input.focus(); }
  }

  function handle(raw) {
    let event = 'message', data = '';
    raw.split('\n').forEach((line) => {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data += line.slice(5).trim();
    });
    if (!data) return;
    const payload = JSON.parse(data);
    if (event === 'node') trail(payload.node, payload.phase);
    else if (event === 'message') {
      conversationId = payload.conversationId;
      try { localStorage.setItem(KEY, conversationId); } catch (e) { /* ignora */ }
      add('bot', payload.reply || '(sem resposta)');
      render(payload);
    } else if (event === 'error') add('error', payload.message);
  }

  async function restore() {
    if (!conversationId) return;
    try {
      const res = await fetch('/api/chat/' + encodeURIComponent(conversationId));
      if (!res.ok) return;
      const view = await res.json();
      view.history.forEach((h) => add(h.role === 'user' ? 'user' : 'bot', h.text));
      render(view.state);
    } catch (e) { /* conversa nova */ }
  }

  async function loadGraph() {
    try {
      const text = await (await fetch('/api/graph')).text();
      const el = $('graph');
      if (window.mermaid) {
        window.mermaid.initialize({ startOnLoad: false, theme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'default' });
        const { svg } = await window.mermaid.render('graph-svg', text);
        el.innerHTML = svg;
      } else { el.textContent = text; }
    } catch (e) { $('graph').textContent = 'grafo indisponível'; }
  }

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = input.value.trim();
    if (!text) return;
    add('user', text); input.value = '';
    send({ message: text });
  });
  $('confirm-yes').addEventListener('click', () => { add('user', 'Confirmar'); send({ message: 'sim', confirm: true }); });
  $('confirm-no').addEventListener('click', () => { add('user', 'Cancelar'); send({ message: 'não', confirm: false }); });
  $('new-chat').addEventListener('click', () => {
    conversationId = null;
    try { localStorage.removeItem(KEY); } catch (e) { /* ignora */ }
    messages.textContent = ''; $('trail').textContent = '';
    render({ phase: 'DISCOVERY' });
  });

  restore();
  loadGraph();
})();
