import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { createApiClient } from '../api/client';
import type { ChangePasswordRequest, RegisterRequest, RegisterResponse, Session } from '../api/contracts';

export type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

export interface AuthContextValue {
  session: Session | null;
  status: AuthStatus;
  notice?: string | null;
  login: (email: string, password: string) => Promise<void>;
  register: (request: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [session, setSession] = useState<Session | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [notice, setNotice] = useState<string | null>(null);

  const client = useMemo(() => createApiClient({
    invalidatePendingWork: () => queryClient.clear(),
    onSessionExpired: message => {
      setSession(null);
      setStatus('anonymous');
      setNotice(message);
    }
  }), [queryClient]);

  useEffect(() => {
    const controller = new AbortController();
    client.api<Session>('/api/session', { signal: controller.signal, handleUnauthorized: false })
      .then(value => {
        setSession(value);
        setStatus('authenticated');
      })
      .catch(error => {
        if (error instanceof DOMException && error.name === 'AbortError') return;
        setSession(null);
        setStatus('anonymous');
      });
    return () => controller.abort();
  }, [client]);

  const login = useCallback(async (email: string, password: string) => {
    const body = new URLSearchParams({ username: email, password });
    const value = await client.api<Session>('/api/auth/login', { method: 'POST', body });
    client.resetSessionExpiry();
    setNotice(null);
    setSession(value);
    setStatus('authenticated');
  }, [client]);

  const register = useCallback(async (request: RegisterRequest) => {
    await client.api<RegisterResponse>('/api/auth/register', { method: 'POST', body: request });
    await login(request.email, request.password);
  }, [client, login]);

  const logout = useCallback(async () => {
    try {
      await client.api<void>('/api/auth/logout', { method: 'POST' });
    } finally {
      queryClient.clear();
      setSession(null);
      setStatus('anonymous');
      setNotice(null);
      client.invalidateCsrf();
    }
  }, [client, queryClient]);

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    const request: ChangePasswordRequest = { currentPassword, newPassword };
    await client.api<void>('/api/auth/change-password', { method: 'POST', body: request });
  }, [client]);

  const value = useMemo<AuthContextValue>(() => ({
    session,
    status,
    notice,
    login,
    register,
    logout,
    changePassword
  }), [session, status, notice, login, register, logout, changePassword]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}
