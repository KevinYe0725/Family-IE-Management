# QA 交接：收支来源筛选

本文件用于本机独立验证；开发测试不是 QA 验收。需求与验收依据为 [PRD](PRD.md)，产品取舍见 [PM 记忆](PM_MEMORY.md)，实现与测试证据见 [开发记忆](DEVELOPMENT_MEMORY.md)。基准提交 `b024053419b88053b6c31871118d3324b4762c92`；先于产品代码的需求/计划提交 `fda7a699c0acfa5a3e08d91432534dc94b28da0d`。最终待测提交以 `git rev-parse HEAD` 为准，不把未提交工作树当作 PR 版本。

## 前提与启动

在本仓库干净检出、Java 17 或更高版本、Node 22/npm 10、依赖可安装的机器上执行。实际开发环境为 Java 26.0.2.1、Node 22.23.2、npm 10.9.8、Maven Wrapper 3.9.16；后端测试依赖 H2 2.3.232。确认 `127.0.0.1:18801` 空闲后，在仓库根目录依次运行：

```bash
npm --prefix frontend ci
npm --prefix frontend run build
./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=target/qa-source-classpath.txt
java -cp "target/test-classes:target/classes:$(cat target/qa-source-classpath.txt)" com.familyfinance.transaction.SourceFilterQaLauncher
```

出现 `QA_SOURCE_READY` 后打开 `http://127.0.0.1:18801/workspace/transactions?month=2026-10`。页面和 `/api/*` 同源，无需单独的 18802 Vite 服务；测试账号为项目测试演示账户 `demo` / `demo1234`。进程前台运行，Ctrl+C 只停止该测试进程。启动器强制 loopback、`test` profile、专用的进程内 `jdbc:h2:mem:qa_source_filter`、H2 迁移与禁用调度，并核对实际连接；它不会连接现有 MySQL。内存数据进程退出即消失，下次启动重新生成。不要在该进程运行时重编译或重建其 `target/classes`；需要重建先停止它。

## 合成数据与可执行检查

启动器 `src/test/java/com/familyfinance/transaction/SourceFilterQaLauncher.java` 生成 2026-10、支出、CNY 的 `QA 合成账户` / `QA 合成分类`：手工 1 条（1.00）、周期 51 条（每条 2.00）、历史贷款 1 条（3.00）、贷款还款 1 条（4.00）、提前还款 1 条（5.00）；另有外家庭周期记录 `QA-FOREIGN-DO-NOT-SHOW`。这些合成收支由测试实体工厂直接写入，不经过周期确认或贷款记账，也不附带完整账务分录；它们只用于验证读取筛选，不用于验证预算、账户余额或真实账务链。来源为 `LOAN` 的历史样例仅在该隔离 H2 中用 SQL 构造，以模拟没有当前创建入口的旧记录。项目还会装载原有合成 demo 数据，使用 2026-10 月份可排除其旧月记录。

1. 登录后选「收支记录」、账期 `2026-10` 和支出，选择「周期账单」：第一页 50 条，第二页 1 条，支出合计 102.00；CSV 有 51 条数据行，且不含外家庭标记。
2. 在第二页选择「手工录入」：回到第一页，看到 1.00 的 `QA-MANUAL`；汇总和下载的 CSV 应同步筛选。切回全部恢复 55 条合成支出。
3. 分别选择历史贷款、贷款还款、贷款提前还款，核对 3.00 / 4.00 / 5.00 与对应备注。组合成员、账户、分类或搜索，列表、统计和 CSV 应一致。空结果应保留原有空状态。
4. 使用登录 Cookie 查询 `GET /api/transactions?month=2026-10&kind=expense&sourceType=RECURRING&page=0&size=50`、`GET /api/transactions/summary?month=2026-10&kind=expense&sourceType=RECURRING` 和 `GET /api/export.csv?month=2026-10&kind=expense&sourceType=RECURRING`。请求通过认证且 CSV 使用 `Accept: */*` 或 `text/csv` 时，错误值如 `sourceType=manual` 应返回 HTTP 400、`error.code=VALIDATION_ERROR`、`error.fields.sourceType=来源类型无效`；导出即便发送 `Accept: text/csv` 也应返回该 JSON 错误。仅接受 `application/json` 的 CSV 请求因既有媒体协商问题在基准和候选均返回 500，不属于本 PR 修复范围。
5. 自动化（隔离 H2）：`./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true -Dtest=TransactionSourceFilterApiTest,CsvExportApiTest test`；界面行为：`cd frontend && npm test -- --run src/features/ledger/TransactionSourceFilter.test.tsx`。不要把以上开发验证直接当成独立 QA 结论。

## 责任和范围

本数据集的业务口径由 Codex 依用户委托模拟 PM 确定，开发也由 Codex 模拟；如实际业务负责人（用户 Kevin）修改规则，应先更新 PRD，再重新测试。没有虚构其他联系人或声称负责人已审批。H2、测试模拟与本机预览不证明 MySQL 生产部署、真实用户数据或线上验收；本交接不授权发布、合并或部署。
