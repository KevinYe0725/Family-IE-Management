# 家账：家庭收支管理系统

一个用于课程演示的 Spring Boot 家庭收支 Web 应用。它把每笔收入和支出放在同一条家庭现金流账轨上，并提供成员与分类管理、筛选、统计、规则分析和 CSV 导出。

## 运行

前提：Java 17。

### Windows 一键启动

在 CMD 中进入项目目录，然后运行：

```bat
start-local.cmd
```

在 PowerShell 中需要显式写出当前目录：

```powershell
.\start-local.cmd
```

脚本会检查 Java 版本和 8080 端口，使用项目自带的 Maven Wrapper 启动 Spring Boot，并在服务就绪后自动打开浏览器。停止应用时，在启动终端中按 `Ctrl+C`。

如果 8080 端口已被其他程序使用，可以指定另一个端口：

```bat
start-local.cmd -Port 8090
```

可选参数：

- `-NoBrowser`：服务启动后不自动打开浏览器。
- `-Smoke`：启动、等待探活成功后自动停止，主要供 CI 或环境检查使用；要求指定端口上没有已运行实例。

### macOS / Linux

```bash
./start-local.sh
```

该入口会检查 Java 17、项目 Maven Wrapper 和现有数据库状态，并从仓库的 `V*.sql` 动态取得最新数字迁移版本。非空旧库没有 Flyway history 或落后于当前版本时，会先完成只读检查与 SHA-256 验证备份，再以前台进程启动应用；在终端按 `Ctrl+C` 即可停止。不要绕过这一入口直接启动 Spring Boot，否则会跳过迁移前备份检查。

打开 [http://127.0.0.1:8080](http://127.0.0.1:8080)，使用本地演示账户登录：

- 邮箱：`demo@local.family`（兼容旧用户名 `demo`）
- 密码：`demo1234`

首次启动会写入演示家庭及 2026 年 6—9 月账目。界面默认打开浏览器本地时间对应的当前月份；演示时可在账期控件中切换到 `2026-09`。默认数据文件是 `data/family-finance.mv.db`（H2 还可能创建同目录的跟随文件）。

### 第二阶段家庭与登录基础

- `POST /api/auth/register` 支持 `CREATE` 创建新家庭及其 `OWNER`，或携带邀请 Token 以 `JOIN` 加入已有家庭。
- 所有者或管理员可创建 `MEMBER` 邀请；只有所有者可以调整家庭成员角色。邀请 Token 仅在创建响应中显示一次，请通过受信任方式转交。
- 第一阶段的 `demo` 数据升级后会对应 `demo@local.family` 和 `OWNER` 角色；旧用户名仅保留为登录兼容别名。
- 当前随应用提供的原生 HTML/JavaScript 界面仍是第一阶段界面的渐进增强版本，尚未显示注册、邀请、家庭资料、成员角色和所有权转让入口；这些能力目前通过下方 API 提供，计划在后续 React 前端阶段接入。

### 第二阶段账本、预算与周期账单

- 家庭可维护现金、银行卡和钱包账户；新建收支必须选择一个有效账户。注册并创建家庭时会在同一事务中创建默认账户和默认分类，但不会替家庭猜测预算月份或金额；预算须由所有者或管理员显式创建。
- 分类支持两级结构，父子分类必须同为收入或同为支出；收支可直接使用一级或二级分类。
- 月度预算支持家庭总额、分类和成员范围，修改会保留不可变修订记录。预算使用额由已确认支出实时计算，不保存可能漂移的累计值。
- 周期规则支持月度和周度计划。到期时先生成待确认项，分配用户确认后才创建一笔真实收支；重复确认返回同一笔收支，不会重复记账。
- `StageTwoLedgerSmokeTest` 使用真实随机端口、HTTP Cookie/CSRF 和临时文件型 H2，完成一次创建、生成、确认和预算核对，完全关闭应用后再以同一数据库重启并核对持久化状态与 Flyway V1–V7。

### 第二阶段资产、A 股投资与日线行情

- 资产只覆盖房产、车辆和其他资产；现金、银行卡和钱包仍以账本账户为准，避免重复计算。资产支持当前价值与不可变估值历史（当天手工估值可更正）。
- 家庭所有者或管理员可维护 CNY 投资账户、登记本地 A 股证券（仅 `######.SH`、`######.SZ`、`######.BJ`）、记录 BUY/SELL/DIVIDEND/FEE 历史。持仓、成本和收益始终由历史交易推导，不能直接写入。
- 行情仅使用收盘日线；工作日上海时间 16:30 刷新，也可手动刷新。有效价格优先选择当天/最近的手工价格，否则使用最后一次已验证的日线快照；响应会显示来源、日期、抓取时间和陈旧状态。
- 默认可在没有行情 Token 的情况下正常启动和使用。此时刷新返回 `MARKET_DISABLED`，手工价格仍可用于组合估值。若要启用 Tushare，请由使用者在自己的启动环境中填写 `TUSHARE_TOKEN`；它绝不应写入仓库、测试、日志或 H2 数据库。

### 第二阶段贷款、提醒与家庭财务汇总

- 贷款提供持久化还款计划、到期确认和幂等确认流水；确认会一次性降低剩余本金并关联真实支出流水。提醒中心汇集贷款到期、预算阈值和资产估值过期等派生提醒。
- `GET /api/net-worth` 仅汇总活跃现金账户余额、活跃非现金资产、已有有效价格的投资市值和活跃贷款本金，避免跨模块重复计算；响应同时给出资产配置、预算、投资价格来源/手工/陈旧/缺失状态，以及最近 24 个日快照。
- `GET /api/debt-analysis` 提供当前负债、负债率和每笔活跃贷款的已还比例。每天上海时间 23:50 生成同一家庭/日期幂等更新的净资产快照。

## 测试

```bash
./mvnw test
```

测试使用独立数据库；不会读取或写入 `data/family-finance.mv.db`。其中的端到端冒烟测试以真实随机端口、HTTP 会话 Cookie 与 CSRF 令牌验证完整流程。只运行第二阶段账本验收可使用：

```bash
./mvnw -q -Dtest=StageTwoLedgerSmokeTest test
```

详细覆盖范围和平台边界见 `docs/acceptance/stage-2-ledger-checklist.md`。

第二阶段资产/投资/行情的真实 HTTP、Cookie/CSRF、文件 H2 重启和无 Token 手工价格验收可单独运行：

```bash
./mvnw -q -Dtest=StageTwoAssetInvestmentSmokeTest test
```

该测试只使用测试上下文中的本地行情提供者；不访问 Tushare，也不需要或读取真实 Token。完整范围与手工演示边界见 `docs/acceptance/stage-2-assets-investments-checklist.md`。

第二阶段贷款、提醒和汇总的真实 HTTP、文件 H2 重启与同日快照幂等验收可单独运行：

```bash
./mvnw -q -Dtest=StageTwoLoanReportingSmokeTest test
```

完整范围、API/UI 边界见 `docs/acceptance/stage-2-loans-reporting-checklist.md`。

## 安全地重置本地数据

先停止应用，再将整个 `data/` 目录移动到一个带日期的备份目录，例如 `data-backup-2026-09-02/`。确认备份可恢复后，再启动应用；系统会创建全新的演示数据。

不要在应用运行时删除数据库文件，也不要把重置命令用于其他目录。

## 第一阶段数据升级备份与恢复

Windows `start-local.cmd` 与 macOS/Linux `./start-local.sh` 会用项目固定的 H2 2.3.232 运行时只读检查非空主库 `data/family-finance.mv.db` 的 Flyway history，并与仓库最新数字迁移版本比较。Windows 通过 Java 17 源码模式运行仓库内的小型 JDBC 检查器，SQL 和本地 `sa`/空密码配置都留在 Java 内部，不跨 PowerShell 原生参数边界，也不生成或提交编译产物。`NO_HISTORY` 或 `BEHIND_CURRENT`（例如 V6 等待 V7）都会在真正启动应用前复制所有 `family-finance.*.db` 跟随文件（包括零字节文件）到 `data-backups/<时间戳>/`；只有 `CURRENT` 跳过迁移备份。macOS/Linux 还会比较检查前后的主库哈希。自动升级仅支持该项目实际使用的 MVStore `.mv.db` 格式。每个文件都采用流式 SHA-256 校验；备份完成目录中的 `RESTORE.txt` 记录文件清单、校验值和恢复提示。`data-backups/` 不会提交到 Git。

备份先写入同名 `.partial` 目录，全部复制、哈希和清单写入成功后才原子发布为完成目录。检查、复制或校验失败时应用不会启动，并会保留 `.partial` 目录供排查。若 history 含失败迁移，脚本会先保存一份校验通过的当前状态备份，再拒绝启动并提示：先修复无效数据，再显式运行 Flyway `repair`，检查备份后重试；启动器绝不会自动 `repair`。未来版本或含糊 history 同样会先备份当前状态，再拒绝启动且保持 history 不变。没有现有主库时不会创建备份。

macOS/Linux 启动脚本会使用 `data-backups/.family-finance-backup.lock` 串行化备份。若另一个启动仍在执行，或异常终止留下了无法确认归属的锁，脚本会拒绝启动且不会自行删除该锁；请先确认没有启动或迁移进程仍在运行，再人工检查锁目录和 `.partial` 证据。

如迁移失败或要回退，先停止应用，将当前 `data/` 目录保留到另一个安全位置，再把某个已验证备份目录中的全部数据库文件复制回 `data/`。不要复制 `.partial` 目录；它表示备份未完整完成。恢复后再启动应用并验证可登录和账目数据。

`start-local.cmd -NoBrowser -Smoke` 始终使用 `target/` 中随机的专用数据库，并且不会读取、迁移或备份生产 `data/` 文件。

## 结构

- `src/main/java/com/familyfinance/auth`：登录、会话与当前用户。
- `src/main/java/com/familyfinance/identity`：注册与密码修改。
- `src/main/java/com/familyfinance/family`：家庭 Membership、邀请、角色与权限。
- `src/main/java/com/familyfinance/household`：家庭与成员。
- `src/main/java/com/familyfinance/category`：收入和支出分类。
- `src/main/java/com/familyfinance/ledger`：家庭账户与周期账单。
- `src/main/java/com/familyfinance/budget`：月度预算、修订历史与实时使用额。
- `src/main/java/com/familyfinance/asset`：房产、车辆、其他资产与估值历史。
- `src/main/java/com/familyfinance/investment`：投资账户、A 股证券、交易与推导持仓。
- `src/main/java/com/familyfinance/market`：日线行情提供者、快照、手工价格与调度刷新。
- `src/main/java/com/familyfinance/transaction`：收支记录与筛选。
- `src/main/java/com/familyfinance/reporting`：看板、规则分析、CSV 与投资组合报告。
- `src/main/resources/static`：原生 HTML、CSS、JavaScript 单页界面。

## API 概览

- `GET /api/csrf`、`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/session`
- `POST /api/auth/register`、`POST /api/auth/change-password`
- `GET|PATCH|DELETE /api/family`
- `GET|POST /api/family/invites`、`DELETE /api/family/invites/{id}`
- `GET /api/family/memberships?page={page}&size={size}`、`PATCH /api/family/memberships/{id}`
- `POST /api/family/transfer-ownership`
- `GET|POST /api/members`、`PATCH|DELETE /api/members/{id}`
- `GET|POST /api/accounts`、`GET|PATCH|DELETE /api/accounts/{id}`
- `GET|POST /api/categories`、`PATCH|DELETE /api/categories/{id}`（支持 `parentId`；`projection=tree` 返回两级树）
- `GET|POST /api/transactions`、`GET|PATCH|DELETE /api/transactions/{id}`（新建需要 `accountId`）
- `GET|POST /api/budgets`、`GET|PATCH /api/budgets/{id}`
- `GET /api/budgets/{id}/revisions`、`GET /api/budgets/usage?periodMonth=YYYY-MM`
- `GET|POST /api/recurring-rules`、`PATCH|DELETE /api/recurring-rules/{id}`
- `GET /api/recurring-occurrences`、`POST /api/recurring-occurrences/{id}/confirm`
- `GET|POST /api/assets`、`GET|PATCH|DELETE /api/assets/{id}`、`GET|POST /api/assets/{id}/valuations`
- `GET|POST /api/investment-accounts`、`GET|PATCH|DELETE /api/investment-accounts/{id}`
- `GET /api/securities/search?q=`、`POST /api/securities/resolve`
- `GET|POST /api/investment-trades`、`GET|PATCH|DELETE /api/investment-trades/{id}`
- `GET /api/market-quotes`、`POST /api/market-quotes/refresh`、`POST /api/securities/{id}/manual-price`
- `GET /api/portfolio`
- `GET|POST /api/loans`、`GET /api/loans/{id}/schedule`、`POST /api/loan-installments/{id}/confirm`、`POST /api/loans/{id}/prepay`
- `GET /api/notifications`、`POST /api/notifications/{id}/read`、`POST /api/notifications/{id}/resolve`
- `GET /api/net-worth`、`GET /api/debt-analysis`
- `GET /api/dashboard?month=YYYY-MM`、`GET /api/analysis?month=YYYY-MM`
- `GET /api/export.csv`（接受收支列表的筛选参数）

所有写操作都需要先从 `/api/csrf` 取得令牌，并在 `X-XSRF-TOKEN` 请求头中发送。浏览器界面会在每次写入后重新读取服务器的权威状态。写入、CSV 导出或退出遇到 401 时，界面会统一清除本地会话标记并返回登录页。

API 响应通过 `X-Request-ID` 返回请求关联 ID；未预期的服务器异常只向客户端返回通用 500 内容，并在服务端以该 ID 记录堆栈，便于排查而不记录请求体、密码或 CSRF 令牌。

## 当前静态界面不包含

当前原生静态界面已经支持收支的账户选择/筛选和两级分类维护，但没有独立的账户管理、预算、周期账单、贷款、提醒、净资产或债务分析页面，也尚未接入已完成的注册、密码修改、家庭邀请、成员角色与所有权转让 API；这些入口将在后续 React 计划中实现。密码找回、附件、OCR、银行或支付平台同步、投资/行情、原生 App、推送通知和 AI 对话也不属于当前界面范围。
