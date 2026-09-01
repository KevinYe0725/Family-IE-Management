export const SESSION_EXPIRED_MESSAGE = '登录会话已过期，请重新登录。';

export function expireSessionOnUnauthorized(error, state, invalidateRefreshes, { storage, renderLogin }) {
  if (error?.status !== 401) return false;
  const alreadyExpired = state.data.session === null
    && storage.getItem('family-ledger-authenticated') !== '1';
  invalidateRefreshes();
  state.data.session = null;
  state.flash = null;
  storage.removeItem('family-ledger-authenticated');
  if (!alreadyExpired) renderLogin(SESSION_EXPIRED_MESSAGE);
  return true;
}
