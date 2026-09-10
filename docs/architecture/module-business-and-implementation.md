# 家账：模块业务与实现架构说明

编写日期：2026-09-10  
代码基线：codex/family-finance-stage-2 / 0eb8703c4c7b259a73a5da7be554b92ba31e61a0  
适用对象：项目组成员、课程答辩、后续接手开发者。

本文说明“现在系统做了什么、分别如何实现”，不是未来重构方案。以当前源码、构建配置和数据库迁移为依据；旧 README 中的早期描述不作为当前实现的唯一依据。本文不代表所有功能已完成实机验收，验收进度另见[收支一致性验收报告](../acceptance/2026-09-10-income-expense-audit.md)。

## 1. 一句话理解系统

家账是一个“家庭共同维护数据的财务管理系统”：用户登记实际发生的收支、借款、还款、投资和资产变化，系统统一维护账内现金、负债、持仓及报表。

它不是银行或券商交易终端。页面上的“买入、还款、换汇”是在登记账目，不会向真实银行或券商发起交易；行情和参考汇率也不会自动替用户确认交易。

当前整体是：

> React 前端 + 按业务划分的 Spring Boot 单体后端 + MySQL + 独立 Python 行情适配服务 + 可信内置插件。

“按业务划分的单体”是指业务代码分包，但账户、贷款、资产等仍在同一个 Java 应用、同一套数据库事务中协作；不是每个模块一个微服务，也不是严格隔离的插件沙箱。

## 2. 总体架构

~~~mermaid
flowchart TD
    User["用户浏览器"] --> UI["React / TypeScript 工作区"]
    UI --> Nginx["Nginx 统一入口"]
    Nginx --> App["Spring Boot 应用：静态资源与 REST API"]

    subgraph Java["同一 Java 进程"]
        App --> Auth["Spring Security / 家庭权限"]
        Auth --> Biz["收支、账户、预算、周期、资产、贷款、投资"]
        Biz --> Ledger["统一账本引擎"]
        Biz --> Read["统计与读取服务"]
        Plugins["可信内置插件"] --> Ports["本体公开接口"]
        Ports --> Read
        App --> Plugins
    end

    Ledger --> DB[("MySQL")]
    Biz --> DB
    Read --> DB
    Biz --> Market["Python 行情适配服务"]
    Market --> Quotes["股票目录、参考报价、K线数据源"]
    Biz --> FX["Frankfurter / ECB 日度汇率"]
    Plugins --> AI["服务器统一 AI 网关"]
    AI --> Provider["配置允许的阿里云服务"]
~~~

图中箭头表示主要调用/读取关系，不表示所有模块都直接调用行情或 AI。行情服务不直接修改家庭交易；AI 也没有自主操作账本的能力。

### 2.1 技术组成

| 层次 | 当前实现 | 作用 |
| --- | --- | --- |
| 前端 | React 19、TypeScript、React Router、Vite | 工作区、页面、弹窗和路由 |
| UI 与图表 | Semi Design、Lucide、本地 SVG、业务 SVG 图表、KLineChart | 表单、图标、财务图表和证券 K 线 |
| 前端数据管理 | TanStack Query | API 查询、缓存及写入后的关联刷新 |
| Java 后端 | Java 17、Spring Boot 4.1.1、Spring MVC | API、业务编排和服务启动 |
| 安全 | Spring Security、BCrypt、Cookie Session、CSRF | 登录、会话和请求保护 |
| 数据访问 | Spring Data JPA + JdbcTemplate | 业务实体维护、账本过账及汇总查询 |
| 数据库 | 生产 MySQL 8；测试 H2 | 持久化与隔离测试 |
| 迁移 | Flyway，当前迁移到 V47 | 可追踪的数据库结构演进 |
| 外部数据 | Python 行情适配器、汇率提供者、AI 网关 | 隔离上游协议与失败处理 |
| 发布 | GitHub Actions、受限 SSH 部署接收器、systemd、Nginx | 构建、部署和版本校验 |

依据：[后端构建](../../pom.xml)、[前端依赖](../../frontend/package.json)、[应用配置](../../src/main/resources/application.yml)、[生产迁移](../../src/main/resources/db/migration-mysql)。

### 2.2 三条最重要的边界

1. 前端负责输入和展示，后端负责最终金额、权限和资金可用性校验。
2. 业务表记录“发生了什么”；统一账本记录“影响了哪些资金、资产、负债科目”。
3. 报价、估值、参考汇率与实际成交/到账分离，不能用参考数据替代真实付款记录。

## 3. 各模块的业务与实现

### 3.1 登录、注册与账号设置

**业务功能**

- 登录、退出登录、读取当前会话、修改密码。
- 注册时创建新家庭，或使用邀请加入已有家庭。
- 区分登录失败和会话失效；限制频繁登录尝试。
- 账号设置展示统一 AI 服务状态，不要求个人输入 AI 密钥。

**实现架构**

登录由 Spring Security 过滤链处理，不是普通 Controller 自行比对明文密码。DatabaseUserDetailsService 读取用户，BCrypt 校验密码；成功后建立服务器 Session，前端 AuthProvider 管理登录状态。ActiveUserSessionFilter 检查账号是否仍有效，写请求受 CSRF 保护。

主要边界：/api/auth/login、/api/auth/register、/api/auth/logout、/api/session、/api/auth/change-password。业务账号保存在 app_users；Session 与用户数据不是一回事，服务重启后可能需要重新登录，不等于账号丢失。

源码：[SecurityConfig](../../src/main/java/com/familyfinance/config/SecurityConfig.java)、[RegistrationService](../../src/main/java/com/familyfinance/identity/RegistrationService.java)、[AuthProvider](../../frontend/src/auth/AuthProvider.tsx)。

### 3.2 家庭、家人、邀请与权限

**业务功能**

- 展示家庭资料及全部家人，区分“有登录账号的家人”和“仅用于记账归属的成员”。
- 管理家庭名称、记账成员、邀请、角色和所有权转让。
- 家庭归档保留历史数据，并限制后续使用。
- 从头像菜单集中进入家庭、邀请、设置和退出登录。

**实现架构**

三个概念分开保存：

| 概念 | 数据 | 含义 |
| --- | --- | --- |
| 登录用户 | app_users | 谁在使用系统 |
| 家庭成员关系 | household_memberships | 用户属于哪个家庭、具有什么权限 |
| 记账成员 | family_members | 一笔账归属于谁，可以没有登录账号 |

家庭数据以 household_id 隔离。CurrentMembership 从认证上下文取得家庭身份，FamilyMutationAuthorization 在写操作前锁定家庭并重新校验权限，不信任前端传来的家庭 ID。

OWNER 管理所有权等敏感事项；OWNER/ADMIN 管理家庭财务配置；MEMBER 对部分业务仅能修改本人创建的记录。指定人员确认的账单不能仅因是管理员就代为确认，最终以相应服务端规则为准。

源码：[FamilyPermissionService](../../src/main/java/com/familyfinance/family/FamilyPermissionService.java)、[FamilyMutationAuthorization](../../src/main/java/com/familyfinance/family/FamilyMutationAuthorization.java)、[FamilyPeopleController](../../src/main/java/com/familyfinance/family/FamilyPeopleController.java)、[ProfileMenu](../../frontend/src/layout/ProfileMenu.tsx)。

### 3.3 现金账户、钱包与多币种银行卡

**业务功能**

- 管理现金、银行卡、支付宝和微信等钱包账户。
- 确认或更正期初余额，查看当前及可用余额。
- 为同一张银行卡添加 CNY、HKD、USD 币种账户。
- 在业务表单中先选择银行卡，再按交易币种定位实际资金账户。
- 查看账户历史、互转及换汇；受引用和余额等规则约束地归档账户。

**实现架构**

~~~text
bank_accounts：银行卡主账户，保存银行、名称、尾号等身份信息
  ├─ financial_accounts：CNY 账户 → CASH:账户ID
  ├─ financial_accounts：HKD 账户 → CASH:账户ID
  └─ financial_accounts：USD 账户 → CASH:账户ID

现金、支付宝、微信等也使用 financial_accounts，但不必挂在银行卡下面。
~~~

银行卡主账户是组织层，不是再加一遍余额的资金池。扣款最终仍落到一个确定币种的 financial_accounts 账户。同一银行卡不能重复添加同一币种，选卡也不意味着自动换汇。

BankAccountService 管理主账户和币种子账户；AccountService 管理实际账户；CashAccountingService 记录期初及现金影响；BankAccountPicker 完成前端选卡/选币种映射。账户管理以前端弹窗为主要入口。

主要数据：bank_accounts、financial_accounts、cash_opening_events，以及账本科目与分录。

源码：[BankAccountService](../../src/main/java/com/familyfinance/ledger/BankAccountService.java)、[AccountService](../../src/main/java/com/familyfinance/ledger/AccountService.java)、[BankAccountPicker](../../frontend/src/features/ledger/BankAccountPicker.tsx)、[银行卡迁移 V41](../../src/main/resources/db/migration-mysql/V41__bank_accounts.sql)。

### 3.4 分类与成员归属

**业务功能**

- 管理收入、支出两种分类及两级父子分类。
- 用分类组织收支图表、预算及周期账单。
- 将普通收支标记为某位成员，或“家庭共同”。

**实现架构**

CategoryService 维护 categories，校验分类层级及父子收支方向一致性。交易、预算和周期规则引用分类 ID，而不是仅存分类名称。

普通收支中的 member_id 可以为空，表示家庭共同；不能把“没有指定记账成员”误认为“没有权限校验”。记录创建人仍单独保存，用于操作授权。

分类汇总是否包含子分类由读取接口的 rollupCategories 控制。预算使用汇总和明细当前默认包含子分类，也支持明确关闭。

源码：[CategoryService](../../src/main/java/com/familyfinance/category/CategoryService.java)、[FinancialTransaction](../../src/main/java/com/familyfinance/transaction/FinancialTransaction.java)、[V44 家庭共同记账](../../src/main/resources/db/migration-mysql/V44__family_shared_transactions.sql)。

### 3.5 收支明细与 CSV 导出

**业务功能**

- 新增收入/支出，选择账户、日期、分类、成员、商家和备注。
- 查看详情，编辑或删除允许修改的记录。
- 按月份、日期、账户、银行卡、成员、分类及关键词筛选。
- 显示分类占比、每日分类堆叠柱状图，支持点图筛选。
- 收入视图只突出收入，支出视图只突出支出。
- 保存后定位到记录所属月份和方向，避免被旧筛选隐藏。
- 导出匹配筛选条件的 CSV，而非仅导出当前一页。

**实现架构**

TransactionsPage → TransactionController / TransactionService → FinancialTransactionRepository；涉及资金的写入再调用 CashAccountingService → 账本引擎。

financial_transactions 保存用户可读的收支记录，也承接周期确认、贷款还款生成的记录。来源不同，修改权限不同：普通手工记录可进行允许的财务更正；自动生成记录不开放同样的任意金额改写/删除能力。

TransactionSummaryService 使用与明细相同的筛选，计算完整金额、每日、分类和成员汇总。CsvExportService 复用筛选读取，并区分不可信文本与数值列，防止文本被电子表格当作公式。

主要 API：/api/transactions、/api/transactions/summary、/api/export.csv。

源码：[TransactionsPage](../../frontend/src/features/ledger/TransactionsPage.tsx)、[TransactionService](../../src/main/java/com/familyfinance/transaction/TransactionService.java)、[TransactionSummaryService](../../src/main/java/com/familyfinance/transaction/TransactionSummaryService.java)、[CsvExportService](../../src/main/java/com/familyfinance/reporting/CsvExportService.java)。

### 3.6 统一账本引擎：跨模块资金一致性的底座

**业务职责**

这一层不是新的记账页面，而是防止“没有现金也能还款”“同一笔操作扣两次”“修改后旧金额仍算进去”的共同基础。

**实现架构**

| 核心对象 | 职责 |
| --- | --- |
| LedgerPostingCommand / LedgerEntryInput | 描述一项业务的生效日期、来源、币种及借贷分录 |
| LedgerPostingService | 新增过账 post、更正 replace、冲销 reverse |
| LedgerValidation | 校验金额、币种、科目、家庭归属及凭证平衡 |
| LedgerBalances | 计算账户余额变化，校验现金及历史日期约束 |
| LedgerStore | 持久化凭证、分录、当前来源指针和重放记录 |
| AccountingRequests | 业务命令层幂等与结果关联 |
| AccountingCommandExecutor | 对已确认的数据库死锁进行有限重试，不盲目重试未知结果 |

主要数据：ledger_accounts、ledger_journals、ledger_entries、ledger_sources、accounting_commands。账本也在凭证层保存幂等信息。

业务表不是被账本表替代。以还款为例，贷款模块维护还款状态及本金分配，账本维护现金和负债科目；两者在业务事务中一起提交或一起回滚。

更正使用“冲回原凭证＋新凭证”，不直接抹掉审计轨迹；当前统计使用有效业务来源，不能把旧凭证和替代凭证重复累计。当前余额是可重算的结果，不是任由各页面分别增减的数字。

精度边界：核心账本使用 BigDecimal，正式入账金额精确到分；不是只在展示时四舍五入、数据库无限保留分以下现金。利率、汇率、证券单价和内部计划计算可有更高精度，部分历史业务字段及兼容接口仍使用整数分。

源码：[LedgerPostingService](../../src/main/java/com/familyfinance/accounting/LedgerPostingService.java)、[LedgerBalances](../../src/main/java/com/familyfinance/accounting/LedgerBalances.java)、[LedgerStore](../../src/main/java/com/familyfinance/accounting/LedgerStore.java)、[DecimalMoney](../../src/main/java/com/familyfinance/shared/DecimalMoney.java)。

### 3.7 账户互转、换汇与资金流水

**业务功能**

- 登记同币种账户互转。
- 登记跨币种实际转出本金、到账金额和手续费。
- 使用日度汇率预填参考值，再由用户核对实际到账。
- 更正或冲销换汇，查看双方资金变化和账务轨迹。
- 查看借款、还款、资产/投资买卖、互转等完整资金变动。

**实现架构**

CashTransferService 处理同币种互转；FxTransferService 处理不同币种的双边分录。跨币种通过各币种内部的清算科目保持各自借贷平衡，不直接把美元数额与港币数额相加。

CashMovementService 从当前有效凭证的 CASH 分录按“业务来源＋实际账户”汇总资金变化。它不是复制一张新的收支表；一笔互转会显示转出和转入两端，但家庭合计现金在无费用的同币种互转中不变。

主要数据：cash_transfers、fx_transfers、fx_transfer_revisions，及账本公共表。主要 API：/api/transfers、/api/fx-transfers、/api/cash-movements、/api/accounting/history。

源码：[CashTransferService](../../src/main/java/com/familyfinance/accounting/CashTransferService.java)、[FxTransferService](../../src/main/java/com/familyfinance/accounting/FxTransferService.java)、[CashMovementService](../../src/main/java/com/familyfinance/accounting/CashMovementService.java)、[FxTransfersPanel](../../frontend/src/features/ledger/FxTransfersPanel.tsx)。

### 3.8 首页、净资产与分析读取

**业务功能**

- 首页突出可用现金、净资产、月度收支、持仓和固定股票行情。
- 查看净资产构成及历史，查看负债与到期情况。
- 按月份查看收支趋势，并进入同月明细。
- 后端还保留费用分析接口，不等于首页展示了全部分析卡片。

**实现架构**

- DashboardService 复用 TransactionSummaryService，避免首页和明细分别计算同一个收支指标。
- CashPositionService 汇总账内可用现金；PortfolioService 推导持仓及估值。
- NetWorthService 合并现金、非现金资产、投资价值和贷款负债，处理历史时点与缺价/缺汇率状态。
- NetWorthSnapshotService 生成日快照，历史修订由 net_worth_snapshot_revisions 保留。
- AnalysisService 读取账本费用活动，给出费用变化、最大单笔等分析；这不是 AI 自动做出的建议。

净资产概念为“资产价值合计－负债”，月度收支结余为“该口径收入－支出”，二者不是同一指标。参考估值、历史快照和最新行情也不应混称为完全实时的确定余额。

主要 API：/api/dashboard、/api/cash-position、/api/net-worth、/api/portfolio、/api/debt-analysis、/api/analysis。

源码：[DashboardService](../../src/main/java/com/familyfinance/reporting/DashboardService.java)、[NetWorthService](../../src/main/java/com/familyfinance/reporting/NetWorthService.java)、[AnalysisService](../../src/main/java/com/familyfinance/reporting/AnalysisService.java)、[DashboardPage](../../frontend/src/features/dashboard/DashboardPage.tsx)。

### 3.9 费用预算

**业务功能**

- 设置家庭月度总预算，分配分类预算。
- 设置成员、分类＋成员观察线；观察线不重复占用总预算额度。
- 展示已用、剩余、执行率和超额提醒。
- 查看预算命中明细、修订历史，停用预算。
- 保存/应用模板、复制预算、导出 CSV。

**实现架构**

BudgetService 管理预算；BudgetTotalService 管理月度总额及分配约束；BudgetUsageService 从 LedgerReportingService 的有效费用分录实时计算使用额；BudgetTemplateService 管理复用模板。

预算不是把支出总额永久累计进一个字段。金额更正后，使用额按有效账务重算；父子分类汇总、成员筛选和明细需要保持相同口径。

主要数据：budgets、budget_revisions、budget_monthly_totals、budget_total_revisions、budget_templates、budget_template_rows。/api/budgets/expense-summary 为预算顶部提供独立费用总额，不借用首页的完整还款现金支出。

源码：[BudgetUsageService](../../src/main/java/com/familyfinance/budget/BudgetUsageService.java)、[BudgetTotalService](../../src/main/java/com/familyfinance/budget/BudgetTotalService.java)、[BudgetTemplateService](../../src/main/java/com/familyfinance/budget/BudgetTemplateService.java)、[BudgetsPage](../../frontend/src/features/budget/BudgetsPage.tsx)。

### 3.10 周期账单

**业务功能**

- 创建月、季、年、周周期规则，指定账户、分类、成员和确认人。
- 到期生成待确认项，不自动扣款。
- 确认时可修改本期实际金额，不修改后续规则金额。
- 单笔/批量确认、跳过本期、暂停、复制和归档规则。
- 登录后提示待确认账单或仍在执行的规则。

**实现架构**

recurring_rules 描述规则，recurring_occurrences 描述每一次到期事项。确认后才创建 FinancialTransaction，随后进入公共现金过账路径。

RecurringConfirmationService 在事务中检查确认人、状态、实际金额和资金可用性。当前接口还校验 confirmationToken：前端保存用户核对的规则快照，服务端确认规则未被悄悄改成另一个账户、方向或金额。它是业务核对标识，不是授权令牌。

单笔失败不记账；批量任一项目校验失败则整批回滚；已成功入账的重复确认返回原记录。调度游标变化本身不导致核对标识改变。

源码：[RecurringService](../../src/main/java/com/familyfinance/ledger/recurring/RecurringService.java)、[RecurringConfirmationService](../../src/main/java/com/familyfinance/ledger/recurring/RecurringConfirmationService.java)、[RecurringRuleSnapshot](../../src/main/java/com/familyfinance/ledger/recurring/RecurringRuleSnapshot.java)、[RecurringPage](../../frontend/src/features/recurring/RecurringPage.tsx)。

### 3.11 非现金资产管理

**业务功能**

- 管理房产、车辆及其他资产，填写类型相关信息。
- 区分期初已有资产和本次现金购入资产。
- 追加估值历史，编辑允许修改的资料。
- 查看关联贷款、购置融资或抵押关系。
- 预览并登记出售：费用、还贷、本次到账一起核对，完成后保留处置历史。

**实现架构**

AssetService 管理资产资料；AssetValuationService 管理估值；AssetAccountingService 把购入、估值等变化转成分录；AssetSaleService 编排出售和关联贷款偿还。

出售支持 VIA_ACCOUNT（经过家庭账户）及 DIRECT（买方直接代偿）路径；选中的贷款可结清或部分偿还，未选贷款按业务校验及用户确认保留。买方直接支付给贷款方的金额不能再算一次家庭现金扣款。

主要数据：assets、property_assets、vehicle_assets、asset_valuations、asset_sale_receipts。当前实物资产账务限 CNY，现金和钱包不在这里再建一份资产。

源码：[AssetService](../../src/main/java/com/familyfinance/asset/AssetService.java)、[AssetSaleService](../../src/main/java/com/familyfinance/asset/AssetSaleService.java)、[AssetSaleDraft](../../src/main/java/com/familyfinance/asset/AssetSaleDraft.java)、[AssetsPage](../../frontend/src/features/asset/AssetsPage.tsx)。

### 3.12 贷款与还款计划

**业务功能**

- 登记期初负债、实际现金放款、本次贷款购买物三种入账方式。
- 现金放款区分借款本金和实际到账；预扣费用不应被当作实际到账现金。
- 贷款购买物可自动创建资产，记录首付款及融资关系。
- 支持等额本息、等额本金、自定义计划。
- 查看剩余本金、本息、已还款、下期应还和逾期状态。
- 到期还款、部分提前还款、一次结清。
- 提前还款可保留期数降低后续付款、按原付款上限缩期，或自选更短期数。
- 保留旧计划、已付期次和还款凭证，限制不安全的历史改写。

**实现架构**

LoanService 管理合同及计划；LoanAccountingService 管理负债与现金分录。LoanInstallmentConfirmationService 处理到期确认；LoanRepaymentService 将“到期本息＋额外本金”编排成一次事务；LoanPayoffService 处理结清；LoanPrepaymentService 和计划计算器负责重算未来计划。

实际付款先校验具体现金账户，再分别减少现金和贷款本金、记录利息费用。不能用净资产代替付款余额。多步骤还款使用批次和子记录关联，任一子操作失败不能留下部分扣款。

主要数据：loans、loan_installments、loan_prepayments、loan_repayment_batches、loan_repayment_batch_children、loan_repayment_policy_history，并关联 financial_transactions。当前贷款账务限 CNY。

源码：[LoanController](../../src/main/java/com/familyfinance/loan/LoanController.java)、[LoanRepaymentService](../../src/main/java/com/familyfinance/loan/LoanRepaymentService.java)、[LoanPayoffService](../../src/main/java/com/familyfinance/loan/LoanPayoffService.java)、[LoansPage](../../frontend/src/features/loan/LoansPage.tsx)。

### 3.13 投资账户、交易与持仓

**业务功能**

- 初始化投资管理，建立投资账户并关联实际资金账户。
- 统一搜索选择 A 股、港股、美股证券，不要求家庭用户自行注册股票。
- 记录期初持仓、买入、卖出、分红和独立费用。
- 查看股数、成本、均价、已实现及未实现收益、财务历史。
- 编辑或撤销允许更正的交易，查看行情，固定一支股票展示。
- 导出投资记录。

**实现架构**

InvestmentAccount 是投资归属/核算容器，FinancialAccount 才是实际现金来源，不能把两者各加一次现金。

InvestmentTradeService 校验交易及历史依赖；PositionCalculator / BasePositionCalculator 从按日期和顺序排列的交易推导原币及人民币参考成本；InvestmentAccountingService 把持仓成本、现金和已实现损益变化写入统一账本；PortfolioService 叠加价格得到估值。

OPENING 只登记已有持仓，不重复扣款；BUY 减少现金；SELL 根据成交与费用增加或减少现金；DIVIDEND 增加现金；FEE 减少现金。卖出数量、历史修订和资金余额都由后端约束，历史交易不是任意可删。

主要数据：investment_accounts、investment_trades、securities、investment_setup；持仓结果主要由交易推导，而不是让用户直接修改“当前总持仓”数值。

源码：[InvestmentTradeService](../../src/main/java/com/familyfinance/investment/InvestmentTradeService.java)、[PositionCalculator](../../src/main/java/com/familyfinance/investment/PositionCalculator.java)、[InvestmentAccountingService](../../src/main/java/com/familyfinance/investment/InvestmentAccountingService.java)、[InvestmentsPage](../../frontend/src/features/investment/InvestmentsPage.tsx)。

### 3.14 定投计划

**业务功能**

- 指定证券、投资账户、计划股数、周期及确认人。
- 到期提醒后由用户确认实际成交，不自动下单。
- 跳过、稍后提醒、调整计划状态，查看本期和历史执行情况。
- 按股数规划，不把计划金额直接假装成已成交股数。

**实现架构**

investment_plans 保存计划，investment_plan_occurrences 保存期次。InvestmentPlanSchedule 定时生成应处理事项；InvestmentPlanService 负责确认、状态和去重。

确认通过后调用投资交易逻辑生成一笔实际交易，再由共同账本扣减资金。重复确认不能再生成另一笔买入，失败也不能留下“计划成功但交易没成功”的半状态。资金绑定变化和历史交易状态需要一起校验。

源码：[InvestmentPlanService](../../src/main/java/com/familyfinance/investment/InvestmentPlanService.java)、[InvestmentPlanController](../../src/main/java/com/familyfinance/investment/InvestmentPlanController.java)、[InvestmentPlansPanel](../../frontend/src/features/investment/InvestmentPlansPanel.tsx)。

### 3.15 证券目录、报价与 K 线

**业务功能**

- 搜索市场、代码和名称，取得证券身份及币种。
- 查看参考报价、报价时间、来源和不可用状态。
- 显示日/周/月 K 线、成交量及 MA/MACD 等指标。
- 首页和投资页读取更新后的持仓参考估值。
- 允许在已有业务边界内维护手工价格，外部行情失败时不伪造报价。

**实现架构**

Java 的 SecurityCatalogService、SpotQuoteService、CandleService、OverseasMarketService 负责标准化 API 和校验；MarketDataClient 访问独立 Python 服务。

Python 适配层使用 AKShare、BaoStock 及公开行情接口，承担目录获取、历史行情、腾讯公开参考报价、缓存和失败处理；港美股历史数据包含新浪数据路径。仓库仍保留 Tushare 提供者代码，不代表所有请求必然经过 Tushare。

报价使用轮询而非交易所逐笔推送。前端 live-quotes 根据页面可见性和后端 nextRefreshSeconds 决定刷新，后端有缓存/限速；当前前端和报价服务设有约一分钟的最短刷新约束，不能称为毫秒级实时行情。日线快照调度与盘中参考报价是不同路径。

行情覆盖依赖市场、代码和上游服务；“支持搜索某市场”不等于每支证券的每种历史周期都保证可取。K 线只用于展示，不修改成交价或持仓数量。

源码：[MarketDataClient](../../src/main/java/com/familyfinance/market/MarketDataClient.java)、[SpotQuoteService](../../src/main/java/com/familyfinance/market/SpotQuoteService.java)、[行情服务](../../scripts/market-data/server.py)、[港美股与报价来源](../../scripts/market-data/overseas_sources.py)、[StockChart](../../frontend/src/features/investment/StockChart.tsx)、[live-quotes](../../frontend/src/features/investment/live-quotes.ts)。

### 3.16 汇率与历史折算

**业务功能**

- 查看 CNY/HKD/USD 的日度参考汇率和历史覆盖。
- 更新指定日期汇率，补齐历史交易折算所需数据。
- 区分实际换汇成交比例与报表参考汇率。
- 缺少汇率时明确显示未知或待补齐，不把金额当成零。

**实现架构**

FrankfurterRateProvider 获取 ECB 日度数据；ExchangeRateService 管理批次和查询；ExchangeRateHistory 维护日期解析和覆盖；FxJournalRates 将历史参考汇率绑定到凭证。

主要数据：fx_rate_batches、fx_rates、fx_date_resolutions、fx_history_coverage、fx_journal_rates。历史入账折算与当前估值使用的参考值分开处理，不能用今天的新汇率悄悄改写原入账金额。

源码：[FrankfurterRateProvider](../../src/main/java/com/familyfinance/fx/FrankfurterRateProvider.java)、[FxJournalRates](../../src/main/java/com/familyfinance/fx/FxJournalRates.java)、[ExchangeRateController](../../src/main/java/com/familyfinance/fx/ExchangeRateController.java)、[ExchangeRatesPanel](../../frontend/src/features/investment/ExchangeRatesPanel.tsx)。

### 3.17 提醒中心与登录引导

**业务功能**

- 提示到期贷款、周期账单、预算阈值、资产估值过期及行情问题等事项。
- 查看未读状态、标记已读或处理，进入关联业务。
- 新用户/未初始化账户登录后提供账务起点引导。
- 周期账单和定投提醒均不等于已经发生付款或投资。

**实现架构**

NotificationService 从业务状态派生通知，使用业务引用和接收人维度避免重复打开同一提醒；notifications 保存提醒状态，业务处理后可关联解决。定投计划还通过自身期次生成逻辑管理提醒。

AccountInitializationGuide 和 RecurringBillingGuide 属于前端流程引导，通过后台数据决定是否出现，不是另一个独立账户系统。通知主要是站内能力，不能把它描述成已经接入短信、邮件或手机系统推送。

源码：[NotificationService](../../src/main/java/com/familyfinance/notification/NotificationService.java)、[NotificationsPage](../../frontend/src/features/notification/NotificationsPage.tsx)、[AccountInitializationGuide](../../frontend/src/layout/AccountInitializationGuide.tsx)、[RecurringBillingGuide](../../frontend/src/layout/RecurringBillingGuide.tsx)。

### 3.18 内置插件、年度统计与 AI 合同提取

**业务功能**

- 年度统计：全年及逐月收支、结余、按完整 12 个月计算的月平均。
- 贷款合同提取：上传含可识别文本的 Word/PDF，提取贷款字段并预填表单。
- 用户明确选择 AI 处理时，使用服务器统一配置的模型辅助提取；失败可回退到规则识别。
- 个人设置可查看 AI 状态及发起经过确认的连接测试。

**实现架构**

FinancePlugin 定义插件描述；PluginRegistry 在启动时收集 Spring Bean，验证 ID、路径和协议。前端 registry 只允许加载团队登记的内置组件。

年度插件通过 LedgerReadPort → LedgerReadAdapter → TransactionSummaryService 读本体收支，不直接读取任意家庭。贷款合同提取插件通过 LoanContractAutoFillService 编排规则与 AI，其入口嵌在贷款创建流程；当前前端独立插件页白名单只登记年度统计，不能把合同插件也描述成已注册的独立导航页面。

Word/PDF 文本提取使用 POI/PDFBox。AiGateway 的当前实现虽然名为 PersonalAiGateway，实际读取 AiSystemConfiguration 的服务器配置，源码指定模型为 qwen3.8-max。个人 AI 配置相关旧类/表仍存在，但当前用户设置接口不允许个人改写系统配置。

application.yml 中还留有 spring.ai.zhipuai 配置项，但当前 Java 源码的 AiGateway 实现走上述服务器网关；不能仅凭旧配置项名称判断正在调用哪一个模型。

插件随完整 JAR 构建部署，配置开关通常需要重启；不支持任意第三方 JAR/远程 JS 在线安装。capabilities 是描述和约定，不是操作系统沙箱。AI 网关也不具备自动查询全家账目、执行转账或自动创建贷款的工具权限。

源码：[PluginRegistry](../../src/main/java/com/familyfinance/extension/PluginRegistry.java)、[前端插件注册](../../frontend/src/extensions/registry.tsx)、[LedgerReadAdapter](../../src/main/java/com/familyfinance/transaction/LedgerReadAdapter.java)、[LoanContractAutoFillService](../../src/main/java/com/familyfinance/plugins/loancontract/LoanContractAutoFillService.java)、[AiSystemConfiguration](../../src/main/java/com/familyfinance/ai/AiSystemConfiguration.java)、[AiGateway](../../src/main/java/com/familyfinance/ai/AiGateway.java)。

## 4. 为什么几个“收支数字”不能直接混用

这是当前架构中最需要全组统一理解的业务规则。

| 展示/功能 | 主要来源 | 计算边界 |
| --- | --- | --- |
| 首页月度收支、收支图表、年度收支 | TransactionSummaryService → financial_transactions | 普通收支、周期确认、家庭实际付款的贷款本息；排除买方直接代偿的非现金记录 |
| 费用预算、费用分析 | LedgerReportingService 的有效 INCOME/EXPENSE 活动，其中预算只取费用 | 还款本金不算费用；还款利息、适用的已实现损失等按账务计算 |
| 完整资金流水 | CashMovementService → 当前有效 CASH 分录 | 展示放款、买卖、还款、互转等现金两端，按原币查看 |
| 净资产 | NetWorthService + 账本余额 + 资产/持仓估值 | 财务存量，不是某个月的收支结余 |
| 股价/K 线 | 行情读取服务与外部数据源 | 市场参考，不是家庭实际成交 |

例如还款 1,100 元，其中本金 1,000、利息 100：

- 付款账户减少 1,100；贷款本金减少 1,000。
- 首页、收支明细和年度记录口径的支出增加 1,100。
- 费用预算增加的是 100，而不是 1,100。

目前投资买卖、放款、互转等主要在完整资金流水中反映，不会全部额外复制成普通收支记录。要新增统计卡片，先确定它要回答“记录收支”“费用”“现金流动”还是“资产存量”，再选择对应数据源。

依据：[共享收支汇总](../../src/main/java/com/familyfinance/transaction/TransactionSummaryService.java)、[费用活动读取](../../src/main/java/com/familyfinance/accounting/LedgerReportingService.java)、[资金流水读取](../../src/main/java/com/familyfinance/accounting/CashMovementService.java)。

## 5. 一次财务操作怎样穿过整个系统

以“确认到期款并额外提前还本”为例：

~~~mermaid
sequenceDiagram
    participant U as 用户
    participant F as 前端还款弹窗
    participant C as LoanController
    participant B as LoanRepaymentService
    participant L as 账本引擎
    participant D as MySQL

    U->>F: 选择账户、日期、额外本金与策略
    F->>C: 请求还款预览
    C->>B: 计算到期款、总付款和后续计划
    B-->>F: 返回金额、余额预估、核对信息
    U->>F: 确认
    F->>C: 提交业务请求与幂等信息
    C->>B: 进入业务事务
    B->>D: 锁定家庭、贷款等必要记录并重验
    B->>L: 校验总资金需求并过账
    L->>D: 写入凭证、分录和余额
    B->>D: 更新本金、期次、批次及关联收支
    D-->>B: 同一事务提交或回滚
    B-->>F: 返回结果
    F->>F: 刷新贷款、账户、收支、预算和总览缓存
~~~

这里的预览不是最终扣款授权依据：服务器必须在正式提交时再次校验。用户看到的前端余额不会直接作为数据库新余额写回。

依据：[LoanRepaymentService](../../src/main/java/com/familyfinance/loan/LoanRepaymentService.java)、[账本过账](../../src/main/java/com/familyfinance/accounting/LedgerPostingService.java)、[跨模块缓存刷新](../../frontend/src/shared/write-refresh.ts)。

## 6. 前端公共架构

工作区由 WorkspaceLayout 承载：侧边栏、头像菜单、模块页面、提醒和初始化引导共享登录状态。账户、汇率、交易详情、行情等功能可作为业务页面中的弹窗，而不是每个功能都独立占用一个导航页面。

| 公共能力 | 实现位置 | 解决的问题 |
| --- | --- | --- |
| 登录状态、统一请求 | auth/AuthProvider、api/client | 会话失效、CSRF、错误解析 |
| 服务端状态缓存 | TanStack Query | 查询、加载和缓存状态 |
| 写后刷新 | shared/write-refresh | 一次还款后刷新账户、收支、贷款、总览等关联数据 |
| 弹窗及错误/空状态 | features/common | Drawer、ActionDialog、ModalDialog、ConfirmDialog、QueryState |
| 未保存保护 | shared/draft-guard | 避免误关表单丢失输入 |
| 账户选择 | ledger/BankAccountPicker | 银行卡身份与实际币种账户映射 |
| 资金预览 | features/accounting | 余额、预计变动和资金不足提示 |
| 日期、分页、货币展示 | shared/DateField、pagination、currency | 统一交互及显示格式 |

统一缓存刷新不等于跨用户实时推送。一般页面的 staleTime 是缓存新鲜度配置，不是定时刷新频率；行情另有轮询。没有证据表明当前应用已使用 Redis、Kafka 或 WebSocket 来同步全站财务数据。

依据：[App](../../frontend/src/app/App.tsx)、[WorkspaceLayout](../../frontend/src/layout/WorkspaceLayout.tsx)、[公共组件](../../frontend/src/features/common.tsx)、[write-refresh](../../frontend/src/shared/write-refresh.ts)。

## 7. 定时任务与外部依赖

当前定时任务在 Spring 应用进程中执行，不是独立消息队列平台。下表是源码默认调度，不是对上游数据准时到达的承诺。

| 任务 | 默认触发方式 | 源码 |
| --- | --- | --- |
| 周期账单生成 | 上海时间每日 00:10 | [RecurringGenerationScheduler](../../src/main/java/com/familyfinance/ledger/recurring/RecurringGenerationScheduler.java) |
| 业务提醒生成 | 上海时间每日 00:20 | [NotificationSchedule](../../src/main/java/com/familyfinance/notification/NotificationSchedule.java) |
| 定投期次生成 | 每分钟 | [InvestmentPlanSchedule](../../src/main/java/com/familyfinance/investment/InvestmentPlanSchedule.java) |
| 日线快照刷新 | 工作日上海时间 18:10、20:10 | [MarketSchedule](../../src/main/java/com/familyfinance/market/MarketSchedule.java) |
| 净资产日快照 | 上海时间每日 23:50 | [NetWorthSnapshotService](../../src/main/java/com/familyfinance/reporting/NetWorthSnapshotService.java) |
| 日度汇率及历史补齐 | 日度更新，加分钟/小时级补齐任务 | [ExchangeRateScheduler](../../src/main/java/com/familyfinance/fx/ExchangeRateScheduler.java) |

外部服务的失败要与账内数据分开：没有行情时，不能虚构股价；缺汇率时不能伪造人民币合计；AI 失败时不应自动提交不可信合同字段。若未来部署多个 Java 实例，还需单独核查调度协调、会话共享和限流状态，不能仅增加实例就认为分布式能力已经完成。

## 8. 持久化、部署与验证

### 8.1 数据库

生产配置使用 MySQL，JPA 的 ddl-auto 为 validate，数据库结构由 Flyway 管理。测试使用独立 H2 配置及相应迁移，不代表线上仍使用文件型数据库。

主要领域数据按“家庭身份 → 业务记录 → 账务来源 → 有效凭证/历史凭证”关联。迁移文件中还包含历史迁移辅助表及旧结构，因此不能把所有出现过的建表语句都视为当前活跃业务模块。

核心证据：[application.yml](../../src/main/resources/application.yml)、[账本 V14](../../src/main/resources/db/migration-mysql/V14__unified_accounting.sql)、[金额精度 V22](../../src/main/resources/db/migration-mysql/V22__decimal_ledger.sql)、[出售结算 V47](../../src/main/resources/db/migration-mysql/V47__asset_sale_settlement.sql)。

### 8.2 发布

- 当前开发部署分支 push 会触发 GitHub Actions；针对该分支的 PR 执行构建验证，不直接部署。
- CI 执行部署脚本测试、行情适配测试、前端类型/测试/构建、Java 测试与打包。
- 发布包包含 Java JAR、行情适配器及版本信息，不是只上传一份前端静态页面。
- 部署接收器校验发布内容并切换服务；systemd 管理应用和行情进程。
- Nginx 对外提供入口；部署结束后核对 deployment.json 中的提交和公共接口。
- SSH、数据库、行情及 AI 配置由部署环境/Secrets 管理，本文不记录真实地址、账号密码或密钥。

具体工作流见 [deploy-stage2.yml](../../.github/workflows/deploy-stage2.yml)、[发布打包](../../scripts/build_release.py)、[部署接收器](../../scripts/ci_deploy.py)。

### 8.3 验证层次

金额公式、还款计划和持仓计算有领域测试；API、权限、幂等、回滚及并发有后端测试；前端有组件、交互和缓存刷新测试；CI 另有真实 HTTP/重启持久化的 smoke 测试。

这些验证与真实浏览器验收是不同层次。本文描述代码能力，不把自动化通过等同于所有生产操作都已人工验证。本次文档编写没有更改业务代码、数据库或部署配置。

## 9. 给项目组的阅读顺序与边界

建议先阅读：

1. [WorkspaceLayout](../../frontend/src/layout/WorkspaceLayout.tsx)：理解用户能从哪里进入各业务。
2. [TransactionService](../../src/main/java/com/familyfinance/transaction/TransactionService.java)：从最常见的一笔收支认识写入链路。
3. [LedgerPostingService](../../src/main/java/com/familyfinance/accounting/LedgerPostingService.java)：理解钱为什么不会由每个页面分别增减。
4. [LoanRepaymentService](../../src/main/java/com/familyfinance/loan/LoanRepaymentService.java)：理解跨模块原子操作。
5. [TransactionSummaryService](../../src/main/java/com/familyfinance/transaction/TransactionSummaryService.java) 与 [LedgerReportingService](../../src/main/java/com/familyfinance/accounting/LedgerReportingService.java)：理解不同报表口径。
6. [插件开发指南](plugin-developer-guide.md)：了解扩展接入方式；实现细节仍以当前代码为准。

组内讲解时可以用下面四个问题自检：

- 为什么一张银行卡有三个币种账户，但净资产里不能再多算一次“银行卡总余额”？
- 为什么还款 1,100 元会让收支记录增加 1,100，而费用预算只增加利息部分？
- 为什么修改交易不能只更新业务表中的金额，而必须处理原凭证？
- 为什么周期确认需要绑定用户核对过的规则，只有数据库锁还不够？

当前明确边界：这是可信团队共同开发的单体系统，不是沙箱化插件平台；实物资产、贷款、周期账单等仍有人民币业务限制；行情是外部参考服务；AI 是辅助录入工具；全量实机验收尚不能据本文判定完成。
