import type { ApiEnvelope, ApiFailure, CsrfToken } from './contracts';

export const SESSION_EXPIRED_MESSAGE = '登录会话已过期，请重新登录。';

interface ApiErrorDetails {
  status: number;
  code?: string;
  fields?: Record<string, string>;
  requestId?: string;
  sessionExpired?: boolean;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly fields?: Record<string, string>;
  readonly requestId?: string;
  sessionExpired: boolean;

  constructor(message: string, details: ApiErrorDetails) {
    super(message);
    this.name = 'ApiError';
    this.status = details.status;
    this.code = details.code;
    this.fields = details.fields;
    this.requestId = details.requestId;
    this.sessionExpired = details.sessionExpired ?? false;
  }
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown;
  handleUnauthorized?: boolean;
  responseType?: 'json' | 'blob' | 'text';
}

interface ApiClientOptions {
  fetchImpl?: typeof fetch;
  onSessionExpired?: (message: string) => void;
  invalidatePendingWork?: () => void;
}

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function isBodyInit(body: unknown): body is BodyInit {
  return typeof body === 'string'
    || body instanceof URLSearchParams
    || body instanceof FormData
    || body instanceof Blob
    || body instanceof ArrayBuffer
    || ArrayBuffer.isView(body);
}

function requestIdFrom(response: Response): string | undefined {
  return response.headers.get('X-Request-ID')
    ?? response.headers.get('X-Request-Id')
    ?? undefined;
}

async function readEnvelope(response: Response): Promise<ApiEnvelope<unknown> | undefined> {
  if (response.status === 204) return undefined;
  return response.json().catch(() => undefined) as Promise<ApiEnvelope<unknown> | undefined>;
}

export function createApiClient({
  fetchImpl = fetch,
  onSessionExpired = () => undefined,
  invalidatePendingWork = () => undefined
}: ApiClientOptions = {}) {
  let csrfPromise: Promise<CsrfToken> | null = null;
  let expiryHandled = false;

  async function loadCsrf(): Promise<CsrfToken> {
    csrfPromise ??= (async () => {
      const response = await fetchImpl('/api/csrf', { credentials: 'same-origin' });
      const envelope = await readEnvelope(response) as ApiEnvelope<CsrfToken> | undefined;
      if (!response.ok || !envelope?.data) {
        csrfPromise = null;
        throw new ApiError(envelope?.error?.message ?? '无法建立安全连接，请重试', {
          status: response.status,
          code: envelope?.error?.code,
          fields: envelope?.error?.fields,
          requestId: requestIdFrom(response)
        });
      }
      return envelope.data;
    })();
    return csrfPromise;
  }

  async function api<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    const {
      body,
      handleUnauthorized = true,
      responseType = 'json',
      ...requestInit
    } = options;
    const method = (requestInit.method ?? 'GET').toUpperCase();
    const headers = new Headers(requestInit.headers);
    if (WRITE_METHODS.has(method)) {
      const csrf = await loadCsrf();
      headers.set(csrf.headerName, csrf.token);
    }

    let encodedBody: BodyInit | undefined;
    if (body !== undefined && body !== null) {
      if (isBodyInit(body)) {
        encodedBody = body;
      } else {
        headers.set('Content-Type', 'application/json');
        encodedBody = JSON.stringify(body);
      }
    }

    const response = await fetchImpl(path, {
      ...requestInit,
      method,
      headers,
      body: encodedBody,
      credentials: 'same-origin'
    });

    if (!response.ok) {
      const envelope = await readEnvelope(response);
      const failure = envelope?.error as ApiFailure | undefined;
      const error = new ApiError(failure?.message ?? '请求未能完成', {
        status: response.status,
        code: failure?.code,
        fields: failure?.fields,
        requestId: requestIdFrom(response)
      });
      // Login credential failures also use HTTP 401, but they do not mean that
      // an existing browser session expired. Keep the server's actionable
      // LOGIN_FAILED response on the login form instead of redirecting the
      // user into the session-expired flow.
      if (response.status === 401 && handleUnauthorized && failure?.code !== 'LOGIN_FAILED') {
        error.sessionExpired = true;
        if (!expiryHandled) {
          expiryHandled = true;
          invalidatePendingWork();
          onSessionExpired(SESSION_EXPIRED_MESSAGE);
        }
      }
      throw error;
    }

    if (response.status === 204) return undefined as T;
    if (responseType === 'blob') return await response.blob() as T;
    if (responseType === 'text') return await response.text() as T;
    const envelope = await readEnvelope(response);
    return envelope?.data as T;
  }

  return {
    api,
    resetSessionExpiry() {
      expiryHandled = false;
    },
    invalidateCsrf() {
      csrfPromise = null;
    }
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
