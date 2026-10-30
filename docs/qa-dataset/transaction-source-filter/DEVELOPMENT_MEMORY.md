# 开发工作记忆：收支来源筛选

## 身份、基准与实现

本记录由 Codex 在用户委托的数据集任务中模拟开发角色填写；它记录实现与开发验证，不代表 QA 验收。基准 `b024053419b88053b6c31871118d3324b4762c92`；先提交的 PRD、PM 记忆与计划为 `fda7a699c0acfa5a3e08d91432534dc94b28da0d`。实际待测版本以检出的 `git rev-parse HEAD` 为准。

`TransactionController`、`reporting/ExportController` 将原始参数放入 `TransactionFilter`；原有 8/9 参数构造器保留。`TransactionFilterParser` 对非空白值使用严格枚举解析并累积 `sourceType` 字段错误；`TransactionCriteria` 携带强类型值；`TransactionSpecifications` 将来源谓词与现有家庭和其他筛选条件用 AND 连接。汇总和 CSV 通过 `TransactionService.findAllForCsvExport` 复用该路径。`ExportController` 对筛选校验异常显式指定 JSON 内容类型，使要求 CSV 的客户端也能获得字段级 400。它不改变成功时 CSV 格式。

`frontend/src/features/ledger/TransactionsPage.tsx` 用 `Transaction['sourceType']` 与空字符串保存选择；同一过滤串用于列表、`LedgerCharts` 汇总和 CSV。来源变更与回到第一页在同一个事件中完成，保存记录后按原筛选清空惯例清空来源。没有迁移、记账数据修改、权限或金额公式修改。合成测试记录直接通过实体工厂插入，不验证周期确认、贷款记账或账务分录链路。未筛选时不增加来源条件；数据库 V4 的历史默认值是非空 `MANUAL`，具体选择使用相等比较，不为非法 NULL 数据增设归类。

## 测试和证据

命令在本仓库运行，时间为 2026-10-30，环境使用独立 test profile/H2、合成数据、Java 26.0.2.1、Node 22.23.2；测试文件不访问真实财务数据。

- 基线相关后端 `./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true -Dtest=TransactionSummaryApiTest,TransactionPaginationApiTest,CsvExportApiTest test`：9 项通过，0 失败、0 跳过。
- 新增 `TransactionSourceFilterApiTest`：真正的随机端口 Tomcat、HTTP Cookie/CSRF 和 H2 数据库；覆盖 5 个枚举、空白、未知值、组合筛选、不同家庭、51 条跨页的统计与 CSV、银行卡/日期范围，以及 `Accept: text/csv` 的字段错误。最初 RED 4 项中 3 项失败（筛选被忽略及非法值返回 200），完成共享筛选后通过；随后 CSV Accept 专项先实际返回 500 再修复为 400；银行卡/日期组合在独立基准导出上先 RED（错误混入手工记录），本分支的最终运行结果见下。
- 前端 `cd frontend && npm test -- --run src/features/ledger/TransactionSourceFilter.test.tsx`：2 项通过；最初两个测试因缺少「来源筛选」控件而 RED。使用真实 React 组件和模拟网络响应，验证分页重置、选项值、列表/统计/CSV 查询、空状态、恢复全部和保存后清空。模拟网络并非真实浏览器或真实后端联调证明。
- `npm run typecheck`：通过；`npm run build`：通过，Vite 提示既有大于 500 kB 的资源块。
- 串行后端全量 `./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true test`：884 项中 881 通过、1 失败、2 跳过、0 错误；新增 `TransactionSourceFilterApiTest` 为 6/6 通过。唯一失败是下述精确基准可复现的 `ConsolidatedReportingApiTest`。2 项 MySQL 迁移测试因为没有配置一次性 MySQL 测试库而按假设跳过：`DecimalLedgerMigrationTest#populatedMySqlV21UpgradePreservesAmountsSourcesAndReceipts`、`LoanPrecisionMigrationTest#populatedMySqlV22UpgradePreservesLegacyAmountsAndMetadataIsUnknown`。
- 前端全量 `npm test -- --run`：76 个文件中 75 通过、1 失败；440 项中 439 通过、1 失败，0 跳过。失败为 `TransactionsPage.test.tsx > shows creator, paginates transactions, and links a complete csv export`：测试将月份写死为 `2026-09`，当前实际是 `2026-10`。未调整系统日期、跳过用例或修改无关测试。
- 确认基线方式：`mkdir -p target/qa-baseline && git archive b024053419b88053b6c31871118d3324b4762c92 | tar -x -C target/qa-baseline`，将同一 `frontend/node_modules` 链接到导出的 `frontend/node_modules` 后，在该目录执行同一前端测试文件，复现相同断言（17 通过、1 失败）。同一导出中执行 `./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true -Dtest=ConsolidatedReportingApiTest test`，复现 `netWorthBudgetUsesStoredCentsAndMatchesUserFacingCategoryRollup` 期望预算 `1591.36`、实际 `0.00`（2 项中 1 失败）；测试创建 2026-09 预算，而未传 `asOf` 的净资产接口使用当前上海日期 2026-10-30。
- 首次后端全量期间误在同一目录并发启动另一 Maven 测试，编译器重建共享输出目录导致 83 个类加载/上下文错误（总计 882 项、3 failures、83 errors、2 skipped）。该运行受操作污染，不能用作产品通过或失败结论。之后串行重跑，不并发修改 `target/classes`。上述原始命令输出保存在本机忽略目录 `target/qa-evidence/`，不作为版本化文件。

## 交接与限制

[HANDOFF](HANDOFF.md) 给出独立本机服务、演示账号、合成数据和精确复现步骤。真实浏览器验证、独立 QA、MySQL 数据库、CI、部署与生产验收均未由本记录宣称完成。H2 测试和模拟 UI 不能证明 MySQL 查询计划或线上运行行为；真实业务规则由用户/实际业务负责人确认。
