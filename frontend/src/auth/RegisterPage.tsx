import { useState, type FormEvent } from 'react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import Input from '@douyinfe/semi-ui/lib/es/input';
import Radio from '@douyinfe/semi-ui/lib/es/radio';
import RadioGroup from '@douyinfe/semi-ui/lib/es/radio/radioGroup';
import { Link, useNavigate } from 'react-router-dom';
import type { RegisterRequest } from '../api/contracts';
import { useAuth } from './AuthProvider';
import { AuthFrame } from './AuthFrame';
import { errorMessage, focusField } from './form-utils';

type Mode = RegisterRequest['mode'];
type FormErrors = Record<string, string>;

function FieldError({ id, children }: { id: string; children?: string }) {
  return children ? <p className="field-error" id={id} role="alert">{children}</p> : null;
}

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState<Mode>('CREATE');
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [householdName, setHouseholdName] = useState('');
  const [inviteToken, setInviteToken] = useState('');
  const [errors, setErrors] = useState<FormErrors>({});
  const [generalError, setGeneralError] = useState<string | null>(null);
  const [requestId, setRequestId] = useState<string>();
  const [busy, setBusy] = useState(false);

  function validate(): FormErrors {
    const next: FormErrors = {};
    if (!email.trim()) next.email = '请输入邮箱';
    if (!displayName.trim() || displayName.trim().length > 40) next.displayName = '姓名需为 1–40 个字符';
    if (password.length < 8 || password.length > 72) next.password = '密码需为 8–72 个字符';
    if (mode === 'CREATE' && !householdName.trim()) next.householdName = '请输入家庭名称';
    if (mode === 'JOIN' && !inviteToken.trim()) next.inviteToken = '请输入邀请码';
    return next;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const nextErrors = validate();
    if (Object.keys(nextErrors).length) {
      setErrors(nextErrors);
      focusField(Object.keys(nextErrors)[0]!);
      return;
    }
    const request: RegisterRequest = {
      email: email.trim().toLowerCase(),
      displayName: displayName.trim(),
      password,
      mode,
      householdName: mode === 'CREATE' ? householdName.trim() : null,
      inviteToken: mode === 'JOIN' ? inviteToken.trim() : null
    };
    setBusy(true);
    setErrors({});
    setGeneralError(null);
    try {
      await register(request);
      navigate('/workspace/overview', { replace: true });
    } catch (cause) {
      const failure = errorMessage(cause);
      setErrors(failure.fields ?? {});
      setGeneralError(failure.message);
      setRequestId(failure.requestId);
      const firstField = Object.keys(failure.fields ?? {})[0];
      if (firstField) focusField(firstField);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthFrame
      eyebrow="开始共同记账"
      title={mode === 'CREATE' ? '创建家庭空间' : '加入家庭空间'}
      description="账号只属于你，账目属于共同生活的家。"
      footer={<>已有账号？ <Link to="/login">返回登录</Link></>}
    >
      <RadioGroup
        className="registration-mode"
        value={mode}
        onChange={event => {
          setMode(event.target.value as Mode);
          setErrors({});
          setGeneralError(null);
        }}
        aria-label="注册方式"
      >
        <Radio value="CREATE">创建新家庭</Radio>
        <Radio value="JOIN">通过邀请码加入</Radio>
      </RadioGroup>
      {generalError && <div className="form-alert" role="alert">{generalError}</div>}
      <form className="auth-form" onSubmit={submit} noValidate>
        <label htmlFor="email">邮箱</label>
        <Input id="email" value={email} onChange={setEmail} autoComplete="email" aria-describedby={errors.email ? 'email-error' : undefined} placeholder="name@example.com" />
        <FieldError id="email-error">{errors.email}</FieldError>

        <label htmlFor="displayName">姓名</label>
        <Input id="displayName" value={displayName} onChange={setDisplayName} autoComplete="name" aria-describedby={errors.displayName ? 'displayName-error' : undefined} placeholder="家庭成员如何称呼你" maxLength={40} />
        <FieldError id="displayName-error">{errors.displayName}</FieldError>

        <label htmlFor="password">密码</label>
        <Input id="password" mode="password" value={password} onChange={setPassword} autoComplete="new-password" aria-describedby="password-help password-error" placeholder="8–72 个字符" />
        <p className="field-help" id="password-help">请使用 8–72 个字符，不与其他网站共用密码。</p>
        <FieldError id="password-error">{errors.password}</FieldError>

        {mode === 'CREATE' ? (
          <>
            <label htmlFor="householdName">家庭名称</label>
            <Input id="householdName" value={householdName} onChange={setHouseholdName} aria-describedby={errors.householdName ? 'householdName-error' : undefined} placeholder="例如：凯文之家" />
            <FieldError id="householdName-error">{errors.householdName}</FieldError>
          </>
        ) : (
          <>
            <label htmlFor="inviteToken">邀请码</label>
            <Input id="inviteToken" value={inviteToken} onChange={setInviteToken} aria-describedby={errors.inviteToken ? 'inviteToken-error' : undefined} placeholder="粘贴家庭管理员发来的邀请码" />
            <FieldError id="inviteToken-error">{errors.inviteToken}</FieldError>
          </>
        )}
        {requestId && <p className="request-id">请求编号 {requestId}</p>}
        <Button theme="solid" type="primary" htmlType="submit" loading={busy} block>
          {mode === 'CREATE' ? '创建家庭' : '加入家庭'}
        </Button>
      </form>
    </AuthFrame>
  );
}
