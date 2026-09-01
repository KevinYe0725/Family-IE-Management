# 家庭收支管理系统 MVP 设计

## 目标

在课程实践周期内交付一个真实可运行的家庭收支 Web 系统。第一版必须使用 Java + Spring Boot，并具备真实后端、数据库持久化、登录鉴权、家庭数据隔离、错误反馈和可重复验收，不做仅供展示的静态页面。

## 技术基线

- Java 17（本机已安装）。
- Spring Boot 4.1.1（当前稳定版，最低要求 Java 17）。
- Maven Wrapper，固定 Maven 3.9.x；本机无需预装 Maven。
- Spring Web MVC、Spring Data JPA、Spring Security、Bean Validation。
- H2 文件数据库用于第一版持久化；测试使用独立内存数据库。
- JUnit 5、AssertJ、MockMvc 和 Spring Security Test。
- 前端由 Spring Boot 静态资源目录直接托管，使用原生 HTML、CSS、JavaScript 与 SVG，不依赖 CDN。

官方兼容依据：

- Spring Boot 项目页：https://spring.io/projects/spring-boot
- Spring Boot 系统要求：https://docs.spring.io/spring-boot/system-requirements.html

## 用户与使用场景

- 主要用户：需要共同记录和查看家庭财务的普通家庭。
- 课程场景：教师使用演示账户登录，新增或修改一笔收支，筛选记录，查看统计与分析，并重启服务验证数据仍然存在。
- 第一版账户模型：一个账户属于一个家庭；一个家庭可包含多个成员。系统保留多家庭数据隔离能力，但不开放注册和复杂角色权限。

## Sprint 1 功能范围

### 必须交付

1. 登录与会话
   - 用户名、密码登录和退出。
   - 密码使用 BCrypt 哈希，登录成功后使用 Spring Security 的服务端会话。
   - 未登录访问 API 返回统一结构的 401 错误。
   - Cookie 型 CSRF 令牌保护写操作。

2. 家庭成员
   - 查看、新增、修改和删除家庭成员。
   - 已被收支记录使用的成员不能删除，系统说明原因。

3. 收支分类
   - 区分收入与支出分类。
   - 提供默认分类，并允许新增、修改和删除自定义分类。
   - 已被收支记录使用的分类不能删除。

4. 收支记录
   - 新增、查看、编辑和删除收入或支出。
   - 字段：类型、金额、日期、家庭成员、分类、商家、地点和备注。
   - 金额以整数分存入数据库，API 使用两位小数的字符串，避免浮点误差。
   - 按月份、日期范围、类型、成员、分类和关键字筛选。

5. 统计看板
   - 展示所选月份的总收入、总支出和结余。
   - 展示按日收支趋势、支出分类占比和成员支出对比。
   - 首页默认展示当前月份；演示数据集中在 2026 年 9 月，并允许切换月份。

6. 规则分析
   - 将本月支出与前三个有数据月份的平均值比较。
   - 识别占比最高的支出分类和最大单笔支出。
   - 输出简短结论；数据不足时明确说明，不伪造结论。

7. 数据导出与演示支持
   - 按当前筛选条件导出 UTF-8 CSV。
   - 首次启动自动创建演示家庭、成员、分类和 2026 年 6—9 月演示账目。
   - 登录页显示本地演示账户 `demo / demo1234`；数据库只保存 BCrypt 哈希。

### 本 Sprint 不做

- 公开注册、找回密码和复杂家庭角色权限。
- 预算、周期账单、附件、OCR、银行或支付平台同步。
- 房产、贷款、投资、股票和实时行情。
- 手机原生 App、消息推送和 AI 对话。

## 架构

系统采用单体分层架构：

- Web 层：REST 控制器、DTO、统一异常处理、Spring Security。
- 应用层：成员、分类、收支、统计和分析服务，负责事务及家庭范围校验。
- 持久层：Spring Data JPA Repository。
- 数据层：H2 文件数据库。
- 前端层：Spring Boot 托管的响应式单页界面，通过 REST API 操作数据。

启动命令为 `./mvnw spring-boot:run`，默认监听 `127.0.0.1:8080`。生产数据库默认写入 `./data/family-finance.mv.db`；测试配置使用随机命名的 H2 内存数据库。

## Java 包与文件边界

基础包为 `com.familyfinance`：

- `FamilyFinanceApplication`：应用入口。
- `config`：安全、Jackson、静态路由和种子配置。
- `auth`：登录结果、当前用户和会话接口。
- `household`：家庭、用户和成员实体/仓储/服务/控制器。
- `category`：分类实体/仓储/服务/控制器。
- `transaction`：收支实体、查询规格、仓储、服务和控制器。
- `reporting`：统计、分析和 CSV 导出。
- `shared`：API 响应、异常类型、全局异常处理和金额/日期工具。
- `src/main/resources/static`：浏览器前端。

任何 Java 文件超过约 250 行时优先按职责拆分，不建立“万能 Service”或“万能 Controller”。

## 数据模型

- `households(id, name, created_at)`
- `app_users(id, household_id, username, password_hash, created_at)`
- `family_members(id, household_id, name, role_label, created_at)`
- `categories(id, household_id, kind, name, color, is_default, created_at)`
- `financial_transactions(id, household_id, member_id, category_id, kind, amount_cents, occurred_on, merchant, location, note, created_at, updated_at)`

所有成员、分类、收支和聚合查询都必须按当前用户的 `household_id` 限定。外键开启，金额必须大于零，日期使用 ISO `YYYY-MM-DD`。

## API 合同

成功响应统一为 `{ "data": ... }`，失败响应统一为：

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "请检查输入内容",
    "fields": { "amount": "金额必须大于 0" }
  }
}
```

主要端点：

- `GET /api/csrf`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/session`
- `GET|POST /api/members`
- `PATCH|DELETE /api/members/{id}`
- `GET|POST /api/categories`
- `PATCH|DELETE /api/categories/{id}`
- `GET|POST /api/transactions`
- `GET|PATCH|DELETE /api/transactions/{id}`
- `GET /api/dashboard?month=YYYY-MM`
- `GET /api/analysis?month=YYYY-MM`
- `GET /api/export.csv`，接受与收支列表相同的筛选参数

## 交互与视觉方向

产品的核心任务是让家庭快速看懂“钱从哪里来、花到哪里去、哪里出现变化”。视觉采用“现代家庭账本”而非通用后台模板：

- 深墨蓝 `#17324D`、瓷白 `#F3F7F8`、玉石绿 `#3B7A72`、柿子红 `#D8664B`、黄铜色 `#C49A4A`。
- 中文标题使用 `Songti SC`，正文和数据使用 `PingFang SC`/系统无衬线字体。
- 桌面端使用账本式侧栏，移动端转为顶部和底部操作区。
- 标志性组件是“家庭现金流账轨”：在同一条时间轨上对照每日收入与支出。
- 所有交互支持键盘焦点、清楚的空状态和具体错误提示，并尊重 `prefers-reduced-motion`。

## 错误与安全边界

- 请求体使用 Bean Validation；无效 JSON 和参数返回 400/422 风格的结构化错误。
- 所有写操作验证类型、金额、日期、成员和分类归属。
- 会话 Cookie 设置 HttpOnly、SameSite=Lax；生产配置可开启 Secure。
- 登录失败统一提示，不泄露用户名是否存在。
- CSRF 令牌通过可读 Cookie 和 `/api/csrf` 提供，前端写请求发送 `X-XSRF-TOKEN`。
- 服务器不记录密码、CSRF 令牌或完整敏感请求体。
- 未知 API 返回结构化 404；未处理异常返回通用 500 并记录请求 ID。

## Sprint 1 验收标准

1. `./mvnw test` 全部通过，测试不读写生产数据库。
2. `./mvnw spring-boot:run` 后可在 `http://127.0.0.1:8080` 使用演示账户登录。
3. 新增、编辑、筛选和删除收支均通过真实 REST API 与 JPA/H2 完成。
4. 重启服务后新增数据仍然存在。
5. 成员和分类不能跨家庭访问；被账目引用时不能删除。
6. 看板数字与账目列表可核对，结余等于收入减支出。
7. 分析结论来自规则和真实账目；无数据或历史不足时显示边界说明。
8. CSV 导出包含当前筛选结果并能正确显示中文。
9. 桌面和移动视口完成真实浏览器验收；无阻断性控制台错误、布局溢出或不可操作控件。

