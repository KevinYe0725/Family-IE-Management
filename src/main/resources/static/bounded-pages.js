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
  for (let requestedPage = 0; requestedPage < maxPages; requestedPage += 1) {
    const response = await fetchPage(requestedPage, pageSize);
    const metadata = readBoundedPage(response?.headers, resourceName);
    const items = itemsFrom(response?.data);
    if (metadata.page !== requestedPage
        || metadata.size !== pageSize
        || !Array.isArray(items)) {
      throw pagingError(resourceName);
    }
    loaded.push(...items);

    if (!metadata.hasNext) {
      if (loaded.length !== metadata.totalElements) {
        throw pagingError(resourceName);
      }
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
  const valid = page != null && page >= 0
    && size != null && size >= 1 && size <= RESOURCE_PAGE_SIZE
    && totalElements != null && totalElements >= 0
    && totalPages != null && totalPages >= 0
    && hasNext != null
    && hasNext === (page + 1 < totalPages);
  if (!valid) throw pagingError(resourceName);
  return { page, size, totalElements, totalPages, hasNext };
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
