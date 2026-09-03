import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ApiError } from '../api/client';
import { AuthContext, type AuthContextValue } from './AuthProvider';
import { ChangePasswordPage } from './ChangePasswordPage';
import { LoginPage } from './LoginPage';
import { RegisterPage } from './RegisterPage';

const authValue = (overrides: Partial<AuthContextValue> = {}): AuthContextValue => ({
  session: null,
  status: 'anonymous',
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
  ...overrides
});

const renderWithAuth = (ui: React.ReactNode, value: AuthContextValue) => render(
  <MemoryRouter>
    <AuthContext.Provider value={value}>{ui}</AuthContext.Provider>
  </MemoryRouter>
);

it('submits normalized email for create-family registration with Enter', async () => {
  const register = vi.fn().mockResolvedValue(undefined);
  const user = userEvent.setup();
  renderWithAuth(<RegisterPage />, authValue({ register }));

  await user.type(screen.getByLabelText('邮箱'), ' Parent@Example.com ');
  await user.type(screen.getByLabelText('姓名'), '凯文');
  await user.type(screen.getByLabelText('密码'), 'family-123');
  await user.type(screen.getByLabelText('家庭名称'), '凯文之家');
  await user.keyboard('{Enter}');

  await waitFor(() => expect(register).toHaveBeenCalledWith({
    email: 'parent@example.com',
    displayName: '凯文',
    password: 'family-123',
    mode: 'CREATE',
    householdName: '凯文之家',
    inviteToken: null
  }));
});

it('switches to invite join and maps the token without family name', async () => {
  const register = vi.fn().mockResolvedValue(undefined);
  const user = userEvent.setup();
  renderWithAuth(<RegisterPage />, authValue({ register }));
  await user.click(screen.getByRole('radio', { name: '通过邀请码加入' }));
  await user.type(screen.getByLabelText('邮箱'), 'member@example.com');
  await user.type(screen.getByLabelText('姓名'), '新成员');
  await user.type(screen.getByLabelText('密码'), 'member-123');
  await user.type(screen.getByLabelText('邀请码'), '  FAMILY-INVITE  ');
  await user.click(screen.getByRole('button', { name: '加入家庭' }));
  await waitFor(() => expect(register).toHaveBeenCalledWith(expect.objectContaining({
    mode: 'JOIN', inviteToken: 'FAMILY-INVITE', householdName: null
  })));
});

it('keeps focusable field errors beside the registration fields', async () => {
  const register = vi.fn().mockRejectedValue(new ApiError('请检查输入内容', {
    status: 400,
    code: 'VALIDATION_ERROR',
    fields: { email: '邮箱已被使用' },
    requestId: 'req-register'
  }));
  const user = userEvent.setup();
  renderWithAuth(<RegisterPage />, authValue({ register }));
  await user.type(screen.getByLabelText('邮箱'), 'used@example.com');
  await user.type(screen.getByLabelText('姓名'), '成员');
  await user.type(screen.getByLabelText('密码'), 'member-123');
  await user.type(screen.getByLabelText('家庭名称'), '共同之家');
  await user.click(screen.getByRole('button', { name: '创建家庭' }));
  expect(await screen.findByText('邮箱已被使用')).toBeInTheDocument();
  expect(screen.getByText(/请求编号 req-register/)).toBeInTheDocument();
  expect(screen.getByLabelText('邮箱')).toHaveFocus();
});

it('rejects passwords outside the 8 to 72 character boundary before submit', async () => {
  const register = vi.fn();
  const user = userEvent.setup();
  renderWithAuth(<RegisterPage />, authValue({ register }));
  await user.type(screen.getByLabelText('邮箱'), 'parent@example.com');
  await user.type(screen.getByLabelText('姓名'), '成员');
  await user.type(screen.getByLabelText('密码'), 'short');
  await user.type(screen.getByLabelText('家庭名称'), '共同之家');
  await user.click(screen.getByRole('button', { name: '创建家庭' }));
  expect(await screen.findByText('密码需为 8–72 个字符')).toBeInTheDocument();
  expect(register).not.toHaveBeenCalled();
});

it('submits login using the backend form contract', async () => {
  const login = vi.fn().mockResolvedValue(undefined);
  const user = userEvent.setup();
  renderWithAuth(<LoginPage />, authValue({ login }));
  await user.type(screen.getByLabelText('邮箱'), ' Demo@Local.Family ');
  await user.type(screen.getByLabelText('密码'), 'demo1234');
  await user.click(screen.getByRole('button', { name: '登录' }));
  await waitFor(() => expect(login).toHaveBeenCalledWith('demo@local.family', 'demo1234'));
});

it('changes password without retaining either password in browser storage', async () => {
  const changePassword = vi.fn().mockResolvedValue(undefined);
  const user = userEvent.setup();
  renderWithAuth(<ChangePasswordPage />, authValue({ changePassword }));
  await user.type(screen.getByLabelText('当前密码'), 'old-secret');
  await user.type(screen.getByLabelText('新密码'), 'new-secret-123');
  await user.type(screen.getByLabelText('确认新密码'), 'new-secret-123');
  await user.click(screen.getByRole('button', { name: '更新密码' }));
  await waitFor(() => expect(changePassword).toHaveBeenCalledWith('old-secret', 'new-secret-123'));
  expect(JSON.stringify(localStorage)).not.toContain('secret');
});
