import { ApiError, createApiClient } from './client';

const jsonResponse = (status: number, body: unknown, headers?: Record<string, string>) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers }
  });

it('shares one CSRF load across concurrent writes and sends the returned header', async () => {
  let csrfLoads = 0;
  const requests: Array<[RequestInfo | URL, RequestInit | undefined]> = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push([input, init]);
    if (input === '/api/csrf') {
      csrfLoads += 1;
      return jsonResponse(200, { data: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'shared-token' } });
    }
    return jsonResponse(200, { data: { saved: true } });
  });
  const client = createApiClient({ fetchImpl });

  await Promise.all([
    client.api('/api/transactions', { method: 'POST', body: { amount: '12.00' } }),
    client.api('/api/budgets', { method: 'PATCH', body: { amount: '800.00' } })
  ]);

  expect(csrfLoads).toBe(1);
  const writes = requests.filter(([path]) => path !== '/api/csrf');
  expect(writes).toHaveLength(2);
  for (const [, init] of writes) {
    expect(new Headers(init?.headers).get('X-XSRF-TOKEN')).toBe('shared-token');
    expect(init?.credentials).toBe('same-origin');
  }
});

it('announces concurrent session expiry once and invalidates pending work', async () => {
  const onSessionExpired = vi.fn();
  const invalidate = vi.fn();
  const client = createApiClient({
    fetchImpl: vi.fn(async () => jsonResponse(401, { error: { code: 'AUTH_REQUIRED', message: '请先登录' } })),
    onSessionExpired,
    invalidatePendingWork: invalidate
  });

  const results = await Promise.allSettled([
    client.api('/api/session'),
    client.api('/api/family')
  ]);

  expect(results.every(result => result.status === 'rejected')).toBe(true);
  expect(onSessionExpired).toHaveBeenCalledTimes(1);
  expect(invalidate).toHaveBeenCalledTimes(1);
  expect((results[0] as PromiseRejectedResult).reason).toMatchObject({ status: 401, sessionExpired: true });
});

it('keeps credential failures separate from session expiry', async () => {
  const onSessionExpired = vi.fn();
  const client = createApiClient({
    fetchImpl: vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/csrf') {
        return jsonResponse(200, { data: { headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'login-token' } });
      }
      return jsonResponse(401, { error: { code: 'LOGIN_FAILED', message: '用户名或密码错误' } });
    }),
    onSessionExpired
  });

  await expect(client.api('/api/auth/login', {
    method: 'POST',
    body: new URLSearchParams({ username: 'missing@example.com', password: 'wrong-password' })
  })).rejects.toMatchObject({
    status: 401,
    code: 'LOGIN_FAILED',
    message: '用户名或密码错误',
    sessionExpired: false
  });
  expect(onSessionExpired).not.toHaveBeenCalled();
});

it('preserves server field errors and request id for an actionable message', async () => {
  const client = createApiClient({
    fetchImpl: vi.fn(async () => jsonResponse(
      400,
      { error: { code: 'VALIDATION_ERROR', message: '请检查输入内容', fields: { email: '邮箱格式不正确' } } },
      { 'X-Request-ID': 'req-42' }
    ))
  });

  await expect(client.api('/api/auth/register', { method: 'POST', body: {} })).rejects.toEqual(
    expect.objectContaining({
      status: 400,
      requestId: 'req-42',
      fields: { email: '邮箱格式不正确' }
    })
  );
});

it('passes the caller AbortSignal to fetch', async () => {
  let capturedSignal: AbortSignal | null | undefined;
  const fetchImpl = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
    capturedSignal = init?.signal;
    return jsonResponse(200, { data: [] });
  });
  const client = createApiClient({ fetchImpl });
  const controller = new AbortController();
  await client.api('/api/assets', { signal: controller.signal });
  expect(capturedSignal).toBe(controller.signal);
});
