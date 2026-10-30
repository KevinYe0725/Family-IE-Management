# PM 工作记忆：收支记录来源筛选

- 身份与来源：Codex simulated PM under user delegated dataset design。用户委托构造可测试的数据集并授权主题与设计选择；下面是本次模拟 PM 作出的决定，不是虚构的用户审批或既有访谈。
- 基准：`b024053419b88053b6c31871118d3324b4762c92`。需求入口：[PRD](PRD.md)。
- 现状证据：`src/main/java/com/familyfinance/transaction/TransactionController.java`、`TransactionFilter.java`、`TransactionFilterParser.java`、`TransactionSpecifications.java`；`src/main/java/com/familyfinance/reporting/ExportController.java` 和 `CsvExportService.java`；`frontend/src/features/ledger/TransactionsPage.tsx` 与 `LedgerCharts.tsx`；`src/main/resources/db/migration/V4__accounts_categories_recurring_budgets.sql`。
- 已作决定：使用已有五种来源的精确枚举；空白表示全部；错误值返回字段级 400；筛选与其他条件及家庭作用域相交，三个读取端点一致。原因是避免页面、报表和导出数字不一致且防止错误值扩大查询范围。
- 已作决定：`LOAN` 独立表示旧贷款记录，不合并 `LOAN_PAYMENT`/`LOAN_PREPAYMENT`。迁移要求来源非空，旧记录默认 `MANUAL`；不筛选时不新增 NULL 判定，选择单一来源仅匹配对应值。
- 已作决定：界面添加一项筛选，不更改 CSV 格式、货币语义、账务写入或角色权限。切换来源回到第一页，保存后按既有筛选清空惯例清空来源。
- 已排除：前端本地过滤（只过滤当前页，统计和 CSV 会偏离）；三个端点分别实现过滤（容易产生不一致）；将不同贷款枚举合并（丢失来源信息）。
- 待验证：本地自动化和独立 QA 对本 PR 的验收；MySQL 运行与真实浏览器操作仍需独立环境核查。本模拟决策没有尚待用户回答的业务问题；如真实业务负责人之后给出不同定义，应更新 PRD 与测试，不假定其已同意。
