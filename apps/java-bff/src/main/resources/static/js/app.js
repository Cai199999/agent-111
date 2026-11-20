const ACTOR = '10001';
const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED']);

const state = {
  sessions: [],
  currentSession: null,
  streaming: false,
  taskEls: new Map(),
  taskData: new Map(),
  polls: new Map(),
};

const els = {
  sessionList: document.getElementById('sessionList'),
  agentStatus: document.getElementById('agentStatus'),
  messages: document.getElementById('messages'),
  input: document.getElementById('input'),
  send: document.getElementById('send'),
  newSession: document.getElementById('newSession'),
  chatTitle: document.getElementById('chatTitle'),
  chatSub: document.getElementById('chatSub'),
  toast: document.getElementById('toast'),
};

const QUICK_PROMPTS = [
  '我有哪些待处理工单？',
  '查一下 GD20261119000107 详情',
  '把 GD20261119000107 派给 10002',
  '报修：3栋2单元水管漏水',
  '合同审查规则有哪些？',
  '审查合同 10001',
  '查一下知识库',
  '审查合同 HT-2026-001',
];

init();

async function init() {
  bindEvents();
  try {
    await loadAgents();
    await loadSessions();
    if (!state.sessions.length) {
      await createSession();
    } else {
      await selectSession(state.sessions[0].sessionNo);
    }
  } catch (error) {
    showToast(error.message || String(error));
  }
}

function bindEvents() {
  els.newSession.addEventListener('click', () => createSession().catch((e) => showToast(e.message)));
  els.send.addEventListener('click', sendMessage);
  els.input.addEventListener('input', autoResize);
  els.input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      sendMessage();
    }
  });
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'X-Actor-Id': ACTOR,
      ...(options.headers || {}),
    },
  });
  const text = await response.text();
  let body = {};
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    body = {};
  }
  if (!response.ok || (body.code && body.code !== 'OK')) {
    throw new Error(body.message || `HTTP ${response.status}`);
  }
  return body.data;
}

async function loadAgents() {
  const agents = await api('/api/v1/admin/agents');
  els.agentStatus.replaceChildren(...agents.map((agent) => {
    const chip = el('div', 'agent-chip');
    const name = el('div', 'name');
    name.append(el('span', 'dot'), el('span', '', agent.displayName.replace('（Mock）', '')));
    chip.append(name, el('span', 'ver', `v${agent.contractVersion}`));
    return chip;
  }));
}

async function loadSessions() {
  state.sessions = await api('/api/v1/chat/sessions');
  renderSessions();
}

async function createSession() {
  const session = await api('/api/v1/chat/sessions', {
    method: 'POST',
    body: JSON.stringify({ title: '新会话' }),
  });
  state.sessions.unshift(session);
  renderSessions();
  await selectSession(session.sessionNo);
  els.input.focus();
}

async function selectSession(sessionNo) {
  const session = state.sessions.find((item) => item.sessionNo === sessionNo);
  if (!session) return;
  state.currentSession = session;
  els.chatTitle.textContent = session.title || '新会话';
  els.chatSub.textContent = `${session.messageCount || 0} 条消息 · 物业 + 合同`;
  renderSessions();
  await loadMessages(sessionNo);
}

function renderSessions() {
  els.sessionList.replaceChildren(...state.sessions.map((session) => {
    const item = el('button', 'session-item');
    item.type = 'button';
    if (state.currentSession && state.currentSession.sessionNo === session.sessionNo) {
      item.classList.add('active');
    }
    item.append(
      el('span', 'title', session.title || '新会话'),
      el('span', 'count', String(session.messageCount || 0)),
    );
    item.addEventListener('click', () => selectSession(session.sessionNo));
    return item;
  }));
}

async function loadMessages(sessionNo) {
  const messages = await api(`/api/v1/chat/sessions/${sessionNo}/messages`);
  clearMessages();
  if (!messages.length) {
    showEmptyState();
    return;
  }
  for (const message of messages) {
    if (message.role === 'USER') {
      appendUserMessage(message.content);
      continue;
    }
    const assistant = appendAssistantMessage(message.content || '');
    const metadata = message.metadata || {};
    if (metadata.capabilityId) {
      showRoute(assistant, metadata.capabilityId, null, '历史消息');
    }
    if (metadata.taskNo) {
      try {
        const task = await api(`/api/v1/tasks/${metadata.taskNo}`);
        renderTaskCard(assistant.cards, task);
      } catch {
        // 历史任务可能已被清理，忽略即可
      }
    }
  }
  scrollToBottom();
}

function clearMessages() {
  els.messages.replaceChildren();
  state.taskEls.clear();
  state.taskData.clear();
  state.polls.forEach((timer) => clearInterval(timer));
  state.polls.clear();
}

function showEmptyState() {
  const empty = el('div', 'empty');
  empty.append(
    el('h2', '', '一句话，查工单或审合同'),
    el('p', '', '原型使用 Mock Adapter，验证路由、确认、异步与取消链路。'),
  );
  const grid = el('div', 'quick-grid');
  for (const prompt of QUICK_PROMPTS) {
    const button = el('button', 'quick', prompt);
    button.type = 'button';
    button.addEventListener('click', () => {
      els.input.value = prompt;
      autoResize();
      sendMessage();
    });
    grid.append(button);
  }
  empty.append(grid);
  els.messages.append(empty);
}

async function sendMessage() {
  const content = els.input.value.trim();
  if (!content || state.streaming) return;
  if (!state.currentSession) {
    await createSession();
  }

  els.messages.querySelector('.empty')?.remove();
  els.input.value = '';
  autoResize();
  setSending(true);
  state.streaming = true;

  appendUserMessage(content);
  const assistant = appendAssistantMessage('');
  const clientMessageId = `cm_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

  try {
    const response = await fetch(`/api/v1/chat/sessions/${state.currentSession.sessionNo}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'X-Actor-Id': ACTOR,
      },
      body: JSON.stringify({ content, clientMessageId }),
    });
    if (!response.ok || !response.body) {
      throw new Error(`HTTP ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let finished = false;

    while (!finished) {
      const chunk = await reader.read();
      if (chunk.done) break;
      buffer += decoder.decode(chunk.value, { stream: true });
      let index;
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        const raw = buffer.slice(0, index);
        buffer = buffer.slice(index + 2);
        const event = parseSse(raw);
        if (!event) continue;
        const data = event.data ? safeJson(event.data) : {};
        handleEvent(event.event, data, assistant);
        if (event.event === 'done') {
          finished = true;
        }
      }
    }
  } catch (error) {
    appendDelta(assistant, `\n[请求失败] ${error.message}`);
    showToast(error.message || '请求失败');
  } finally {
    state.streaming = false;
    setSending(false);
    els.input.focus();
    await loadSessions().catch(() => {});
    if (state.currentSession) {
      const latest = state.sessions.find((item) => item.sessionNo === state.currentSession.sessionNo);
      if (latest) {
        state.currentSession = latest;
        els.chatTitle.textContent = latest.title || '新会话';
        els.chatSub.textContent = `${latest.messageCount || 0} 条消息 · 物业 + 合同`;
      }
    }
  }
}

function handleEvent(event, data, assistant) {
  switch (event) {
    case 'meta':
      break;
    case 'route':
      showRoute(assistant, data.capabilityId, data.confidence, data.reason);
      break;
    case 'delta':
      appendDelta(assistant, data.content || '');
      break;
    case 'task':
      renderTaskCard(assistant.cards, data);
      break;
    case 'task_event': {
      const task = state.taskData.get(data.taskNo);
      if (task) {
        task.events = task.events || [];
        if (!task.events.some((item) => item.seq === data.seq)) {
          task.events.push(data);
        }
        updateTaskCard(task);
      }
      break;
    }
    case 'confirm': {
      const task = state.taskData.get(data.taskNo) || {
        taskNo: data.taskNo,
        status: 'WAITING_CONFIRM',
        cancelSupported: true,
        capabilityId: '',
        agentKey: '',
      };
      task.status = 'WAITING_CONFIRM';
      task.confirmation = data.confirmation;
      state.taskData.set(data.taskNo, task);
      const container = state.taskEls.get(data.taskNo) || assistant.cards;
      renderTaskCard(container, task);
      break;
    }
    case 'done':
      break;
    case 'error':
      appendDelta(assistant, `\n[错误] ${data.message || data.code || '未知错误'}`);
      showToast(data.message || data.code || '请求失败');
      break;
    default:
      break;
  }
}

function parseSse(raw) {
  let eventName = 'message';
  const data = [];
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).trimStart());
    }
  }
  if (!data.length && eventName === 'message') return null;
  return { event: eventName, data: data.join('\n') };
}

function safeJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return {};
  }
}

function appendUserMessage(content) {
  const root = el('div', 'message user');
  root.append(el('div', 'avatar', '我'), wrapBubble([el('div', 'text', content)]));
  els.messages.append(root);
  scrollToBottom();
}

function appendAssistantMessage(initialText) {
  const root = el('div', 'message assistant');
  const bubble = el('div', 'bubble');
  const route = el('div', 'route-badge');
  route.hidden = true;
  const text = el('div', 'text', initialText || '');
  const cards = el('div', 'cards');
  bubble.append(route, text, cards);
  root.append(el('div', 'avatar', 'AI'), bubble);
  els.messages.append(root);
  scrollToBottom();
  return { root, bubble, route, text, cards };
}

function wrapBubble(children) {
  const bubble = el('div', 'bubble');
  bubble.append(...children);
  return bubble;
}

function showRoute(assistant, capabilityId, confidence, reason) {
  assistant.route.hidden = false;
  assistant.route.replaceChildren(
    el('span', '', '路由'),
    el('strong', '', capabilityId || '未识别'),
    el('span', '', confidence != null ? `· ${Math.round(confidence * 100)}%` : ''),
    el('span', '', reason ? `· ${reason}` : ''),
  );
}

function appendDelta(assistant, content) {
  if (!content) return;
  assistant.text.textContent += content;
  scrollToBottom();
}

function renderTaskCard(container, task) {
  if (!container || !task || !task.taskNo) return;
  const taskNo = task.taskNo;
  state.taskEls.set(taskNo, container);
  state.taskData.set(taskNo, task);
  container.replaceChildren();

  const card = el('div', 'task-card');
  const head = el('div', 'task-head');
  const left = el('div');
  left.append(
    el('div', 'task-title', capabilityLabel(task.capabilityId)),
    el('div', 'task-meta', `${taskNo}${task.agentKey ? ` · ${task.agentKey}` : ''}`),
  );
  head.append(left, el('span', `status ${task.status || 'PENDING'}`, statusLabel(task.status)));

  const body = el('div', 'task-body');
  const progress = Number(task.progress || 0);
  if (task.progress != null || ['RUNNING', 'PENDING'].includes(task.status)) {
    const bar = el('div', 'progress');
    const fill = el('span');
    fill.style.width = `${Math.max(0, Math.min(100, progress))}%`;
    bar.append(fill);
    body.append(bar);
  }

  if (task.status === 'WAITING_CONFIRM' && task.confirmation) {
    body.append(renderConfirm(task));
  }

  const actions = el('div', 'actions');
  const cancelable = task.cancelable !== undefined
    ? task.cancelable
    : (task.cancelSupported !== false && !TERMINAL.has(task.status));
  if (cancelable) {
    const cancel = el('button', 'btn danger small', '取消任务');
    cancel.type = 'button';
    cancel.addEventListener('click', () => cancelTask(taskNo));
    actions.append(cancel);
  }
  if (actions.childElementCount) body.append(actions);

  if (Array.isArray(task.events) && task.events.length) {
    const events = el('div', 'events');
    for (const event of task.events.slice(-4)) {
      const row = el('div', 'event-row');
      row.append(
        el('span', 'seq', `#${event.seq}`),
        el('span', 'type', event.type || 'EVENT'),
        el('span', '', event.payload?.message || event.payload?.step || statusLabel(event.status)),
      );
      events.append(row);
    }
    body.append(events);
  }

  card.append(head, body);
  container.append(card);

  if (['PENDING', 'RUNNING'].includes(task.status)) {
    startPolling(taskNo);
  }
}

function renderConfirm(task) {
  const confirmation = task.confirmation;
  const box = el('div', 'confirm-box');
  const title = el('div', 'confirm-title');
  title.append(
    el('span', '', confirmation.title || '操作确认'),
    el('span', 'risk', confirmation.riskLevel || 'MEDIUM'),
  );
  box.append(title);

  const fields = el('div', 'confirm-fields');
  for (const field of confirmation.fields || []) {
    const row = el('div');
    row.append(el('span', '', field.label), el('span', '', String(field.value)));
    fields.append(row);
  }
  box.append(fields);

  const actions = el('div', 'actions');
  const approve = el('button', 'btn ok small', '同意');
  const reject = el('button', 'btn danger small', '拒绝');
  approve.type = 'button';
  reject.type = 'button';
  approve.addEventListener('click', () => confirmTask(task.taskNo, 'APPROVE'));
  reject.addEventListener('click', () => confirmTask(task.taskNo, 'REJECT'));
  actions.append(approve, reject);
  box.append(actions);
  return box;
}

async function confirmTask(taskNo, decision) {
  try {
    const task = await api(`/api/v1/tasks/${taskNo}/confirm`, {
      method: 'POST',
      body: JSON.stringify({ decision, comment: decision === 'APPROVE' ? '同意' : '拒绝' }),
    });
    updateTaskCard(task);
    if (['PENDING', 'RUNNING'].includes(task.status)) {
      startPolling(taskNo);
    } else {
      stopPolling(taskNo);
    }
    showToast(decision === 'APPROVE' ? '已确认' : '已拒绝');
  } catch (error) {
    showToast(error.message || '确认失败');
  }
}

async function cancelTask(taskNo) {
  try {
    const task = await api(`/api/v1/tasks/${taskNo}/cancel`, {
      method: 'POST',
      body: JSON.stringify({ reason: '用户取消' }),
    });
    stopPolling(taskNo);
    updateTaskCard(task);
    showToast('任务已取消');
  } catch (error) {
    showToast(error.message || '取消失败');
  }
}

function startPolling(taskNo) {
  if (state.polls.has(taskNo)) return;
  const timer = setInterval(async () => {
    try {
      const task = await api(`/api/v1/tasks/${taskNo}`);
      updateTaskCard(task);
      if (TERMINAL.has(task.status) || task.status === 'WAITING_CONFIRM') {
        stopPolling(taskNo);
      }
    } catch (error) {
      stopPolling(taskNo);
      showToast(error.message || '任务查询失败');
    }
  }, 1000);
  state.polls.set(taskNo, timer);
}

function stopPolling(taskNo) {
  const timer = state.polls.get(taskNo);
  if (timer) {
    clearInterval(timer);
    state.polls.delete(taskNo);
  }
}

function updateTaskCard(task) {
  state.taskData.set(task.taskNo, task);
  const container = state.taskEls.get(task.taskNo);
  if (container) {
    renderTaskCard(container, task);
  }
}

function capabilityLabel(capabilityId) {
  const labels = {
    'property.ticket.query': '物业 · 查询工单',
    'property.ticket.detail': '物业 · 工单详情',
    'property.ticket.create': '物业 · 创建工单',
    'property.ticket.assign': '物业 · 派单',
    'property.knowledge.search': '物业 · 知识检索',
    'contract.rule.list': '合同 · 审查规则',
    'contract.rule.create': '合同 · 创建规则',
    'contract.knowledge.search': '合同 · 知识检索',
    'contract.resolve': '合同 · 解析合同',
    'contract.review': '合同 · 审查合同',
  };
  return labels[capabilityId] || capabilityId || '任务';
}

function statusLabel(status) {
  const labels = {
    PENDING: '待执行',
    RUNNING: '执行中',
    WAITING_CONFIRM: '待确认',
    SUCCEEDED: '已完成',
    FAILED: '失败',
    REJECTED: '已拒绝',
    CANCELLED: '已取消',
  };
  return labels[status] || status || '未知';
}

function setSending(sending) {
  els.send.disabled = sending;
  els.input.disabled = sending;
  els.send.textContent = sending ? '处理中…' : '发送';
}

function autoResize() {
  els.input.style.height = 'auto';
  els.input.style.height = `${Math.min(140, els.input.scrollHeight)}px`;
}

function scrollToBottom() {
  els.messages.scrollTop = els.messages.scrollHeight;
}

function showToast(message) {
  els.toast.textContent = message;
  els.toast.hidden = false;
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => {
    els.toast.hidden = true;
  }, 2600);
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}
