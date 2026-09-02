export function categoryPayload(formEntries) {
  const payload = Object.fromEntries(formEntries);
  payload.parentId = payload.parentId === '' || payload.parentId == null
    ? null
    : Number(payload.parentId);
  return payload;
}

export function availableCategoryParents(categories, kind, currentCategoryId = null) {
  const currentId = currentCategoryId == null ? null : Number(currentCategoryId);
  return (categories || []).filter(category =>
    category.kind === kind
      && Number(category.level || 1) === 1
      && Number(category.id) !== currentId
  );
}

export function configureCategorySelect(
  select,
  categories,
  selectedCategoryId,
  selectedCategoryName,
  selectedKind
) {
  select.replaceChildren();
  select.required = true;
  select.disabled = false;
  const available = Array.isArray(categories) ? categories : [];
  const selectedId = selectedCategoryId == null || selectedCategoryId === ''
    ? null
    : Number(selectedCategoryId);
  const selectedIsLoaded = available.some(category => Number(category.id) === selectedId);

  if (selectedId != null && !selectedIsLoaded) {
    if (!selectedCategoryName || !selectedKind) {
      const unavailable = option(select, '', '原分类未完整加载');
      unavailable.disabled = true;
      unavailable.selected = true;
      select.append(unavailable);
      select.disabled = true;
      return { canSubmit: false, message: '原分类未完整加载，无法安全保存，请刷新后重试。' };
    }
    const historical = option(select, String(selectedId), `${selectedCategoryName}（未加载，保持不变）`);
    historical.disabled = true;
    historical.selected = true;
    historical.dataset.kind = selectedKind;
    historical.dataset.historical = 'true';
    select.append(historical);
  }

  let selectedFirstKind = false;
  for (const category of available) {
    const categoryOption = option(
      select,
      String(category.id),
      `${category.name}（${category.kind === 'income' ? '收入' : '支出'}）`
    );
    categoryOption.dataset.kind = category.kind;
    categoryOption.selected = selectedIsLoaded
      ? Number(category.id) === selectedId
      : selectedId == null && !selectedFirstKind && category.kind === selectedKind;
    if (categoryOption.selected) selectedFirstKind = true;
    select.append(categoryOption);
  }

  if (available.length === 0 && selectedId == null) {
    const empty = option(select, '', '暂无可用分类');
    empty.disabled = true;
    empty.selected = true;
    select.append(empty);
    select.disabled = true;
    return { canSubmit: false, message: '暂无可用分类，请先联系家庭管理员创建分类。' };
  }
  return { canSubmit: true, message: '' };
}

function option(select, value, label) {
  const item = select.ownerDocument.createElement('option');
  item.value = value;
  item.textContent = label;
  return item;
}
