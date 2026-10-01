/* AI 网关 Web 控制台 —— 零依赖单页应用。
 * 后端：/admin/api/**（X-Admin-Token）；对话：/v1/chat/completions（虚拟 Key）。
 * 设计参考 new-api：左侧模块化导航 + 资源化 API + 渠道测试/复制/批量测试。
 */
(() => {
  'use strict';

  const state = { token: localStorage.getItem('gw_token') || '', view: 'dashboard', connected: false };
  const $ = (s) => document.querySelector(s);

  // ---------------- helpers ----------------
  const esc = (s) => String(s == null ? '' : s)
    .replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  async function api(path, opts = {}) {
    const res = await fetch('/admin/api' + path, {
      method: opts.method || 'GET',
      headers: Object.assign({ 'X-Admin-Token': state.token },
        opts.body ? { 'Content-Type': 'application/json' } : {}),
      body: opts.body ? JSON.stringify(opts.body) : undefined,
    });
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = text; }
    if (res.status === 401) {
      // 会话失效：回登录界面
      state.connected = false;
      state.token = '';
      localStorage.removeItem('gw_token');
      renderAuth();
    }
    if (!res.ok) {
      const msg = (data && data.error && data.error.message) || (typeof data === 'string' && data) || ('HTTP ' + res.status);
      throw new Error(msg);
    }
    return data;
  }

  function toast(msg, type = 'ok') {
    const box = $('#toast');
    const item = document.createElement('div');
    item.className = 'item ' + (type === 'error' ? 'error' : 'ok');
    item.textContent = msg;
    box.appendChild(item);
    setTimeout(() => item.remove(), 4200);
  }

  function table(headers, rows) {
    const head = headers.map((h) => `<th>${esc(h)}</th>`).join('');
    const body = rows.length
      ? rows.map((r) => `<tr>${r.map((c) => `<td>${c == null ? '' : c}</td>`).join('')}</tr>`).join('')
      : `<tr><td colspan="${headers.length}" class="empty">暂无数据</td></tr>`;
    return `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`;
  }

  function openForm(title, fields, onOk) {
    const rows = fields.map((f) => {
      const id = 'f_' + f.name;
      const label = esc(f.label || f.name);
      if (f.type === 'select') {
        const opts = (f.options || []).map((o) =>
          `<option value="${esc(o.value)}"${String(o.value) === String(f.value) ? ' selected' : ''}>${esc(o.label)}</option>`).join('');
        return `<label>${label}<select id="${id}">${opts}</select></label>`;
      }
      if (f.type === 'textarea') {
        return `<label>${label}<textarea id="${id}" rows="${f.rows || 4}" placeholder="${esc(f.placeholder || '')}">${esc(f.value || '')}</textarea></label>`;
      }
      return `<label>${label}<input id="${id}" type="${f.type || 'text'}" value="${esc(f.value == null ? '' : f.value)}" placeholder="${esc(f.placeholder || '')}"></label>`;
    }).join('');
    const root = $('#modalRoot');
    root.innerHTML = `<div class="modal-mask"><div class="modal"><h3>${esc(title)}</h3>
      <div class="form">${rows}</div>
      <div class="modal-actions"><button class="btn" data-cancel>取消</button><button class="btn primary" data-ok>确定</button></div>
    </div></div>`;
    const close = () => { root.innerHTML = ''; };
    root.querySelector('[data-cancel]').onclick = close;
    root.querySelector('[data-ok]').onclick = async () => {
      const values = {};
      for (const f of fields) {
        const el = document.getElementById('f_' + f.name);
        values[f.name] = f.type === 'number'
          ? (el.value === '' ? null : Number(el.value))
          : el.value;
      }
      try { await onOk(values); close(); } catch (e) { toast(e.message, 'error'); }
    };
  }

  function confirmBox(title, onOk) {
    const root = $('#modalRoot');
    root.innerHTML = `<div class="modal-mask"><div class="modal"><h3>${esc(title)}</h3>
      <div class="modal-actions"><button class="btn" data-cancel>取消</button><button class="btn danger" data-ok>确定</button></div>
    </div></div>`;
    const close = () => { root.innerHTML = ''; };
    root.querySelector('[data-cancel]').onclick = close;
    root.querySelector('[data-ok]').onclick = async () => {
      try { await onOk(); close(); } catch (e) { toast(e.message, 'error'); }
    };
  }

  const num = (v) => (v == null ? 0 : Number(v));
  const tag = (on, onText, offText) => on
    ? `<span class="tag ok">${esc(onText)}</span>` : `<span class="tag muted">${esc(offText)}</span>`;

  // ---------------- navigation ----------------
  const NAV = [
    ['dashboard', '概览'], ['channels', '渠道'], ['providers', '供应商'],
    ['models', '模型'], ['apps', '应用'], ['keys', '密钥'],
    ['prices', '定价'], ['usage', '用量'], ['playground', '对话'], ['system', '系统'],
  ];

  function renderNav() {
    $('#nav').innerHTML = NAV.map(([id, label]) =>
      `<a data-act="go" data-view="${id}" class="${state.view === id ? 'active' : ''}">${esc(label)}</a>`).join('');
    bindActions($('#nav'));
  }

  function bindActions(root) {
    root.querySelectorAll('[data-act]').forEach((el) => {
      el.addEventListener('click', () => {
        const fn = App[el.dataset.act];
        if (typeof fn === 'function') fn(el.dataset, el);
      });
    });
  }

  async function mount(html) {
    const v = $('#view');
    v.innerHTML = html;
    bindActions(v);
  }

  async function go(view) {
    state.view = view;
    renderNav();
    try {
      const html = await (VIEWS[view] || VIEWS.dashboard)();
      await mount(html);
    } catch (e) {
      await mount(`<div class="empty">加载失败：${esc(e.message)}</div>`);
    }
  }

  // ---------------- views ----------------
  const VIEWS = {};

  VIEWS.dashboard = async () => {
    const s = await api('/status');
    const doctor = await api('/doctor');
    const doctorRows = doctor.map((c) => {
      const cls = c.level === 'OK' ? 'ok' : (c.level === 'WARN' ? 'warn' : 'fail');
      return [`<span class="tag ${cls}">${esc(c.level)}</span>`, esc(c.name), esc(c.detail)];
    });
    return `<h2>概览</h2><div class="sub">配置版本 ${esc(s.configVersion)} · 加载于 ${esc(s.loadedAt)}</div>
      <div class="cards">
        <div class="card"><div class="k">供应商</div><div class="v">${num(s.providers)}</div></div>
        <div class="card"><div class="k">渠道</div><div class="v">${num(s.channels)}</div></div>
        <div class="card"><div class="k">逻辑模型</div><div class="v">${num(s.models)}</div></div>
        <div class="card"><div class="k">虚拟 Key</div><div class="v">${num(s.virtualKeys)}</div></div>
        <div class="card"><div class="k">定价条目</div><div class="v">${num(s.prices)}</div></div>
        <div class="card"><div class="k">熔断中渠道</div><div class="v" style="color:${num(s.circuitOpen) > 0 ? 'var(--fail)' : 'var(--ok)'}">${num(s.circuitOpen)}</div></div>
      </div>
      <div class="toolbar"><button class="btn primary" data-act="refreshConfig">刷新配置</button>
        <span class="hint">适配器：${esc((s.adapters || []).join(', '))}</span></div>
      <h2 style="margin-top:18px">自检</h2><div class="sub">数据库 / Redis / 密钥 / 默认凭据 / 适配器</div>
      ${table(['状态', '检查项', '说明'], doctorRows)}`;
  };

  VIEWS.channels = async () => {
    const channels = await api('/channels');
    const rows = channels.map((c) => {
      const mapping = c.modelMapping ? Object.entries(c.modelMapping).map(([k, v]) => k + '→' + v).join(', ') : '-';
      const circuit = c.circuit === 'CLOSED' ? '<span class="tag ok">CLOSED</span>'
        : `<span class="tag fail">${esc(c.circuit)}</span>`;
      const actions = [
        `<button class="btn mini" data-act="channelKey" data-id="${c.id}" data-name="${esc(c.name)}">密钥</button>`,
        `<button class="btn mini" data-act="channelTest" data-id="${c.id}">测试</button>`,
        c.status === 'ACTIVE'
          ? `<button class="btn mini" data-act="channelStatus" data-id="${c.id}" data-status="DISABLED">停用</button>`
          : `<button class="btn mini" data-act="channelStatus" data-id="${c.id}" data-status="ACTIVE">启用</button>`,
        `<button class="btn mini" data-act="channelReset" data-id="${c.id}">重置熔断</button>`,
        `<button class="btn mini" data-act="channelCopy" data-id="${c.id}">复制</button>`,
        `<button class="btn mini danger" data-act="channelDelete" data-id="${c.id}" data-name="${esc(c.name)}">删除</button>`,
      ].join(' ');
      return [
        String(c.id), esc(c.name), esc(c.provider), c.status === 'ACTIVE' ? '<span class="tag ok">ACTIVE</span>' : '<span class="tag muted">DISABLED</span>',
        `<span class="mono">${esc(mapping)}</span>`, c.hasKey ? '<span class="tag ok">已配置</span>' : '<span class="tag warn">未配置</span>',
        num(c.rpmLimit) || '-', num(c.tpmLimit) || '-', num(c.concurrencyLimit) || '-', circuit, actions,
      ];
    });
    return `<h2>渠道</h2><div class="sub">渠道 = 供应商 + 密钥 + 模型映射，是路由 / 熔断 / 配额的最小单元。</div>
      <div class="toolbar">
        <button class="btn primary" data-act="channelAdd">新增渠道</button>
        <button class="btn" data-act="channelTestAll">批量测试</button>
      </div>
      ${table(['ID', '名称', '供应商', '状态', '模型映射', '密钥', 'RPM', 'TPM', '并发', '熔断', '操作'], rows)}`;
  };

  VIEWS.providers = async () => {
    const providers = await api('/providers');
    const rows = providers.map((p) => {
      const actions = [
        `<button class="btn mini" data-act="providerEdit" data-id="${p.id}" data-code="${esc(p.code)}" data-name="${esc(p.name)}" data-baseurl="${esc(p.baseUrl || '')}" data-adapter="${esc(p.adapterClass || '')}">编辑</button>`,
        `<button class="btn mini danger" data-act="providerDelete" data-id="${p.id}" data-code="${esc(p.code)}">删除</button>`,
      ].join(' ');
      return [
        String(p.id), esc(p.code), esc(p.name), `<span class="mono">${esc(p.baseUrl || '-')}</span>`,
        `<span class="mono">${esc(p.adapterClass || '-')}</span>`, tag(p.enabled, '启用', '停用'), String(p.channelCount), actions,
      ];
    });
    return `<h2>供应商</h2><div class="sub">供应商 = 协议适配器 + 默认 baseUrl。新增供应商后到「渠道」里配置密钥与模型映射。</div>
      <div class="toolbar"><button class="btn primary" data-act="providerAdd">新增供应商</button></div>
      ${table(['ID', 'Code', '名称', 'Base URL', '适配器', '状态', '渠道数', '操作'], rows)}`;
  };

  VIEWS.models = async () => {
    const models = await api('/models');
    const rows = models.map((m) => {
      const fb = (m.fallbackChain || []).join(',') || '-';
      const actions = [
        `<button class="btn mini" data-act="modelFallback" data-name="${esc(m.logicalName)}" data-fallback="${esc((m.fallbackChain || []).join(','))}">降级链</button>`,
        m.enabled === 1
          ? `<button class="btn mini" data-act="modelStatus" data-name="${esc(m.logicalName)}" data-enabled="0">停用</button>`
          : `<button class="btn mini" data-act="modelStatus" data-name="${esc(m.logicalName)}" data-enabled="1">启用</button>`,
      ].join(' ');
      return [String(m.id), esc(m.logicalName), esc(m.type),
        tag(m.enabled === 1, '启用', '停用'), `<span class="mono">${esc(fb)}</span>`, esc(m.description || '-'), actions];
    });
    return `<h2>逻辑模型</h2><div class="sub">业务侧看到的稳定名字（chat-default），与上游物理模型解耦。</div>
      <div class="toolbar"><button class="btn primary" data-act="modelAdd">新增模型</button></div>
      ${table(['ID', '逻辑名', '类型', '状态', '降级链', '说明', '操作'], rows)}`;
  };

  VIEWS.apps = async () => {
    const apps = await api('/apps');
    const rows = apps.map((a) => {
      const models = (a.allowedModels || []).join(',') || '全部';
      const actions = [
        `<button class="btn mini" data-act="appBalance" data-id="${a.id}" data-name="${esc(a.name)}">余额</button>`,
        a.status === 1
          ? `<button class="btn mini" data-act="appStatus" data-id="${a.id}" data-enabled="0">停用</button>`
          : `<button class="btn mini" data-act="appStatus" data-id="${a.id}" data-enabled="1">启用</button>`,
      ].join(' ');
      return [String(a.id), String(a.tenantId), esc(a.name), num(a.dailyBudget) || '-', num(a.monthlyBudget) || '-',
        `<span class="mono">${esc(models)}</span>`, tag(a.status === 1, '启用', '停用'), actions];
    });
    return `<h2>应用 / 租户</h2><div class="sub">预算、可用模型、脱敏策略的归属单元；虚拟 Key 挂在应用下。</div>
      <div class="toolbar"><button class="btn primary" data-act="appAdd">新增应用</button></div>
      ${table(['ID', '租户', '名称', '日预算', '月预算', '可用模型', '状态', '操作'], rows)}`;
  };

  VIEWS.keys = async () => {
    const apps = await api('/apps');
    const keys = await api('/keys');
    const appName = {};
    apps.forEach((a) => { appName[a.id] = a.name; });
    const rows = keys.map((k) => [
      String(k.id), String(k.appId), esc(k.appName || appName[k.appId] || '-'),
      `<span class="mono">${esc(k.keyPrefix)}</span>`,
      k.status === '启用' ? '<span class="tag ok">启用</span>' : '<span class="tag fail">已吊销</span>',
      esc(k.expireAt || '永不过期'), num(k.rpmLimit) || '-', num(k.tpmLimit) || '-', num(k.concurrencyLimit) || '-',
      k.status === '启用' ? `<button class="btn mini danger" data-act="keyRevoke" data-id="${k.id}">吊销</button>` : '',
    ]);
    return `<h2>虚拟 Key</h2><div class="sub">明文只在签发时显示一次；库中只存加盐 SHA-256。</div>
      <div class="toolbar"><button class="btn primary" data-act="keyCreate">签发密钥</button>
        <span class="hint">余额是 Redis 运行时状态，可在「应用」里设置。</span></div>
      ${table(['ID', '应用', '应用名', '前缀', '状态', '过期', 'RPM', 'TPM', '并发', '操作'], rows)}`;
  };

  VIEWS.prices = async () => {
    const prices = await api('/prices');
    const rows = prices.map((p) => [
      esc(p.provider), esc(p.model), num(p.inputPrice), num(p.outputPrice),
      num(p.cacheReadPrice), num(p.cacheWritePrice), esc(p.currency),
      `<button class="btn mini danger" data-act="priceRemove" data-provider="${esc(p.provider)}" data-model="${esc(p.model)}">移除</button>`,
    ]);
    return `<h2>定价</h2><div class="sub">单位：元 / 1K tokens。缓存价用于 Prompt Cache 计费，缺省 0 表示缓存不计费。</div>
      <div class="toolbar"><button class="btn primary" data-act="priceSet">设置价格</button></div>
      ${table(['供应商', '模型', '输入/1K', '输出/1K', '缓存读/1K', '缓存写/1K', '币种', '操作'], rows)}`;
  };

  VIEWS.usage = async () => {
    const apps = await api('/apps');
    const opts = ['<option value="">全部应用</option>'].concat(
      apps.map((a) => `<option value="${a.id}">${esc(a.name)} (#${a.id})</option>`)).join('');
    return `<h2>用量与成本</h2><div class="sub">来自访问日志（异步落库，可能略有延迟）。</div>
      <div class="toolbar">
        <select id="u_appId">${opts}</select>
        <input id="u_days" type="number" min="1" value="7" style="width:90px">
        <button class="btn primary" data-act="usageLoad">查询</button>
        <span class="spacer"></span><span id="u_totals" class="hint"></span>
      </div>
      <div id="u_table"><div class="empty">点击查询加载数据。</div></div>`;
  };

  VIEWS.playground = async () => {
    let models = [];
    try {
      const res = await fetch('/v1/models', { headers: { 'Authorization': 'Bearer ' + (localStorage.getItem('gw_vkey') || 'sk-gw-dev-demo-0001') } });
      if (res.ok) { const d = await res.json(); models = (d.data || []).map((m) => m.id); }
    } catch (e) { /* ignore */ }
    const opts = (models.length ? models : ['chat-default']).map((m) => `<option value="${esc(m)}">${esc(m)}</option>`).join('');
    return `<h2>对话实验场</h2><div class="sub">直接通过网关发起一次对话（走完整责任链：鉴权/限流/计费/路由）。</div>
      <div class="row">
        <label>虚拟 Key<input id="p_key" value="${esc(localStorage.getItem('gw_vkey') || 'sk-gw-dev-demo-0001')}"></label>
        <label>模型<select id="p_model">${opts}</select></label>
      </div>
      <label>系统提示（可选）<input id="p_system" placeholder="You are a helpful assistant."></label>
      <label style="display:block;margin-top:8px">用户消息
        <textarea id="p_message" rows="4">你好</textarea></label>
      <div class="toolbar" style="margin-top:10px">
        <button class="btn primary" data-act="chatSend">发送</button>
        <label style="flex-direction:row;align-items:center;gap:6px;color:var(--text)"><input type="checkbox" id="p_stream"> 流式</label>
        <button class="btn" data-act="chatClear">清空</button>
      </div>
      <pre id="p_out" class="out">（回复会显示在这里）</pre>`;
  };

  VIEWS.system = async () => {
    const s = await api('/status');
    const doctor = await api('/doctor');
    const rows = doctor.map((c) => {
      const cls = c.level === 'OK' ? 'ok' : (c.level === 'WARN' ? 'warn' : 'fail');
      return [`<span class="tag ${cls}">${esc(c.level)}</span>`, esc(c.name), esc(c.detail)];
    });
    return `<h2>系统</h2><div class="sub">自检、配置刷新与运行信息。</div>
      <div class="toolbar">
        <button class="btn primary" data-act="refreshConfig">刷新配置</button>
        <span class="hint">配置版本 ${esc(s.configVersion)} · 适配器：${esc((s.adapters || []).join(', '))}</span>
      </div>
      ${table(['状态', '检查项', '说明'], rows)}
      <h2 style="margin-top:20px">管理 API</h2>
      <div class="sub">脚本可通过 <code>X-Admin-Token</code> 调用同一套接口。</div>
      <pre class="out">GET    /admin/api/status
GET    /admin/api/doctor
GET    /admin/api/providers          POST /admin/api/providers
GET    /admin/api/channels           POST /admin/api/channels
PUT    /admin/api/channels/{id}/key  PUT  /admin/api/channels/{id}/status
POST   /admin/api/channels/{id}/test DELETE /admin/api/channels/{id}
POST   /admin/api/channels/{id}/copy POST /admin/api/channels/test-all
DELETE /admin/api/channels/{id}/circuit
GET    /admin/api/models             POST /admin/api/models
PUT    /admin/api/models/{name}/fallback  PUT /admin/api/models/{name}/status
GET    /admin/api/apps               POST /admin/api/apps
GET    /admin/api/apps/{id}/balance  POST /admin/api/apps/{id}/balance
GET    /admin/api/keys               POST /admin/api/keys
DELETE /admin/api/keys/{id}
GET    /admin/api/prices             POST /admin/api/prices   DELETE /admin/api/prices
GET    /admin/api/usage?days=7
POST   /admin/api/config/refresh</pre>`;
  };

  // ---------------- actions ----------------
  const App = {};

  App.go = (d) => go(d.view);

  App.refreshConfig = async () => {
    try { await api('/config/refresh', { method: 'POST' }); toast('配置已刷新并广播'); go(state.view); }
    catch (e) { toast(e.message, 'error'); }
  };

  // providers
  App.providerAdd = () => openForm('新增供应商', [
    { name: 'code', label: 'Code（唯一）', placeholder: 'myrelay' },
    { name: 'name', label: '显示名' },
    { name: 'baseUrl', label: 'Base URL', placeholder: 'https://relay.example.com/v1' },
    { name: 'adapter', label: '适配器', type: 'select', value: 'openai-compatible', options: [
      'openai-compatible', 'anthropic', 'gemini', 'openai-responses', 'azure', 'openai', 'qwen', 'deepseek', 'ollama',
    ].map((v) => ({ value: v, label: v })) },
    { name: 'disabled', label: '创建后停用（填 yes 停用）' },
  ], async (v) => {
    await api('/providers', { method: 'POST', body: { code: v.code, name: v.name, baseUrl: v.baseUrl, adapter: v.adapter, disabled: v.disabled === 'yes' } });
    toast('供应商已新增'); go('providers');
  });

  App.providerEdit = (d) => openForm('编辑供应商 · ' + d.code, [
    { name: 'name', label: '显示名', value: d.name },
    { name: 'baseUrl', label: 'Base URL', value: d.baseurl },
    { name: 'adapter', label: '适配器', type: 'select', value: d.adapter, options: [
      'openai-compatible', 'anthropic', 'gemini', 'openai-responses', 'azure', 'openai', 'qwen', 'deepseek', 'ollama',
    ].map((v) => ({ value: v, label: v })) },
  ], async (v) => {
    await api('/providers/' + d.id, { method: 'PUT', body: { name: v.name, baseUrl: v.baseUrl, adapter: v.adapter } });
    toast('供应商已更新'); go('providers');
  });

  App.providerDelete = (d) => confirmBox('确定删除供应商「' + d.code + '」？', async () => {
    await api('/providers/' + d.id, { method: 'DELETE' }); toast('供应商已删除'); go('providers');
  });

  // channels
  App.channelAdd = async () => {
    const providers = await api('/providers');
    openForm('新增渠道', [
      { name: 'provider', label: '供应商', type: 'select', options: providers.map((p) => ({ value: p.code, label: p.code + ' · ' + p.name })) },
      { name: 'name', label: '渠道名称' },
      { name: 'models', label: '模型映射（每行 逻辑模型=物理模型）', type: 'textarea', value: 'chat-default=gpt-4o-mini\nchat-cheap=gpt-4o-mini', rows: 4 },
      { name: 'baseUrl', label: 'Base URL（可选，覆盖供应商默认）' },
      { name: 'rpm', label: 'RPM 上限', type: 'number' },
      { name: 'tpm', label: 'TPM 上限', type: 'number' },
      { name: 'concurrency', label: '并发上限', type: 'number' },
      { name: 'status', label: '状态', type: 'select', value: 'DISABLED', options: [{ value: 'DISABLED', label: 'DISABLED' }, { value: 'ACTIVE', label: 'ACTIVE' }] },
    ], async (v) => {
      const mapping = {};
      (v.models || '').split('\n').forEach((line) => {
        const t = line.trim(); if (!t) return;
        const i = t.indexOf('='); if (i <= 0) throw new Error('映射格式应为 逻辑模型=物理模型: ' + t);
        mapping[t.slice(0, i).trim()] = t.slice(i + 1).trim();
      });
      await api('/channels', { method: 'POST', body: { provider: v.provider, name: v.name, models: mapping, baseUrl: v.baseUrl, rpm: v.rpm, tpm: v.tpm, concurrency: v.concurrency, status: v.status } });
      toast('渠道已新增'); go('channels');
    });
  };

  App.channelKey = (d) => openForm('配置渠道密钥 · ' + d.name, [
    { name: 'apiKey', label: '上游 API Key' },
    { name: 'baseUrl', label: 'Base URL（可选）' },
    { name: 'activate', label: '写入后启用', type: 'select', value: 'true', options: [{ value: 'true', label: '是' }, { value: 'false', label: '否' }] },
  ], async (v) => {
    const r = await api('/channels/' + d.id + '/key', { method: 'PUT', body: { apiKey: v.apiKey, baseUrl: v.baseUrl, activate: v.activate === 'true' } });
    toast('已写入密钥（掩码 ' + r.keyMasked + '）'); go('channels');
  });

  App.channelStatus = async (d) => {
    try { await api('/channels/' + d.id + '/status', { method: 'PUT', body: { status: d.status } }); toast('状态已更新'); go('channels'); }
    catch (e) { toast(e.message, 'error'); }
  };

  App.channelReset = async (d) => {
    try { await api('/channels/' + d.id + '/circuit', { method: 'DELETE' }); toast('熔断与冷却已重置'); go('channels'); }
    catch (e) { toast(e.message, 'error'); }
  };

  App.channelCopy = async (d) => {
    try { const r = await api('/channels/' + d.id + '/copy', { method: 'POST', body: {} }); toast('已复制为渠道 #' + r.id); go('channels'); }
    catch (e) { toast(e.message, 'error'); }
  };

  App.channelDelete = (d) => confirmBox('确定删除渠道「' + d.name + '」？', async () => {
    await api('/channels/' + d.id, { method: 'DELETE' }); toast('渠道已删除'); go('channels');
  });

  App.channelTest = async (d) => {
    toast('测试渠道 #' + d.id + ' ...');
    try {
      const r = await api('/channels/' + d.id + '/test', { method: 'POST', body: {} });
      toast((r.ok ? '[OK] ' : '[FAIL] ') + r.latencyMs + 'ms · ' + r.detail, r.ok ? 'ok' : 'error');
    } catch (e) { toast(e.message, 'error'); }
  };

  App.channelTestAll = async () => {
    toast('批量测试中 ...');
    try {
      const rows = await api('/channels/test-all', { method: 'POST', body: {} });
      const html = rows.map((r) => (r.ok ? '[OK] ' : '[FAIL] ') + '#' + r.id + ' ' + r.name + ' ' + r.latencyMs + 'ms · ' + r.detail).join('\n');
      const root = $('#modalRoot');
      root.innerHTML = `<div class="modal-mask"><div class="modal"><h3>批量测试结果</h3><pre class="out">${esc(html)}</pre>
        <div class="modal-actions"><button class="btn primary" data-cancel>关闭</button></div></div></div>`;
      root.querySelector('[data-cancel]').onclick = () => { root.innerHTML = ''; };
    } catch (e) { toast(e.message, 'error'); }
  };

  // models
  App.modelAdd = () => openForm('新增逻辑模型', [
    { name: 'name', label: '逻辑模型名', placeholder: 'chat-fast' },
    { name: 'type', label: '类型', type: 'select', value: 'CHAT', options: ['CHAT', 'EMBEDDING', 'RERANK'].map((v) => ({ value: v, label: v })) },
    { name: 'fallback', label: '降级链（逗号分隔，可选）', placeholder: 'chat-cheap' },
    { name: 'description', label: '说明' },
  ], async (v) => {
    await api('/models', { method: 'POST', body: { name: v.name, type: v.type, fallback: v.fallback ? v.fallback.split(',').map((s) => s.trim()).filter(Boolean) : null, description: v.description } });
    toast('逻辑模型已新增'); go('models');
  });

  App.modelFallback = (d) => openForm('设置降级链 · ' + d.name, [
    { name: 'models', label: '降级链（逗号分隔；留空清空）', value: d.fallback || '' },
  ], async (v) => {
    await api('/models/' + encodeURIComponent(d.name) + '/fallback', { method: 'PUT', body: { models: v.models ? v.models.split(',').map((s) => s.trim()).filter(Boolean) : [] } });
    toast('降级链已更新'); go('models');
  });

  App.modelStatus = async (d) => {
    try { await api('/models/' + encodeURIComponent(d.name) + '/status', { method: 'PUT', body: { enabled: d.enabled === '1' } }); toast('状态已更新'); go('models'); }
    catch (e) { toast(e.message, 'error'); }
  };

  // apps
  App.appAdd = () => openForm('新增应用', [
    { name: 'name', label: '应用名' },
    { name: 'tenantId', label: '租户 ID', type: 'number', value: 1 },
    { name: 'daily', label: '日预算（元）', type: 'number' },
    { name: 'monthly', label: '月预算（元）', type: 'number' },
    { name: 'models', label: '可用模型（逗号分隔；留空=全部）' },
  ], async (v) => {
    await api('/apps', { method: 'POST', body: { name: v.name, tenantId: v.tenantId, daily: v.daily, monthly: v.monthly, models: v.models ? v.models.split(',').map((s) => s.trim()).filter(Boolean) : null } });
    toast('应用已新增'); go('apps');
  });

  App.appStatus = async (d) => {
    try { await api('/apps/' + d.id + '/status', { method: 'PUT', body: { enabled: d.enabled === '1' } }); toast('状态已更新'); go('apps'); }
    catch (e) { toast(e.message, 'error'); }
  };

  App.appBalance = async (d) => {
    let cur = 0;
    try { const b = await api('/apps/' + d.id + '/balance'); cur = b.balanceFen; } catch (e) { /* ignore */ }
    openForm('应用余额 · ' + d.name + '（当前 ' + (cur / 100).toFixed(2) + ' 元）', [
      { name: 'yuan', label: '设置为（元）', type: 'number', value: (cur / 100).toFixed(2) },
    ], async (v) => {
      await api('/apps/' + d.id + '/balance', { method: 'POST', body: { yuan: v.yuan } });
      toast('余额已更新'); go('apps');
    });
  };

  // keys
  App.keyCreate = async () => {
    const apps = await api('/apps');
    openForm('签发虚拟 Key', [
      { name: 'appId', label: '应用', type: 'select', options: apps.map((a) => ({ value: a.id, label: a.name + ' (#' + a.id + ')' })) },
      { name: 'rpm', label: 'RPM 上限', type: 'number' },
      { name: 'tpm', label: 'TPM 上限', type: 'number' },
      { name: 'concurrency', label: '并发上限', type: 'number' },
      { name: 'expire', label: '过期日期（yyyy-MM-dd，可空）' },
    ], async (v) => {
      const r = await api('/keys', { method: 'POST', body: { appId: v.appId, rpm: v.rpm, tpm: v.tpm, concurrency: v.concurrency, expire: v.expire } });
      const root = $('#modalRoot');
      root.innerHTML = `<div class="modal-mask"><div class="modal"><h3>虚拟 Key 已签发</h3>
        <p class="hint">请立即保存，关闭后无法再次查看。</p>
        <pre class="out">${esc(r.apiKey)}</pre>
        <div class="modal-actions"><button class="btn primary" data-cancel>我已保存</button></div></div></div>`;
      root.querySelector('[data-cancel]').onclick = () => { root.innerHTML = ''; };
      go('keys');
    });
  };

  App.keyRevoke = (d) => confirmBox('确定吊销该 Key？', async () => {
    await api('/keys/' + d.id, { method: 'DELETE' }); toast('已吊销'); go('keys');
  });

  // prices
  App.priceSet = () => openForm('设置价格（元 / 1K tokens）', [
    { name: 'provider', label: '供应商 code' },
    { name: 'model', label: '物理模型名' },
    { name: 'input', label: '输入价', type: 'number' },
    { name: 'output', label: '输出价', type: 'number' },
    { name: 'cacheRead', label: '缓存读价（默认 0）', type: 'number', value: 0 },
    { name: 'cacheWrite', label: '缓存写价（默认 0）', type: 'number', value: 0 },
    { name: 'currency', label: '币种', value: 'CNY' },
  ], async (v) => {
    await api('/prices', { method: 'POST', body: v }); toast('价格已设置'); go('prices');
  });

  App.priceRemove = (d) => confirmBox('移除 ' + d.provider + '/' + d.model + ' 的价格？', async () => {
    await api('/prices?provider=' + encodeURIComponent(d.provider) + '&model=' + encodeURIComponent(d.model), { method: 'DELETE' });
    toast('已移除'); go('prices');
  });

  // usage
  App.usageLoad = async () => {
    const appId = $('#u_appId').value || null;
    const days = Number($('#u_days').value || 7);
    try {
      const r = await api('/usage?days=' + days + (appId ? '&appId=' + appId : ''));
      const rows = r.rows.map((x) => [
        esc(x.provider), esc(x.model), num(x.reqs), num(x.errs), num(x.prompt_tokens),
        num(x.completion_tokens), num(x.cached_tokens), num(x.cache_creation_tokens), num(x.cost),
      ]);
      $('#u_table').innerHTML = table(['供应商', '逻辑模型', '请求', '错误', '输入', '输出', '缓存读', '缓存写', '成本(元)'], rows);
      $('#u_totals').textContent = `合计：请求 ${r.totals.requests} · 错误 ${r.totals.errors} · 缓存读 ${r.totals.cachedTokens} · 成本 ${num(r.totals.cost)} 元`;
    } catch (e) { toast(e.message, 'error'); }
  };

  // playground
  App.chatSend = async () => {
    const key = $('#p_key').value.trim();
    const model = $('#p_model').value;
    const message = $('#p_message').value;
    const system = $('#p_system').value;
    const stream = $('#p_stream').checked;
    localStorage.setItem('gw_vkey', key);
    const out = $('#p_out');
    out.textContent = '';
    const messages = [];
    if (system) messages.push({ role: 'system', content: system });
    messages.push({ role: 'user', content: message });
    const body = { model, messages, stream };
    try {
      const res = await fetch('/v1/chat/completions', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) { out.textContent = 'HTTP ' + res.status + '\n' + await res.text(); return; }
      if (!stream) {
        const data = await res.json();
        const text = data.choices && data.choices[0] && data.choices[0].message ? data.choices[0].message.content : JSON.stringify(data);
        out.textContent = text + (data.gateway ? '\n\n--- gateway ---\n' + JSON.stringify(data.gateway, null, 2) : '');
        return;
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        const lines = buf.split('\n');
        buf = lines.pop();
        for (const line of lines) {
          const s = line.trim();
          if (!s.startsWith('data:')) continue;
          const payload = s.slice(5).trim();
          if (!payload || payload === '[DONE]') continue;
          try {
            const chunk = JSON.parse(payload);
            const d = chunk.choices && chunk.choices[0] && chunk.choices[0].delta;
            if (d && d.content) { out.textContent += d.content; out.scrollTop = out.scrollHeight; }
          } catch (e) { /* skip */ }
        }
      }
    } catch (e) { out.textContent = '调用失败：' + e.message; }
  };

  App.chatClear = () => { $('#p_out').textContent = '（回复会显示在这里）'; };

  // ---------------- 登录 ----------------
  function renderAuth() {
    const area = $('#authArea');
    if (!area) return;
    if (state.connected) {
      area.innerHTML = `<span class="pill on">已登录：${esc(state.user || 'admin')}</span>`
        + `<button class="btn" id="logoutBtn">退出</button>`;
      $('#logoutBtn').onclick = logout;
    } else {
      area.innerHTML = `<input id="u_name" placeholder="用户名" value="${esc(state.user || 'admin')}" autocomplete="username">`
        + `<input id="u_pass" type="password" placeholder="密码" autocomplete="current-password">`
        + `<button class="btn primary" id="loginBtn">登录</button>`;
      $('#loginBtn').onclick = login;
      $('#u_pass').addEventListener('keydown', (e) => { if (e.key === 'Enter') login(); });
      $('#u_name').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#u_pass').focus(); });
    }
  }

  async function login() {
    const username = $('#u_name').value.trim();
    const password = $('#u_pass').value;
    try {
      const res = await fetch('/admin/api/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      const data = await res.json().catch(() => null);
      if (!res.ok) {
        throw new Error((data && data.error && data.error.message) || '登录失败');
      }
      state.token = data.token;
      state.user = data.username || username;
      state.connected = true;
      localStorage.setItem('gw_token', state.token);
      localStorage.setItem('gw_user', state.user);
      renderAuth();
      toast('登录成功');
      go('dashboard');
    } catch (e) { toast(e.message, 'error'); }
  }

  function logout() {
    fetch('/admin/api/logout', { method: 'POST' }).catch(() => {});
    state.connected = false;
    state.token = '';
    state.user = '';
    localStorage.removeItem('gw_token');
    localStorage.removeItem('gw_user');
    renderAuth();
    mount('<div class="empty">已退出登录。</div>');
  }

  async function checkSession() {
    try {
      const me = await api('/me');
      state.user = me.username || state.user;
      state.connected = true;
      return true;
    } catch (e) {
      state.connected = false;
      return false;
    }
  }

  async function init() {
    state.token = localStorage.getItem('gw_token') || '';
    state.user = localStorage.getItem('gw_user') || '';
    renderNav();
    if (state.token && await checkSession()) {
      renderAuth();
      go('dashboard');
    } else {
      renderAuth();
      await mount('<div class="empty">请先在右上角登录（默认账号 admin / admin123）。</div>');
    }
  }

  window.App = App;
  document.addEventListener('DOMContentLoaded', init);
})();
