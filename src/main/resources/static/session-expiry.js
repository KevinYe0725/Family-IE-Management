export const SESSION_EXPIRED_MESSAGE = '登录会话已过期，请重新登录。';

export function expireSessionOnUnauthorized(error, state, invalidateRefreshes) {
  if (error?.status !== 401) return false;
  invalidateRefreshes();
  state.data.session = null;
  state.flash = null;
  return true;
}
