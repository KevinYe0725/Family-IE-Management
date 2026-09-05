import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from '../auth/AuthProvider';
import { LoginPage } from '../auth/LoginPage';
import { RegisterPage } from '../auth/RegisterPage';
import { WorkspaceLayout } from '../layout/WorkspaceLayout';
import { PluginProvider } from '../extensions/registry';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false, staleTime: 15_000 },
    mutations: { retry: false }
  }
});

function LoadingShell() {
  return (
    <main className="loading-shell" role="status" aria-label="正在进入家账">
      <span className="ledger-mark loading-mark" aria-hidden="true"><b>家</b><b>账</b></span>
      <p>正在进入家账</p>
    </main>
  );
}

function AppRoutes() {
  const auth = useAuth();
  if (auth.status === 'loading') return <LoadingShell />;
  if (auth.status === 'anonymous') {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
      </Routes>
    );
  }
  return (
    <Routes>
      <Route path="/workspace/*" element={<PluginProvider><WorkspaceLayout session={auth.session!} onLogout={auth.logout} /></PluginProvider>} />
      <Route path="*" element={<Navigate to="/workspace/overview" replace />} />
    </Routes>
  );
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider><AppRoutes /></AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
