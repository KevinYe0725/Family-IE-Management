export function localYearMonth(date = new Date()) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  return `${year}-${month}`;
}

export function createInitialState(date = new Date()) {
  return {
    month: localYearMonth(date),
    route: 'dashboard',
    filters: { kind: '', memberId: '', categoryId: '', q: '' },
    data: { session: null, members: [], categories: [], transactions: [], dashboard: null, analysis: null },
    flash: null
  };
}

export const state = createInitialState();

if (typeof window !== 'undefined') {
  window.FamilyLedgerState = state;
}
