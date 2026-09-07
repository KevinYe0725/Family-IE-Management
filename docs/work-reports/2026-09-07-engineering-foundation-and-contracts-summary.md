# 工程骨架、数据模型与接口测试工作总结

> 日期：2026-09-07
> 用途：整理本阶段涉及工程骨架、数据模型细化、前后端同步接口约定及测试用例设计的工作内容，作为当日工作日报素材。

## 一、工作目标

围绕家庭财务管理系统第二阶段建设，完成从基础工程、身份与家庭模型，到账本、资产投资、贷款及前端工作区的统一设计，并通过版本化数据库、稳定 API 契约和自动化测试保证各模块可以持续集成。

核心原则如下：

- 后端采用 Java 17 + Spring Boot + Spring Data JPA + Spring Security。
- 数据库使用 Flyway 管理版本迁移，既支持新库初始化，也支持第一阶段文件库原地升级。
- 所有家庭数据按当前用户的家庭成员关系和角色做隔离，不能仅依赖客户端传入的家庭 ID。
- 金额统一以正整数分存储，接口返回规范化的两位小数字符串；数量、利率等需要小数精度的领域使用明确精度的 `BigDecimal`。
- 业务汇总由服务端计算，前端只负责请求、展示和交互，不在浏览器重复推导财务结果。
- 重要写操作遵循 RED -> GREEN -> REFACTOR，先写失败测试，再实现最小行为，最后做回归和重构。

## 二、工程骨架建设

### 1. 后端应用骨架

- 建立 Spring Boot 单体应用，统一基础包为 `com.familyfinance`。
- 固化 Maven Wrapper、Java 17、Spring Boot、Web MVC、JPA、Security、Validation、H2、JUnit 5 和 MockMvc 的技术栈。
- 约定默认启动方式为 `./mvnw spring-boot:run`，默认地址为 `http://127.0.0.1:8080`。
- 生产环境使用文件型 H2；测试环境使用随机内存 H2 或 JUnit 临时目录，避免污染正式 `data/`。
- 关闭默认 SQL 噪声，统一 UTC/时区配置，并设置 HttpOnly、SameSite=Lax 的会话 Cookie。
- 通过启动脚本和迁移前备份机制保护既有数据库，备份包含全部 H2 主文件、伴随文件、清单、哈希和恢复说明。

### 2. 前端工作区骨架

- 形成 `frontend/` 下的 React + TypeScript + Vite 工作区，并纳入 Maven 生命周期。
- Maven 负责固定 Node/npm 工具链、执行 `npm ci`、前端测试和生产构建，将构建产物打包到 Spring Boot 静态资源目录。
- 建立统一 API client、认证上下文、路由、应用轨道、可隐藏模块侧栏、移动端抽屉和响应式工作区布局。
- 使用统一的页面壳、数据面板和查询状态组件，月度消费饼图插件接入既有插件注册机制。
- 月度消费饼图前端已完成 `recharts` 接入、插件页面、金额格式化、无效数据过滤、空状态和响应式布局；页面入口为 `/workspace/extensions/monthly-pie-chart`。

## 三、数据模型细化与演进

### 1. 身份与家庭基础模型

- 第一阶段模型包括家庭、用户、家庭成员、分类和财务流水。
- 第二阶段通过 Flyway V1/V2 引入邮箱身份、家庭成员关系、角色、邀请和家庭状态。
- 邮箱统一 trim + lower-case，并保持全局唯一；密码限制为 8-72 个字符，只保存 BCrypt 哈希。
- `OWNER`、`ADMIN`、`MEMBER` 权限集中由成员关系服务解析；旧的 `demo / demo1234` 登录保持兼容，并映射到 `demo@local.family`。
- 邀请只持久化 SHA-256 Token 哈希，明文 Token 只在创建时返回一次；邀请消费使用锁和唯一约束保证并发下最多成功一次。

### 2. 账本、账户、分类和预算

- V3 增加金融账户、分类父子层级、预算、预算修订、周期规则和待确认发生项。
- 第一阶段已有流水在迁移时补齐默认账户和创建者，不改变原有金额、日期、分类和成员数据。
- 分类最多两级，父子分类类型必须一致；接口返回扁平数据和确定性树投影，不直接暴露 JPA 子实体。
- 预算使用量不落库，按家庭、月份、支出类型和作用域从已确认流水实时计算。
- 预算修订保留旧值、新值、修改人和时间，形成不可变审计历史。
- 周期规则只生成唯一待确认项；确认时在同一事务内创建一笔流水并互相记录来源关系，重复确认返回同一流水。

### 3. 资产、投资和行情

- V4 增加资产、资产估值、投资账户、证券、投资交易、行情快照和手工价格覆盖模型。
- 资产与投资分离建模，估值和交易作为历史事实保存，持仓、平均成本和收益均由服务端根据历史记录计算。
- A 股代码限制为 `######.SH`、`######.SZ`、`######.BJ`，交易类型覆盖 BUY、SELL、DIVIDEND、FEE。
- `MarketQuoteProvider` 抽象隔离 Tushare；外部行情只读、日收盘、HTTPS 调用，Token 只从环境变量读取。
- Token 缺失、无效或权限不足不能阻止手工资产和投资操作；行情刷新支持缓存、最后一次有效价格、过期标记和手工价格回退。

### 4. 贷款、提醒和综合报告

- V5 增加贷款、还款计划、提醒和净资产快照，并为自然键和幂等操作建立唯一约束。
- 等额本息、等额本金等还款计算先通过纯函数完成，再由服务持久化计划，避免把计算逻辑埋在控制器中。
- 确认还款时锁定贷款和还款计划，在一个事务内创建流水、减少本金、标记计划状态并完成提醒；重复请求不能产生重复流水。
- 净资产报告只计算一次现金账户、非现金资产、投资持仓和贷款负债，避免跨模块 join 造成重复统计。
- 快照使用 `(household_id, snapshot_on)` 自然键，重复生成更新同一记录而不是新增重复历史。

## 四、前后端同步接口约定

### 1. 统一响应与错误

- 成功响应统一使用 `{"data": ...}`。
- 错误响应统一使用 `{"error":{"code","message","fields"?}}`。
- 未登录 API 返回标准 `401` 和 `AUTH_REQUIRED`；无权限返回 `403`；资源不存在或跨家庭访问统一按资源边界返回 `404`，避免泄露其他家庭数据存在性。
- 字段校验错误通过 `fields` 返回，前端 API client 负责映射到表单字段。
- 前端集中处理请求 ID、JSON 解析、CSRF、一次性会话过期和错误提示，业务页面不重复实现请求逻辑。

### 2. 认证、CSRF 与家庭上下文

- `GET /api/csrf` 获取 CSRF Token，所有写请求携带 `X-XSRF-TOKEN`。
- `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/session` 保持稳定，并增加注册、改密、邀请加入和家庭管理接口。
- Web 层不直接信任客户端家庭 ID；通过当前认证主体和有效 membership 解析家庭、用户和角色上下文。
- 账户、分类、流水、资产、投资、贷款、提醒和报告接口均要求家庭范围过滤。

### 3. 领域 API 分组

- 身份与家庭：`/api/auth/*`、`/api/session`、`/api/family/*`。
- 账本：`/api/accounts`、`/api/categories`、`/api/transactions`、`/api/budgets`、`/api/recurring-rules`、`/api/recurring-occurrences`。
- 资产与投资：`/api/assets`、`/api/investment-accounts`、`/api/securities/search`、`/api/investment-trades`、`/api/portfolio`。
- 行情：`/api/market-quotes/refresh`、`/api/securities/{id}/manual-price`。
- 贷款与报告：`/api/loans`、`/api/loan-installments/{id}/confirm`、`/api/notifications`、`/api/net-worth`、`/api/debt-analysis`。
- 所有创建、修改和确认动作由服务端校验权限、状态、归属和幂等条件，前端隐藏按钮不能代替后端授权。

## 五、测试用例设计与验证策略

### 1. 测试分层

- **纯单元测试**：金额计算、持仓平均成本、贷款摊销、日期边界、分类层级和预算统计。
- **持久化/迁移测试**：新库 V1 起步、第一阶段库升级、V2-V5 结构、约束、数据回填和失败保护。
- **MockMvc/API 测试**：登录、CSRF、注册、家庭隔离、角色权限、CRUD、错误 envelope 和幂等确认。
- **真实 HTTP Smoke 测试**：随机端口、真实 Cookie 会话、真实 CSRF、文件型 H2、停机重启和状态持久化。
- **前端测试**：Vitest、React Testing Library、TypeScript 类型检查、Vite 构建、响应式导航和可访问性状态。
- **启动与平台测试**：Unix 启动门禁、备份/恢复/迁移失败保护，以及 Windows 独立脚本和远端 Runner 验证。

### 2. 重点测试场景

- 测试数据库不会写入正式 `data/`；迁移前备份失败、版本过旧、版本过新、失败迁移和人工修复重试均有隔离场景。
- 测试邮箱大小写、空格、重复注册、密码边界、邀请码过期/撤销/耗尽和并发消费。
- 测试跨家庭账户、分类、流水、资产、证券、贷款访问均被隔离。
- 测试 MEMBER 只读共享资产/投资/贷款，但可以记录日常收支及确认分配给自己的事项。
- 测试周期发生项、贷款还款、行情刷新、提醒和净资产快照的重复调用不会重复写入。
- 测试预算只计目标月份的已确认支出，不计收入、其他月份和未确认项。
- 测试行情无 Token、上游权限错误、429/5xx、过期价格和手工价格回退。
- 测试前端 1440x900 和 390x844 无横向溢出，抽屉可关闭、焦点恢复、无重复可访问名称和未捕获异常。

### 3. 已记录的验证结果

本地会话中明确记录的月度消费饼图插件验证已通过：

- `npm run typecheck`
- 插件测试 2 个用例通过
- `npm run build`
- `git diff --check`

阶段验收清单还记录了 React 类型检查、生产构建、工作区导航、账本/预算/周期规则、资产/投资、贷款/提醒/报告及本地浏览器尺寸检查的通过项；Windows 和 Ubuntu Runner 仍属于需要远端执行的证据边界，不能用本机结果替代。

## 六、当日工作日报精简版

### 今日完成

1. 完成家庭财务系统工程骨架和前后端工作区的统一规划与落地，明确 Spring Boot、Flyway、React/Vite、Maven 集成及测试环境隔离方式。
2. 细化身份、家庭成员、权限、账本、账户、两级分类、预算、周期账单、资产、投资、行情、贷款、提醒和综合报告等领域模型，并设计 V1-V5 版本化迁移与历史数据回填策略。
3. 统一前后端接口规范，包括 `data/error` 响应 envelope、CSRF、会话、家庭上下文、角色授权、跨家庭隔离、金额格式和错误字段映射。
4. 建立以测试先行为核心的验证体系，覆盖纯计算、迁移、API、真实 HTTP、前端组件、构建、启动备份恢复和幂等并发场景。
5. 完成月度消费饼图插件前端接入，接入既有页面壳、数据面板和查询状态，补充金额格式化、空状态、异常数据过滤和响应式展示，并通过类型检查、插件测试、构建及差异检查。

### 产出价值

- 为后续模块开发提供了统一目录结构、数据边界和接口边界，减少各模块自行约定导致的集成偏差。
- 通过 Flyway、家庭权限和幂等约束保护历史数据与并发写入，提升系统可迁移性和运行安全性。
- 通过服务端计算和集中 API client，保证前后端对金额、汇总、会话过期、CSRF 和错误处理的理解一致。
- 通过分层测试和真实重启验收，将“能编译”提升为“能迁移、能隔离、能恢复、能重复执行”。

### 后续工作

- 持续执行各阶段 Smoke Test 和完整 Maven/前端回归。
- 补齐 Windows、Ubuntu 远端 Runner 证据，并在日报中注明平台、提交 SHA 和工作流结果。
- 按已定义 API 契约继续完善各领域页面和跨模块集成，避免前端重新推导后端业务规则。
- 对未配置 `TUSHARE_TOKEN` 的环境持续验证手工价格和最后有效行情回退路径。

## 七、参考资料

- [MVP 工程与领域计划](../superpowers/plans/2026-09-01-family-finance-mvp.md)
- [第二阶段身份与家庭基础计划](../superpowers/plans/2026-09-02-stage-2-foundation-identity.md)
- [第二阶段账本、预算与周期账单计划](../superpowers/plans/2026-09-02-stage-2-ledger-budget-recurring.md)
- [第二阶段资产、投资与行情计划](../superpowers/plans/2026-09-02-stage-2-assets-investments-market.md)
- [第二阶段贷款、提醒与报告计划](../superpowers/plans/2026-09-02-stage-2-loans-notifications-reporting.md)
- [第二阶段 React 工作区计划](../superpowers/plans/2026-09-02-stage-2-react-feishu-frontend.md)
- [基础身份验收清单](../acceptance/stage-2-foundation-checklist.md)
- [账本验收清单](../acceptance/stage-2-ledger-checklist.md)
- [资产投资验收清单](../acceptance/stage-2-assets-investments-checklist.md)
- [贷款报告验收清单](../acceptance/stage-2-loans-reporting-checklist.md)
- [前端验收清单](../acceptance/stage-2-frontend-checklist.md)
