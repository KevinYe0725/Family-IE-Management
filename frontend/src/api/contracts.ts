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

export type WalletProvider = 'ALIPAY' | 'WECHAT' | 'OTHER';
export interface Account { bankAccountId?:number|null;bankAccountName?:string|null; walletProvider?: WalletProvider | null; bankName?: string | null; cardLastFour?: string | null; id: number; name: string; type: AccountType; currency: string; openingBalance: string; archivedAt: string | null; openingConfirmed: boolean; openingOn: string | null; balance: string | null; availableBalance: string | null }
export interface BankAccount {id:number;name:string;bankName:string|null;cardLastFour:string|null;archivedAt:string|null;accounts:Account[]}
export interface Category { id: number; kind: TransactionKind; name: string; color: string; defaultCategory: boolean; createdAt: string; parentId: number | null; level: number; children: Category[] }
export interface Member { id: number; name: string; roleLabel: string; createdAt: string }
export interface Transaction { currency?: string; id: number; kind: TransactionKind; amount: string; occurredOn: string; accountId: number; accountName: string; memberId: number; memberName: string; createdByUserId: number; createdByName: string | null; sourceId?: number | null; principalAmount?: string | null; interestAmount?: string | null; sourceType: 'MANUAL' | 'RECURRING' | 'LOAN' | 'LOAN_PAYMENT' | 'LOAN_PREPAYMENT'; categoryId: number; categoryName: string; categoryParentId: number | null; categoryLevel: number; merchant: string | null; location: string | null; note: string | null; createdAt: string; updatedAt: string }

export type BudgetScopeType = 'TOTAL' | 'CATEGORY' | 'MEMBER' | 'CATEGORY_MEMBER';
export interface Budget { id: number; periodMonth: string; scopeType: BudgetScopeType; categoryId: number | null; memberId: number | null; amount: string; version: number; active: boolean; note?: string | null }
export interface BudgetUsage { budget: Budget; spent: string; remaining: string; percent: number; status: 'ON_TRACK' | 'NEAR_LIMIT' | 'AT_LIMIT' | 'OVER_BUDGET'; rollupCategories: boolean }
export interface BudgetRevision { id: number; budgetId: number; oldPeriodMonth: string; newPeriodMonth: string; oldAmount: string; newAmount: string; oldActive: boolean; newActive: boolean; oldNote?: string | null; newNote?: string | null; changedAt: string }
export interface BudgetTotal { periodMonth: string; amount: string | null; version: number }
export interface BudgetUsageEntry { entryId: number; occurredOn: string; amount: string; categoryId: number | null; categoryName: string; memberId: number | null; memberName: string; note: string | null; sourceType: string; sourceId: number | null }
export interface BudgetTemplateRow { rowId: number; scopeType: BudgetScopeType; categoryId: number | null; memberId: number | null; amount: string; note: string | null }
export interface BudgetTemplate { id: number; name: string; createdAt: string; rows: BudgetTemplateRow[] }
export interface BudgetTemplateApply { periodMonth: string; copied: number; skipped: number }
export interface BudgetHit { budgetId: number; scopeType: BudgetScopeType; categoryId: number | null; categoryName: string | null; memberId: number | null; memberName: string | null; amount: string; spent: string; spentAfter: string; percentAfter: number; statusAfter: 'ON_TRACK' | 'NEAR_LIMIT' | 'AT_LIMIT' | 'OVER_BUDGET' }

export type RecurringScheduleType = 'MONTHLY' | 'QUARTERLY' | 'YEARLY' | 'WEEKLY';
export interface RecurringRule { id: number; kind: TransactionKind; amount: string; scheduleType: RecurringScheduleType; intervalValue: number; dayOfMonth: number | null; dayOfWeek: string | null; startOn: string; endOn: string | null; nextDueOn: string | null; accountId: number; accountName: string; memberId: number; memberName: string; categoryId: number; categoryName: string; assignedUserId: number; assignedUserName: string; active: boolean; paused: boolean; createdByUserId: number }
export interface RecurringOccurrence { id: number; ruleId: number; dueOn: string; status: 'PENDING' | 'CONFIRMED' | 'CANCELLED'; assignedUserId: number | null; confirmedTransactionId: number | null }

export type AssetType = 'PROPERTY' | 'VEHICLE' | 'OTHER';
export interface Asset { acquisitionSourceType?: string; acquisitionSourceId?: number; detailsPending?: boolean; accountingMode?: 'OPENING' | 'PURCHASE' | 'FINANCED_PURCHASE' | null; accountingOn?: string | null; fundingAccountId?: number | null; disposedOn?: string | null; disposalProceeds?: string | null; disposalBookGain?: string | null; id: number; name: string; type: AssetType; ownerMemberId: number | null; acquiredOn: string | null; purchaseValue: string | null; currentValue: string; status: 'ACTIVE' | 'ARCHIVED'; createdBy: number; archivedAt: string | null; property: { address: string; areaSqm: number; usageType: string } | null; vehicle: { brandModel: string; plateHint: string | null; purchaseYear: number | null } | null }
export interface AssetValuation { id: number; valuedOn: string; value: string; source: 'PURCHASE' | 'MANUAL'; note: string | null; createdBy: number; fetchedAt: string }

export interface InvestmentAccount { fundingAccountId: number | null; id: number; name: string; brokerName: string; currency: string; status: 'ACTIVE' | 'ARCHIVED'; createdBy: number; archivedAt: string | null }
export interface Security { currency?: string; symbol?: string; exchange?: string; timezone?: string; id: number; market: string; tsCode: string; name: string; securityType: string; active: boolean }
export type InvestmentTradeType = 'OPENING' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE';
export interface InvestmentTrade { cashAccountId?: number | null; accountingConfirmed?: boolean; id: number; accountId: number; security: Security; type: InvestmentTradeType; quantity: number; price: string; fee: string; cashImpact: string; tradedOn: string; createdBy: number; sourceType: 'MANUAL' | 'IMPORT'; sourceId: string | null }
export interface MarketPrice { currency?: string; securityId: number; tsCode: string; name: string; price: string | null; source: 'TUSHARE' | 'BAOSTOCK' | 'MANUAL' | 'SINA' | 'TENCENT' | null; tradeDate: string | null; fetchedAt: string | null; stale: boolean; error: string | null }
export interface PortfolioPosition { currency?: string; market?: string; symbol?: string; exchange?: string; timezone?: string; base?: { cost: string | null; marketValue: string | null; realizedProfit: string | null; unrealizedProfit: string | null; totalProfit: string | null; estimatedValue: string | null; fxDate: string | null; fxState: string }; valuationStatus?: 'QUOTED' | 'COST_ESTIMATE' | 'CLOSED'; estimatedValue?: string; accountId: number; accountName: string; brokerName: string; securityId: number; tsCode: string; name: string; quantity: number; averageCost: string; cost: string; price: string | null; marketValue: string | null; realizedProfit: string; unrealizedProfit: string | null; totalProfit: string | null; allocationPercent: string | null; source: 'TUSHARE' | 'BAOSTOCK' | 'MANUAL' | 'SINA' | 'TENCENT' | null; tradeDate: string | null; fetchedAt: string | null; stale: boolean; error: string | null }
export interface Portfolio { positions: PortfolioPosition[]; totals: { currency?: string; missingFxRates?: number; knownEstimatedValue?: string; cost: string | null; estimatedValue?: string | null; marketValue: string | null; realizedProfit: string; unrealizedProfit: string | null; totalProfit: string | null; unpricedPositions: number } }

export type PrepaymentStrategy = 'REDUCE_TERM' | 'REDUCE_PAYMENT' | 'ADJUST_TERM';
export interface LoanPrecision { precisePrincipalAmount?: string | null; preciseInterestAmount?: string | null; interestCarryAmount?: string | null; roundingPolicy?: string | null; principalRoundingAmount?: string | null }
export interface LoanPrepaymentSchedule { principalAmount: string; periodCount: number; maturityOn: string | null; nextPaymentOn: string | null; nextPaymentAmount: string | null; totalInterest: string; repaymentTotal: string; schedule: Array<LoanPrecision & { installmentNo: number; dueOn: string; principal: string; interest: string; paymentAmount: string; remainingPrincipal: string }> }
export interface LoanPrepaymentPreview { strategy: PrepaymentStrategy; principalAmount: string; cashAmount: string; paymentAccountId: number; availableBalance: string; paidOn: string; planToken: string; before: LoanPrepaymentSchedule; after: LoanPrepaymentSchedule }
export interface LoanRepaymentPolicy { minimumInstallmentAmount: string | null; sourceNote: string | null; revision: number }
export interface LoanTermOption { evaluationStatus?: 'FEASIBLE' | 'INFEASIBLE' | 'UNDETERMINED'; periods: number; allowed: boolean; reason: string | null; firstPaymentAmount: string | null; roundingPolicy: string | null }
export interface LoanTermOptions { remainingPrincipal: string; duePrincipal: string; dueInterest: string; policy: LoanRepaymentPolicy; options: LoanTermOption[] }
export interface LoanRepaymentPreview {
 dueInstallments: Array<{ installmentId: number; installmentNo: number; dueOn: string; principalAmount: string; interestAmount: string; cashAmount: string }>;
 duePrincipalAmount: string; dueInterestAmount: string; additionalPrincipal: string; totalPrincipalAmount: string; totalInterestAmount: string; totalCashAmount: string;
 paymentAccountId: number; availableBalance: string; balanceAfter: string; paidOn: string; strategy: PrepaymentStrategy; targetPeriods: number | null;
 before: LoanPrepaymentSchedule; after: LoanPrepaymentSchedule; termOptions: LoanTermOption[]; policy: LoanRepaymentPolicy; planToken: string;
}
export interface LoanRepaymentRequest { additionalPrincipal: string; paidOn: string; paymentAccountId: number; strategy: PrepaymentStrategy; targetPeriods: number | null; planToken: string; idempotencyKey: string }
export interface LoanRepayment extends LoanRepaymentPreview {
 batchId: number; loanId: number; status: 'ACTIVE' | 'CLOSED'; remainingPrincipal: string; recordedAt: string;
 children: Array<{ sourceType: 'LOAN_PAYMENT' | 'LOAN_PREPAYMENT'; sourceId: number; transactionId: number; principalAmount: string; interestAmount: string; cashAmount: string }>;
}
export interface Loan { latestStrategy?: PrepaymentStrategy | null; remainingTerm?: number; maturityOn?: string | null; nextPaymentOn?: string | null; nextPaymentAmount?: string | null; scheduledRepaymentTotal?: string; remainingRepaymentTotal?: string; paidRepaymentTotal?: string; purchasedAssetId?: number | null; fundingMode?: 'OPENING' | 'DISBURSEMENT' | 'FINANCED_PURCHASE' | null; accountingOn?: string | null; disbursementAccountId?: number | null; accountingInitialized?: boolean; lastPaymentOn?: string | null; overdueInstallments?: number; overdueAmount?: string; overdueDays?: number; id: number; name: string; type: 'MORTGAGE' | 'CAR' | 'OTHER'; linkedAssetId: number | null; memberId: number | null; assignedUserId: number | null; paymentAccountId: number; paymentCategoryId: number; principal: string; annualRate: string; termMonths: number; repaymentMethod: 'EQUAL_PAYMENT' | 'EQUAL_PRINCIPAL' | 'CUSTOM'; startOn: string; currentPrincipal: string; status: 'ACTIVE' | 'ARCHIVED' | 'CLOSED' }
export interface LoanPayoffQuote { principalAmount: string; dueInterestAmount: string; interestAmount: string; futureScheduledInterest: string; cashAmount: string; paymentAccountId: number; availableBalance: string; paidOn: string; planToken: string }
export interface LoanPrepayment { repaymentBatchId?: number | null; strategy?: PrepaymentStrategy | null; id: number; transactionId: number; amount: string; remainingPrincipal: string; status: Loan['status']; paidOn: string; principalAmount: string; interestAmount: string; cashAmount: string; operationKind: 'PREPAYMENT' | 'PAYOFF'; paymentAccountId: number; scheduledRepaymentTotal: string; remainingRepaymentTotal: string; paidRepaymentTotal: string }
export interface LoanContractExtraction { documentName: string; fields: { suggestedName: string | null; loanType: Loan['type'] | null; principal: string | null; annualRatePercent: string | null; termMonths: number | null; repaymentMethod: Loan['repaymentMethod'] | null; startOn: string | null }; confidence: Record<string, number>; warnings: string[] }
export interface LoanDebtOverview { count: number; remainingPrincipal: string; remainingRepayment: string; thirtyDayDue: string; paidRepayment: string; weightedAnnualRatePercent: string; nextDueOn: string | null; overdueInstallments: number; overdueAmount: string; overdueDays: number }
export interface LoanInstallment extends LoanPrecision { cancelledByPrepaymentId?: number | null; paymentAccountId?: number | null; paidOn?: string | null; cashAmount?: string; id: number; installmentNo: number; dueOn: string; principal: string; interest: string; status: 'PENDING' | 'PAID' | 'CANCELLED'; confirmedTransactionId: number | null }

export interface NotificationItem { id: number; type: string; title: string; body: string; referenceType: string; referenceId: number; dueAt: string; readAt: string | null; resolvedAt: string | null; userId: number | null }
export interface NotificationPage { items: NotificationItem[]; unreadCount: number }
export interface Family { id: number; name: string; status: string; archivedAt: string | null }
export interface Membership { id: number; userId: number; email: string; displayName: string; role: HouseholdRole; status: 'ACTIVE' | 'SUSPENDED' }
export interface FamilyInvite { id: number; role: HouseholdRole; expiresAt: string; maxUses: number; usedCount: number; revokedAt: string | null; createdAt: string }
export interface CreatedInvite extends FamilyInvite { token: string }

export interface Dashboard { summary: { income: string; expense: string; balance: string; cashIn?: string; cashOut?: string; principalPaid?: string; borrowed?: string; noncashValuationChange?: string }; daily: Array<{ date: string; income: string; expense: string }>; expenseByCategory: Array<{ categoryId: number; categoryName: string; amount: string; sharePercent: string }>; expenseByMember: Array<{ memberId: number; memberName: string; amount: string }> }
export interface NetWorth { knownAsset?: string; unconverted?: Array<{kind:string;currency:string;nativeAmount:string}>; asOf?: string; accountingBasis?: string; cumulativeAssetValuationChange?: string; asset: string | null; liability: string; netWorth: string | null; allocation: Array<{ type: string; amount: string; sharePercent: string }>; debtRatioPercent: string | null; budget: { activeBudgetCount: number; planned: string; spent: string | null; nearLimitCount: number | null; overLimitCount: number | null }; investment: { marketValue: string | null; estimatedValue?: string; positionCount: number; unpricedPositionCount: number; manualPrice: boolean; stalePrice: boolean; missingPrice: boolean }; history: NetWorthHistory[] }
export interface NetWorthHistory { recordedNetWorth?:string; snapshotOn: string; asset: string | null; liability: string; netWorth: string | null; accountingBasis: string; valuationEstimated: boolean; unpricedPositions: number }
export interface DebtAnalysis { liability: string; asset: string; debtRatioPercent: string; loans: Array<{ loanId: number; loanName: string; originalPrincipal: string; currentPrincipal: string; repaidPercent: string }> }
export interface Analysis { historyStatus: string; insights: Array<{ type: string; title: string; message: string; metric: string }> }

export interface CashTransfer { id: number; fromAccountId: number; toAccountId: number; amount: string; occurredOn: string; actorId: number }
export interface AccountingJournal { journalId: number; sourceType: string; sourceId: number; revision: number; operation: 'POST' | 'REPLACE' | 'REVERSE'; effectiveOn: string; recordedAt: string; actorId: number; reversesJournalId: number | null; legs: Array<{ currency?: string; accountCode: string; debit: string; credit: string; categoryId: number | null; memberId: number | null }> }
