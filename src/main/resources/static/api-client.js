export function createApiClient({ fetchImpl, onUnauthorized }) {
  async function execute(path, options, responseType, requestOptions, includeMetadata) {
    const { handleUnauthorized = true } = requestOptions;
    const response = await fetchImpl(path, { credentials: 'same-origin', ...options });
    const body = await readBody(response, response.ok ? responseType : 'json');
    if (!response.ok) {
      const error = new Error(body?.error?.message || '请求未能完成');
      error.status = response.status;
      error.fields = body?.error?.fields;
      if (response.status === 401 && handleUnauthorized && onUnauthorized(error)) {
        error.sessionExpired = true;
      }
      throw error;
    }
    const data = responseType === 'json' ? body?.data : body;
    return includeMetadata ? { data, headers: response.headers, status: response.status } : data;
  }

  return {
    async request(path, options = {}, responseType = 'json', { handleUnauthorized = true } = {}) {
      return execute(path, options, responseType, { handleUnauthorized }, false);
    },
    async requestWithMetadata(path, options = {}, responseType = 'json', { handleUnauthorized = true } = {}) {
      return execute(path, options, responseType, { handleUnauthorized }, true);
    }
  };
}

async function readBody(response, responseType) {
  if (response.status === 204) return null;
  if (responseType === 'blob') return response.blob();
  return response.json().catch(() => null);
}
