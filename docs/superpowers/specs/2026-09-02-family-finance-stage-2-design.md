# 家庭收支管理系统第二阶段设计

## 1. 已确认决策

- 后端继续使用 Java 17 + Spring Boot 4.1.1。
- 架构采用模块化单体，不拆微服务。
- 前端迁移为 React + TypeScript + Vite + Semi Design。
- 视觉采用已确认的 A1 飞书工作空间方向：应用图标栏始终保留，模块栏可隐藏。
- 注册使用邮箱、姓名和密码；用户可创建家庭或通过邀请码加入家庭。
- 家庭角色为所有者、管理员、成员。
- 周期账单和贷款还款到期后进入待确认，用户确认后才生成流水。
- 实现完整本地拓展功能：预算、周期账单、提醒、资产、房产、车辆、A 股投资、贷款和综合分析。
- A 股行情使用 Tushare 只读日线接口，交易日 16:30 自动同步，也支持手动刷新和手工价格兜底。
- 不实现交易下单、实时盘中行情、银行/支付平台同步、OCR 和邮箱验证码。

## 2. 阶段目标

将第一阶段的家庭流水 MVP 扩展为多人协作的家庭财务工作空间，使家庭能够在一个系统中管理：

1. 日常现金流与分类预算。
2. 周期账单和待确认事项。
3. 现金、银行账户、房产、车辆和其他资产。
4. A 股投资账户、交易、持仓、收盘行情和收益。
5. 房贷、车贷、其他贷款和还款计划。
6. 家庭净资产、资产配置、负债率、预算执行和投资收益。

第二阶段必须保留第一阶段所有已验证能力，包括家庭隔离、金额精度、CSRF、会话过期、CSV 安全、请求关联 ID、Windows 启动和 H2 文件数据库升级。

## 3. 架构方案

### 3.1 后端模块

仍为一个 Spring Boot 进程和一个 H2 数据库，但按业务边界拆包：

- `identity`：注册、登录、密码修改、用户状态。
- `family`：家庭、成员身份、角色、邀请和权限判定。
- `ledger`：账户、收支、二级分类、周期账单及待确认发生项。
- `budget`：月度总预算、分类预算、成员预算和修订历史。
- `asset`：房产、车辆、现金类和其他资产及估值历史。
- `investment`：投资账户、证券标的、交易、持仓和价格快照。
- `market`：Tushare 行情适配器、手工价格、缓存和限流。
- `loan`：贷款合同、还款计划、还款确认和提前结清。
- `notification`：预算、周期账单、贷款和估值提醒。
- `reporting`：现金流、净资产、资产配置、预算、投资和负债分析。
- `shared`：金额、日期、当前家庭、错误模型、请求 ID 和权限注解。

模块间通过服务接口和领域事件协作，不直接从控制器跨模块访问 Repository。不引入 MQ；同进程内使用 Spring 事件处理非关键派生更新，关键财务写入保持单事务同步完成。

### 3.2 数据库迁移

引入 Flyway Core 和版本化 SQL：

- `V1__stage1_schema.sql`：第一阶段完整基础表，用于新数据库。
- 现有非空数据库启用 `baseline-on-migrate`，基线版本为 1。
- `V2__identity_and_family_roles.sql`
- `V3__accounts_categories_recurring_budgets.sql`
- `V4__assets_investments_quotes.sql`
- `V5__loans_notifications_snapshots.sql`

完成迁移后将 Hibernate 设置为 `ddl-auto=validate`，禁止运行时隐式修改生产表结构。Flyway Core 采用 Apache 2.0 基础能力，不使用需要许可证的 Enterprise 特性。

### 3.3 前端构建

新增 `frontend/`：

- React 19、TypeScript、Vite。
- `@douyinfe/semi-ui` 与 `@douyinfe/semi-icons`。
- TanStack Query 负责服务端状态缓存与失效。
- React Router 负责工作区路由。
- Vitest + React Testing Library 负责组件与交互测试。

使用 Apache 2.0 的 `frontend-maven-plugin` 固定 Node/npm 版本，在 Maven `generate-resources` 阶段执行 `npm ci`、测试和构建，将产物复制到 Spring Boot 静态资源输出。Windows 朋友仍只需 Java 和 `start-local.cmd`，不要求全局安装 Node。

## 4. 身份、注册和家庭协作

### 4.1 注册流程

注册字段：

- 邮箱：去除首尾空格并转小写后全局唯一。
- 显示姓名：1–40 个字符。
- 密码：8–72 个字符，使用 BCrypt。
- 注册方式：创建家庭或输入邀请码加入家庭。

创建家庭时同时创建：

- 用户账号。
- 家庭。
- `OWNER` 成员身份。
- 与账号关联的家庭成员档案。
- 默认账户、分类和预算模板。

通过邀请码加入时，在同一事务中锁定邀请记录、验证有效期/剩余次数、创建用户和 `MEMBER` 身份，并连接到家庭成员档案。

### 4.2 邀请

- 所有者和管理员可创建邀请。
- 邀请默认 7 天过期、最多使用 5 次、默认角色 `MEMBER`。
- 邀请原文只在创建时返回一次；数据库保存 SHA-256 哈希。
- 可撤销、重新生成和查看已使用次数。
- 邀请不允许授予 `OWNER`；只有所有者能授予或撤销 `ADMIN`。

### 4.3 权限

| 能力 | 所有者 | 管理员 | 成员 |
|---|---:|---:|---:|
| 查看家庭财务 | 是 | 是 | 是 |
| 创建日常收支 | 是 | 是 | 是 |
| 修改/删除自己创建的收支 | 是 | 是 | 是 |
| 修改其他成员收支 | 是 | 是 | 否 |
| 管理分类、账户和周期规则 | 是 | 是 | 否 |
| 管理预算、资产、投资和贷款 | 是 | 是 | 否 |
| 确认分配给自己的待办 | 是 | 是 | 是 |
| 创建和撤销邀请 | 是 | 是 | 否 |
| 调整管理员角色 | 是 | 否 | 否 |
| 转让所有权或删除家庭 | 是 | 否 | 否 |

家庭数据依然按当前 `household_id` 隔离。权限服务同时检查家庭、角色、资源创建者和待办分配人，控制器不自行拼装权限判断。

## 5. 数据模型

### 5.1 身份与家庭

- `app_users(id, email, display_name, password_hash, status, created_at, updated_at)`
- `households(id, name, created_at, updated_at)`
- `household_memberships(id, household_id, user_id, role, status, joined_at)`
- `family_members(id, household_id, linked_user_id, name, role_label, created_at)`
- `family_invites(id, household_id, token_hash, role, expires_at, max_uses, used_count, revoked_at, created_by, created_at)`

一个用户第二阶段只允许加入一个有效家庭，但数据模型保留未来支持多个家庭的唯一扩展点。`family_members.linked_user_id` 可为空，用于尚未拥有登录账号的账目归属成员。

### 5.2 账户、分类和流水

- `financial_accounts(id, household_id, name, type, currency, opening_balance_cents, archived_at)`
- `categories` 增加 `parent_id`，只允许两级；父子分类的收支类型必须一致。
- `financial_transactions` 增加 `account_id`、`created_by_user_id`、`source_type`、`source_id`。
- `recurring_rules(id, household_id, kind, amount_cents, schedule_type, interval_value, day_of_month, next_due_on, account_id, member_id, category_id, active, created_by)`
- `recurring_occurrences(id, rule_id, due_on, status, confirmed_transaction_id, assigned_user_id, unique(rule_id, due_on))`

现有流水迁移到自动创建的“默认账户”，现有分类成为一级分类，`created_by_user_id` 指向迁移后的演示所有者。

### 5.3 预算

- `budgets(id, household_id, period_month, scope_type, category_id, member_id, amount_cents, version, active)`
- `budget_revisions(id, budget_id, old_amount_cents, new_amount_cents, changed_by, changed_at)`

同一家庭、月份和预算范围只能存在一个有效预算。预算使用额来自已确认支出流水，不单独维护可漂移的累计字段。

### 5.4 资产

- `assets(id, household_id, name, asset_type, owner_member_id, acquired_on, purchase_value_cents, current_value_cents, status, created_by, archived_at)`
- `property_assets(asset_id, address, area_sqm, usage_type)`
- `vehicle_assets(asset_id, brand_model, plate_hint, purchase_year)`
- `asset_valuations(id, asset_id, valued_on, value_cents, source, note, created_by, unique(asset_id, valued_on, source))`

现金/银行账户通过 `financial_accounts` 管理并计入资产；房产、车辆和其他资产使用 `assets`。净资产计算避免重复统计账户与资产。

### 5.5 投资和行情

- `investment_accounts(id, household_id, name, broker_name, currency, archived_at)`
- `securities(id, market, ts_code, name, security_type, active)`
- `investment_trades(id, household_id, account_id, security_id, trade_type, quantity, price_cents, fee_cents, traded_on, created_by)`
- `market_price_snapshots(id, security_id, trade_date, open_cents, high_cents, low_cents, close_cents, pre_close_cents, pct_change, source, fetched_at, unique(security_id, trade_date, source))`
- `manual_price_overrides(id, security_id, price_cents, effective_on, note, created_by)`

持仓数量、成本、已实现收益和浮动收益从交易及价格快照计算，不允许客户端直接写入派生汇总。股票代码仅接受六位数字加 `.SH`、`.SZ` 或 `.BJ`。

### 5.6 贷款与提醒

- `loans(id, household_id, name, loan_type, linked_asset_id, principal_cents, annual_rate, term_months, repayment_method, start_on, current_principal_cents, status, created_by)`
- `loan_installments(id, loan_id, installment_no, due_on, principal_cents, interest_cents, status, confirmed_transaction_id, unique(loan_id, installment_no))`
- `notifications(id, household_id, user_id, type, title, body, reference_type, reference_id, due_at, read_at, resolved_at, unique(type, reference_type, reference_id, user_id))`
- `net_worth_snapshots(id, household_id, snapshot_on, asset_cents, liability_cents, net_worth_cents, unique(household_id, snapshot_on))`

## 6. 关键业务事务

### 6.1 确认周期账单

在一个事务中：

1. 锁定待确认发生项。
2. 验证状态和用户权限。
3. 创建收支流水。
4. 设置 `confirmed_transaction_id` 并标记已确认。
5. 解决对应提醒。

重复提交返回同一结果，不生成第二笔流水。

### 6.2 确认贷款还款

在一个事务中：

1. 锁定贷款和还款期次。
2. 验证未还状态。
3. 创建支出流水，金额为本金加利息。
4. 减少 `current_principal_cents`。
5. 标记期次已还并关联流水。
6. 本金归零时结清贷款。

提前还款重新生成未来计划并保留已完成期次，不覆盖历史流水。

### 6.3 投资交易

买入、卖出、分红和手续费记录在 `investment_trades`。卖出不得超过当前可用持仓。交易修改后实时重新计算该证券持仓，价格快照只影响市值和浮动收益，不修改历史成本。

### 6.4 邀请加入

邀请使用采用数据库悲观锁和使用次数条件，保证并发注册不会超过 `max_uses`。邮箱冲突、邀请码过期、撤销和次数用尽返回不同的结构化业务错误。

## 7. A 股行情

### 7.1 数据源

- 接口：`https://api.tushare.pro`
- API：`daily`
- 认证：环境变量 `TUSHARE_TOKEN`。
- 数据：A 股日线开、高、低、收、昨收、涨跌额、涨跌幅、成交量和成交额。
- Token 不写入数据库、日志、前端或 GitHub。

`TushareQuoteProvider` 实现内部 `MarketQuoteProvider` 接口；另提供 `ManualQuoteProvider`。缺少 Token 时自动禁用外部同步，页面仍可使用手工价格。

### 7.2 同步规则

- 时区固定 `Asia/Shanghai`。
- 周一至周五 16:30 调度一次；接口返回的 `trade_date` 决定实际交易日，节假日不会制造假数据。
- 将家庭所有活跃持仓代码去重后批量请求。
- 每个交易日/证券/来源具有唯一键，任务可安全重试。
- HTTP 429/5xx 使用有上限的指数退避；权限或 Token 错误立即停止并生成管理员提醒。
- 手动刷新按家庭限流，每分钟一次；已有当日数据时默认不重复调用。
- 页面始终显示价格来源、交易日、抓取时间和过期状态。

## 8. API 边界

保留第一阶段 API，并新增：

- `POST /api/auth/register`
- `POST /api/auth/change-password`
- `GET|POST|DELETE /api/family/invites`
- `GET|PATCH /api/family/memberships`
- `POST /api/family/transfer-ownership`
- `GET|POST /api/accounts`、`PATCH|DELETE /api/accounts/{id}`
- `GET|POST /api/budgets`、`PATCH /api/budgets/{id}`
- `GET|POST /api/recurring-rules`、`PATCH|DELETE /api/recurring-rules/{id}`
- `GET /api/recurring-occurrences`、`POST /api/recurring-occurrences/{id}/confirm`
- `GET|POST /api/assets`、`PATCH|DELETE /api/assets/{id}`
- `GET|POST /api/assets/{id}/valuations`
- `GET|POST /api/investment-accounts`
- `GET /api/securities/search?q=`
- `GET|POST /api/investment-trades`、`PATCH|DELETE /api/investment-trades/{id}`
- `POST /api/market-quotes/refresh`
- `POST /api/securities/{id}/manual-price`
- `GET|POST /api/loans`、`PATCH /api/loans/{id}`
- `GET /api/loans/{id}/schedule`
- `POST /api/loan-installments/{id}/confirm`
- `POST /api/loans/{id}/prepay`
- `GET /api/notifications`、`POST /api/notifications/{id}/read`
- `GET /api/net-worth`、`GET /api/portfolio`、`GET /api/debt-analysis`

所有写接口继续使用 CSRF、请求关联 ID、标准成功/错误 Envelope 和乐观/悲观并发保护。分页列表使用稳定排序和游标或页码边界，禁止无上限返回长期历史。

## 9. 提醒与后台任务

使用 Spring `@Scheduled`，不引入消息队列：

- 每日 00:10 生成未来到期的周期账单发生项。
- 每日 00:20 生成贷款、预算、估值提醒。
- 工作日 16:30 同步 Tushare 收盘行情。
- 每日 23:50 生成家庭净资产快照。

所有任务以数据库唯一键和状态机保证幂等。调度失败只影响对应派生数据或提醒，不回滚历史交易和核心账本。

## 10. 飞书式前端设计

### 10.1 信息架构

应用图标栏宽 52px，始终保留：总览、收支、资产、投资、贷款、设置。模块栏桌面展开宽 220px，可手动隐藏；隐藏状态保存在本地。手机端模块栏为左侧抽屉，选择模块后自动收起。

模块页面：

1. 家庭总览
2. 收支明细
3. 预算管理
4. 周期账单
5. 资产账户
6. 投资持仓
7. 贷款计划
8. 提醒中心
9. 家庭与成员
10. 系统设置

### 10.2 视觉 Token

- 主色：`#3370FF`
- 主文本：`#1F2329`
- 次文本：`#646A73`
- 页面背景：`#F5F6F7`
- 面板背景：`#FFFFFF`
- 边框：`#DEE0E3`
- 成功：`#2EA85F`
- 警告：`#E58D1B`
- 危险：`#D54941`
- 正文字号：14px；辅助文字 12px；标题 16/20/24px。
- 间距基线：4px，主要间距为 8/12/16/24px。
- 圆角以 4/6/8px 为主，不使用大面积渐变和夸张阴影。

通过 Semi Design 全局和组件 Token 实现，不复制飞书 Logo、插画或受保护品牌资源。

### 10.3 组件行为

- 高频主操作固定在页面标题右侧，例如“记一笔”“新建资产”。
- 表格采用紧凑行高、固定表头和列选择；移动端转为信息卡片。
- 表单在侧边抽屉或中型对话框中完成，危险操作二次确认。
- 服务端状态使用 TanStack Query；写入成功后精确失效相关 Query，不在客户端猜测财务汇总。
- 模块栏折叠、筛选条件和表格列偏好保存在本地；财务数据不存入浏览器持久缓存。
- 键盘操作、焦点恢复、可访问名称、对比度和 `prefers-reduced-motion` 继续作为验收门槛。

## 11. 迁移与兼容

### 11.1 现有演示数据

- `demo` 用户迁移为邮箱 `demo@local.family`，显示名“演示用户”，角色 `OWNER`。
- 原用户名登录在一个版本周期内保留兼容别名，页面只展示邮箱登录。
- 现有家庭成员继续保留，演示用户连接到同名成员档案。
- 创建“默认账户”，现有流水全部关联该账户。
- 现有分类迁移为一级分类。
- 原 API 字段在迁移版本内保持兼容；前端切换后再废弃旧字段。

### 11.2 回滚和备份

启动迁移前自动检查数据库版本并提示备份；Windows 启动脚本在检测到待迁移的生产 H2 文件时先复制到带时间戳的 `data-backups/`。迁移失败不继续启动应用。备份目录默认不提交 Git。

## 12. 错误、安全与隐私

- 注册和登录按 IP/邮箱执行内存限流，响应不泄露邮箱是否存在。
- 邀请 Token、密码、Tushare Token 不进入日志。
- 所有家庭资源使用当前 Membership 再解析 `household_id`，不接受客户端传入家庭 ID 作为授权依据。
- 财务写入使用事务、唯一约束和幂等键；金额继续以整数分存储。
- 外部行情属于不可信输入，校验字段、交易代码、日期和价格范围后才能保存。
- API 错误继续返回请求 ID；外部服务错误对用户显示可恢复操作，不泄露 Token 或上游响应原文。

## 13. 测试与验收

### 后端

- Flyway 从空库建库和从第一阶段数据库升级。
- 注册创建家庭、邀请码加入、过期/撤销/并发用尽。
- 所有者/管理员/成员权限矩阵和跨家庭访问。
- 二级分类、账户、预算和周期账单状态机。
- 资产估值、投资买卖/分红/手续费和持仓计算。
- 贷款计划、确认还款、提前还款和重复提交。
- Tushare HTTP 合同、限流、缓存、错误回退和无 Token 模式；测试只使用本地模拟服务。
- 净资产、预算、投资和负债统计使用手算固定值验证。

### 前端

- Vitest/RTL：注册、登录、邀请、权限隐藏、侧栏折叠、表单、行情过期状态。
- Playwright：所有者完整流程、管理员流程、成员权限流程、桌面 1440×900 和手机 390×844。
- 无阻断控制台错误、无水平溢出、键盘和焦点恢复通过。

### 构建与运行

- `./mvnw test` 同时执行 Java 和前端单元测试。
- `./mvnw package` 生成包含 React 静态资源的单一可执行 JAR。
- `start-local.cmd -NoBrowser -Smoke` 在 GitHub Windows runner 中完成迁移、启动、探活和停止。
- 使用第一阶段真实 H2 副本验证原地迁移、数据保留和重启。

## 14. 非目标

- 不提供证券交易、委托或自动投资建议。
- 不保证实时或盘中行情；仅同步 Tushare A 股日线收盘数据。
- 不提供银行、支付宝、微信同步。
- 不提供 OCR、邮箱验证码、密码邮件找回和原生 App。
- 不拆分微服务，不引入 Redis、MQ 或外部任务调度平台。

