# 家账：家庭收支管理系统

一个部署在服务器上的 Spring Boot + React 家庭财务工作区。它把日常收支、预算、周期账单、资产、A 股持仓、贷款、提醒和家庭协作放在同一条家庭账轨上，所有财务汇总由服务器计算并持久化到 MySQL 8。

## 运行（服务器）

生产运行环境是 Ubuntu 22.04 + Java 17 + MySQL 8。完整初始化和 systemd 配置见 docs/operations/mysql-server-runbook.md。

服务启动后由 Nginx 监听服务器 80 端口并代理到本机 127.0.0.1:8080。请先在阿里云安全组放行入站 TCP 80，然后直接访问：

```text
http://YOUR_SERVER_IP
```

如果暂时不开放安全组，也可以使用 SSH 隧道访问：

~~~bash
ssh -N -L 18080:127.0.0.1:8080 family-finance-server
~~~

打开对应地址后，首次启动会由 Flyway 初始化 MySQL V1–V13，并写入演示家庭：

- 邮箱：demo@local.family（兼容旧用户名 demo）
- 密码：demo1234

演示数据和新用户都会写入服务器 MySQL；重启应用不会清空数据库。

### React 家庭财务工作区

- 桌面端保留 52px 应用轨道和可隐藏模块栏；手机端使用可关闭、可恢复焦点的模块抽屉。
- 总览集中显示服务器提供的现金流、净资产、负债、预算、投资与提醒状态；行情始终标明来源、日期、陈旧或缺失状态。
- 收支页面提供账户、两级分类、组合筛选以及收支新增、编辑和删除；长表格在手机端自动转为卡片。
- 预算、周期账单、资产估值、投资交易、贷款计划和家庭角色均连接对应后端 API，并按 `OWNER`、`ADMIN`、`MEMBER` 权限显示操作。
- 浏览器只在 `localStorage` 保存模块栏布局偏好，不保存密码、令牌或任何财务记录。

如果要单独开发前端，可在后端运行时另开终端：

```bash
cd frontend
npm ci
npm run dev
```

日常交付仍使用仓库根目录的一键启动脚本；Maven 会下载固定 Node/npm、运行前端测试并把哈希静态资源打入可执行 JAR，不要求系统全局安装 Node。

### 第二阶段家庭与登录基础

- `POST /api/auth/register` 支持 `CREATE` 创建新家庭及其 `OWNER`，或携带邀请 Token 以 `JOIN` 加入已有家庭。
- 所有者或管理员可创建 `MEMBER` 邀请；只有所有者可以调整家庭成员角色。邀请 Token 仅在创建响应中显示一次，请通过受信任方式转交。
- 第一阶段的 `demo` 数据升级后会对应 `demo@local.family` 和 `OWNER` 角色；旧用户名仅保留为登录兼容别名。
- React 界面已经接入注册、邀请、家庭资料、成员角色、所有权转让、密码修改和家庭安全归档；邀请原文只在创建成功页面显示一次。

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

详细覆盖范围和平台边界见 `docs/acceptance/stage-2-ledger-checklist.md`，React 工作区验收见 `docs/acceptance/stage-2-frontend-checklist.md`。

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

## 数据库说明

生产环境只使用 MySQL 8。H2 仅用于 Maven 测试，测试配置为随机内存库，不读取或写入服务器生产库。不要在服务器上执行 `mvn clean` 作为数据重置方式；如需重置，请先制作 MySQL 备份并确认恢复方案。

当前服务器已按演示需要开放 MySQL TCP 3306，并创建了仅授权 `family_finance.*` 的 `family_finance_remote` 账号。数据库密码只保存在服务器的 `/etc/family-finance/mysql-remote.env`；正式使用时建议把安全组来源收窄为固定 IP，或恢复为 SSH 隧道访问。

服务器使用 MySQL 8 的事务和 Flyway 迁移，不再对 H2 文件执行生产迁移检查。H2 迁移历史仍用于测试夹具，生产迁移位置为 `classpath:db/migration-mysql`。

MySQL 的备份、恢复和迁移失败处理由服务器运维流程负责；应用启动失败时请先查看 `journalctl -u family-finance`，不要自动执行 `flyway repair`。

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
- `frontend`：React、TypeScript、Vite、Semi Design 与 TanStack Query 工作区。
- `target/classes/static`：Maven 生命周期生成并打包的 React 哈希静态资源，不在源码目录手工维护。

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

## 明确不包含

密码找回、附件、OCR、银行或支付平台同步、证券下单、盘中实时行情、原生 App、系统推送和 AI 对话不属于第二阶段范围。没有 `TUSHARE_TOKEN` 时外部行情刷新会明确显示不可用，手工价格功能仍可使用；Token 不会进入浏览器界面或存储。
