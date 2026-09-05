import { Component, createContext, lazy, Suspense, useContext, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../auth/AuthProvider';
import type { RequestFn } from '../features/common';

export interface PluginDescriptor {
  id: string; version: string; apiVersion: number; name: string;
  description: string; path: string; capabilities: string[];
}

// Only bundled and reviewed UI modules may be loaded; server metadata cannot execute remote code.
const bundled = {
  'annual-stats': lazy(() => import('../plugins/annual-stats/AnnualStatsPage'))
};

export function supportedPlugins(items: PluginDescriptor[]): PluginDescriptor[] {
  return items.filter(item => Object.hasOwn(bundled, item.id) && item.apiVersion === 1
    && item.path === `/workspace/extensions/${item.id}`);
}

const PluginContext = createContext<{ items: PluginDescriptor[]; loading: boolean; error: unknown }>({ items: [], loading: false, error: null });
export const usePlugins = () => useContext(PluginContext);

export function PluginProvider({ children }: { children: ReactNode }) {
  const { request } = useAuth();
  const query = useQuery({ queryKey: ['plugins'], queryFn: () => request<PluginDescriptor[]>('/api/plugins') });
  return <PluginContext.Provider value={{ items: supportedPlugins(query.data ?? []), loading: query.isLoading, error: query.error }}>{children}</PluginContext.Provider>;
}

class PluginBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed ? <div role="alert">扩展页面加载失败，请刷新重试。<a href="/workspace/overview">返回总览</a></div> : this.props.children;
  }
}

export function PluginPage({ path, request }: { path: string; request: RequestFn }) {
  const { items, loading, error } = usePlugins();
  if (loading) return <p role="status">正在读取扩展功能…</p>;
  if (error) return <p role="alert">扩展功能暂时无法读取，请刷新重试。</p>;
  const plugin = items.find(item => item.path === path);
  if (!plugin) return <p>此扩展未启用或不可用。<a href="/workspace/overview">返回总览</a></p>;
  const Page = bundled[plugin.id as keyof typeof bundled];
  return <PluginBoundary key={plugin.id}><Suspense fallback={<p role="status">正在加载扩展页面…</p>}><Page request={request} /></Suspense></PluginBoundary>;
}
