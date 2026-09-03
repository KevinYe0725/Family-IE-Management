export type HouseholdRole = 'OWNER' | 'ADMIN' | 'MEMBER';

export interface ApiFailure {
  code: string;
  message: string;
  fields?: Record<string, string>;
}

export interface ApiEnvelope<T> {
  data?: T;
  error?: ApiFailure;
}

export interface CsrfToken {
  headerName: string;
  parameterName: string;
  token: string;
}

export interface Session {
  userId: number;
  householdId: number;
  email: string;
  displayName: string;
  role: HouseholdRole;
  username: string;
}

export interface RegisterRequest {
  email: string;
  displayName: string;
  password: string;
  mode: 'CREATE' | 'JOIN';
  householdName: string | null;
  inviteToken: string | null;
}

export interface RegisterResponse {
  email: string;
  displayName: string;
  householdName: string;
  role: HouseholdRole;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

export type TransactionKind = 'income' | 'expense';
export type AccountType = 'CASH' | 'BANK' | 'WALLET';

export interface Account { id: number; name: string; type: AccountType; currency: string; openingBalance: string; archivedAt: string | null }
export interface Category { id: number; kind: TransactionKind; name: string; color: string; defaultCategory: boolean; createdAt: string; parentId: number | null; level: number; children: Category[] }
export interface Member { id: number; name: string; roleLabel: string; createdAt: string }
export interface Transaction { id: number; kind: TransactionKind; amount: string; occurredOn: string; accountId: number; accountName: string; memberId: number; memberName: string; categoryId: number; categoryName: string; categoryParentId: number | null; categoryLevel: number; merchant: string | null; location: string | null; note: string | null; createdAt: string; updatedAt: string }

export type BudgetScopeType = 'TOTAL' | 'CATEGORY' | 'MEMBER';
export interface Budget { id: number; periodMonth: string; scopeType: BudgetScopeType; categoryId: number | null; memberId: number | null; amount: string; version: number; active: boolean }
export interface BudgetUsage { budget: Budget; spent: string; remaining: string; percent: number; status: 'ON_TRACK' | 'NEAR_LIMIT' | 'AT_LIMIT' | 'OVER_BUDGET'; rollupCategories: boolean }
export interface BudgetRevision { id: number; budgetId: number; oldPeriodMonth: string; newPeriodMonth: string; oldAmount: string; newAmount: string; oldActive: boolean; newActive: boolean; changedAt: string }

export interface RecurringRule { id: number; kind: TransactionKind; amount: string; scheduleType: 'MONTHLY' | 'WEEKLY'; intervalValue: number; dayOfMonth: number | null; dayOfWeek: string | null; startOn: string; endOn: string | null; nextDueOn: string | null; accountId: number; accountName: string; memberId: number; memberName: string; categoryId: number; categoryName: string; assignedUserId: number; assignedUserName: string; active: boolean; paused: boolean; createdByUserId: number }
export interface RecurringOccurrence { id: number; ruleId: number; dueOn: string; status: 'PENDING' | 'CONFIRMED' | 'CANCELLED'; assignedUserId: number | null; confirmedTransactionId: number | null }

export type AssetType = 'PROPERTY' | 'VEHICLE' | 'OTHER';
export interface Asset { id: number; name: string; type: AssetType; ownerMemberId: number | null; acquiredOn: string | null; purchaseValue: string | null; currentValue: string; status: 'ACTIVE' | 'ARCHIVED'; createdBy: number; archivedAt: string | null; property: { address: string; areaSqm: number; usageType: string } | null; vehicle: { brandModel: string; plateHint: string; purchaseYear: number } | null }
export interface AssetValuation { id: number; valuedOn: string; value: string; source: 'PURCHASE' | 'MANUAL'; note: string | null; createdBy: number; fetchedAt: string }

export interface InvestmentAccount { id: number; name: string; brokerName: string; currency: string; status: 'ACTIVE' | 'ARCHIVED'; createdBy: number; archivedAt: string | null }
export interface Security { id: number; market: string; tsCode: string; name: string; securityType: string; active: boolean }
export type InvestmentTradeType = 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE';
export interface InvestmentTrade { id: number; accountId: number; security: Security; type: InvestmentTradeType; quantity: number; price: string; fee: string; cashImpact: string; tradedOn: string; createdBy: number; sourceType: 'MANUAL' | 'IMPORT'; sourceId: string | null }
export interface MarketPrice { securityId: number; tsCode: string; name: string; price: string | null; source: 'TUSHARE' | 'MANUAL' | null; tradeDate: string | null; fetchedAt: string | null; stale: boolean; error: string | null }
export interface PortfolioPosition { accountId: number; accountName: string; brokerName: string; securityId: number; tsCode: string; name: string; quantity: number; averageCost: string; cost: string; price: string | null; marketValue: string | null; realizedProfit: string; unrealizedProfit: string | null; totalProfit: string | null; allocationPercent: string | null; source: 'TUSHARE' | 'MANUAL' | null; tradeDate: string | null; fetchedAt: string | null; stale: boolean; error: string | null }
export interface Portfolio { positions: PortfolioPosition[]; totals: { cost: string; marketValue: string; realizedProfit: string; unrealizedProfit: string; totalProfit: string; unpricedPositions: number } }

export interface Loan { id: number; name: string; type: 'MORTGAGE' | 'CAR' | 'OTHER'; linkedAssetId: number | null; memberId: number | null; assignedUserId: number | null; paymentAccountId: number; paymentCategoryId: number; principal: string; annualRate: string; termMonths: number; repaymentMethod: 'EQUAL_PAYMENT' | 'EQUAL_PRINCIPAL' | 'CUSTOM'; startOn: string; currentPrincipal: string; status: 'ACTIVE' | 'ARCHIVED' | 'CLOSED' }
export interface LoanInstallment { id: number; installmentNo: number; dueOn: string; principal: string; interest: string; status: 'PENDING' | 'PAID' | 'CANCELLED'; confirmedTransactionId: number | null }

export interface NotificationItem { id: number; type: string; title: string; body: string; referenceType: string; referenceId: number; dueAt: string; readAt: string | null; resolvedAt: string | null; userId: number | null }
export interface NotificationPage { items: NotificationItem[]; unreadCount: number }
export interface Family { id: number; name: string; status: string; archivedAt: string | null }
export interface Membership { id: number; userId: number; email: string; displayName: string; role: HouseholdRole; status: 'ACTIVE' | 'SUSPENDED' }
export interface FamilyInvite { id: number; role: HouseholdRole; expiresAt: string; maxUses: number; usedCount: number; revokedAt: string | null; createdAt: string }
export interface CreatedInvite extends FamilyInvite { token: string }

export interface Dashboard { summary: { income: string; expense: string; balance: string }; daily: Array<{ date: string; income: string; expense: string }>; expenseByCategory: Array<{ categoryId: number; categoryName: string; amount: string; sharePercent: string }>; expenseByMember: Array<{ memberId: number; memberName: string; amount: string }> }
export interface NetWorth { asset: string; liability: string; netWorth: string; allocation: Array<{ type: string; amount: string; sharePercent: string }>; debtRatioPercent: string; budget: { activeBudgetCount: number; planned: string; spent: string; nearLimitCount: number; overLimitCount: number }; investment: { marketValue: string; positionCount: number; unpricedPositionCount: number; manualPrice: boolean; stalePrice: boolean; missingPrice: boolean }; history: Array<{ snapshotOn: string; asset: string; liability: string; netWorth: string }> }
export interface DebtAnalysis { liability: string; asset: string; debtRatioPercent: string; loans: Array<{ loanId: number; loanName: string; originalPrincipal: string; currentPrincipal: string; repaidPercent: string }> }
export interface Analysis { historyStatus: string; insights: Array<{ type: string; title: string; message: string; metric: string }> }
