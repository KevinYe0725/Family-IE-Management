import { RefreshGate } from './refresh-gate.js';

(function () {
  const state = window.FamilyLedgerState;
  const app = document.getElementById('app');
  const refreshGate = new RefreshGate();
  const routeNames = { dashboard: '总览', transactions: '收支明细', analysis: '账目分析', settings: '家庭设置' };
  const money = value => `¥${Number(value || 0).toFixed(2)}`;
  const esc = value => String(value ?? '').replace(/[&<>'"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[c]);
  const selected = (actual, expected) => String(actual) === String(expected) ? ' selected' : '';
  const notice = (message, type = '') => {
    state.flash = { message, type };
    const status = app.querySelector('#main > .status');
    if (status) {
      status.className = `status ${type}`;
      status.textContent = message;
      return;
    }
    render();
  };
  const query = values => Object.entries(values).filter(([, v]) => v !== '' && v != null).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');

  async function api(path, options = {}) {
    const response = await fetch(path, { credentials: 'same-origin', ...options });
    const body = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
      const error = new Error(body?.error?.message || '请求未能完成');
      error.status = response.status;
      error.fields = body?.error?.fields;
      throw error;
    }
    return body?.data;
  }

  async function csrfHeaders() {
    const token = await api('/api/csrf');
    return { [token.headerName || 'X-XSRF-TOKEN']: token.token };
  }

  function restoreRequestedFocus() {
    if (!state.focusSelector) return;
    const selector = state.focusSelector;
    requestAnimationFrame(() => {
      const target = app.querySelector(selector) || document.querySelector(selector);
      if (target) target.focus();
      state.focusSelector = null;
    });
  }

  function showRefreshFailure(context) {
    state.flash = { message: `${context}，但最新数据未能刷新。显示的是上次成功读取的数据。`, type: 'error' };
    render();
  }

  async function refreshSafely(context) {
    try {
      return (await refresh()).current;
    } catch (error) {
      showRefreshFailure(context);
      return false;
    }
  }

  async function write(path, method, payload, refreshContext = '操作已提交') {
    const headers = await csrfHeaders();
    if (payload !== undefined) headers['Content-Type'] = 'application/json';
    const result = await api(path, { method, headers, body: payload === undefined ? undefined : JSON.stringify(payload) });
    void refreshSafely(refreshContext);
    return result;
  }

  async function refresh() {
    const filter = { month: state.month, ...state.filters };
    return refreshGate.run(
      () => Promise.all([
        api('/api/members'), api('/api/categories'), api(`/api/transactions?${query(filter)}`),
        api(`/api/dashboard?month=${encodeURIComponent(state.month)}`), api(`/api/analysis?month=${encodeURIComponent(state.month)}`)
      ]),
      ([members, categories, transactions, dashboard, analysis]) => {
        Object.assign(state.data, { members, categories, transactions, dashboard, analysis });
        render();
      }
    );
  }

  async function boot() {
    if (sessionStorage.getItem('family-ledger-authenticated') !== '1') {
      renderLogin();
      return;
    }
    try {
      state.data.session = await api('/api/session');
      await refreshSafely('会话已恢复');
    } catch (error) {
      if (error.status === 401) { refreshGate.invalidate(); renderLogin(); }
      else { app.innerHTML = `<main class="login-stage"><section class="login-card"><h1>无法连接账本</h1><p>${esc(error.message)}</p><button class="button" id="retry">重新连接</button></section></main>`; document.getElementById('retry').onclick = boot; }
    }
  }

  function renderLogin(error = '') {
    app.innerHTML = `<main class="login-stage"><section class="login-card" aria-labelledby="login-title">
      <div class="eyebrow">家庭现金流账本</div><h1 class="brand" id="login-title">家<em>账</em></h1>
      <p>把每一笔钱放回家庭的共同时间线，今天的收支一眼看清。</p>
      <p class="demo-note"><strong>本地演示账户</strong><br>用户名：demo　密码：demo1234</p>
      <form id="login-form" class="form-grid"><div class="field"><label for="username">用户名</label><input id="username" name="username" autocomplete="username" required value="demo"></div><div class="field"><label for="password">密码</label><input id="password" name="password" type="password" autocomplete="current-password" required value="demo1234"></div><p id="login-error" class="status error" role="status">${esc(error)}</p><button class="button full" type="submit">进入家庭账本</button></form>
    </section></main>`;
    document.getElementById('login-form').onsubmit = async event => {
      event.preventDefault();
      const form = new FormData(event.currentTarget);
      try {
        refreshGate.invalidate();
        const headers = await csrfHeaders();
        const payload = new URLSearchParams({ username: form.get('username'), password: form.get('password') });
        await api('/api/auth/login', { method: 'POST', headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' }, body: payload });
        sessionStorage.setItem('family-ledger-authenticated', '1');
        state.data.session = await api('/api/session');
        await refreshSafely('已登录');
      } catch (problem) { document.getElementById('login-error').textContent = problem.message; }
    };
  }

  function navButton(route, icon) { return `<button type="button" data-route="${route}" aria-current="${state.route === route ? 'page' : 'false'}"><span class="glyph">${icon}</span>${routeNames[route]}</button>`; }
  function renderShell(content) {
    const flash = state.flash ? `<p class="status ${state.flash.type}" role="status">${esc(state.flash.message)}</p>` : '<p class="status" role="status"></p>';
    app.innerHTML = `<div class="app-layout"><aside class="ledger-nav" aria-label="主导航"><div class="ledger-mark">家<span>账</span></div><nav class="nav-list">${navButton('dashboard','◇')}${navButton('transactions','▤')}${navButton('analysis','↗')}${navButton('settings','◌')}</nav><div class="nav-foot">${esc(state.data.session.username)} 的家庭账本<br><button type="button" class="button small logout-button" id="logout">退出登录</button></div></aside><section class="workspace"><header class="topbar"><div><div class="eyebrow">2026 家庭账本</div><h1>${routeNames[state.route]}</h1></div><label class="month-control" for="month"><span class="label">账期</span><input id="month" type="month" value="${esc(state.month)}" aria-label="选择账期"></label></header><main id="main" class="content" tabindex="-1">${flash}${content}</main></section><nav class="mobile-tabs" aria-label="移动导航">${navButton('dashboard','总览')}${navButton('transactions','明细')}${navButton('analysis','分析')}${navButton('settings','设置')}<button type="button" class="mobile-logout" id="mobile-logout">退出</button></nav></div>`;
    app.querySelectorAll('[data-route]').forEach(button => button.onclick = () => go(button.dataset.route));
    document.getElementById('month').onchange = async event => { state.month = event.target.value; state.filters = { kind: '', memberId: '', categoryId: '', q: '' }; await refreshSafely('账期已切换'); };
    document.getElementById('logout').onclick = logout;
    document.getElementById('mobile-logout').onclick = logout;
    restoreRequestedFocus();
  }

  function render() {
    if (!state.data.session) return renderLogin();
    if (state.route === 'dashboard') return renderDashboard();
    if (state.route === 'transactions') return renderTransactions();
    if (state.route === 'analysis') return renderAnalysis();
    return renderSettings();
  }

  function renderDashboard() {
    const data = state.data.dashboard || { summary: {}, daily: [], expenseByCategory: [], expenseByMember: [] };
    const summary = data.summary || {};
    renderShell(`<section class="summary-grid" aria-label="本月收支摘要"><article class="summary"><span class="label">收入</span><strong>${money(summary.income)}</strong></article><article class="summary expense"><span class="label">支出</span><strong>${money(summary.expense)}</strong></article><article class="summary balance"><span class="label">结余</span><strong>${money(summary.balance)}</strong></article></section><div class="dashboard-grid"><section class="panel"><div class="panel-head"><h2>家庭现金流账轨</h2><div class="legend"><span><i class="income"></i>收入</span><span><i class="expense"></i>支出</span></div></div>${track(data.daily || [])}</section><section class="panel"><h2>花到哪里</h2>${bars(data.expenseByCategory || [], 'categoryName', 'sharePercent', true)}</section></div><div class="dashboard-grid"><section class="panel"><h2>成员支出</h2>${bars(data.expenseByMember || [], 'memberName', 'amount', false)}</section><section class="panel"><h2>本月提示</h2>${insightCards((state.data.analysis || {}).insights || [])}</section></div>`);
  }

  function track(daily) {
    if (!daily.length) return '<p class="empty">这个账期还没有可绘制的收支。</p>';
    const max = Math.max(1, ...daily.map(d => Math.max(Number(d.income), Number(d.expense))));
    const width = Math.max(590, daily.length * 38), axis = 112;
    const sticks = daily.map((d, index) => { const x = 30 + index * ((width - 60) / Math.max(1, daily.length - 1)); const up = Number(d.income) / max * 78; const down = Number(d.expense) / max * 78; return `<line class="income-stick" x1="${x}" y1="${axis}" x2="${x}" y2="${axis - up}"/><line class="expense-stick" x1="${x + 10}" y1="${axis}" x2="${x + 10}" y2="${axis + down}"/>${index % Math.ceil(daily.length / 6) === 0 ? `<text class="track-label" x="${x}" y="${axis + 102}">${esc(d.date.slice(-2))}日</text>` : ''}`; }).join('');
    return `<div class="cash-track"><svg viewBox="0 0 ${width} 218" role="img" aria-label="按日显示的家庭收入和支出账轨"><line class="track-line" x1="18" y1="${axis}" x2="${width - 18}" y2="${axis}"/>${sticks}<text class="track-label" x="18" y="18">收入向上，支出向下</text></svg></div>`;
  }

  function bars(items, label, metric, percent) { if (!items.length) return '<p class="empty">暂无可比较的账目。</p>'; return `<div class="bar-list">${items.map(item => { const n = percent ? Number(item[metric]) : Number(item[metric]); const max = percent ? 100 : Math.max(...items.map(v => Number(v[metric]))); return `<div><div class="bar-meta"><span>${esc(item[label])}</span><strong>${percent ? `${n.toFixed(1)}%` : money(n)}</strong></div><div class="bar ${percent ? 'persimmon' : ''}"><b style="width:${Math.max(4, n / max * 100)}%"></b></div></div>`; }).join('')}</div>`; }
  function insightCards(items) { return items.length ? `<div class="insights">${items.map(item => `<article class="insight"><strong>${esc(item.title)}</strong><span>${esc(item.message)}</span></article>`).join('')}</div>` : '<p class="empty">历史数据不足时，系统会在这里说明可判断的边界。</p>'; }

  function renderTransactions() {
    const filters = state.filters; const transactions = state.data.transactions || [];
    renderShell(`<section class="panel"><div class="panel-head"><div><h2>本月流水</h2><p>筛选结果来自家庭账本的真实记录。</p></div><button class="button" id="new-transaction">记一笔收支</button></div><form class="filters" id="filters"><div class="field"><label for="filter-kind">类型</label><select id="filter-kind" name="kind"><option value="">全部</option><option value="income"${selected(filters.kind,'income')}>收入</option><option value="expense"${selected(filters.kind,'expense')}>支出</option></select></div><div class="field"><label for="filter-member">成员</label><select id="filter-member" name="memberId"><option value="">全部成员</option>${state.data.members.map(m => `<option value="${m.id}"${selected(filters.memberId,m.id)}>${esc(m.name)}</option>`).join('')}</select></div><div class="field"><label for="filter-category">分类</label><select id="filter-category" name="categoryId"><option value="">全部分类</option>${state.data.categories.map(c => `<option value="${c.id}"${selected(filters.categoryId,c.id)}>${esc(c.name)}</option>`).join('')}</select></div><div class="field"><label for="filter-q">关键词</label><input id="filter-q" name="q" value="${esc(filters.q)}" placeholder="商家、地点或备注"></div><button class="button secondary" type="submit">应用筛选</button></form><div class="table-wrap"><table class="ledger-table"><thead><tr><th>日期</th><th>收支</th><th>项目</th><th>成员</th><th>金额</th><th><span class="visually-hidden">操作</span></th></tr></thead><tbody>${transactions.length ? transactions.map(row => `<tr><td>${esc(row.occurredOn)}</td><td class="kind-${row.kind}">${row.kind === 'income' ? '收入' : '支出'}</td><td><strong>${esc(row.categoryName)}</strong><br><span>${esc(row.merchant || row.note || '未填写说明')}</span></td><td>${esc(row.memberName)}</td><td class="amount ${row.kind === 'expense' ? 'kind-expense' : 'kind-income'}">${row.kind === 'expense' ? '−' : '+'}${money(row.amount)}</td><td class="row-actions"><button class="button secondary small" data-edit="${row.id}" data-focus-key="transaction-${row.id}-edit">编辑</button><button class="button danger small" data-delete="${row.id}">删除</button></td></tr>`).join('') : '<tr><td colspan="6"><p class="empty">没有符合条件的收支。调整筛选，或记下第一笔账。</p></td></tr>'}</tbody></table></div><div class="panel-head" style="margin-top:16px"><span class="label">导出当前筛选</span><button class="button secondary small" id="export">下载 CSV</button></div></section>`);
    document.getElementById('new-transaction').onclick = () => transactionDialog();
    document.getElementById('filters').onsubmit = async event => { event.preventDefault(); const form = new FormData(event.currentTarget); state.filters = { kind: form.get('kind'), memberId: form.get('memberId'), categoryId: form.get('categoryId'), q: form.get('q') }; await refreshSafely('筛选条件已应用'); };
    app.querySelectorAll('[data-edit]').forEach(button => button.onclick = () => transactionDialog(transactions.find(row => row.id === Number(button.dataset.edit))));
    app.querySelectorAll('[data-delete]').forEach(button => button.onclick = () => removeTransaction(button.dataset.delete));
    document.getElementById('export').onclick = downloadCsv;
  }

  function renderAnalysis() { const analysis = state.data.analysis || {}; renderShell(`<section class="panel"><div class="panel-head"><div><h2>规则分析</h2><p>${esc(analysis.historyStatus || '正在核对历史账目')}</p></div><span class="label">账期 ${esc(state.month)}</span></div>${insightCards(analysis.insights || [])}</section><section class="panel"><h2>用数据说话</h2><p>分析使用本月支出与此前三个有数据账期的平均值比较；当历史不足时，只展示可以确认的结论。</p></section>`); }

  function renderSettings() {
    renderShell(`<div class="settings-grid"><section class="panel"><div class="panel-head"><h2>家庭成员</h2><button class="button small" id="new-member">新增成员</button></div><div class="list-editor">${state.data.members.map(member => `<div class="editor-row"><div><strong>${esc(member.name)}</strong><br><span>${esc(member.roleLabel || '家庭成员')}</span></div><div><button class="button secondary small" data-member-edit="${member.id}">编辑</button><button class="button danger small" data-member-delete="${member.id}">删除</button></div></div>`).join('')}</div></section><section class="panel"><div class="panel-head"><h2>收支分类</h2><button class="button small" id="new-category">新增分类</button></div><div class="list-editor">${state.data.categories.map(category => `<div class="editor-row"><div><strong><i style="display:inline-block;width:10px;height:10px;background:${esc(category.color)};margin-right:6px"></i>${esc(category.name)}</strong><br><span>${category.kind === 'income' ? '收入' : '支出'}${category.defaultCategory ? ' · 默认分类' : ''}</span></div><div><button class="button secondary small" data-category-edit="${category.id}">编辑</button><button class="button danger small" data-category-delete="${category.id}">删除</button></div></div>`).join('')}</div></section></div>`);
    document.getElementById('new-member').onclick = () => memberDialog(); document.getElementById('new-category').onclick = () => categoryDialog();
    app.querySelectorAll('[data-member-edit]').forEach(button => button.onclick = () => memberDialog(state.data.members.find(m => m.id === Number(button.dataset.memberEdit)))); app.querySelectorAll('[data-member-delete]').forEach(button => button.onclick = () => removeResource('/api/members', button.dataset.memberDelete, '成员'));
    app.querySelectorAll('[data-category-edit]').forEach(button => button.onclick = () => categoryDialog(state.data.categories.find(c => c.id === Number(button.dataset.categoryEdit)))); app.querySelectorAll('[data-category-delete]').forEach(button => button.onclick = () => removeResource('/api/categories', button.dataset.categoryDelete, '分类'));
  }

  function appendDialog(title, content, submitLabel, onSubmit, focusSelector) { const dialog = document.createElement('dialog'); const titleId = `dialog-title-${Date.now()}`; let submitted = false; dialog.setAttribute('aria-labelledby', titleId); dialog.innerHTML = `<form class="modal-card" method="dialog"><div class="panel-head"><h2 id="${titleId}">${title}</h2><button type="button" class="button secondary small" aria-label="关闭">关闭</button></div>${content}<p class="status error" role="status"></p><div class="modal-actions"><button class="button secondary" type="button">取消</button><button class="button" type="submit">${submitLabel}</button></div></form>`; document.body.append(dialog); const form = dialog.querySelector('form'); const close = () => dialog.close(); dialog.querySelector('[aria-label="关闭"]').onclick = close; dialog.querySelector('.modal-actions .secondary').onclick = close; dialog.addEventListener('close', () => { dialog.remove(); if (!submitted && focusSelector) queueMicrotask(() => document.querySelector(focusSelector)?.focus()); }); dialog.addEventListener('cancel', event => { event.preventDefault(); close(); }); form.onsubmit = async event => { event.preventDefault(); const status = dialog.querySelector('.status'); const submit = form.querySelector('[type="submit"]'); submit.disabled = true; try { await onSubmit(new FormData(form)); submitted = true; state.focusSelector = focusSelector; close(); notice(`${submitLabel}成功`); } catch (problem) { submit.disabled = false; status.textContent = problem.fields ? Object.values(problem.fields).join('；') : problem.message; } }; dialog.showModal(); dialog.querySelector('input,select,textarea')?.focus(); return dialog; }

  function transactionDialog(row) { const isEdit = Boolean(row); const values = row || { kind: 'expense', amount: '', occurredOn: `${state.month}-01`, memberId: state.data.members[0]?.id, categoryId: '', merchant: '', location: '', note: '' }; const categoryOptions = state.data.categories.map(c => `<option value="${c.id}" data-kind="${c.kind}"${selected(values.categoryId,c.id)}>${esc(c.name)}（${c.kind === 'income' ? '收入' : '支出'}）</option>`).join(''); const focusSelector = isEdit ? `[data-focus-key="transaction-${row.id}-edit"]` : '#new-transaction'; const dialog = appendDialog(isEdit ? '编辑收支' : '记一笔收支', `<div class="modal-grid"><div class="field"><label for="tx-kind">类型</label><select id="tx-kind" name="kind"><option value="expense"${selected(values.kind,'expense')}>支出</option><option value="income"${selected(values.kind,'income')}>收入</option></select></div><div class="field"><label for="tx-amount">金额</label><input id="tx-amount" name="amount" inputmode="decimal" required value="${esc(values.amount)}" placeholder="例如 88.60"></div><div class="field"><label for="tx-date">日期</label><input id="tx-date" name="occurredOn" type="date" required value="${esc(values.occurredOn)}"></div><div class="field"><label for="tx-member">成员</label><select id="tx-member" name="memberId" required>${state.data.members.map(m => `<option value="${m.id}"${selected(values.memberId,m.id)}>${esc(m.name)}</option>`).join('')}</select></div><div class="field"><label for="tx-category">分类</label><select id="tx-category" name="categoryId" required>${categoryOptions}</select></div><div class="field"><label for="tx-merchant">商家</label><input id="tx-merchant" name="merchant" value="${esc(values.merchant)}"></div><div class="field wide"><label for="tx-location">地点</label><input id="tx-location" name="location" value="${esc(values.location)}"></div><div class="field wide"><label for="tx-note">备注</label><textarea id="tx-note" name="note">${esc(values.note)}</textarea></div></div>`, isEdit ? '保存修改' : '保存收支', async form => { const payload = Object.fromEntries(form); payload.memberId = Number(payload.memberId); payload.categoryId = Number(payload.categoryId); return write(isEdit ? `/api/transactions/${row.id}` : '/api/transactions', isEdit ? 'PATCH' : 'POST', payload, isEdit ? '收支已保存' : '收支已保存'); }, focusSelector); const kind = dialog.querySelector('#tx-kind'); const category = dialog.querySelector('#tx-category'); const matchCategories = () => { [...category.options].forEach(option => { option.hidden = option.dataset.kind !== kind.value; }); const current = category.selectedOptions[0]; if (!current || current.dataset.kind !== kind.value) { category.value = [...category.options].find(option => option.dataset.kind === kind.value)?.value || ''; } }; kind.onchange = matchCategories; matchCategories(); }
  function memberDialog(member) { const value = member || { name: '', roleLabel: '' }; appendDialog(member ? '编辑成员' : '新增成员', `<div class="form-grid"><div class="field"><label for="member-name">姓名</label><input id="member-name" name="name" required value="${esc(value.name)}"></div><div class="field"><label for="member-role">身份说明</label><input id="member-role" name="roleLabel" value="${esc(value.roleLabel)}" placeholder="例如 爸爸"></div></div>`, member ? '保存修改' : '新增成员', form => write(member ? `/api/members/${member.id}` : '/api/members', member ? 'PATCH' : 'POST', Object.fromEntries(form), '成员已保存'), member ? `[data-member-edit="${member.id}"]` : '#new-member'); }
  function categoryDialog(category) { const value = category || { kind: 'expense', name: '', color: '#3B7A72' }; appendDialog(category ? '编辑分类' : '新增分类', `<div class="form-grid"><div class="field"><label for="category-kind">类型</label><select id="category-kind" name="kind"><option value="expense"${selected(value.kind,'expense')}>支出</option><option value="income"${selected(value.kind,'income')}>收入</option></select></div><div class="field"><label for="category-name">名称</label><input id="category-name" name="name" required value="${esc(value.name)}"></div><div class="field"><label for="category-color">标记颜色</label><input id="category-color" name="color" required value="${esc(value.color)}" pattern="^#[0-9A-Fa-f]{6}$"></div></div>`, category ? '保存修改' : '新增分类', form => write(category ? `/api/categories/${category.id}` : '/api/categories', category ? 'PATCH' : 'POST', Object.fromEntries(form), '分类已保存'), category ? `[data-category-edit="${category.id}"]` : '#new-category'); }
  async function removeResource(base, id, label) { if (!window.confirm(`删除这位${label}？被账目使用时系统会说明原因。`)) return; try { await write(`${base}/${id}`, 'DELETE', undefined, `${label}已删除`); notice(`${label}已删除`); } catch (problem) { notice(problem.message, 'error'); } }
  async function removeTransaction(id) { if (!window.confirm('删除这笔收支？此操作不能撤销。')) return; try { await write(`/api/transactions/${id}`, 'DELETE', undefined, '收支已删除'); notice('收支已删除'); } catch (problem) { notice(problem.message, 'error'); } }
  async function downloadCsv() { try { const response = await fetch(`/api/export.csv?${query({ month: state.month, ...state.filters })}`, { credentials: 'same-origin' }); if (!response.ok) throw new Error('导出失败'); const url = URL.createObjectURL(await response.blob()); const link = Object.assign(document.createElement('a'), { href: url, download: 'family-finance.csv' }); link.click(); URL.revokeObjectURL(url); notice('CSV 已开始下载'); } catch (problem) { notice(problem.message, 'error'); } }
  async function logout() { refreshGate.invalidate(); try { await api('/api/auth/logout', { method: 'POST', headers: await csrfHeaders() }); } catch (problem) { notice(problem.message, 'error'); return; } sessionStorage.removeItem('family-ledger-authenticated'); state.data.session = null; state.flash = null; renderLogin(); }
  function go(route) { state.route = route; history.pushState({}, '', route === 'dashboard' ? '/' : `/${route}`); render(); document.getElementById('main')?.focus(); }
  window.addEventListener('popstate', () => { const route = location.pathname.slice(1) || 'dashboard'; state.route = routeNames[route] ? route : 'dashboard'; render(); });
  const initial = location.pathname.slice(1) || 'dashboard'; state.route = routeNames[initial] ? initial : 'dashboard'; boot();
})();
