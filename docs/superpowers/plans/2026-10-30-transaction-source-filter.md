# Transaction Source Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking. Independent final review is coordinated by the parent agent.

**Goal:** 按来源一致筛选当前家庭的收支列表、统计、CSV 和账本界面。

**Architecture:** 控制器将原始 `sourceType` 传给现有 `TransactionFilter`，解析为强类型后由共享 JPA Specification 与其他条件取交集。React 从同一个 filters 字符串生成列表、统计和导出地址，并在变更来源时重置页码。

**Tech Stack:** Java 17+, Spring Boot 4.1.1, Spring Data JPA, H2 test profile, React 19, TypeScript, Vitest/Testing Library。

**Spec:** `docs/qa-dataset/transaction-source-filter/PRD.md`

## Global Constraints

- 唯一合法值 `MANUAL`、`RECURRING`、`LOAN`、`LOAN_PAYMENT`、`LOAN_PREPAYMENT`；缺省或空白为全部，通过认证及现有 CSV 媒体协商后其他值 HTTP 400 字段 `sourceType`。
- 不改数据库、记账、金额及币种语义、导出列、权限或排序；旧过滤器构造调用继续可用。
- 只使用隔离测试库和合成数据；先有实际 RED，再实现 GREEN。

## Review Focus

- 非法、大小写变化、两侧空格：通过认证和现有媒体协商后，三个 API 均按字段拒绝。
- 空白及缺省：三个 API 维持不筛选的全部结果。
- 多条件交集和跨家庭同来源：列表、统计与导出一致且隔离。
- 筛选后超过一页：分页不影响全量统计和 CSV。
- 前端第二页切换来源及恢复全部：列表、统计和导出参数一致，页码回到零。

### Task 1: 共享后端筛选

**Files:** Modify `src/main/java/com/familyfinance/transaction/{TransactionController,TransactionFilter,TransactionCriteria,TransactionFilterParser,TransactionSpecifications}.java`, `src/main/java/com/familyfinance/reporting/ExportController.java`; create `src/test/java/com/familyfinance/transaction/TransactionSourceFilterApiTest.java`。

**Interfaces:** 现有过滤器扩展 `String sourceType` 并保留 8/9 参数重载；解析后 criteria 保存 `TransactionSourceType`；三个现有 GET 接口均接受相同查询参数。

- [x] 添加随机端口 Spring HTTP + H2 测试：真实 Cookie/CSRF 会话下插入手工、周期、贷款付款、提前还款、旧贷款以及其他家庭合成记录，断言来源、现有组合筛选、隔离、所有端点、分页、全部和精确 400 契约。
- [x] 运行 `./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true -Dtest=TransactionSourceFilterApiTest test`，确认因忽略来源而失败。
- [x] 添加控制器参数、过滤器和 criteria 字段、严格解析及共享 JPA 相等条件；保留原构造器。
- [x] 重跑该测试并确认通过；运行相关现有交易、统计、CSV 测试。

### Task 2: 账本来源选择

**Files:** Modify `frontend/src/features/ledger/TransactionsPage.tsx`; test `frontend/src/features/ledger/TransactionSourceFilter.test.tsx`。

**Interfaces:** 下拉框可访问名称为 `来源筛选`，值为空或 `Transaction['sourceType']`；已有 `filters` 参数字符串同时传给列表、`LedgerCharts` 和 CSV 链接。

- [x] 添加 UI 行为测试：进入第二页，选 `RECURRING`，检查列表页码 0、统计请求与 CSV 包含来源和既有条件；切换全部时参数消失；核对五个可选值。
- [x] 运行 `npm test -- --run src/features/ledger/TransactionSourceFilter.test.tsx`，确认因控件不存在而失败。
- [x] 实现下拉、共享查询串和同步页码重置，并将保存后的来源清空；不改角色判断。
- [x] 重跑定向前端测试、`npm run typecheck` 和 `npm run build`。

### Task 3: 全量验证和交接

**Files:** Create `docs/qa-dataset/transaction-source-filter/{DEVELOPMENT_MEMORY,HANDOFF}.md`。

- [x] 运行全量前后端测试，记录命令、用例数、失败及跳过和环境限制；不修无关问题。
- [x] 写明测试库和可运行的 API/UI、合成夹具准备、基准及规范提交、职责与已知限制；开发测试结果不称作独立 QA 验收。
- [x] 对改动和测试记录做最终检查，精确暂存本任务文件并提交；将提交给父代理复核。

### Task 4: 固定测试数据库与媒体错误边界

**Files:** Modify `src/test/java/com/familyfinance/transaction/TransactionSourceFilterApiTest.java`, `SourceFilterQaLauncher.java`; create `SourceFilterQaLauncherIsolationTest.java`; update QA handoff and developer memory。

- [x] 先用隔离测试库断言与错误外部 URL/凭据的启动器进程测试取得 RED，再在 Spring 初始化前固定应用和 Flyway 同一个 H2 URL、驱动、凭据和迁移位置。
- [x] 在故意无效的外部 datasource/Flyway 配置下验证真实 HTTP、数据源与 Flyway 同库，以及独立启动器的合成夹具。
- [x] 用显式绑定同一一次性 H2 的命令回归既有 CSV 测试；核对 CSV `Accept: text/csv` 下其他字段（无效月份）的 JSON 400。
- [x] 在交接中提供受控测试命令，记录前一轮全量结果和本轮定向结果的区别。
