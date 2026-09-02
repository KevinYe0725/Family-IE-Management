export function normalizeActiveAccounts(accountPage) {
  if (!accountPage || !Array.isArray(accountPage.items)) return [];
  return accountPage.items.filter(account => account && account.archivedAt == null);
}

export function configureAccountSelect(select, activeAccounts, selectedAccountId, selectedAccountName) {
  select.replaceChildren();
  select.required = true;
  select.disabled = false;
  const selectedId = selectedAccountId == null ? null : Number(selectedAccountId);
  const selectedIsActive = activeAccounts.some(account => Number(account.id) === selectedId);

  if (selectedId != null && !selectedIsActive) {
    const archived = option(select, String(selectedId), `${selectedAccountName || '原账户'}（已归档）`);
    archived.disabled = true;
    archived.selected = true;
    archived.dataset.archived = 'true';
    select.append(archived);
  }

  for (const account of activeAccounts) {
    const accountOption = option(select, String(account.id), account.name);
    accountOption.selected = selectedIsActive
      ? Number(account.id) === selectedId
      : selectedId == null && account === activeAccounts[0];
    select.append(accountOption);
  }

  if (activeAccounts.length === 0 && selectedId == null) {
    const empty = option(select, '', '暂无可用账户');
    empty.disabled = true;
    empty.selected = true;
    select.append(empty);
    select.disabled = true;
    return { canSubmit: false, message: '暂无可用账户，请先联系家庭管理员创建账户。' };
  }
  if (activeAccounts.length === 0 && selectedId != null) {
    select.disabled = true;
  }
  return { canSubmit: true, message: '' };
}

export function transactionPayload(formEntries, { activeAccounts, existingAccountId = null } = {}) {
  const payload = Object.fromEntries(formEntries);
  payload.memberId = Number(payload.memberId);
  payload.categoryId = Number(payload.categoryId);
  const accountId = Number(payload.accountId);
  const active = (activeAccounts || []).some(account => Number(account.id) === accountId);
  if (active) {
    payload.accountId = accountId;
  } else {
    delete payload.accountId;
  }
  return payload;
}

export function readTransactionPage(headers) {
  return {
    page: nonNegativeInteger(headers?.get('X-Page'), 0),
    size: positiveInteger(headers?.get('X-Page-Size'), 20),
    totalElements: nonNegativeInteger(headers?.get('X-Total-Elements'), 0),
    totalPages: nonNegativeInteger(headers?.get('X-Total-Pages'), 0),
    hasNext: headers?.get('X-Has-Next') === 'true'
  };
}

function option(select, value, label) {
  const item = select.ownerDocument.createElement('option');
  item.value = value;
  item.textContent = label;
  return item;
}

function nonNegativeInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : fallback;
}

function positiveInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}
