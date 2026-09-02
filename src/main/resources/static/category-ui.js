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
