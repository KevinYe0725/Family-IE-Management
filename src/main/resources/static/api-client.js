export function createApiClient({ fetchImpl, onUnauthorized }) {
  return {
    async request(path, options = {}, responseType = 'json', { handleUnauthorized = true } = {}) {
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
      return responseType === 'json' ? body?.data : body;
    }
  };
}

async function readBody(response, responseType) {
  if (response.status === 204) return null;
  if (responseType === 'blob') return response.blob();
  return response.json().catch(() => null);
}
