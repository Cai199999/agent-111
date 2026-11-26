const TOKEN_KEY = 'wuye_token';
const UI_VERSION = '20261125-attach1';
window.__wuyeAppLoaded = true;
const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED']);
console.info(`[wuye] auth ui ${UI_VERSION}`);

const FIELD_LABELS = {
  id: '编号',
  ticketNo: '工单号',
  title: '标题',
  description: '报修内容',
  status: '状态',
  statusCode: '状态',
  priorityCode: '优先级',
  ticketTypeCode: '工单类型',
  reporterId: '报修人',
  customerId: '客户',
  roomId: '房间',
  projectId: '项目',
  buildingId: '楼栋',
  equipmentId: '设备',
  serviceItemId: '服务项',
  serviceItemText: '服务项',
  locationText: '位置',
  locationType: '位置类型',
  currentNodeCode: '当前节点',
  createdAt: '创建时间',
  updatedAt: '更新时间',
  slaResolveDueAt: 'SLA解决截止',
  taskDueAt: '任务截止',
  appointmentAt: '预约时间',
  version: '版本',
  contactName: '联系人',
  contactPhone: '联系电话',
  contactPhoneMask: '联系电话',
  indoorPublicArea: '户内/公区',
  assigneeId: '处理人',
  assigneeName: '处理人',
  ruleCode: '规则编码',
  level: '级别',
  snippet: '摘要',
  score: '相关度',
  summary: '摘要',
  findingsCount: '风险数',
  findingsByLevel: '风险分布',
  overall: '结论',
  contractId: '合同ID',
  contractNo: '合同号',
  versionId: '版本ID',
  name: '名称',
  phoneMask: '联系电话',
  reviewStatus: '审核状态',
  matchedBy: '匹配依据',
  parcelNo: '地块号',
  parcelName: '地块名称',
  region: '区域',
  areaMu: '面积（亩）',
  landUse: '用地类型',
  changeNo: '图斑号',
  changeType: '变化类型',
  patrolNo: '巡查任务号',
  patrolType: '巡查类型',
  deadline: '截止时间',
  reportNo: '报告编号',
  reportType: '报告类型',
  period: '统计周期',
  planNo: '计划编号',
  planName: '计划名称',
  fileName: '文件名',
  rows: '记录数',
  downloadUrl: '下载地址',
  question: '问题',
  answer: '回答',
  citations: '引用',
  reviewNo: '审查编号',
  tickets: '工单列表',
  customer: '客户',
  issue: '问题描述',
  priority: '优先级',
  memoryUsed: '使用记忆',
  memoryHint: '记忆说明',
  code: '编码',
  preferredProjectId: '默认项目',
  preferredBuildingId: '默认楼栋',
  preferredRoomId: '默认房间',
  preferredTicketTypeCode: '常用工单类型',
  preferredPriorityCode: '常用优先级',
  commonIntents: '常用意图',
  notes: '备注',
};

const VALUE_LABELS = {
  statusCode: {
    PENDING: '待处理', ASSIGNED: '已派单', ACCEPTED: '已接单',
    PROCESSING: '处理中', RUNNING: '处理中', RESOLVED: '已解决',
    VERIFIED: '已验证', PENDING_VERIFY: '待验证', CLOSED: '已关闭',
    CANCELLED: '已取消', HOLD: '已挂起',
  },
  priorityCode: { LOW: '低', NORMAL: '普通', HIGH: '高', URGENT: '紧急' },
  ticketTypeCode: {
    ELEVATOR: '电梯故障', HVAC: '空调故障', PUMP: '水泵故障', FIRE: '消防故障',
    ACCESS: '门禁故障', NETWORK: '网络故障', ELECTRICAL: '电气故障',
    GENERAL: '通用', OTHER: '其他', PLUMBING: '给排水',
    LIGHTING: '照明', SECURITY: '安防', CLEANING: '保洁',
  },
  currentNodeCode: {
    ACCEPT: '待接单', ACCEPTED: '已接单', PROCESS: '处理中',
    PROCESSING: '处理中', VERIFY: '待验证', CLOSE: '已关闭', CLOSED: '已关闭',
  },
  locationType: { INDOOR: '室内', OUTDOOR: '室外' },
  indoorPublicArea: { INDOOR: '户内', PUBLIC: '公区' },
  level: { MAJOR: '重大', GENERAL: '一般', HIGH: '高' },
  overall: { PASS: '通过', FAIL: '不通过' },
  reviewStatus: { PENDING: '审核中', APPROVED: '已通过', REJECTED: '已拒绝', NONE: '未提交' },
};

function escapeHtml(value) {
  return String(value == null ? '' : value).replace(/[&<>"']/g, (char) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]
  ));
}

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
  notifications: [],
  notifyTimer: null,
  notifyAbort: null,
  attachment: null,
  uploading: false,
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
  profileSections: document.getElementById('profileSections'),
  profileMeta: document.getElementById('profileMeta'),
  profileError: document.getElementById('profileError'),
  profileSave: document.getElementById('profileSave'),
  profileClose: document.getElementById('profileClose'),
  memoryProfile: document.getElementById('memoryProfile'),
  memoryClear: document.getElementById('memoryClear'),
  settingsModal: document.getElementById('settingsModal'),
  settingNotify: document.getElementById('settingNotify'),
  settingMemory: document.getElementById('settingMemory'),
  settingsClose: document.getElementById('settingsClose'),
  settingsSave: document.getElementById('settingsSave'),
  dashboardMenuItem: document.getElementById('dashboardMenuItem'),
  dashboardModal: document.getElementById('dashboardModal'),
  dashboardCards: document.getElementById('dashboardCards'),
  dashboardFunnel: document.getElementById('dashboardFunnel'),
  dashboardTable: document.getElementById('dashboardTable'),
  dashboardClose: document.getElementById('dashboardClose'),
  observabilityMenuItem: document.getElementById('observabilityMenuItem'),
  observabilityModal: document.getElementById('observabilityModal'),
  observabilitySummary: document.getElementById('observabilitySummary'),
  observabilityOrchestration: document.getElementById('observabilityOrchestration'),
  observabilityEvents: document.getElementById('observabilityEvents'),
  observabilitySamples: document.getElementById('observabilitySamples'),
  observabilityClose: document.getElementById('observabilityClose'),
  a2aMenuItem: document.getElementById('a2aMenuItem'),
  a2aModal: document.getElementById('a2aModal'),
  a2aSummary: document.getElementById('a2aSummary'),
  a2aList: document.getElementById('a2aList'),
  a2aClose: document.getElementById('a2aClose'),
  installAgent: document.getElementById('installAgent'),
  installAgentModal: document.getElementById('installAgentModal'),
  installTemplateAgent: document.getElementById('installTemplateAgent'),
  installPolicyAgent: document.getElementById('installPolicyAgent'),
  installCustomerAgent: document.getElementById('installCustomerAgent'),
  installFromUrl: document.getElementById('installFromUrl'),
  installAgentUrl: document.getElementById('installAgentUrl'),
  installAgentToken: document.getElementById('installAgentToken'),
  installAgentError: document.getElementById('installAgentError'),
  installAgentClose: document.getElementById('installAgentClose'),
  auditMenuItem: document.getElementById('auditMenuItem'),
  auditModal: document.getElementById('auditModal'),
  auditList: document.getElementById('auditList'),
  auditClose: document.getElementById('auditClose'),
  humanMenuItem: document.getElementById('humanMenuItem'),
  humanModal: document.getElementById('humanModal'),
  humanList: document.getElementById('humanList'),
  humanClose: document.getElementById('humanClose'),
  sessionList: document.getElementById('sessionList'),
  agentStatus: document.getElementById('agentStatus'),
  messages: document.getElementById('messages'),
  input: document.getElementById('input'),
  send: document.getElementById('send'),
  newSession: document.getElementById('newSession'),
  chatTitle: document.getElementById('chatTitle'),
  chatSub: document.getElementById('chatSub'),
  toast: document.getElementById('toast'),
  notifyMenu: document.getElementById('notifyMenu'),
  notifyButton: document.getElementById('notifyButton'),
  notifyBadge: document.getElementById('notifyBadge'),
  notifyDropdown: document.getElementById('notifyDropdown'),
  notifyList: document.getElementById('notifyList'),
  notifyReadAll: document.getElementById('notifyReadAll'),
  attachButton: document.getElementById('attachButton'),
  attachInput: document.getElementById('attachInput'),
  attachChip: document.getElementById('attachChip'),
};

const QUICK_PROMPTS = [
  { text: '我有哪些待处理工单？', capability: 'property.ticket.query' },
  { text: '查一下 GD20261119000107 详情', capability: 'property.ticket.detail' },
  { text: '把 GD20261119000107 派给 10002', capability: 'property.ticket.assign' },
  { text: '报修：3栋2单元水管漏水', capability: 'property.ticket.create' },
  { text: '查询地块 P-2026-001', capability: 'gis.parcel.query' },
  { text: '有哪些遥感变化图斑？', capability: 'gis.change.query' },
  { text: '为地块 P-2026-001 创建巡查任务', capability: 'gis.patrol.create' },
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
  startNotifications();
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
  els.attachButton.addEventListener('click', () => els.attachInput.click());
  els.attachInput.addEventListener('change', () => {
    const file = els.attachInput.files?.[0];
    els.attachInput.value = '';
    if (file) uploadAttachment(file);
  });
  els.attachChip.addEventListener('click', (event) => {
    if (event.target.closest('button')) clearAttachment();
  });
  els.input.addEventListener('input', autoResize);
  els.input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      sendMessage();
    }
  });
  els.notifyButton.addEventListener('click', (event) => {
    event.stopPropagation();
    els.notifyDropdown.hidden = !els.notifyDropdown.hidden;
    if (!els.notifyDropdown.hidden) {
      refreshNotifications().catch(() => {});
    }
  });
  els.notifyReadAll.addEventListener('click', () => markAllNotificationsRead());
  els.notifyList.addEventListener('click', (event) => {
    const item = event.target.closest('.notify-item');
    if (!item) return;
    openNotification(item.dataset.no);
  });
  els.userButton.addEventListener('click', (event) => {
    event.stopPropagation();
    els.userDropdown.hidden = !els.userDropdown.hidden;
  });
  els.userDropdown.addEventListener('click', (event) => {
    const action = event.target.closest('button')?.dataset.action;
    if (!action) return;
    els.userDropdown.hidden = true;
    if (action === 'dashboard') openDashboard();
    if (action === 'observability') openObservability();
    if (action === 'a2a') openA2A();
    if (action === 'human') openHumanTasks();
    if (action === 'audit') openAudit();
    if (action === 'profile') openProfile();
    if (action === 'settings') openSettings();
    if (action === 'logout') logoutLocal();
  });
  document.addEventListener('click', (event) => {
    if (!event.target.closest('.user-menu')) {
      els.userDropdown.hidden = true;
    }
    if (!event.target.closest('.notify-menu')) {
      els.notifyDropdown.hidden = true;
    }
  });
  els.profileClose.addEventListener('click', () => {
    els.profileModal.hidden = true;
  });
  els.profileSave.addEventListener('click', saveProfile);
  els.memoryClear.addEventListener('click', clearMemory);
  els.settingsClose.addEventListener('click', () => {
    els.settingsModal.hidden = true;
  });
  els.settingsSave.addEventListener('click', saveSettings);
  els.dashboardClose.addEventListener('click', () => {
    els.dashboardModal.hidden = true;
  });
  els.observabilityClose.addEventListener('click', () => closeObservability());
  els.a2aClose.addEventListener('click', () => {
    els.a2aModal.hidden = true;
  });
  els.installAgent.addEventListener('click', openInstallAgent);
  els.installAgentClose.addEventListener('click', () => {
    els.installAgentModal.hidden = true;
  });
  els.installTemplateAgent.addEventListener('click', installTemplateAgent);
  els.installPolicyAgent.addEventListener('click', () => installDemoEnterpriseAgent('policy'));
  els.installCustomerAgent.addEventListener('click', () => installDemoEnterpriseAgent('customer'));
  els.installFromUrl.addEventListener('click', installAgentFromUrl);
  els.auditClose.addEventListener('click', () => {
    els.auditModal.hidden = true;
  });
  els.humanClose.addEventListener('click', () => {
    els.humanModal.hidden = true;
  });
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
  stopNotifications();
  clearAttachment();
  showLogin();
}

function renderCurrentUser() {
  if (!state.me) return;
  els.userName.textContent = state.me.displayName || state.me.username;
  els.userAvatar.textContent = (state.me.displayName || state.me.username || '?').slice(0, 1);
  const role = (state.me.roles || [])[0] || 'USER';
  els.userRole.textContent = ({ ADMIN: '管理员', WORKER: '工人', USER: '用户' })[role] || role;
  const isAdmin = (state.me.roles || []).includes('ADMIN');
  if (els.dashboardMenuItem) {
    els.dashboardMenuItem.hidden = !isAdmin;
  }
  if (els.observabilityMenuItem) {
    els.observabilityMenuItem.hidden = !isAdmin;
  }
  if (els.a2aMenuItem) {
    els.a2aMenuItem.hidden = !isAdmin;
  }
  if (els.installAgent) {
    els.installAgent.hidden = !isAdmin;
  }
  if (els.auditMenuItem) {
    els.auditMenuItem.hidden = !isAdmin;
  }
  if (els.humanMenuItem) {
    els.humanMenuItem.hidden = !(state.me.permissions || []).includes('human:manage');
  }
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
  const isAdmin = (state.me?.roles || []).includes('ADMIN');
  els.agentStatus.replaceChildren(...state.agents.map((agent) => {
    const chip = el('div', 'agent-chip');
    const name = el('div', 'name');
    name.append(el('span', 'dot'), el('span', '', agent.displayName.replace('（Mock）', '')));
    const right = el('div', 'agent-actions');
    right.append(el('span', 'ver', `v${agent.contractVersion}`));
    if (agent.dynamic && isAdmin) {
      const remove = el('button', 'agent-remove', '×');
      remove.type = 'button';
      remove.title = '卸载这个运行时安装的 Agent';
      remove.addEventListener('click', (event) => {
        event.stopPropagation();
        uninstallAgent(agent.agentKey, agent.displayName);
      });
      right.append(remove);
    }
    chip.append(name, right);
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

async function uploadAttachment(file) {
  if (state.uploading) return;
  if (file.size > 10 * 1024 * 1024) {
    showToast('附件不能超过 10MB');
    return;
  }
  state.uploading = true;
  els.attachButton.disabled = true;
  try {
    const form = new FormData();
    form.append('file', file);
    if (state.currentSession) {
      form.append('sessionNo', state.currentSession.sessionNo);
    }
    const response = await fetch('/api/v1/chat/attachments', {
      method: 'POST',
      headers: { Authorization: `Bearer ${state.token}` },
      body: form,
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok || body.code !== 'OK') {
      throw new Error(body.message || `上传失败 HTTP ${response.status}`);
    }
    state.attachment = body.data;
    renderAttachmentChip();
    showToast('附件已上传');
  } catch (error) {
    showToast(error.message || '附件上传失败');
  } finally {
    state.uploading = false;
    els.attachButton.disabled = false;
  }
}

function renderAttachmentChip() {
  if (!els.attachChip) return;
  if (!state.attachment) {
    els.attachChip.hidden = true;
    els.attachChip.innerHTML = '';
    return;
  }
  const kind = state.attachment.parseKind === 'SIMPLE_TEXT' ? '文本' : '多模态';
  els.attachChip.hidden = false;
  els.attachChip.innerHTML = `
    <b>${escapeHtml(state.attachment.fileName)}</b>
    <span class="kind">${kind}</span>
    <button type="button" title="移除附件">×</button>`;
}

function clearAttachment() {
  state.attachment = null;
  renderAttachmentChip();
}

async function sendMessage() {
  const content = els.input.value.trim();
  const attachment = state.attachment;
  if ((!content && !attachment) || state.streaming || state.uploading) return;
  if (!state.currentSession) {
    await createSession();
  }

  els.messages.querySelector('.empty')?.remove();
  els.input.value = '';
  autoResize();
  setSending(true);
  state.streaming = true;

  const display = attachment ? `${content || '请处理这个附件'}\n📎 ${attachment.fileName}` : content;
  appendUserMessage(display);
  clearAttachment();
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
      body: JSON.stringify({
        content,
        clientMessageId,
        attachmentId: attachment ? attachment.attachmentNo : null,
      }),
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
    case 'orchestration':
      renderOrchestration(assistant, data);
      break;
    case 'delta':
      appendDelta(assistant, data.content || '');
      break;
    case 'batch':
      break;
    case 'task':
      if (data.batchIndex != null) {
        const key = String(data.batchIndex);
        let container = assistant.cards.querySelector(`[data-batch-index="${key}"]`);
        if (!container) {
          container = el('div');
          container.dataset.batchIndex = key;
          assistant.cards.append(container);
        }
        renderTaskCard(container, data);
      } else {
        renderTaskCard(assistant.cards, data);
      }
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
    case 'feedback':
      if (data.action === 'RECOVERED') showToast('已自动修复并重试成功');
      if (data.action === 'TRANSFERRED') showToast('已转人工跟进');
      if (data.action === 'NO_CAPABILITY') showToast('目前没有这个功能');
      break;
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
  const orchestration = el('div', 'orchestration-badge');
  orchestration.hidden = true;
  const text = el('div', 'text', initialText || '');
  const cards = el('div', 'cards');
  bubble.append(orchestration, route, text, cards);
  root.append(el('div', 'avatar', 'AI'), bubble);
  els.messages.append(root);
  scrollToBottom();
  return { root, bubble, route, orchestration, text, cards };
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

const ORCHESTRATION_LABELS = {
  COMPLETE: '通过',
  DISPATCH_REQUIRED: '待执行',
  NEEDS_INPUT: '需补充',
  FAILED: '失败',
};

function renderOrchestration(assistant, data) {
  if (!assistant || !assistant.orchestration || !data) return;
  const phase = data.phase === 'VERIFY' ? '验收' : '编排';
  const verdict = data.verdict || 'UNKNOWN';
  const details = [];
  if (phase === '编排' && Array.isArray(data.plan) && data.plan.length) {
    details.push(`计划 ${data.plan.map((step) => step.capabilityId).join(' → ')}`);
  }
  if (Array.isArray(data.steps) && data.steps.length) {
    details.push(data.steps.map((step) => `${step.capabilityId}=${step.status}`).join('，'));
  }
  if (data.clarification) details.push(data.clarification);
  assistant.orchestration.hidden = false;
  assistant.orchestration.className = [
    'orchestration-badge',
    phase === '验收' ? 'verify' : 'plan',
    verdict === 'FAILED' ? 'failed' : '',
  ].filter(Boolean).join(' ');
  assistant.orchestration.replaceChildren(
    el('span', '', phase),
    el('strong', '', `${verdict}${ORCHESTRATION_LABELS[verdict] ? `（${ORCHESTRATION_LABELS[verdict]}）` : ''}`),
    ...(details.length ? [el('span', 'orch-detail', details.join(' · '))] : []),
  );
  scrollToBottom();
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
      // 技术错误只保留在事件 API/审计里；用户看到的是“已自动处理/转人工”的反馈。
      const detail = event.type === 'ERROR'
        ? '处理遇到问题，已自动分析或转人工'
        : (event.payload?.message || event.payload?.step || statusLabel(event.status));
      row.append(
        el('span', 'seq', `#${event.seq}`),
        el('span', 'type', event.type || 'EVENT'),
        el('span', '', detail),
      );
      events.append(row);
    }
    body.append(events);
  }

  if (task.output !== undefined && task.output !== null) {
    body.append(resultElement(task.output));
    if (task.output && typeof task.output === 'object' && task.output.previewUrl) {
      body.append(previewButton(task.output.previewUrl));
    }
  }

  card.append(head, body);
  container.append(card);

  if (['PENDING', 'RUNNING'].includes(task.status)) {
    startPolling(taskNo);
  }
}

function renderRawResult(container, output) {
  if (!container || output === undefined || output === null) return;
  container.append(resultElement(output));
  if (output && typeof output === 'object' && output.previewUrl) {
    container.append(previewButton(output.previewUrl));
  }
}

function previewButton(url) {
  const link = el('a', 'preview-btn', '预览单据');
  link.href = url;
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  return link;
}

const TICKET_COLUMNS = [
  { key: 'ticketNo', label: '工单号' },
  { key: 'title', label: '标题' },
  { key: 'statusCode', label: '状态' },
  { key: 'priorityCode', label: '优先级' },
  { key: 'ticketTypeCode', label: '工单类型' },
  { key: 'locationText', label: '位置' },
  { key: 'contactName', label: '联系人' },
  { key: 'currentNodeCode', label: '当前节点' },
  { key: 'slaResolveDueAt', label: 'SLA解决截止' },
  { key: 'assigneeName', label: '处理人' },
  { key: 'assigneeId', label: '处理人' },
];

function resultElement(output) {
  const box = el('div', 'result-view');
  if (Array.isArray(output)) {
    if (output.length && output.every((item) => item && typeof item === 'object' && !Array.isArray(item))) {
      if (output.some((item) => item.ticketNo)) {
        box.append(ticketTable(output));
      } else {
        output.forEach((item, index) => {
          if (index) box.append(el('div', 'result-sep'));
          box.append(objectView(item));
        });
      }
    } else {
      const list = el('ul', 'result-list');
      output.forEach((value) => list.append(el('li', '', formatValue('', value))));
      box.append(list);
    }
  } else if (output && typeof output === 'object') {
    box.append(objectView(output));
  } else {
    box.append(el('div', 'result-text', String(output)));
  }
  return box;
}

function ticketTable(items) {
  const columns = TICKET_COLUMNS.filter((column) => items.some((item) =>
    item[column.key] !== undefined && item[column.key] !== null && item[column.key] !== ''));
  const table = el('table', 'result-table');
  const head = el('thead');
  const headRow = el('tr');
  columns.forEach((column) => headRow.append(el('th', '', column.label)));
  head.append(headRow);
  table.append(head);

  const body = el('tbody');
  for (const item of items) {
    const row = el('tr');
    for (const column of columns) {
      let value = item[column.key];
      if (column.key === 'assigneeName' && (value === undefined || value === null)) {
        value = item.assigneeId;
      }
      row.append(el('td', '', value === undefined || value === null || value === ''
        ? '—' : formatValue(column.key, value)));
    }
    body.append(row);
  }
  table.append(body);
  const wrap = el('div', 'result-table-wrap');
  wrap.append(table);
  return wrap;
}

function objectView(object) {
  const grid = el('div', 'result-grid');
  const keys = Object.keys(object)
    .filter((key) => FIELD_LABELS[key])
    .sort((a, b) => fieldOrder(a) - fieldOrder(b));
  for (const key of keys) {
    const value = formatValue(key, object[key]);
    if (value === '' || value === '—') continue;
    grid.append(el('div', 'k', FIELD_LABELS[key]), el('div', 'v', value));
  }
  return grid;
}

function fieldOrder(key) {
  const order = [
    'ticketNo', 'title', 'statusCode', 'priorityCode', 'ticketTypeCode',
    'locationText', 'contactName', 'contactPhoneMask', 'currentNodeCode',
    'slaResolveDueAt', 'taskDueAt', 'createdAt', 'updatedAt', 'description',
    'ruleCode', 'level', 'snippet', 'score', 'summary', 'findingsCount', 'overall',
    'contractId', 'contractNo', 'name', 'reviewStatus',
  ];
  const index = order.indexOf(key);
  return index < 0 ? 999 : index;
}

function formatValue(key, value) {
  if (value === null || value === undefined) return '—';
  const labels = VALUE_LABELS[key];
  if (labels && labels[String(value)]) return labels[String(value)];
  if (typeof value === 'string' && (key.endsWith('At') || key.endsWith('Time'))) {
    const date = new Date(value);
    if (!Number.isNaN(date.getTime())) return date.toLocaleString('zh-CN', { hour12: false });
  }
  if (Array.isArray(value)) return value.map((item) => formatValue('', item)).join('、');
  if (typeof value === 'object') {
    return Object.entries(value)
      .filter(([childKey]) => FIELD_LABELS[childKey])
      .map(([childKey, childValue]) => `${FIELD_LABELS[childKey]}：${formatValue(childKey, childValue)}`)
      .join('；');
  }
  return String(value);
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
    'gis.parcel.query': '时空 · 查询地块',
    'gis.change.query': '时空 · 变化图斑',
    'gis.patrol.create': '时空 · 创建巡查',
    'inspect.report.query': '巡检 · 查询报告',
    'inspect.plan.create': '巡检 · 创建计划',
    'inspect.report.generate': '巡检 · 生成报告',
    'inspect.report.export': '巡检 · 导出报告',
    'policy.qa.ask': '政策 · 法规问答',
    'policy.document.review': '政策 · 文件审查',
    'customer.ticket.query': '客服 · 查询工单',
    'customer.ticket.create': '客服 · 创建工单',
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
  // 画像接口独立加载，物业 Agent 停机时也能查看/清除画像
  try {
    const memory = await api('/api/v1/profile/memory');
    renderMemoryProfile(memory || {});
  } catch (error) {
    els.memoryProfile.textContent = error.message || '画像加载失败';
  }
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
  await loadProfileSections();
}

async function loadProfileSections() {
  if (!els.profileSections) return;
  els.profileSections.replaceChildren();
  try {
    const data = await api('/api/v1/profile/forms');
    const sections = (data?.sections || []).filter(
      (section) => section.agentKey !== 'property-maintenance' && section.available !== false);
    for (const section of sections) {
      renderProfileSection(section);
    }
    els.profileSections.hidden = sections.length === 0;
  } catch {
    // 聚合接口失败不影响物业表单；隐藏扩展段即可。
    els.profileSections.hidden = true;
  }
}

function renderProfileSection(section) {
  const wrap = document.createElement('section');
  wrap.className = 'profile-section';
  const head = document.createElement('div');
  head.className = 'profile-section-head';
  head.textContent = section.title || section.displayName || section.agentKey;
  wrap.append(head);

  const form = document.createElement('form');
  form.className = 'profile-form';
  const draft = {};
  for (const field of section.form?.fields || []) {
    const label = el('label', field.required ? 'required' : '', field.label || field.name);
    let input;
    if (field.type === 'select') {
      input = document.createElement('select');
      input.append(new Option('请选择', ''));
      const options = Array.isArray(field.options) ? field.options : [];
      for (const option of options) {
        const value = typeof option === 'object'
          ? (option.id ?? option.value ?? option.code ?? option.name ?? '')
          : option;
        const text = typeof option === 'object'
          ? (option.name ?? option.label ?? option.title ?? value)
          : option;
        input.append(new Option(text, value));
      }
    } else {
      input = document.createElement('input');
      input.type = field.sensitive ? 'password' : 'text';
      if (field.maxLength) input.maxLength = field.maxLength;
      input.autocomplete = 'off';
    }
    input.addEventListener('input', () => {
      draft[field.name] = input.value;
    });
    input.addEventListener('change', () => {
      draft[field.name] = input.value;
    });
    label.append(input);
    form.append(label);
  }
  wrap.append(form);

  const actions = document.createElement('div');
  actions.className = 'profile-section-actions';
  const save = document.createElement('button');
  save.type = 'button';
  save.className = 'btn primary small';
  save.textContent = '保存';
  save.hidden = !section.updateCapability;
  save.addEventListener('click', () => saveProfileSection(section, draft, save));
  actions.append(save);
  wrap.append(actions);
  els.profileSections.append(wrap);
}

async function saveProfileSection(section, draft, button) {
  button.disabled = true;
  try {
    const payload = {};
    for (const [key, value] of Object.entries(draft)) {
      if (value === undefined || value === null || String(value).trim() === '') continue;
      payload[key] = /^\d+$/.test(String(value)) ? Number(value) : value;
    }
    let task = await api(`/api/v1/profile/forms/${encodeURIComponent(section.agentKey)}`, {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    if (task.status === 'WAITING_CONFIRM') {
      const confirmed = await askConfirm(
        `确认保存「${section.title || section.displayName || section.agentKey}」？`,
        { title: '保存资料', confirmText: '确认保存', danger: false },
      );
      if (!confirmed) return;
      task = await api(`/api/v1/tasks/${task.taskNo}/confirm`, {
        method: 'POST',
        body: JSON.stringify({ decision: 'APPROVE', comment: '个人信息保存' }),
      });
      if (task.status === 'FAILED' || task.status === 'REJECTED') {
        throw new Error(task.error?.message || '保存失败');
      }
    } else if (task.status !== 'SUCCEEDED') {
      throw new Error(task.error?.message || '保存未完成');
    }
    showToast('资料已保存');
  } catch (error) {
    showToast(error.message || '保存失败');
  } finally {
    button.disabled = false;
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

function renderMemoryProfile(memory) {
  const profile = memory.profile || {};
  if (!Object.keys(profile).length) {
    els.memoryProfile.textContent = '暂无画像（多轮使用后自动生成）';
    return;
  }
  els.memoryProfile.replaceChildren(objectView(profile));
}

async function clearMemory() {
  const confirmed = await askConfirm('确定清除全局用户画像吗？会话摘要不受影响。',
    { title: '清除用户画像', confirmText: '清除', danger: true });
  if (!confirmed) return;
  try {
    await api('/api/v1/profile/memory', { method: 'DELETE' });
    els.memoryProfile.textContent = '已清除';
    showToast('用户画像已清除');
  } catch (error) {
    els.profileError.textContent = error.message || '清除失败';
  }
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
  if (els.settingNotify.checked) {
    startNotifications();
  } else {
    stopNotifications();
  }
  showToast('设置已保存');
}

function notifyEnabled() {
  return localStorage.getItem('wuye_notify') !== 'off';
}

function stopNotifications() {
  if (state.notifyTimer) {
    clearInterval(state.notifyTimer);
    state.notifyTimer = null;
  }
  if (state.notifyAbort) {
    state.notifyAbort.abort();
    state.notifyAbort = null;
  }
  state.notifications = [];
  if (els.notifyBadge) els.notifyBadge.hidden = true;
  if (els.notifyList) els.notifyList.innerHTML = '<div class="notify-empty">暂无通知</div>';
}

function startNotifications() {
  if (!notifyEnabled() || !state.token) {
    stopNotifications();
    return;
  }
  refreshNotifications().catch(() => {});
  if (state.notifyTimer) clearInterval(state.notifyTimer);
  state.notifyTimer = setInterval(() => refreshNotifications().catch(() => {}), 30000);
  connectNotificationStream();
}

async function refreshNotifications() {
  const data = await api('/api/v1/notifications?limit=20');
  state.notifications = data.items || [];
  renderNotifications(Number(data.unread || 0));
}

function renderNotifications(unread) {
  if (!els.notifyBadge || !els.notifyList) return;
  const count = Number(unread || 0);
  els.notifyBadge.textContent = count > 99 ? '99+' : String(count);
  els.notifyBadge.hidden = count <= 0;
  if (!state.notifications.length) {
    els.notifyList.innerHTML = '<div class="notify-empty">暂无通知</div>';
    return;
  }
  els.notifyList.innerHTML = state.notifications.map((item) => {
    const unreadClass = item.status === 'UNREAD' ? ' unread' : '';
    const time = item.createdAt ? new Date(item.createdAt).toLocaleString('zh-CN', { hour12: false }) : '';
    return `
      <div class="notify-item${unreadClass}" data-no="${escapeHtml(item.notificationNo)}" data-link="${escapeHtml(item.linkUrl || '')}">
        <div class="notify-title">${escapeHtml(item.title || '通知')}</div>
        <div class="notify-content">${escapeHtml(item.content || '')}</div>
        <div class="notify-meta"><span class="notify-source">${escapeHtml(item.sourceAgent || '')}</span><span>${escapeHtml(time)}</span></div>
      </div>`;
  }).join('');
}

async function openNotification(notificationNo) {
  const item = state.notifications.find((entry) => entry.notificationNo === notificationNo);
  if (item && item.status === 'UNREAD') {
    try {
      await api(`/api/v1/notifications/${encodeURIComponent(notificationNo)}/read`, { method: 'POST' });
      item.status = 'READ';
      const unread = state.notifications.filter((entry) => entry.status === 'UNREAD').length;
      renderNotifications(unread);
    } catch (error) {
      console.warn('mark notification read failed', error);
    }
  }
  if (item && item.linkUrl) {
    window.open(item.linkUrl, '_blank', 'noopener');
  }
}

async function markAllNotificationsRead() {
  try {
    const data = await api('/api/v1/notifications/read-all', { method: 'POST' });
    state.notifications.forEach((item) => { item.status = 'READ'; });
    renderNotifications(Number(data.unread || 0));
  } catch (error) {
    showToast(error.message || '操作失败');
  }
}

async function connectNotificationStream() {
  if (!notifyEnabled() || !state.token) return;
  if (state.notifyAbort) state.notifyAbort.abort();
  const controller = new AbortController();
  state.notifyAbort = controller;
  try {
    const response = await fetch('/api/v1/notifications/stream', {
      headers: { Authorization: `Bearer ${state.token}` },
      signal: controller.signal,
    });
    if (!response.ok || !response.body) return;
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let index = buffer.indexOf('\n\n');
      while (index >= 0) {
        handleNotificationSse(buffer.slice(0, index));
        buffer = buffer.slice(index + 2);
        index = buffer.indexOf('\n\n');
      }
    }
  } catch (error) {
    if (error.name !== 'AbortError') {
      console.warn('notification stream failed', error);
    }
  }
  if (notifyEnabled() && state.token && state.notifyAbort === controller) {
    setTimeout(connectNotificationStream, 5000);
  }
}

function handleNotificationSse(chunk) {
  const eventLine = chunk.split('\n').find((line) => line.startsWith('event:'));
  const dataLine = chunk.split('\n').find((line) => line.startsWith('data:'));
  if (eventLine && eventLine.slice(6).trim() !== 'notification') return;
  if (!dataLine) return;
  try {
    const item = JSON.parse(dataLine.slice(5).trim());
    if (!item || !item.notificationNo) return;
    state.notifications = [item, ...state.notifications.filter((entry) => entry.notificationNo !== item.notificationNo)];
    const unread = state.notifications.filter((entry) => entry.status === 'UNREAD').length;
    renderNotifications(unread);
    showToast(`新通知：${item.title || '站内通知'}`);
  } catch (error) {
    console.warn('bad notification event', error);
  }
}

async function openDashboard() {
  els.dashboardModal.hidden = false;
  els.dashboardCards.textContent = '加载中…';
  const businessContainer = document.getElementById('dashboardBusiness');
  if (businessContainer) businessContainer.replaceChildren();
  els.dashboardFunnel.replaceChildren();
  els.dashboardTable.replaceChildren();
  try {
    const data = await api('/api/v1/admin/metrics/summary');
    renderDashboard(data);
  } catch (error) {
    els.dashboardCards.textContent = error.message || '加载失败';
  }
}

function renderDashboard(data) {
  const tasks = data.tasks || {};
  const human = data.humanIntervention || {};
  const llm = data.llmUsage || {};
  const total = tasks.total || 0;
  const succeeded = tasks.succeeded || 0;
  const businessCards = [
    ['自动化完成率', `${Math.round((tasks.successRate || 0) * 100)}%`],
    ['预计节省工时', `${((succeeded * 8) / 60).toFixed(1)} h`],
    ['单任务 LLM 成本', `¥${(total ? (llm.costCny || 0) / total : 0).toFixed(4)}`],
  ];
  const businessContainer = document.getElementById('dashboardBusiness');
  if (businessContainer) {
    businessContainer.replaceChildren(...businessCards.map(([label, value]) => {
      const card = el('div', 'metric-card');
      card.append(el('div', 'label', label), el('div', 'value', value));
      return card;
    }));
  }
  const cards = [
    ['任务总数', String(tasks.total || 0)],
    ['成功率', `${Math.round((tasks.successRate || 0) * 100)}%`],
    ['待确认', String(tasks.waitingConfirm || 0)],
    ['平均耗时', `${tasks.avgLatencyMs || 0} ms`],
    ['人工介入率', `${Math.round((human.rate || 0) * 100)}%`],
    ['LLM 调用', String(llm.calls || 0)],
    ['Token 总量', String(llm.totalTokens || 0)],
    ['成本（元）', Number(llm.costCny || 0).toFixed(4)],
  ];
  els.dashboardCards.replaceChildren(...cards.map(([label, value]) => {
    const card = el('div', 'metric-card');
    card.append(el('div', 'label', label), el('div', 'value', value));
    return card;
  }));

  const funnel = data.funnel || {};
  const steps = [
    ['建单', funnel.create || 0],
    ['派单', funnel.assign || 0],
    ['接单', funnel.accept || 0],
    ['解决', funnel.resolve || 0],
    ['回访', funnel.visit || 0],
  ];
  els.dashboardFunnel.replaceChildren(...steps.map(([name, count]) => {
    const step = el('div', 'funnel-step');
    step.append(el('div', 'name', name), el('div', 'count', String(count)));
    return step;
  }));

  const table = el('table', 'metric-table');
  const head = el('tr');
  ['能力', '调用数', '成功', '失败'].forEach((label) => head.append(el('th', '', label)));
  table.append(head);
  for (const item of data.byCapability || []) {
    const row = el('tr');
    row.append(
      el('td', '', capabilityLabel(item.capabilityId)),
      el('td', '', String(item.total)),
      el('td', '', String(item.succeeded)),
      el('td', '', String(item.failed)),
    );
    table.append(row);
  }
  els.dashboardTable.replaceChildren(table);
}

let observabilityTimer = null;

async function openObservability() {
  els.observabilityModal.hidden = false;
  els.observabilitySummary.textContent = '加载中…';
  els.observabilityOrchestration.replaceChildren();
  els.observabilityEvents.replaceChildren();
  els.observabilitySamples.replaceChildren();
  await refreshObservability();
  clearInterval(observabilityTimer);
  observabilityTimer = setInterval(refreshObservability, 5000);
}

function closeObservability() {
  els.observabilityModal.hidden = true;
  clearInterval(observabilityTimer);
  observabilityTimer = null;
}

async function refreshObservability() {
  if (els.observabilityModal.hidden) return;
  try {
    const [events, samples, orchestration] = await Promise.all([
      api('/api/v1/admin/events/recent?limit=20'),
      api('/api/v1/admin/flywheel/samples?limit=10'),
      api('/api/v1/admin/orchestration/recent?limit=20'),
    ]);
    if (els.observabilityModal.hidden) return;
    renderObservability(events, samples, orchestration);
  } catch (error) {
    els.observabilitySummary.textContent = error.message || '加载失败';
  }
}

function renderObservability(eventsData, samplesData, orchestrationData) {
  const bus = eventsData.stats || {};
  const outbox = eventsData.outbox || {};
  const flywheel = samplesData.stats || {};
  const orchestration = orchestrationData.stats || {};
  const verdicts = orchestration.verdicts || {};
  const pending = outbox.pending != null
    ? outbox.pending
    : ((outbox.counts && outbox.counts.PENDING) || 0);
  const dispatched = outbox.dispatched != null
    ? outbox.dispatched
    : ((outbox.counts && outbox.counts.DISPATCHED) || 0);
  const sampleTotal = flywheel.total != null
    ? flywheel.total
    : (flywheel.recent != null ? flywheel.recent : 0);
  const hasIssue = (bus.failed || 0) > 0
    || pending > 0
    || (verdicts.FAILED || 0) > 0;
  els.observabilitySummary.classList.toggle('warn', hasIssue);
  els.observabilitySummary.textContent =
    `事件 已发布 ${bus.published || 0} · 缓冲 ${bus.buffered || 0} · 消费失败 ${bus.failed || 0}`
    + ` ｜ Outbox ${outbox.backend || '-'} · 已投递 ${dispatched} · PENDING ${pending}`
    + ` ｜ 编排 ${orchestration.size || 0} 条`
    + ` ｜ 飞轮 ${flywheel.backend || 'JSONL'} · ${sampleTotal} 条`;

  const traces = (orchestrationData.traces || []).slice().reverse();
  if (!traces.length) {
    els.observabilityOrchestration.replaceChildren(el('div', 'notify-empty', '暂无编排记录'));
  } else {
    els.observabilityOrchestration.replaceChildren(...traces.map((trace) => {
      const phase = trace.phase === 'VERIFY' ? '验收' : '编排';
      const verdict = trace.verdict || 'UNKNOWN';
      const capabilities = (trace.capabilities || []).join(' → ') || '-';
      const row = el('div', 'audit-row');
      const main = el('div');
      main.append(
        el('div', 'main', `${phase} · ${capabilities}`),
        el('div', 'meta',
          `${trace.traceId || '-'} · ${formatDate(trace.occurredAt)}`
          + (trace.clarification ? ` · ${trace.clarification}` : '')),
      );
      const pillClass = verdict === 'COMPLETE'
        ? 'SUCCEEDED'
        : (verdict === 'FAILED' ? 'FAILED' : 'PENDING');
      row.append(main, el('span', `status ${pillClass}`, verdict));
      return row;
    }));
  }

  // 进程内缓冲重启后为空；此时用 Outbox 历史补足最近事件。
  const liveEvents = eventsData.events || [];
  const events = liveEvents.length ? liveEvents : (eventsData.outboxEvents || []);
  if (!events.length) {
    els.observabilityEvents.replaceChildren(el('div', 'notify-empty', '暂无事件'));
  } else {
    els.observabilityEvents.replaceChildren(...events.slice().reverse().map((event) => {
      const row = el('div', 'audit-row');
      const main = el('div');
      main.append(
        el('div', 'main', event.eventType || '-'),
        el('div', 'meta',
          `${event.subject || '-'} · ${event.actorId || '-'} · ${formatDate(event.occurredAt)}`),
      );
      row.append(main, el('span', 'obs-version', `v${event.eventVersion || 1}`));
      return row;
    }));
  }

  const samples = samplesData.samples || [];
  if (!samples.length) {
    els.observabilitySamples.replaceChildren(el('div', 'notify-empty', '暂无样本'));
  } else {
    els.observabilitySamples.replaceChildren(...samples.slice().reverse().map((sample) => {
      const row = el('div', 'audit-row');
      const main = el('div');
      main.append(
        el('div', 'main', `${capabilityLabel(sample.capabilityId)} · ${sample.taskNo || '-'}`),
        el('div', 'meta',
          `${sample.agentKey || '-'} · ${sample.durationMs || 0} ms · ${formatDate(sample.occurredAt)}`),
      );
      row.append(main, el('span', `status ${sample.status || 'PENDING'}`, statusLabel(sample.status)));
      return row;
    }));
  }
}

const ACTION_LABELS = {
  LOGIN: '登录',
  TASK_SUBMIT: '提交任务',
  TASK_CONFIRM: '确认任务',
  TASK_CANCEL: '取消任务',
  SESSION_DELETE: '删除会话',
  TOOL_EXECUTE: '调用工具',
  HUMAN_CLAIM: '认领人工任务',
  HUMAN_RESOLVE: '处理人工任务',
};

async function openA2A() {
  els.a2aModal.hidden = false;
  els.a2aSummary.textContent = '加载中…';
  els.a2aList.replaceChildren();
  try {
    const summary = await api('/api/v1/admin/a2a/summary');
    renderA2A(summary);
  } catch (error) {
    els.a2aSummary.textContent = error.message || '加载失败';
  }
}

function renderA2A(summary) {
  const main = summary.main || {};
  const agents = summary.agents || [];
  const compat = summary.compatibility || {};
  els.a2aSummary.textContent =
    `协议 ${main.protocol || '-'} · ${main.a2aCompatibility || compat.status || '-'} · `
    + `子 Agent ${agents.length} 个 · JSON-RPC ${compat.jsonRpc ? '已启用' : '未实现（保留 facade）'}`;
  const cards = [a2aCard(main, '主 Agent')];
  for (const item of agents) {
    cards.push(a2aCard(item.card || {}, '子 Agent', item.health));
  }
  els.a2aList.replaceChildren(...cards);
}

function a2aCard(card, kind, health) {
  const box = el('div', 'a2a-card');
  const head = el('div', 'a2a-head');
  head.append(
    el('div', 'a2a-name', card.name || card.agentId || '未命名 Agent'),
    el('span', 'pill', kind),
  );
  box.append(head);
  box.append(el('div', 'a2a-meta',
    `${card.agentId || '-'} · v${card.version || '-'} · ${card.protocol || '-'}`
    + `${card.dialect ? ` · ${card.dialect}` : ''}`));
  if (health) {
    box.append(el('div', 'a2a-meta',
      `健康 ${health.status || '-'} / 就绪 ${health.ready || '-'} · ${health.latencyMs ?? '-'} ms`));
  }
  const capabilities = card.capabilities || [];
  const caps = el('div', 'a2a-caps');
  for (const capability of capabilities.slice(0, 10)) {
    caps.append(el('span', 'a2a-cap', capability.capabilityId || capability.name || '-'));
  }
  if (capabilities.length > 10) {
    caps.append(el('span', 'a2a-cap', `+${capabilities.length - 10}`));
  }
  box.append(caps);
  const features = card.features || {};
  const confirmation = features.humanConfirmation ?? features.confirmation;
  box.append(el('div', 'a2a-meta',
    `${features.sse ? 'SSE' : '事件轮询'} · 人工确认 ${confirmation ? '✓' : '—'}`
    + ` · 取消 ${features.cancel ? '✓' : '—'} · 流式 ${features.streaming ? '✓' : '—'}`));
  return box;
}

function openInstallAgent() {
  els.installAgentError.textContent = '';
  els.installAgentModal.hidden = false;
  setTimeout(() => els.installAgentUrl.focus(), 0);
}

async function installTemplateAgent() {
  els.installAgentError.textContent = '';
  els.installTemplateAgent.disabled = true;
  try {
    const result = await api('/api/v1/admin/agents/install', {
      method: 'POST',
      body: JSON.stringify({ template: 'inspection' }),
    });
    await refreshCatalog();
    els.installAgentModal.hidden = true;
    showToast(`已安装：${result.displayName || result.agentKey}`);
  } catch (error) {
    els.installAgentError.textContent = error.message || '安装失败';
  } finally {
    els.installTemplateAgent.disabled = false;
  }
}

async function installDemoEnterpriseAgent(id) {
  els.installAgentUrl.value = `${location.origin}/demo-agents/${id}`;
  await installAgentFromUrl();
}

async function installAgentFromUrl() {
  const cardUrl = els.installAgentUrl.value.trim();
  if (!cardUrl) {
    els.installAgentError.textContent = '请输入 Agent Card URL';
    return;
  }
  els.installAgentError.textContent = '';
  els.installFromUrl.disabled = true;
  try {
    const result = await api('/api/v1/admin/agents/install', {
      method: 'POST',
      body: JSON.stringify({
        cardUrl,
        internalToken: els.installAgentToken.value || undefined,
      }),
    });
    await refreshCatalog();
    els.installAgentModal.hidden = true;
    els.installAgentUrl.value = '';
    els.installAgentToken.value = '';
    showToast(`已安装：${result.displayName || result.agentKey}`);
  } catch (error) {
    els.installAgentError.textContent = error.message || '安装失败';
  } finally {
    els.installFromUrl.disabled = false;
  }
}

async function uninstallAgent(agentKey, displayName) {
  const ok = await askConfirm(
    `确定卸载「${displayName}」吗？卸载后该 Agent 的能力会从目录移除。`,
    { title: '卸载 Agent', confirmText: '卸载', danger: true },
  );
  if (!ok) return;
  try {
    await api(`/api/v1/admin/agents/${encodeURIComponent(agentKey)}`, { method: 'DELETE' });
    await refreshCatalog();
    showToast(`已卸载：${displayName}`);
  } catch (error) {
    showToast(error.message || '卸载失败');
  }
}

async function refreshCatalog() {
  const me = await api('/api/v1/auth/me');
  state.me = me.user;
  state.agents = me.agents || [];
  state.capabilities = new Set(
    state.agents.flatMap((agent) => (agent.capabilities || []).map((cap) => cap.capabilityId)),
  );
  renderCurrentUser();
  renderAgents();
}

async function openHumanTasks() {
  els.humanModal.hidden = false;
  els.humanList.textContent = '加载中…';
  try {
    const items = await api('/api/v1/human-tasks?status=PENDING&limit=50');
    els.humanList.replaceChildren();
    if (!items.length) {
      els.humanList.append(el('div', 'meta', '暂无待人工处理的任务'));
      return;
    }
    for (const item of items) {
      const human = item.human || {};
      const task = item.task || {};
      const row = el('div', 'audit-row');
      const main = el('div', 'main',
        `${capabilityLabel(task.capabilityId)} · ${human.taskNo || task.taskNo || ''}`);
      const status = el('span', 'status PENDING', humanStatusLabel(human.status));
      const meta = el('div', 'meta',
        `任务状态：${statusLabel(task.status)} · 创建：${formatDate(human.createdAt)}`);
      const actions = el('div', 'actions');
      const claim = el('button', 'btn primary small', '认领');
      claim.type = 'button';
      claim.addEventListener('click', () => claimHuman(human.taskNo));
      const resolve = el('button', 'btn small', '标记处理');
      resolve.type = 'button';
      resolve.addEventListener('click', () => resolveHuman(human.taskNo));
      actions.append(claim, resolve);
      row.append(main, status, meta, actions);
      els.humanList.append(row);
    }
  } catch (error) {
    els.humanList.textContent = error.message || '加载失败';
  }
}

async function claimHuman(taskNo) {
  try {
    await api(`/api/v1/human-tasks/${taskNo}/claim`, { method: 'POST' });
    showToast('已认领');
    await openHumanTasks();
  } catch (error) {
    showToast(error.message || '认领失败');
  }
}

async function resolveHuman(taskNo) {
  const confirmed = await askConfirm('确认该人工任务已处理完成？',
    { title: '处理人工任务', confirmText: '标记完成', danger: false });
  if (!confirmed) return;
  try {
    await api(`/api/v1/human-tasks/${taskNo}/resolve`, {
      method: 'POST',
      body: JSON.stringify({ note: '人工处理完成' }),
    });
    showToast('已标记处理');
    await openHumanTasks();
  } catch (error) {
    showToast(error.message || '处理失败');
  }
}

function humanStatusLabel(status) {
  return ({ PENDING: '待处理', CLAIMED: '已认领', RESOLVED: '已处理' })[status] || status;
}

async function openAudit() {
  els.auditModal.hidden = false;
  els.auditList.textContent = '加载中…';
  try {
    const logs = await api('/api/v1/admin/audit-logs?limit=50');
    els.auditList.replaceChildren();
    if (!logs.length) {
      els.auditList.append(el('div', 'meta', '暂无审计记录'));
      return;
    }
    for (const log of logs) {
      const row = el('div', 'audit-row');
      const main = el('div', 'main',
        `${ACTION_LABELS[log.action] || log.action} · ${log.resource || ''}${log.resourceId ? ` · ${log.resourceId}` : ''}`);
      const status = el('span', `status ${log.status}`, log.status === 'SUCCESS' ? '成功' : '失败');
      const meta = el('div', 'meta',
        `${log.username || 'anonymous'} · ${formatDate(log.createdAt)}${log.capabilityId ? ` · ${capabilityLabel(log.capabilityId)}` : ''}`);
      row.append(main, status, meta);
      els.auditList.append(row);
    }
  } catch (error) {
    els.auditList.textContent = error.message || '加载失败';
  }
}

function formatDate(value) {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
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
