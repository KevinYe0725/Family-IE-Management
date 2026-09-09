import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import Button from "@douyinfe/semi-ui/lib/es/button";
import type {
  Account,
  Asset,
  Category,
  HouseholdRole,
  Loan,
  LoanContractExtraction,
  LoanDebtOverview,
  LoanRepayment,
  LoanPrepayment,
  LoanInstallment,
  Member,
  Membership,
  Page,
} from "../../api/contracts";
import { LoanPrepaymentPanel } from "./LoanPrepaymentPanel";
import { LoanPayoffPanel } from "./LoanPayoffPanel";
import { LoanDebtOverviewPanel } from "./LoanDebtOverview";
import { ApiError } from "../../api/client";
import { businessDate, newIdempotencyKey } from "../../shared/runtime";
import { DateField } from "../../shared/DateField";
import {
  PaginationControls,
  readAllPages,
  usePageRecovery,
} from "../../shared/pagination";
import {
  AccountOptions,
  PaymentPreview,
  sumMoney,
  useFundsRefresh,
} from "../accounting";
import { AccountingHistory } from "../ledger/accounting-flows";
import {
  DataPanel,
  Drawer,
  FormError,
  PageScaffold,
  QueryState,
  StatusTag,
  dateText,
  isManager,
  money,
  type RequestFn,
} from "../common";

export type LoanDraft = {
  id?: number;
  key?: string;
  purchasedAssetId?: number | null;
  createPurchasedAsset?: boolean;
  fundingMode?: "OPENING" | "DISBURSEMENT" | "FINANCED_PURCHASE";
  accountingOn?: string;
  disbursementAccountId?: string;
  name: string;
  type: "MORTGAGE" | "CAR" | "OTHER";
  linkedAssetId: string;
  memberId: string;
  assignedUserId: string;
  paymentAccountId: string;
  paymentCategoryId: string;
  principal: string;
  annualRate: string;
  termMonths: string;
  repaymentMethod: "EQUAL_PAYMENT" | "EQUAL_PRINCIPAL" | "CUSTOM";
  startOn: string;
  customSchedule: Array<{ dueOn: string; principal: string; interest: string }>;
};

const ANNUAL_RATE_ERROR =
  "年利率请输入 0 到 100 之间的百分比，最多保留 4 位小数";

export function annualRatePercentError(raw: string): string | null {
  return /^(?:\d{1,2}(?:\.\d{1,4})?|100(?:\.0{1,4})?)$/.test(raw.trim())
    ? null
    : ANNUAL_RATE_ERROR;
}

function parseAnnualRatePercent(raw: string): number {
  const error = annualRatePercentError(raw);
  if (error) throw new Error(error);
  const [whole, decimal = ""] = raw.trim().split(".");
  const millionths = Number(whole) * 10_000 + Number(decimal.padEnd(4, "0"));
  return Number((millionths / 1_000_000).toFixed(6));
}

export function loanCreatePayload(value: LoanDraft) {
  return {
    name: value.name,
    type: value.type,
    principal: value.principal,
    repaymentMethod: value.repaymentMethod,
    startOn: value.startOn,
    createPurchasedAsset: value.createPurchasedAsset === true,
    fundingMode: value.fundingMode,
    accountingOn: value.accountingOn,
    disbursementAccountId:
      value.fundingMode === "DISBURSEMENT"
        ? Number(value.disbursementAccountId)
        : null,
    linkedAssetId: value.createPurchasedAsset
      ? null
      : value.linkedAssetId
        ? Number(value.linkedAssetId)
        : null,
    memberId: value.memberId ? Number(value.memberId) : null,
    assignedUserId: Number(value.assignedUserId),
    paymentAccountId: Number(value.paymentAccountId),
    paymentCategoryId: Number(value.paymentCategoryId),
    annualRate: parseAnnualRatePercent(value.annualRate),
    termMonths: Number(value.termMonths),
    customSchedule:
      value.repaymentMethod === "CUSTOM" ? value.customSchedule : null,
  };
}

export function formatAnnualRatePercent(raw: string): string {
  const value = Number(raw);
  return Number.isFinite(value)
    ? String(Number((value * 100).toFixed(4)))
    : raw;
}

export function LoansPage({
  request,
  role,
  userId = 0,
}: {
  request: RequestFn;
  role: HouseholdRole;
  userId?: number;
}) {
  const [repaymentAudit, setRepaymentAudit] = useState<number | null>(null);
  const [payoffOpen, setPayoffOpen] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const setSelected = (loan: Loan | null) => {
    setSelectedId(loan?.id ?? null);
    setSchedulePage(0);
    setScheduleView(loan?.status === "CLOSED" ? "HISTORY" : "CURRENT");
  };
  const [loanStatus, setLoanStatus] = useState("ACTIVE");
  const [payment, setPayment] = useState<{
    installment: LoanInstallment;
    paidOn: string;
    paymentAccountId: string;
    key: string;
  } | null>(null);
  const [settings, setSettings] = useState<{
    id: number;
    name: string;
    paymentAccountId: string;
    paymentCategoryId: string;
    assignedUserId: string;
    key: string;
  } | null>(null);
  const [auditOpen, setAuditOpen] = useState(false);
  const [draft, setDraft] = useState<LoanDraft | null>(null);
  const [step, setStep] = useState(0);
  const [annualRateFeedback, setAnnualRateFeedback] = useState<string | null>(
    null,
  );
  const [contractExtraction, setContractExtraction] =
    useState<LoanContractExtraction | null>(null);
  const [useAiConsent, setUseAiConsent] = useState(false);
  const [prepayOpen, setPrepayOpen] = useState(false);
  const [loanPage, setLoanPage] = useState(0);
  const [schedulePage, setSchedulePage] = useState(0);
  const [scheduleView, setScheduleView] = useState<"CURRENT" | "HISTORY">(
    "CURRENT",
  );
  const loans = useQuery({
    queryKey: ["loans", "page", loanStatus, loanPage],
    queryFn: () =>
      request<Page<Loan>>(
        `/api/loans?status=${loanStatus}&page=${loanPage}&size=50`,
        { responseType: "page" },
      ),
  });
  const debtOverview = useQuery({
    queryKey: ["loans", "debt-overview", loanStatus],
    queryFn: () =>
      request<LoanDebtOverview>("/api/loans/debt-overview"),
    enabled: loanStatus === "ACTIVE",
  });
  const selectedDetail = useQuery({
    queryKey: ["loans", "detail", selectedId],
    queryFn: () => request<Loan>(`/api/loans/${selectedId}`),
    enabled: selectedId !== null,
  });
  const selected =
    selectedDetail.data?.id === selectedId
      ? selectedDetail.data
      : (loans.data?.items.find((item) => item.id === selectedId) ?? null);
  const schedule = useQuery({
    queryKey: ["loan-schedule", selected?.id, scheduleView, schedulePage],
    queryFn: () =>
      request<Page<LoanInstallment>>(
        `/api/loans/${selected!.id}/schedule?page=${schedulePage}&size=50&view=${scheduleView}`,
        { responseType: "page" },
      ),
    enabled: selected !== null,
  });
  useEffect(() => {
    if (selected?.status === "CLOSED") {
      setScheduleView("HISTORY");
      setSchedulePage(0);
    }
  }, [selected?.id, selected?.status]);
  const history = useQuery({
    queryKey: ["loan-prepayments", selectedId],
    queryFn: () =>
      request<LoanPrepayment[]>(`/api/loans/${selectedId}/prepayments`),
    enabled: selectedId !== null,
  });
  const repayments = useQuery({
    queryKey: ["loan-repayments", selectedId],
    queryFn: () =>
      request<LoanRepayment[]>(`/api/loans/${selectedId}/repayments`),
    enabled: selectedId !== null,
  });
  const accounts = useQuery({
    queryKey: ["accounts", "all-options"],
    queryFn: () =>
      readAllPages((page) =>
        request<Page<Account>>(`/api/accounts?page=${page}&size=50`, {
          responseType: "page",
        }),
      ),
  });
  const categories = useQuery({
    queryKey: ["categories", "flat-all-options"],
    queryFn: () =>
      readAllPages((page) =>
        request<Page<Category>>(
          `/api/categories?projection=flat&page=${page}&size=50`,
          { responseType: "page" },
        ),
      ),
  });
  const assets = useQuery({
    queryKey: ["assets", "active-all-options"],
    queryFn: () =>
      readAllPages((page) =>
        request<Page<Asset>>(`/api/assets?status=ACTIVE&page=${page}&size=50`, {
          responseType: "page",
        }),
      ),
  });
  const members = useQuery({
    queryKey: ["members"],
    queryFn: () => request<Member[]>("/api/members"),
  });
  const memberships = useQuery({
    queryKey: ["memberships", "all-options"],
    queryFn: () =>
      readAllPages((page) =>
        request<Page<Membership>>(
          `/api/family/memberships?page=${page}&size=50`,
          { responseType: "page" },
        ),
      ),
  });
  usePageRecovery(loanPage, loans.data, setLoanPage);
  usePageRecovery(schedulePage, schedule.data, setSchedulePage);
  const manager = isManager(role);
  const fundsError = useFundsRefresh();
  const extractContract = useMutation({
    mutationFn: ({ file, useAi }: { file: File; useAi: boolean }) => {
      const body = new FormData();
      body.append("file", file);
      return request<LoanContractExtraction>(
        `/api/plugins/loan-contract-extractor/extract?useAi=${useAi ? "true" : "false"}`,
        { method: "POST", body },
      );
    },
    onSuccess: (result) => {
      setContractExtraction(result);
      if (!draft) return;
      const fields = result.fields;
      setDraft({
        ...draft,
        name: fields.suggestedName ?? draft.name,
        type: fields.loanType ?? draft.type,
        principal: fields.principal ?? draft.principal,
        annualRate: fields.annualRatePercent ?? draft.annualRate,
        termMonths: fields.termMonths
          ? String(fields.termMonths)
          : draft.termMonths,
        repaymentMethod: fields.repaymentMethod ?? draft.repaymentMethod,
        startOn: fields.startOn ?? draft.startOn,
      });
    },
  });
  const paymentAccount = accounts.data?.find(
    (item) => item.id === selected?.paymentAccountId,
  );
  const saveSettings = useMutation({
    mutationFn: (value: NonNullable<typeof settings>) =>
      request(`/api/loans/${value.id}`, {
        method: "PATCH",
        headers: { "Idempotency-Key": value.key },
        body: {
          name: value.name,
          paymentAccountId: Number(value.paymentAccountId),
          paymentCategoryId: Number(value.paymentCategoryId),
          assignedUserId: Number(value.assignedUserId),
        },
      }),
    onError: fundsError,
    onSuccess: () => setSettings(null),
  });
  const save = useMutation({
    mutationFn: (value: LoanDraft) =>
      request<Loan>(value.id ? `/api/loans/${value.id}` : "/api/loans", {
        method: value.id ? "PATCH" : "POST",
        headers: { "Idempotency-Key": value.key ?? "" },
        body: value.id
          ? loanCorrectionPayload(value)
          : loanCreatePayload(value),
      }),
    onError: (error) => {
      fundsError(error);
      if (!(error instanceof ApiError)) return;
      const field = Object.keys(error.fields ?? {})[0];
      if (!field) return;
      if (
        [
          "name",
          "type",
          "principal",
          "startOn",
          "fundingMode",
          "accountingOn",
          "disbursementAccountId",
          "createPurchasedAsset",
        ].includes(field)
      )
        setStep(0);
      else if (
        ["annualRate", "termMonths", "repaymentMethod"].includes(field) ||
        field.startsWith("customSchedule")
      )
        setStep(1);
      else setStep(2);
    },
    onSuccess: () => {
      setDraft(null);
      setStep(0);
      void debtOverview.refetch();
      void loans.refetch();
    },
  });
  const confirm = useMutation({
    mutationFn: (value: NonNullable<typeof payment>) =>
      request<LoanInstallment>(
        `/api/loan-installments/${value.installment.id}/confirm`,
        {
          method: "POST",
          headers: { "Idempotency-Key": value.key },
          body: {
            paidOn: value.paidOn,
            paymentAccountId: Number(value.paymentAccountId),
          },
        },
      ),
    onError: fundsError,
    onSuccess: async () => {
      setPayment(null);
      await selectedDetail.refetch();
      void debtOverview.refetch();
      void loans.refetch();
    },
  });

  const archive = useMutation({
    mutationFn: (id: number) =>
      request<void>(`/api/loans/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      void debtOverview.refetch();
      void loans.refetch();
    },
  });
  const blank = (): LoanDraft => ({
    key: newIdempotencyKey(),
    fundingMode: "OPENING",
    accountingOn: businessDate(),
    disbursementAccountId: "",
    name: "",
    type: "MORTGAGE",
    linkedAssetId: "",
    memberId: "",
    assignedUserId: "",
    paymentAccountId: "",
    paymentCategoryId: "",
    principal: "",
    annualRate: "",
    termMonths: "360",
    repaymentMethod: "EQUAL_PAYMENT",
    startOn: businessDate(),
    customSchedule: [],
  });
  const loanType = (value: Loan["type"]) =>
    value === "MORTGAGE" ? "房贷" : value === "CAR" ? "车贷" : "其他贷款";
  return (
    <PageScaffold
      title="贷款计划"
      primaryAction={
        manager
          ? {
              label: "新建贷款",
              onClick: () => {
                setDraft(blank());
                setStep(0);
                setAnnualRateFeedback(null);
              },
            }
          : undefined
      }
      readonly={!manager}
    >
      {loanStatus === "ACTIVE" &&
        loans.data &&
        loans.data.totalElements > 0 &&
        (debtOverview.isLoading ? (
          <p className="source-note" role="status">
            正在计算债务总览…
          </p>
        ) : debtOverview.error ? null : debtOverview.data ? (
          <LoanDebtOverviewPanel data={debtOverview.data} />
        ) : null)}
      <div className="filter-bar">
        <label>
          贷款状态
          <select
            value={loanStatus}
            onChange={(e) => {
              setLoanStatus(e.target.value);
              setLoanPage(0);
            }}
          >
            <option value="ACTIVE">还款中</option>
            <option value="CLOSED">已结清本金</option>
            <option value="ARCHIVED">已归档历史</option>
          </select>
        </label>
      </div>
      <FormError error={archive.error} />
      <QueryState
        loading={loans.isLoading}
        error={loans.error}
        empty={!loans.data?.items.length && loanPage === 0}
        emptyTitle="还没有活跃贷款"
        emptyDetail={
          manager
            ? "创建贷款后即可查看每一期还款安排。"
            : "家庭目前没有需要查看的贷款。"
        }
      >
        <>
          <div className="loan-grid">
            {loans.data?.items.map((item) => (
              <article className="loan-card" key={item.id}>
                <header>
                  <div>
                    <StatusTag tone="blue">{loanType(item.type)}</StatusTag>
                    <h2>{item.name}</h2>
                  </div>
                  <span>
                    原合同 ·{" "}
                    {item.repaymentMethod === "EQUAL_PAYMENT"
                      ? "等额本息"
                      : item.repaymentMethod === "EQUAL_PRINCIPAL"
                        ? "等额本金"
                        : "自定义"}
                  </span>
                </header>
                {item.status !== "CLOSED" && (
                  <div className="loan-principal">
                    <span>剩余本金</span>
                    <strong>{money(item.currentPrincipal)}</strong>
                  </div>
                )}
                {loanOverviewFacts(item)}
                <footer>
                  <Button
                    size="small"
                    onClick={() => {
                      setSchedulePage(0);
                      setSelected(item);
                    }}
                  >
                    查看计划
                  </Button>
                  {manager &&
                    item.accountingInitialized &&
                    item.status === "ACTIVE" && (
                      <>
                        <Button
                          size="small"
                          onClick={() => {
                            setSelected(item);
                            setPrepayOpen(true);
                          }}
                        >
                          提前还款
                        </Button>
                        <Button
                          size="small"
                          onClick={() => {
                            setSelected(item);
                            setPayoffOpen(true);
                          }}
                        >
                          一次结清
                        </Button>
                        <button
                          className="text-action danger"
                          onClick={() => archive.mutate(item.id)}
                        >
                          归档
                        </button>
                      </>
                    )}
                </footer>
              </article>
            ))}
          </div>
          <PaginationControls
            page={loanPage}
            totalPages={loans.data?.totalPages ?? 0}
            hasNext={loans.data?.hasNext ?? false}
            onPageChange={setLoanPage}
            label="贷款"
          />
        </>
      </QueryState>
      <Drawer
        draft={draft}
        busy={save.isPending}
        onSessionStart={save.reset}
        open={draft !== null}
        title={draft?.id ? "更正未付款合同" : "新建贷款"}
        description="登记已有负债、现金放款或本次贷款购买物；每期还款在确认后记录。"
        onClose={() => setDraft(null)}
      >
        {draft && (
          <>
            <ol className="wizard-steps" aria-label="贷款创建步骤">
              <li className={step >= 0 ? "active" : ""}>1 合同</li>
              <li className={step >= 1 ? "active" : ""}>2 还款</li>
              <li className={step >= 2 ? "active" : ""}>3 关联</li>
            </ol>
            <form
              className="feature-form"
              onSubmit={(e: FormEvent<HTMLFormElement>) => {
                e.preventDefault();
                if (step === 1) {
                  const error = annualRatePercentError(draft.annualRate);
                  setAnnualRateFeedback(error);
                  if (error) {
                    const field = e.currentTarget.elements.namedItem(
                      "annualRate",
                    ) as HTMLElement | null;
                    field?.focus();
                    field?.scrollIntoView?.({ block: "nearest" });
                    return;
                  }
                }
                if (step < 2) setStep(step + 1);
                else save.mutate(draft);
              }}
            >
              <FormError error={save.error} scopeKey={step} />
              {step === 0 && (
                <>
                  <div className="loan-contract-import">
                    <label htmlFor="loan-contract-file">合同文件（Word/PDF）</label>
                    <input
                      id="loan-contract-file"
                      type="file"
                      accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                      onChange={event => {
                        const file = event.target.files?.[0];
                        if (file)
                          extractContract.mutate({
                            file,
                            useAi: useAiConsent,
                          });
                        event.currentTarget.value = "";
                      }}
                    />
                    <label className="ai-consent">
                      使用系统 AI 提取
                      <input
                        type="checkbox"
                        checked={useAiConsent}
                        onChange={(e) => setUseAiConsent(e.target.checked)}
                      />
                    </label>
                    <p className="source-note">
                      勾选会把合同文本发送到 AI 服务用于更准确识别；不勾选则用规则识别。<br />
                      上传后仅提取字段并预填表单，不会自动提交，请核对合同原文。
                    </p>
                    {extractContract.isPending && <p role="status">正在提取合同信息…</p>}
                    <FormError error={extractContract.error} />
                    {contractExtraction?.warnings.map(warning => <p className="field-help" key={warning}>{warning}</p>)}
                  </div>
                  <label>
                    贷款名称
                    <input
                      name="name"
                      required
                      value={draft.name}
                      onChange={(e) =>
                        setDraft({ ...draft, name: e.target.value })
                      }
                    />
                  </label>
                  <label>
                    贷款类型
                    <select
                      name="type"
                      disabled={Boolean(draft.id)}
                      value={draft.type}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          type: e.target.value as LoanDraft["type"],
                          linkedAssetId: draft.createPurchasedAsset
                            ? "PURCHASED"
                            : "",
                        })
                      }
                    >
                      <option value="MORTGAGE">房贷</option>
                      <option value="CAR">车贷</option>
                      <option value="OTHER">其他</option>
                    </select>
                  </label>
                  <label>
                    入账方式
                    <select
                      name="fundingMode"
                      disabled={Boolean(draft.id)}
                      value={draft.fundingMode}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          fundingMode: e.target
                            .value as LoanDraft["fundingMode"],
                          createPurchasedAsset:
                            e.target.value === "FINANCED_PURCHASE",
                          linkedAssetId:
                            e.target.value === "FINANCED_PURCHASE"
                              ? "PURCHASED"
                              : "",
                          disbursementAccountId: "",
                        })
                      }
                    >
                      <option value="OPENING">期初贷款（已有负债）</option>
                      <option value="DISBURSEMENT">
                        实际放款（新借入现金）
                      </option>
                      <option value="FINANCED_PURCHASE">
                        本次贷款购买物（直接支付购买款）
                      </option>
                    </select>
                  </label>
                  <label>
                    {draft.fundingMode === "OPENING"
                      ? "账务起始日剩余本金"
                      : draft.fundingMode === "FINANCED_PURCHASE"
                        ? "贷款购买本金"
                        : "实际放款本金"}
                    <input
                      name="principal"
                      disabled={Boolean(draft.purchasedAssetId)}
                      required
                      inputMode="decimal"
                      value={draft.principal}
                      onChange={(e) =>
                        setDraft({ ...draft, principal: e.target.value })
                      }
                    />
                  </label>
                  <label>
                    {draft.fundingMode === "OPENING"
                      ? "账务起始日期"
                      : draft.fundingMode === "FINANCED_PURCHASE"
                        ? "购买入账日期"
                        : "实际放款日期"}
                    <DateField
                      name="accountingOn"
                      disabled={Boolean(draft.purchasedAssetId)}
                      required
                      max={businessDate()}
                      value={draft.accountingOn}
                      onChange={(e) =>
                        setDraft({ ...draft, accountingOn: e.target.value })
                      }
                    />
                  </label>
                  {draft.fundingMode === "DISBURSEMENT" && (
                    <>
                      <label>
                        放款到账账户
                        <select
                          required
                          name="disbursementAccountId"
                          value={draft.disbursementAccountId}
                          onChange={(e) =>
                            setDraft({
                              ...draft,
                              disbursementAccountId: e.target.value,
                            })
                          }
                        >
                          <option value="">请选择</option>
                          <AccountOptions accounts={(accounts.data ?? []).filter(a=>(a.currency??'CNY')==='CNY')} />
                        </select>
                      </label>
                      <PaymentPreview
                        incoming
                        account={accounts.data?.find(
                          (a) => String(a.id) === draft.disbursementAccountId,
                        )}
                        amount={draft.principal}
                        adjustment={Boolean(draft.id)}
                      />
                    </>
                  )}
                  <label>
                    起息 / 计划起算日期
                    <DateField
                      name="startOn"
                      disabled={Boolean(draft.purchasedAssetId)}
                      required
                      value={draft.startOn}
                      onChange={(e) =>
                        setDraft({ ...draft, startOn: e.target.value })
                      }
                    />
                  </label>
                </>
              )}
              {step === 1 && (
                <>
                  <label>
                    年利率（%）
                    <input
                      aria-label="年利率（%）"
                      name="annualRate"
                      required
                      inputMode="decimal"
                      aria-invalid={annualRateFeedback ? true : undefined}
                      aria-describedby={
                        annualRateFeedback ? "annual-rate-error" : undefined
                      }
                      value={draft.annualRate}
                      onChange={(e) => {
                        const annualRate = e.target.value;
                        setDraft({ ...draft, annualRate });
                        if (annualRateFeedback)
                          setAnnualRateFeedback(
                            annualRatePercentError(annualRate),
                          );
                      }}
                    />
                  </label>
                  {annualRateFeedback && (
                    <span
                      id="annual-rate-error"
                      className="field-help"
                      role="alert"
                    >
                      {annualRateFeedback}
                    </span>
                  )}
                  <label>
                    {draft.fundingMode === "OPENING"
                      ? "剩余计划期限（月）"
                      : "期限（月）"}
                    <input
                      name="termMonths"
                      required
                      type="number"
                      min="1"
                      value={draft.termMonths}
                      onChange={(e) =>
                        setDraft({ ...draft, termMonths: e.target.value })
                      }
                    />
                  </label>
                  <label>
                    还款方式
                    <select
                      name="repaymentMethod"
                      value={draft.repaymentMethod}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          repaymentMethod: e.target
                            .value as LoanDraft["repaymentMethod"],
                        })
                      }
                    >
                      <option value="EQUAL_PAYMENT">等额本息</option>
                      <option value="EQUAL_PRINCIPAL">等额本金</option>
                      <option value="CUSTOM">自定义计划</option>
                    </select>
                  </label>
                  {draft.repaymentMethod === "CUSTOM" ? (
                    <fieldset data-field="customSchedule" tabIndex={-1}>
                      <legend>自定义期次</legend>
                      {draft.customSchedule.map((row, index) => (
                        <div className="custom-installment" key={index}>
                          <DateField
                            aria-label={`第 ${index + 1} 期日期`}
                            required
                            name={`customSchedule[${index}].dueOn`}
                            value={row.dueOn}
                            onChange={(e) =>
                              setDraft({
                                ...draft,
                                customSchedule: draft.customSchedule.map(
                                  (item, i) =>
                                    i === index
                                      ? { ...item, dueOn: e.target.value }
                                      : item,
                                ),
                              })
                            }
                          />
                          <input
                            aria-label={`第 ${index + 1} 期本金`}
                            required
                            placeholder="本金"
                            name={`customSchedule[${index}].principal`}
                            value={row.principal}
                            onChange={(e) =>
                              setDraft({
                                ...draft,
                                customSchedule: draft.customSchedule.map(
                                  (item, i) =>
                                    i === index
                                      ? { ...item, principal: e.target.value }
                                      : item,
                                ),
                              })
                            }
                          />
                          <input
                            aria-label={`第 ${index + 1} 期利息`}
                            required
                            placeholder="利息"
                            name={`customSchedule[${index}].interest`}
                            value={row.interest}
                            onChange={(e) =>
                              setDraft({
                                ...draft,
                                customSchedule: draft.customSchedule.map(
                                  (item, i) =>
                                    i === index
                                      ? { ...item, interest: e.target.value }
                                      : item,
                                ),
                              })
                            }
                          />
                          <button
                            type="button"
                            onClick={() =>
                              setDraft({
                                ...draft,
                                customSchedule: draft.customSchedule.filter(
                                  (_, i) => i !== index,
                                ),
                              })
                            }
                          >
                            移除
                          </button>
                        </div>
                      ))}
                      <Button
                        type="tertiary"
                        onClick={() =>
                          setDraft({
                            ...draft,
                            customSchedule: [
                              ...draft.customSchedule,
                              { dueOn: "", principal: "", interest: "" },
                            ],
                          })
                        }
                      >
                        添加期次
                      </Button>
                    </fieldset>
                  ) : (
                    <div className="source-note">
                      创建时登记贷款本金；之后确认每期还款会分别记录偿还本金与利息费用。期初模式请填剩余还款计划。
                    </div>
                  )}
                </>
              )}
              {step === 2 && (
                <>
                  <label>
                    关联资产
                    <select
                      name="linkedAssetId"
                      disabled={Boolean(draft.purchasedAssetId)}
                      value={
                        draft.createPurchasedAsset
                          ? "PURCHASED"
                          : draft.linkedAssetId
                      }
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          linkedAssetId: e.target.value,
                          createPurchasedAsset: e.target.value === "PURCHASED",
                          fundingMode:
                            e.target.value === "PURCHASED"
                              ? "FINANCED_PURCHASE"
                              : draft.fundingMode === "FINANCED_PURCHASE"
                                ? "OPENING"
                                : draft.fundingMode,
                          disbursementAccountId:
                            e.target.value === "PURCHASED"
                              ? ""
                              : draft.disbursementAccountId,
                        })
                      }
                    >
                      <option value="">不关联</option>
                      {!draft.id && (
                        <option value="PURCHASED">本次贷款购买物</option>
                      )}
                      {assets.data
                        ?.filter(
                          (item) =>
                            item.type ===
                            (draft.type === "MORTGAGE"
                              ? "PROPERTY"
                              : draft.type === "CAR"
                                ? "VEHICLE"
                                : "OTHER"),
                        )
                        .map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.name}
                          </option>
                        ))}
                    </select>
                  </label>
                  {draft.fundingMode === "FINANCED_PURCHASE" && (
                    <p className="source-note">
                      贷款人直接支付购买款，不经过家庭现金。自动创建
                      {draft.type === "MORTGAGE"
                        ? "房产"
                        : draft.type === "CAR"
                          ? "车辆"
                          : "其他资产"}
                      ，购入价值与初始估值均为 {money(draft.principal)}
                      ；可之后在资产页面补齐真实资料。扣款账户只用于今后的还款。
                    </p>
                  )}
                  <label>
                    归属成员
                    <select
                      name="memberId"
                      value={draft.memberId}
                      onChange={(e) =>
                        setDraft({ ...draft, memberId: e.target.value })
                      }
                    >
                      <option value="">家庭共有</option>
                      {members.data?.map((item) => (
                        <option key={item.id} value={item.id}>
                          {item.name}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    确认还款人
                    <select
                      name="assignedUserId"
                      required
                      value={draft.assignedUserId}
                      onChange={(e) =>
                        setDraft({ ...draft, assignedUserId: e.target.value })
                      }
                    >
                      <option value="">请选择</option>
                      {memberships.data?.map((item) => (
                        <option key={item.userId} value={item.userId}>
                          {item.displayName}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    扣款账户
                    <select
                      name="paymentAccountId"
                      required
                      value={draft.paymentAccountId}
                      onChange={(e) =>
                        setDraft({ ...draft, paymentAccountId: e.target.value })
                      }
                    >
                      <option value="">请选择</option>
                      <AccountOptions accounts={(accounts.data ?? []).filter(a=>(a.currency??'CNY')==='CNY')} />
                    </select>
                  </label>
                  <label>
                    还款分类
                    <select
                      name="paymentCategoryId"
                      required
                      value={draft.paymentCategoryId}
                      onChange={(e) =>
                        setDraft({
                          ...draft,
                          paymentCategoryId: e.target.value,
                        })
                      }
                    >
                      <option value="">请选择支出分类</option>
                      {categories.data
                        ?.filter((item) => item.kind === "expense")
                        .map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.name}
                          </option>
                        ))}
                    </select>
                  </label>
                </>
              )}
              <div className="form-footer">
                {step > 0 && (
                  <Button onClick={() => setStep(step - 1)}>上一步</Button>
                )}
                <Button
                  htmlType="submit"
                  theme="solid"
                  type="primary"
                  loading={save.isPending}
                >
                  {step < 2
                    ? "下一步"
                    : draft.id
                      ? "保存合同更正"
                      : "创建并生成计划"}
                </Button>
              </div>
            </form>
          </>
        )}
      </Drawer>
      <Drawer
        sessionKey={selected?.id}
        busy={confirm.isPending}
        onSessionStart={confirm.reset}
        open={
          selectedId !== null &&
          !payoffOpen &&
          repaymentAudit === null &&
          !prepayOpen &&
          payment === null &&
          settings === null &&
          !auditOpen &&
          draft === null
        }
        title={`${selected?.name ?? ""} · 还款计划`}
        description="到期后可确认本期还款；如需提前偿还本金，请使用贷款卡片上的「提前还款」。"
        onClose={() => {
          setSelected(null);
          setSchedulePage(0);
        }}
      >
        <FormError error={selectedDetail.error} />
        {selected && !selected.accountingInitialized && (
          <p className="source-note">
            此贷款尚未完成账务初始化，只能查看。历史负债须经明确核对流程处理。
          </p>
        )}
        {selected && (
          <>
            <LoanTotals loan={selected} />
            <LoanCurrentPlan loan={selected} />
            <p>
              当前剩余本金 {money(selected.currentPrincipal)} · 扣款账户{" "}
              {paymentAccount?.name ?? "账户不可用"}
            </p>
            <div className="toolbar">
              {manager &&
                selected.accountingInitialized &&
                selected.status === "ACTIVE" && (
                  <Button
                    onClick={() =>
                      setSettings({
                        id: selected.id,
                        name: selected.name,
                        paymentAccountId: String(selected.paymentAccountId),
                        paymentCategoryId: String(selected.paymentCategoryId),
                        assignedUserId: String(selected.assignedUserId ?? ""),
                        key: newIdempotencyKey(),
                      })
                    }
                  >
                    未来还款设置
                  </Button>
                )}
              {manager &&
                selected.accountingInitialized &&
                !selected.lastPaymentOn &&
                selected.status === "ACTIVE" && (
                  <Button
                    onClick={() => {
                      setDraft({
                        ...blank(),
                        ...selected,
                        key: newIdempotencyKey(),
                        fundingMode: selected.fundingMode ?? "OPENING",
                        accountingOn: selected.accountingOn ?? "",
                        disbursementAccountId: String(
                          selected.disbursementAccountId ?? "",
                        ),
                        annualRate: formatAnnualRatePercent(
                          selected.annualRate,
                        ),
                        termMonths: String(selected.termMonths),
                        linkedAssetId: String(selected.linkedAssetId ?? ""),
                        memberId: String(selected.memberId ?? ""),
                        assignedUserId: String(selected.assignedUserId ?? ""),
                        paymentAccountId: String(selected.paymentAccountId),
                        paymentCategoryId: String(selected.paymentCategoryId),
                        customSchedule: [],
                      });
                      setStep(0);
                    }}
                  >
                    更正未付款合同
                  </Button>
                )}
              <Button onClick={() => setAuditOpen(true)}>
                贷款起始账务历史
              </Button>
            </div>
          </>
        )}
        <label>
          计划范围
          <select
            value={scheduleView}
            onChange={(event) => {
              setScheduleView(event.target.value as "CURRENT" | "HISTORY");
              setSchedulePage(0);
            }}
          >
            <option value="CURRENT">待还计划</option>
            <option value="HISTORY">已还与已取消历史</option>
          </select>
        </label>
        <FormError error={confirm.error} />
        <QueryState
          loading={schedule.isLoading}
          error={schedule.error}
          empty={!schedule.data?.items.length && schedulePage === 0}
          emptyTitle={
            scheduleView === "CURRENT"
              ? "没有待还期次，可切换历史计划查看"
              : "暂无已还或已取消期次"
          }
        >
          <>
            <div className="schedule-list">
              {schedule.data?.items.map((item) => (
                <article
                  key={item.id}
                  className={item.status !== "PENDING" ? "settled" : ""}
                >
                  <div>
                    <b>{String(item.installmentNo).padStart(2, "0")}</b>
                    <span>{dateText(item.dueOn)}</span>
                  </div>
                  <dl>
                    <span>
                      本金 <strong>{money(item.principal)}</strong>
                    </span>
                    <span>
                      利息 <strong>{money(item.interest)}</strong>
                    </span>
                  </dl>
                  {item.status === "PAID" ? (
                    <>
                      <StatusTag tone="success">已记录还款</StatusTag>
                      <span>
                        实付 {dateText(item.paidOn)} · 合计{" "}
                        {money(
                          item.cashAmount ??
                            sumMoney(item.principal, item.interest),
                        )}
                      </span>
                    </>
                  ) : item.status === "CANCELLED" ? (
                    <StatusTag>已取消</StatusTag>
                  ) : item.dueOn > businessDate() ? (
                    <StatusTag>未到期</StatusTag>
                  ) : selected?.accountingInitialized &&
                    selected.assignedUserId === userId ? (
                    <Button
                      size="small"
                      loading={confirm.isPending}
                      onClick={() => {
                        confirm.reset();
                        setPayment({
                          installment: item,
                          paidOn: businessDate(),
                          paymentAccountId: String(selected.paymentAccountId),
                          key: newIdempotencyKey(),
                        });
                      }}
                    >
                      确认还款
                    </Button>
                  ) : (
                    <StatusTag tone="warning">待确认</StatusTag>
                  )}
                </article>
              ))}
            </div>
            <PaginationControls
              page={schedulePage}
              totalPages={schedule.data?.totalPages ?? 0}
              hasNext={schedule.data?.hasNext ?? false}
              onPageChange={setSchedulePage}
              label="还款计划"
            />
          </>
        </QueryState>
        <FormError error={history.error} />
        <FormError error={repayments.error} />
        {((Array.isArray(history.data) &&
          history.data.some((event) => !event.repaymentBatchId)) ||
          (Array.isArray(repayments.data) && repayments.data.length > 0)) && (
          <section aria-label="提前还款与结清历史">
            <h3>提前还款与结清历史</h3>
            {Array.isArray(repayments.data) &&
              repayments.data.map((batch) => (
                <article key={`batch-${batch.batchId}`} className="source-note">
                  <p>
                    到期款与额外还本 · {dateText(batch.paidOn)} · 本次总付款{" "}
                    {money(batch.totalCashAmount)}
                  </p>
                  <p>
                    本金 {money(batch.totalPrincipalAmount)} · 利息{" "}
                    {money(batch.totalInterestAmount)} · 额外本金{" "}
                    {money(batch.additionalPrincipal)}
                  </p>
                  <p>
                    当时付款后余额 {money(batch.balanceAfter)} · 当时剩余本金{" "}
                    {money(batch.remainingPrincipal)}
                  </p>
                  <details>
                    <summary>查看本次还款凭证与子记录</summary>
                    <p>
                      还款批次 #{batch.batchId} · 账户 #{batch.paymentAccountId}{" "}
                      · 记录时间 {batch.recordedAt}
                    </p>
                    {batch.children.map((child) => (
                      <div key={child.transactionId}>
                        <p>
                          {child.sourceType === "LOAN_PAYMENT"
                            ? "到期期次"
                            : "额外还本"}{" "}
                          #{child.sourceId} · 交易 #{child.transactionId} · 本金{" "}
                          {money(child.principalAmount)} + 利息{" "}
                          {money(child.interestAmount)} ={" "}
                          {money(child.cashAmount)}
                        </p>
                        {child.sourceType === "LOAN_PREPAYMENT" && (
                          <Button
                            size="small"
                            onClick={() => setRepaymentAudit(child.sourceId)}
                          >
                            查看额外还本账务凭证
                          </Button>
                        )}
                      </div>
                    ))}
                  </details>
                </article>
              ))}
            {Array.isArray(history.data) &&
              history.data
                .filter((event) => !event.repaymentBatchId)
                .map((event) => (
                  <article key={event.id} className="source-note">
                    <p>
                      {event.operationKind === "PAYOFF"
                        ? "一次结清"
                        : event.strategy === "REDUCE_TERM"
                          ? "提前还款 · 按原付款上限缩期"
                          : event.strategy === "ADJUST_TERM"
                            ? "提前还款 · 自选更短期数"
                            : event.strategy === "REDUCE_PAYMENT"
                              ? "提前还款 · 保留期数"
                              : "提前还款"}{" "}
                      · {dateText(event.paidOn)}
                    </p>
                    <p>
                      本金 {money(event.principalAmount)} · 利息{" "}
                      {money(event.interestAmount)} · 实付{" "}
                      {money(event.cashAmount)}
                    </p>
                    <Button
                      size="small"
                      onClick={() => setRepaymentAudit(event.id)}
                    >
                      查看还款凭证
                    </Button>
                  </article>
                ))}
          </section>
        )}
      </Drawer>
      {selected && prepayOpen && (
        <LoanPrepaymentPanel
          key={selected.id}
          loan={selected}
          accounts={accounts.data ?? []}
          request={request}
          onClose={() => {
            setPrepayOpen(false);
            setSelected(null);
          }}
          onPaid={async () => {
            const refreshed = await selectedDetail.refetch();
            setScheduleView(
              (refreshed.data ?? selected).status === "CLOSED"
                ? "HISTORY"
                : "CURRENT",
            );
            setSchedulePage(0);
            setPrepayOpen(false);
            void debtOverview.refetch();
            void loans.refetch();
      void loans.refetch();
          }}
          onPayoff={() => {
            setPrepayOpen(false);
            setPayoffOpen(true);
          }}
        />
      )}

      <Drawer
        open={payment !== null}
        draft={payment}
        sessionKey={payment?.key}
        busy={confirm.isPending}
        onSessionStart={confirm.reset}
        title="记录本期还款"
        description="仅由指定还款人确认；按待还顺序记录，计划日期与实际付款日期分别保留。"
        onClose={() => setPayment(null)}
      >
        {payment && (
          <form
            className="feature-form"
            onSubmit={(e) => {
              e.preventDefault();
              confirm.mutate(payment);
            }}
          >
            <FormError error={confirm.error} />
            <p>计划还款日 {payment.installment.dueOn}</p>
            <p>
              本金 {money(payment.installment.principal)} · 利息{" "}
              {money(payment.installment.interest)}
            </p>
            <label>
              实际还款日期
              <DateField
                required
                name="paidOn"
                max={businessDate()}
                min={[
                  payment.installment.dueOn,
                  selected?.accountingOn ?? "",
                  selected?.lastPaymentOn ?? "",
                ]
                  .sort()
                  .at(-1)}
                value={payment.paidOn}
                onChange={(e) =>
                  setPayment({ ...payment, paidOn: e.target.value })
                }
              />
            </label>
            <label>
              本次付款账户
              <select
                required
                name="paymentAccountId"
                value={payment.paymentAccountId}
                onChange={(e) =>
                  setPayment({ ...payment, paymentAccountId: e.target.value })
                }
              >
                <AccountOptions accounts={(accounts.data ?? []).filter(a=>(a.currency??'CNY')==='CNY')} />
              </select>
            </label>
            <PaymentPreview
              account={accounts.data?.find(
                (a) => String(a.id) === payment.paymentAccountId,
              )}
              amount={sumMoney(
                payment.installment.principal,
                payment.installment.interest,
              )}
            />
            <Button
              htmlType="submit"
              theme="solid"
              loading={confirm.isPending}
              disabled={
                selected?.assignedUserId !== userId ||
                !selected?.accountingInitialized
              }
            >
              记录本期还款
            </Button>
          </form>
        )}
      </Drawer>
      <Drawer
        open={settings !== null}
        draft={settings}
        sessionKey={settings?.key}
        busy={saveSettings.isPending}
        onSessionStart={saveSettings.reset}
        title="未来还款设置"
        description="只调整之后的扣款与确认人，不更改已记录还款。"
        onClose={() => setSettings(null)}
      >
        {settings && (
          <form
            className="feature-form"
            onSubmit={(e) => {
              e.preventDefault();
              saveSettings.mutate(settings);
            }}
          >
            <FormError error={saveSettings.error} />
            <label>
              贷款名称
              <input
                required
                name="name"
                value={settings.name}
                onChange={(e) =>
                  setSettings({ ...settings, name: e.target.value })
                }
              />
            </label>
            <label>
              扣款账户
              <select
                required
                name="paymentAccountId"
                value={settings.paymentAccountId}
                onChange={(e) =>
                  setSettings({ ...settings, paymentAccountId: e.target.value })
                }
              >
                <option value="">请选择</option>
                <AccountOptions accounts={(accounts.data ?? []).filter(a=>(a.currency??'CNY')==='CNY')} />
              </select>
            </label>
            <label>
              利息费用分类
              <select
                required
                name="paymentCategoryId"
                value={settings.paymentCategoryId}
                onChange={(e) =>
                  setSettings({
                    ...settings,
                    paymentCategoryId: e.target.value,
                  })
                }
              >
                <option value="">请选择</option>
                {categories.data
                  ?.filter((c) => c.kind === "expense")
                  .map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name}
                    </option>
                  ))}
              </select>
            </label>
            <label>
              确认还款人
              <select
                required
                name="assignedUserId"
                value={settings.assignedUserId}
                onChange={(e) =>
                  setSettings({ ...settings, assignedUserId: e.target.value })
                }
              >
                <option value="">请选择</option>
                {memberships.data
                  ?.filter((m) => m.status === "ACTIVE")
                  .map((m) => (
                    <option key={m.userId} value={m.userId}>
                      {m.displayName}
                    </option>
                  ))}
              </select>
            </label>
            <Button
              htmlType="submit"
              theme="solid"
              loading={saveSettings.isPending}
            >
              保存未来设置
            </Button>
          </form>
        )}
      </Drawer>
      {payoffOpen && selected && (
        <LoanPayoffPanel
          key={selected.id}
          loan={selected}
          accounts={accounts.data ?? []}
          request={request}
          onClose={() => {
            setPayoffOpen(false);
            setSelected(null);
          }}
          onPaid={async () => {
            setPayoffOpen(false);
            await selectedDetail.refetch();
            void debtOverview.refetch();
            void loans.refetch();
      void loans.refetch();
          }}
        />
      )}
      <Drawer
        open={repaymentAudit !== null}
        title="还款账务历史"
        onClose={() => setRepaymentAudit(null)}
      >
        {repaymentAudit !== null && (
          <AccountingHistory
            request={request}
            source={{ sourceType: "LOAN_PREPAYMENT", sourceId: repaymentAudit }}
          />
        )}
      </Drawer>
      <Drawer
        open={auditOpen}
        title="贷款起始账务历史"
        onClose={() => setAuditOpen(false)}
      >
        {selected && (
          <AccountingHistory
            request={request}
            source={{
              sourceType:
                selected.fundingMode === "FINANCED_PURCHASE"
                  ? "LOAN_FINANCED_PURCHASE"
                  : selected.fundingMode === "DISBURSEMENT"
                    ? "LOAN_DISBURSEMENT"
                    : "LOAN_OPENING",
              sourceId: selected.id,
            }}
          />
        )}
      </Drawer>
    </PageScaffold>
  );
}

function loanOverviewFacts(item: Loan) {
  const closed = item.status === "CLOSED";
  const principalLabel =
    item.fundingMode === "OPENING"
      ? "期初剩余本金"
      : item.fundingMode === "FINANCED_PURCHASE"
        ? "贷款购买本金"
        : "放款本金";
  const rate = `${formatAnnualRatePercent(item.annualRate)}%`;
  const term = `${item.termMonths} ${item.repaymentMethod === "CUSTOM" ? "期" : "个月"}`;
  const strategy =
    item.latestStrategy === "REDUCE_TERM"
      ? "按原付款上限缩期"
      : item.latestStrategy === "ADJUST_TERM"
        ? "自选更短期数"
        : item.latestStrategy
          ? "保留期数"
          : null;
  type Fact = { dt: string; dd: string; wide?: boolean };
  const facts = (rows: Array<Fact | null>): Fact[] =>
    rows.filter((row): row is Fact => row !== null);

  const head: Fact[] = closed
    ? facts([
        { dt: "年利率", dd: rate },
        { dt: principalLabel, dd: money(item.principal) },
        { dt: "开始日", dd: dateText(item.startOn) },
        { dt: "累计已还现金", dd: money(item.paidRepaymentTotal) },
      ])
    : facts([
        { dt: "年利率", dd: rate },
        item.remainingTerm !== undefined
          ? { dt: "剩余期数", dd: `${item.remainingTerm} 期` }
          : null,
        item.nextPaymentOn
          ? { dt: "下期还款日", dd: dateText(item.nextPaymentOn) }
          : null,
        item.nextPaymentOn
          ? { dt: "下期应还", dd: money(item.nextPaymentAmount) }
          : null,
      ]);

  const fold: Fact[] = closed
    ? facts([
        { dt: "原合同期限", dd: term },
        {
          dt: "当前有效计划总金额",
          dd: money(item.scheduledRepaymentTotal),
        },
        item.nextPaymentOn
          ? { dt: "下期还款日", dd: dateText(item.nextPaymentOn) }
          : null,
        item.nextPaymentOn
          ? { dt: "下期应还", dd: money(item.nextPaymentAmount) }
          : null,
        strategy ? { dt: "最近调整", dd: strategy, wide: true } : null,
      ])
    : facts([
        { dt: principalLabel, dd: money(item.principal) },
        { dt: "原合同期限", dd: term },
        { dt: "开始日", dd: dateText(item.startOn) },
        {
          dt: "当前有效计划总金额",
          dd: money(item.scheduledRepaymentTotal),
        },
        { dt: "计划剩余本息", dd: money(item.remainingRepaymentTotal) },
        { dt: "累计已还现金", dd: money(item.paidRepaymentTotal) },
        item.remainingTerm !== undefined
          ? { dt: "计划到期", dd: dateText(item.maturityOn) }
          : null,
        strategy ? { dt: "最近调整", dd: strategy, wide: true } : null,
      ]);

  return (
    <>
      <dl className="loan-facts" aria-label="贷款信息">
        {head.map((fact) => (
          <div key={fact.dt}>
            <dt>{fact.dt}</dt>
            <dd>{fact.dd}</dd>
          </div>
        ))}
      </dl>
      {fold.length > 0 && (
        <details className="loan-facts-more">
          <summary>更多贷款信息</summary>
          <dl className="loan-facts">
            {fold.map((fact) => (
              <div
                className={fact.wide ? "loan-facts__wide" : undefined}
                key={fact.dt}
              >
                <dt>{fact.dt}</dt>
                <dd>{fact.dd}</dd>
              </div>
            ))}
          </dl>
        </details>
      )}
    </>
  );
}

function LoanTotals({ loan }: { loan: Loan }) {
  return (
    <dl className="loan-amount-grid" aria-label="贷款本息总览">
      <div>
        <dt>当前有效计划总金额</dt>
        <dd>{money(loan.scheduledRepaymentTotal)}</dd>
      </div>
      <div>
        <dt>计划剩余本息</dt>
        <dd>{money(loan.remainingRepaymentTotal)}</dd>
      </div>
      <div>
        <dt>累计已还现金</dt>
        <dd>{money(loan.paidRepaymentTotal)}</dd>
      </div>
    </dl>
  );
}
function LoanCurrentPlan({ loan }: { loan: Loan }) {
  return loan.remainingTerm === undefined ? null : (
    <div className="source-note">
      <p>
        当前剩余 {loan.remainingTerm} 期 · 计划到期 {dateText(loan.maturityOn)}
      </p>
      {loan.nextPaymentOn && (
        <p>
          下期 {dateText(loan.nextPaymentOn)} · 应还{" "}
          {money(loan.nextPaymentAmount)}
        </p>
      )}
      {loan.latestStrategy && (
        <p>
          最近调整：
          {loan.latestStrategy === "REDUCE_TERM"
            ? "按原付款上限缩期"
            : loan.latestStrategy === "ADJUST_TERM"
              ? "自选更短期数"
              : "保留期数"}
        </p>
      )}
    </div>
  );
}

function loanCorrectionPayload(value: LoanDraft) {
  const {
    fundingMode: _fundingMode,
    createPurchasedAsset: _createPurchasedAsset,
    ...created
  } = loanCreatePayload(value);
  const {
    principal: _principal,
    startOn: _startOn,
    accountingOn: _accountingOn,
    linkedAssetId: _linkedAssetId,
    disbursementAccountId: _disbursementAccountId,
    ...safeCorrection
  } = created;
  const payload = value.purchasedAssetId ? safeCorrection : created;
  if (value.repaymentMethod === "CUSTOM" && value.customSchedule.length === 0) {
    const { customSchedule: _schedule, ...retained } = payload;
    return retained;
  }
  return payload;
}
