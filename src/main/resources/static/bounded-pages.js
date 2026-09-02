export const MAX_RESOURCE_PAGES = 200;
export const RESOURCE_PAGE_SIZE = 50;

export async function loadAllBoundedPages({
  fetchPage,
  itemsFrom,
  resourceName,
  pageSize = RESOURCE_PAGE_SIZE,
  maxPages = MAX_RESOURCE_PAGES
}) {
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > RESOURCE_PAGE_SIZE) {
    throw new Error(`${resourceName}分页大小必须在 1 到 ${RESOURCE_PAGE_SIZE} 之间`);
  }
  if (!Number.isInteger(maxPages) || maxPages < 1) {
    throw new Error(`${resourceName}安全页数上限无效`);
  }

  const loaded = [];
  const seenIds = new Set();
  let baseline = null;
  for (let requestedPage = 0; requestedPage < maxPages; requestedPage += 1) {
    const response = await fetchPage(requestedPage, pageSize);
    const metadata = readBoundedPage(response?.headers, resourceName);
    const items = itemsFrom(response?.data);
    if (!Array.isArray(items)
        || metadata.page !== requestedPage
        || metadata.size > pageSize) {
      throw pagingError(resourceName);
    }
    if (baseline == null) {
      baseline = {
        size: metadata.size,
        totalElements: metadata.totalElements,
        totalPages: metadata.totalPages
      };
    } else if (metadata.size !== baseline.size
        || metadata.totalElements !== baseline.totalElements
        || metadata.totalPages !== baseline.totalPages) {
      throw pagingError(resourceName);
    }
    if (items.length !== expectedItemCount(metadata)) {
      throw pagingError(resourceName);
    }
    for (const item of items) {
      const id = item?.id;
      if (!Number.isSafeInteger(id) || id <= 0 || seenIds.has(id)) {
        throw pagingError(resourceName);
      }
      seenIds.add(id);
    }
    loaded.push(...items);

    if (!metadata.hasNext) {
      return loaded;
    }
    if (requestedPage + 1 >= maxPages) {
      throw new Error(`${resourceName}数据超过安全页数上限（${maxPages} 页），请缩小范围后重试`);
    }
  }
  throw new Error(`${resourceName}数据超过安全页数上限（${maxPages} 页），请缩小范围后重试`);
}

export function readBoundedPage(headers, resourceName) {
  const page = integerHeader(headers, 'X-Page');
  const size = integerHeader(headers, 'X-Page-Size');
  const totalElements = integerHeader(headers, 'X-Total-Elements');
  const totalPages = integerHeader(headers, 'X-Total-Pages');
  const hasNextValue = headers?.get('X-Has-Next');
  const hasNext = hasNextValue === 'true' ? true : hasNextValue === 'false' ? false : null;
  const expectedTotalPages = totalElements === 0
    ? 0
    : Math.ceil(totalElements / size);
  const valid = page != null && page >= 0
    && size != null && size >= 1 && size <= RESOURCE_PAGE_SIZE
    && totalElements != null && totalElements >= 0
    && totalPages != null && totalPages >= 0
    && totalPages === expectedTotalPages
    && (totalPages === 0 ? page === 0 : page < totalPages)
    && hasNext != null
    && hasNext === (page + 1 < totalPages);
  if (!valid) throw pagingError(resourceName);
  return { page, size, totalElements, totalPages, hasNext };
}

function expectedItemCount(metadata) {
  if (metadata.totalPages === 0) return 0;
  if (metadata.page < metadata.totalPages - 1) return metadata.size;
  return metadata.totalElements - metadata.size * (metadata.totalPages - 1);
}

function integerHeader(headers, name) {
  const raw = headers?.get(name);
  if (raw == null || raw === '') return null;
  const parsed = Number(raw);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

function pagingError(resourceName) {
  return new Error(`${resourceName}分页响应异常，请刷新后重试`);
}
