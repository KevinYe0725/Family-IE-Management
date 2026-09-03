import { render, screen } from '@testing-library/react';
import { App } from './App';

it('shows an intentional loading state while the session is restored', () => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise(() => undefined)));
  render(<App />);
  expect(screen.getByRole('status', { name: '正在进入家账' })).toBeInTheDocument();
  vi.unstubAllGlobals();
});
