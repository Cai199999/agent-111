const TOKEN_KEY = 'wuye_token';
const UI_VERSION = '20261120-auth5';
window.__wuyeAppLoaded = true;
const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED']);
console.info(`[wuye] auth ui ${UI_VERSION}`);

function readToken() {
  try {
    const value = localStorage.getItem(TOKEN_KEY);
    if (value) return value;
  } catch {
    // ignore
  }
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function writeToken(token) {
  let stored = false;
  try {
    localStorage.setItem(TOKEN_KEY, token);
    stored = true;
  } catch {
    // ignore
  }
  try {
    sessionStorage.setItem(TOKEN_KEY, token);
    stored = true;
  } catch {
    // ignore
  }
  return stored;
}

function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    // ignore
  }
  try {
    sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // ignore
  }
}

const state = {
  token: null,
  me: null,
  agents: [],
  capabilities: new Set(),
  sessions: [],
  currentSession: null,
  streaming: false,
  taskEls: new Map(),
  taskData: new Map(),
  polls: new Map(),
  profileForm: null,
  profileOptions: {},
  profileData: {},
  profileDraft: {},
};

const els = {
  app: document.getElementById('app'),
  loginView: document.getElementById('loginView'),
  loginForm: document.getElementById('loginForm'),
  loginUsername: document.getElementById('loginUsername'),
  loginPassword: document.getElementById('loginPassword'),
  loginError: document.getElementById('loginError'),
  userButton: document.getElementById('userButton'),
  userAvatar: document.getElementById('userAvatar'),
  userName: document.getElementById('userName'),
  userRole: document.getElementById('userRole'),
  userDropdown: document.getElementById('userDropdown'),
  profileModal: document.getElementById('profileModal'),
  profileForm: document.getElementById('profileForm'),
  profileMeta: document.getElementById('profileMeta'),
  profileError: document.getElementById('profileError'),
  profileSave: document.getElementById('profileSave'),
  profileClose: document.getElementById('profileClose'),
  settingsModal: document.getElementById('settingsModal'),
  settingNotify: document.getElementById('settingNotify'),
  settingMemory: document.getElementById('settingMemory'),
  settingsClose: document.getElementById('settingsClose'),
  settingsSave: document.getElementById('settingsSave'),
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
  { text: '我有哪些待处理工单？', capability: 'property.ticket.query' },
  { text: '查一下 GD20261119000107 详情', capability: 'property.ticket.detail' },
  { text: '把 GD20261119000107 派给 10002', capability: 'property.ticket.assign' },
  { text: '报修：3栋2单元水管漏水', capability: 'property.ticket.create' },
  { text: '合同审查规则有哪些？', capability: 'contract.rule.list' },
  { text: '审查合同 10001', capability: 'contract.review' },
  { text: '查一下知识库', capability: 'property.knowledge.search', alt: 'contract.knowledge.search' },
  { text: '审查合同 HT-2026-001', capability: 'contract.review' },
];

init();

async function init() {
  bindEvents();
  state.token = readToken();
  if (!state.token) {
    showLogin();
    return;
  }
  try {
    await bootstrap();
  } catch (error) {
    console.warn('bootstrap failed', error);
    logoutLocal();
  }
}

async function bootstrap() {
  const me = await api('/api/v1/auth/me');
  state.me = me.user;
  state.agents = me.agents || [];
  state.capabilities = new Set(
    state.agents.flatMap((agent) => (agent.capabilities || []).map((cap) => cap.capabilityId)),
  );
  renderCurrentUser();
  renderAgents();
  hideLogin();
  await loadSessions();
  if (!state.sessions.length) {
    await createSession();
  } else {
    await selectSession(state.sessions[0].sessionNo);
  }
}

function bindEvents() {
  els.loginForm.addEventListener('submit', handleLogin);
  els.newSession.addEventListener('click', () => createSession().catch((e) => showToast(e.message)));
  els.send.addEventListener('click', sendMessage);
  els.input.addEventListener('input', autoResize);
  els.input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      sendMessage();
    }
  });
  els.userButton.addEventListener('click', (event) => {
    event.stopPropagation();
    els.userDropdown.hidden = !els.userDropdown.hidden;
  });
  els.userDropdown.addEventListener('click', (event) => {
    const action = event.target.closest('button')?.dataset.action;
    if (!action) return;
    els.userDropdown.hidden = true;
    if (action === 'profile') openProfile();
    if (action === 'settings') openSettings();
    if (action === 'logout') logoutLocal();
  });
  document.addEventListener('click', (event) => {
    if (!event.target.closest('.user-menu')) {
      els.userDropdown.hidden = true;
    }
  });
  els.profileClose.addEventListener('click', () => {
    els.profileModal.hidden = true;
  });
  els.profileSave.addEventListener('click', saveProfile);
  els.settingsClose.addEventListener('click', () => {
    els.settingsModal.hidden = true;
  });
  els.settingsSave.addEventListener('click', saveSettings);
}

async function handleLogin(event) {
  event.preventDefault();
  els.loginError.textContent = '';
  const username = els.loginUsername.value.trim();
  const password = els.loginPassword.value;
  if (!username || !password) {
    els.loginError.textContent = '请输入用户名和密码';
    return;
  }
  try {
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    const body = await response.json();
    if (!response.ok || body.code !== 'OK') {
      throw new Error(body.message || '登录失败');
    }
    state.token = body.data.accessToken;
    writeToken(state.token);
    await bootstrap();
  } catch (error) {
    els.loginError.textContent = error.message || '登录失败';
  }
}

function showLogin() {
  els.app.hidden = true;
  els.loginView.hidden = false;
  els.loginPassword.value = '';
  setTimeout(() => els.loginUsername.focus(), 0);
}

function hideLogin() {
  els.loginView.hidden = true;
  els.app.hidden = false;
}

function logoutLocal() {
  clearToken();
  state.token = null;
  state.me = null;
  state.agents = [];
  state.capabilities = new Set();
  state.sessions = [];
  state.currentSession = null;
  state.polls.forEach((timer) => clearInterval(timer));
  state.polls.clear();
  showLogin();
}

function renderCurrentUser() {
  if (!state.me) return;
  els.userName.textContent = state.me.displayName || state.me.username;
  els.userAvatar.textContent = (state.me.displayName || state.me.username || '?').slice(0, 1);
  const role = (state.me.roles || [])[0] || 'USER';
  els.userRole.textContent = ({ ADMIN: '管理员', WORKER: '工人', USER: '用户' })[role] || role;
  const hasContract = state.agents.some((agent) => agent.agentKey === 'contract-review');
  const propertyPill = document.getElementById('propertyPill');
  const contractPill = document.getElementById('contractPill');
  if (propertyPill) propertyPill.hidden = !state.agents.some((agent) => agent.agentKey === 'property-maintenance');
  if (contractPill) contractPill.hidden = !hasContract;
}

function renderAgents() {
  if (!state.agents.length) {
    els.agentStatus.replaceChildren(el('div', 'agent-chip', '无可用 Agent'));
    return;
  }
  els.agentStatus.replaceChildren(...state.agents.map((agent) => {
    const chip = el('div', 'agent-chip');
    const name = el('div', 'name');
    name.append(el('span', 'dot'), el('span', '', agent.displayName.replace('（Mock）', '')));
    chip.append(name, el('span', 'ver', `v${agent.contractVersion}`));
    return chip;
  }));
}

async function api(path, options = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...(options.headers || {}),
  };
  if (state.token) {
    headers.Authorization = `Bearer ${state.token}`;
  }
  const response = await fetch(path, { ...options, headers });
  const text = await response.text();
  let body = {};
  try {
    body = text ? JSON.parse(text) : {};
  } catch {
    body = {};
  }
  if (response.status === 401) {
    logoutLocal();
    throw new Error('登录已过期，请重新登录');
  }
  if (!response.ok || (body.code && body.code !== 'OK')) {
    throw new Error(body.message || `HTTP ${response.status}`);
  }
  return body.data;
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
  const agentNames = state.agents.map((agent) => agent.displayName.replace('（Mock）', '')).join(' + ');
  els.chatSub.textContent = `${session.messageCount || 0} 条消息${agentNames ? ` · ${agentNames}` : ''}`;
  renderSessions();
  await loadMessages(sessionNo);
}

function renderSessions() {
  els.sessionList.replaceChildren(...state.sessions.map((session) => {
    const item = el('div', 'session-item');
    item.setAttribute('role', 'button');
    item.tabIndex = 0;
    if (state.currentSession && state.currentSession.sessionNo === session.sessionNo) {
      item.classList.add('active');
    }
    const remove = el('button', 'session-delete', '×');
    remove.type = 'button';
    remove.title = '删除会话';
    remove.setAttribute('aria-label', '删除会话');
    remove.addEventListener('click', (event) => {
      event.stopPropagation();
      deleteSession(session);
    });
    item.append(
      el('span', 'title', session.title || '新会话'),
      el('span', 'count', String(session.messageCount || 0)),
      remove,
    );
    item.addEventListener('click', () => selectSession(session.sessionNo));
    item.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        selectSession(session.sessionNo);
      }
    });
    return item;
  }));
}

async function deleteSession(session) {
  const confirmed = await askConfirm(
    `确定删除会话「${session.title || '新会话'}」及其消息、任务和事件吗？此操作不可恢复。`,
    { title: '删除会话', confirmText: '删除', danger: true },
  );
  if (!confirmed) {
    return;
  }
  try {
    await api(`/api/v1/chat/sessions/${session.sessionNo}`, { method: 'DELETE' });
    for (const [taskNo, task] of state.taskData) {
      if (task.sessionNo === session.sessionNo) {
        stopPolling(taskNo);
        state.taskData.delete(taskNo);
        state.taskEls.delete(taskNo);
      }
    }
    state.sessions = state.sessions.filter((item) => item.sessionNo !== session.sessionNo);
    if (state.currentSession && state.currentSession.sessionNo === session.sessionNo) {
      state.currentSession = null;
      clearMessages();
      if (state.sessions.length) {
        await selectSession(state.sessions[0].sessionNo);
      } else {
        await createSession();
      }
    } else {
      renderSessions();
    }
    showToast('会话已删除');
  } catch (error) {
    showToast(error.message || '删除失败');
  }
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
  for (const prompt of availablePrompts()) {
    const button = el('button', 'quick', prompt.text);
    button.type = 'button';
    button.addEventListener('click', () => {
      els.input.value = prompt.text;
      autoResize();
      sendMessage();
    });
    grid.append(button);
  }
  empty.append(grid);
  els.messages.append(empty);
}

function availablePrompts() {
  return QUICK_PROMPTS.filter((prompt) => {
    if (!prompt.capability) return true;
    return state.capabilities.has(prompt.capability)
        || (prompt.alt && state.capabilities.has(prompt.alt));
  });
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
        'Authorization': `Bearer ${state.token}`,
      },
      body: JSON.stringify({ content, clientMessageId }),
    });
    if (response.status === 401) {
      logoutLocal();
      throw new Error('登录已过期，请重新登录');
    }
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
    case 'result': {
      const task = state.taskData.get(data.taskNo);
      if (task) {
        task.output = data.output;
        updateTaskCard(task);
      } else {
        renderRawResult(assistant.cards, data.output);
      }
      break;
    }
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

  if (task.output !== undefined && task.output !== null) {
    body.append(rawResultElement(task.output));
  }

  card.append(head, body);
  container.append(card);

  if (['PENDING', 'RUNNING'].includes(task.status)) {
    startPolling(taskNo);
  }
}

function renderRawResult(container, output) {
  if (!container || output === undefined || output === null) return;
  container.append(rawResultElement(output));
}

function rawResultElement(output) {
  const box = el('details', 'raw-result');
  box.append(el('summary', '', '子 Agent 原始返回（未经主 Agent 改写）'));
  const pre = el('pre', 'raw-json');
  try {
    pre.textContent = JSON.stringify(output, null, 2);
  } catch {
    pre.textContent = String(output);
  }
  box.append(pre);
  return box;
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

function askConfirm(message, options = {}) {
  const modal = document.getElementById('confirmModal');
  const title = document.getElementById('confirmTitle');
  const text = document.getElementById('confirmText');
  const ok = document.getElementById('confirmOk');
  const cancel = document.getElementById('confirmCancel');

  if (!modal || !title || !text || !ok || !cancel) {
    return Promise.resolve(false);
  }
  if (!modal.hidden) {
    return Promise.resolve(false);
  }

  title.textContent = options.title || '请确认';
  text.textContent = message;
  ok.textContent = options.confirmText || '确定';
  ok.className = options.danger === false ? 'btn primary' : 'btn danger';
  modal.hidden = false;
  ok.focus();

  return new Promise((resolve) => {
    const cleanup = (value) => {
      modal.hidden = true;
      ok.removeEventListener('click', onOk);
      cancel.removeEventListener('click', onCancel);
      modal.removeEventListener('click', onBackdrop);
      document.removeEventListener('keydown', onKey);
      resolve(value);
    };
    const onOk = () => cleanup(true);
    const onCancel = () => cleanup(false);
    const onBackdrop = (event) => {
      if (event.target === modal) cleanup(false);
    };
    const onKey = (event) => {
      if (event.key === 'Escape') cleanup(false);
      if (event.key === 'Enter' && document.activeElement === ok) cleanup(true);
    };
    ok.addEventListener('click', onOk);
    cancel.addEventListener('click', onCancel);
    modal.addEventListener('click', onBackdrop);
    document.addEventListener('keydown', onKey);
  });
}

async function openProfile() {
  els.profileModal.hidden = false;
  els.profileError.textContent = '';
  els.profileForm.replaceChildren();
  els.profileSave.hidden = !state.capabilities.has('property.profile.update');
  els.profileMeta.textContent = '加载中…';
  try {
    const [form, options, profile] = await Promise.all([
      api('/api/v1/profile/form'),
      api('/api/v1/profile/options'),
      api('/api/v1/profile'),
    ]);
    state.profileForm = form || {};
    state.profileOptions = options || {};
    state.profileData = profile || {};
    state.profileDraft = {};
    for (const field of state.profileForm.fields || []) {
      const value = state.profileData[field.name];
      if (value !== undefined && value !== null) {
        state.profileDraft[field.name] = value;
      }
    }
    renderProfileForm();
    els.profileMeta.textContent =
      `审核状态：${state.profileData.reviewStatus || '未知'} · 表单来源：${state.profileForm.source || 'manifest'}`;
  } catch (error) {
    els.profileMeta.textContent = '';
    els.profileError.textContent = error.message || '加载个人资料失败';
  }
}

function renderProfileForm() {
  const form = state.profileForm;
  if (!form) return;
  els.profileForm.replaceChildren();
  for (const field of form.fields || []) {
    const label = el('label', field.required ? 'required' : '', field.label || field.name);
    let input;
    if (field.type === 'select') {
      input = document.createElement('select');
      input.append(new Option('请选择', ''));
      for (const option of optionsFor(field)) {
        input.append(new Option(option.name || option.label || option.id, option.id ?? option.value ?? ''));
      }
      const value = state.profileDraft[field.name];
      if (value !== undefined && value !== null) {
        input.value = String(value);
      }
      input.addEventListener('change', () => {
        state.profileDraft[field.name] = input.value;
        for (const dependent of form.fields || []) {
          if (dependent.dependsOn === field.name) {
            delete state.profileDraft[dependent.name];
          }
        }
        renderProfileForm();
      });
    } else {
      input = document.createElement('input');
      input.type = field.sensitive ? 'password' : 'text';
      if (field.maxLength) input.maxLength = field.maxLength;
      if (field.sensitive && state.profileData.phoneMask) {
        input.placeholder = state.profileData.phoneMask;
      }
      input.value = state.profileDraft[field.name] ?? '';
      input.autocomplete = 'off';
      input.addEventListener('input', () => {
        state.profileDraft[field.name] = input.value;
      });
    }
    label.append(input);
    els.profileForm.append(label);
  }
}

function optionsFor(field) {
  const all = state.profileOptions[field.optionsKey] || [];
  if (!field.dependsOn) return all;
  const parent = state.profileDraft[field.dependsOn];
  if (parent === undefined || parent === null || parent === '') return [];
  return all.filter((item) => String(item.projectId ?? item.buildingId ?? '') === String(parent));
}

async function saveProfile() {
  els.profileError.textContent = '';
  const payload = {};
  for (const field of state.profileForm?.fields || []) {
    let value = state.profileDraft[field.name];
    if (value === undefined || value === null || String(value).trim() === '') continue;
    if (field.type === 'select' && /^\d+$/.test(String(value))) {
      value = Number(value);
    }
    payload[field.name] = value;
  }
  try {
    const task = await api('/api/v1/profile', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    if (task.status === 'WAITING_CONFIRM') {
      const confirmed = await askConfirm(
        '确认保存个人资料？项目/楼宇/房间/客户绑定变更可能需要管理员审核。',
        { title: '保存个人资料', confirmText: '确认保存', danger: false },
      );
      if (!confirmed) return;
      const result = await api(`/api/v1/tasks/${task.taskNo}/confirm`, {
        method: 'POST',
        body: JSON.stringify({ decision: 'APPROVE', comment: '个人信息保存' }),
      });
      if (result.status === 'FAILED' || result.status === 'REJECTED') {
        throw new Error(result.error?.message || '保存失败');
      }
      const reviewStatus = result.output?.reviewStatus || 'PENDING';
      els.profileMeta.textContent = `审核状态：${reviewStatus}`;
      showToast(reviewStatus === 'PENDING' ? '已保存，绑定变更待管理员审核' : '个人资料已保存');
      await refreshProfile();
    } else if (task.status === 'SUCCEEDED') {
      showToast('个人资料已保存');
      await refreshProfile();
    } else {
      throw new Error(task.error?.message || '保存未完成');
    }
  } catch (error) {
    els.profileError.textContent = error.message || '保存失败';
  }
}

async function refreshProfile() {
  try {
    state.profileData = (await api('/api/v1/profile')) || {};
    els.profileMeta.textContent =
      `审核状态：${state.profileData.reviewStatus || '未知'} · 表单来源：${state.profileForm?.source || 'manifest'}`;
  } catch (error) {
    els.profileError.textContent = error.message || '刷新个人资料失败';
  }
}

function openSettings() {
  els.settingNotify.checked = localStorage.getItem('wuye_notify') !== 'off';
  els.settingMemory.checked = localStorage.getItem('wuye_memory') !== 'off';
  els.settingsModal.hidden = false;
}

function saveSettings() {
  localStorage.setItem('wuye_notify', els.settingNotify.checked ? 'on' : 'off');
  localStorage.setItem('wuye_memory', els.settingMemory.checked ? 'on' : 'off');
  els.settingsModal.hidden = true;
  showToast('设置已保存');
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
