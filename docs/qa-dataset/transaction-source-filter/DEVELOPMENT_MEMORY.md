# 开发工作记忆：收支来源筛选

## 身份、基准与实现

本记录由 Codex 在用户委托的数据集任务中模拟开发角色填写；它记录实现与开发验证，不代表 QA 验收。基准 `b024053419b88053b6c31871118d3324b4762c92`；先提交的 PRD、PM 记忆与计划为 `fda7a699c0acfa5a3e08d91432534dc94b28da0d`。实际待测版本以检出的 `git rev-parse HEAD` 为准。

`TransactionController`、`reporting/ExportController` 将原始参数放入 `TransactionFilter`；原有 8/9 参数构造器保留。`TransactionFilterParser` 对非空白值使用严格枚举解析并累积 `sourceType` 字段错误；`TransactionCriteria` 携带强类型值；`TransactionSpecifications` 将来源谓词与现有家庭和其他筛选条件用 AND 连接。汇总和 CSV 通过 `TransactionService.findAllForCsvExport` 复用该路径。`ExportController` 对筛选校验异常显式指定 JSON 内容类型，使通过现有媒体协商的 CSV 客户端（`Accept: */*` 或 `text/csv`）也能获得字段级 400；它同样影响其他 CSV 筛选校验，例如无效 `month` 现在也可在 `Accept: text/csv` 下获得 JSON 字段级 400。这是与筛选相关的小幅异常响应扩展，不改变成功时 CSV 格式。仅接受 `application/json` 的 CSV 请求在路由匹配阶段抛出媒体协商异常，并被既有全局异常处理转为 500；未进入来源校验，本 PR 未修复该既有边界。

`frontend/src/features/ledger/TransactionsPage.tsx` 用 `Transaction['sourceType']` 与空字符串保存选择；同一过滤串用于列表、`LedgerCharts` 汇总和 CSV。来源变更与回到第一页在同一个事件中完成，保存记录后按原筛选清空惯例清空来源。没有迁移、记账数据修改、权限或金额公式修改。合成测试记录直接通过实体工厂插入，不验证周期确认、贷款记账或账务分录链路。未筛选时不增加来源条件；数据库 V4 的历史默认值是非空 `MANUAL`，具体选择使用相等比较，不为非法 NULL 数据增设归类。

## 测试和证据

命令在本仓库运行，时间为 2026-10-30，环境使用独立 test profile/H2、合成数据、Java 26.0.2.1、Node 22.23.2；测试文件不访问真实财务数据。

- 基线相关后端 `./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true -Dtest=TransactionSummaryApiTest,TransactionPaginationApiTest,CsvExportApiTest test`：9 项通过，0 失败、0 跳过。
- 新增 `TransactionSourceFilterApiTest`：真正的随机端口 Tomcat、HTTP Cookie/CSRF 和 H2 数据库；覆盖 5 个枚举、空白、未知值、组合筛选、不同家庭、51 条跨页的统计与 CSV、银行卡/日期范围，以及 `Accept: text/csv` 的字段错误。最初 RED 4 项中 3 项失败（筛选被忽略及非法值返回 200），完成共享筛选后通过；随后 CSV Accept 专项先实际返回 500 再修复为 400；银行卡/日期组合在独立基准导出上先 RED（错误混入手工记录），本分支的最终运行结果见下。
- 前端 `cd frontend && npm test -- --run src/features/ledger/TransactionSourceFilter.test.tsx`：2 项通过；最初两个测试因缺少「来源筛选」控件而 RED。使用真实 React 组件和模拟网络响应，验证分页重置、选项值、列表/统计/CSV 查询、空状态、恢复全部和保存后清空。模拟网络并非真实浏览器或真实后端联调证明。
- `npm run typecheck`：通过；`npm run build`：通过，Vite 提示既有大于 500 kB 的资源块。
- 隔离修复前的串行后端全量 `./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true test`：884 项中 881 通过、1 失败、2 跳过、0 错误；新增 `TransactionSourceFilterApiTest` 为 6/6 通过。唯一失败是下述精确基准可复现的 `ConsolidatedReportingApiTest`。2 项 MySQL 迁移测试因为没有配置一次性 MySQL 测试库而按假设跳过：`DecimalLedgerMigrationTest#populatedMySqlV21UpgradePreservesAmountsSourcesAndReceipts`、`LoanPrecisionMigrationTest#populatedMySqlV22UpgradePreservesLegacyAmountsAndMetadataIsUnknown`。
- 前端全量 `npm test -- --run`：76 个文件中 75 通过、1 失败；440 项中 439 通过、1 失败，0 跳过。失败为 `TransactionsPage.test.tsx > shows creator, paginates transactions, and links a complete csv export`：测试将月份写死为 `2026-09`，当前实际是 `2026-10`。未调整系统日期、跳过用例或修改无关测试。
- 确认基线方式：`mkdir -p target/qa-baseline && git archive b024053419b88053b6c31871118d3324b4762c92 | tar -x -C target/qa-baseline`，将同一 `frontend/node_modules` 链接到导出的 `frontend/node_modules` 后，在该目录执行同一前端测试文件，复现相同断言（17 通过、1 失败）。同一导出中执行 `./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true -Dtest=ConsolidatedReportingApiTest test`，复现 `netWorthBudgetUsesStoredCentsAndMatchesUserFacingCategoryRollup` 期望预算 `1591.36`、实际 `0.00`（2 项中 1 失败）；测试创建 2026-09 预算，而未传 `asOf` 的净资产接口使用当前上海日期 2026-10-30。
- CSV 媒体协商基线：在 `b024053` 的独立导出中临时添加 MockMvc 检查并运行 `./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true -Dtest=CsvExportApiTest#baselineCsvOnlyAcceptJsonReturns500BeforeSourceParsing test`；不带参数与 `sourceType=invalid` 在 `Accept: application/json` 下均返回 500（检查 1/1 通过）。最终候选服务实际 HTTP 也对合法及非法来源返回 500；这不是来源值的特定判断，未宣称修复。完整日志保存在 `target/qa-evidence/backend-exact-base-json-accept.log`。
- 首次后端全量期间误在同一目录并发启动另一 Maven 测试，编译器重建共享输出目录导致 83 个类加载/上下文错误（总计 882 项、3 failures、83 errors、2 skipped）。该运行受操作污染，不能用作产品通过或失败结论。之后串行重跑，不并发修改 `target/classes`。上述原始命令输出保存在本机忽略目录 `target/qa-evidence/`，不作为版本化文件。

## 数据库隔离修复（独立审查后）

`TransactionSourceFilterApiTest` 通过一次生成的静态 UUID 和 `@DynamicPropertySource` 在 Spring 启动前固定同一 `jdbc:h2:mem:transaction-source-filter-*` 给应用与 Flyway，并显式固定两者 H2 驱动、用户名、密码和 H2 Flyway 迁移位置。该测试获取两连接的 JDBC 元数据，核对 URL 与用户；不会对两条配置分别求值随机数。`SourceFilterQaLauncher` 在 `SpringApplicationBuilder.run(...)` 之前用应用命令行参数固定应用与 Flyway 的同一 H2 URL、驱动、凭据、迁移位置；启动后再次核对两条实际连接。测试进程将 `FAMILY_QA_SOURCE_PORT=0` 作为端口选择，避免与正常 18801 预览争用；应用 URL 与凭据不可经此选项修改。

新增两个隔离验证先 RED（2 项失败，0 错误：测试连接了故意覆盖的另一内存库；启动器受到故意错误的外部驱动影响），然后 GREEN。明确向命令环境注入不可能连接的 `jdbc:deliberately-invalid:*` URL、无效驱动与合成凭据后，串行运行 `-Dtest=TransactionSourceFilterApiTest,SourceFilterQaLauncherIsolationTest`：10 项通过、0 失败、0 跳过；其中 API 类 8 项、独立启动器进程 2 项。两个进程场景分别验证无效 datasource 驱动及 datasource 驱动有效但 Flyway 配置无效时仍能启动；子进程还用真实 HTTP/CSRF 登录并验证 51 条周期样例和家庭隔离。该组包含 `Accept: text/csv` 下无效月份的 JSON 400 检查。

另外，从隔离修复前的 `563330d` 提取旧启动器至独立编译目录，令应用数据源驱动有效且仅 Flyway URL 无效，旧启动器在 Flyway 初始化阶段失败；这是额外的 RED 证据，日志为 `target/qa-evidence/old-launcher-flyway-red.log`。

原有 `CsvExportApiTest` 不具上述动态防护，因此交接中不再建议直接调用。使用交接中的同一 H2 URL 受控命令，并额外注入上述无效外部配置，串行运行该类：4 项通过、0 失败、0 跳过；Flyway 和应用日志显示连接同一个 `qa-csv-*` H2 库。以同样的显式绑定方式串行运行 `TransactionSummaryApiTest,TransactionPaginationApiTest,CsvExportApiTest`，9 项通过、0 失败、0 跳过。本轮未重复之前的 884 项后端全量；之前的全量结果属于隔离修复前版本，不能冒充当前 HEAD 的全量结果。隔离修复 RED/GREEN 与受控 CSV 日志分别保存在 `target/qa-evidence/backend-isolation-red.log`、`backend-isolation-green.log`、`backend-controlled-csv.log` 和 `backend-controlled-regression.log`。

## 交接与限制

[HANDOFF](HANDOFF.md) 给出独立本机服务、演示账号、合成数据和精确复现步骤。真实浏览器验证、独立 QA、MySQL 数据库、CI、部署与生产验收均未由本记录宣称完成。H2 测试和模拟 UI 不能证明 MySQL 查询计划或线上运行行为；真实业务规则由用户/实际业务负责人确认。

## 协调者的最终浏览器核验

2026-10-30，协调者在产品代码对应 `0edeaa8` 的独立 18801 预览上，以真实浏览器登录并选择“稍后处理”关闭合成账户期初提示；确认周期来源共 51 条、两页为 50/1、汇总 102.00、CSV 51 行且无外家庭标记，再从第二页切到手工来源，实际只显示 1.00 的 QA-MANUAL，导出链接同步为 MANUAL。网页行为和同源接口均实际检查；这是一轮交付烟测，未启动 QA Agent、未覆盖整个 PRD，也不是完整 SOP 或上线通过结论。
