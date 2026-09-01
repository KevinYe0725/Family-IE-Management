# 家庭收支管理系统 MVP 设计

## 目标

在课程实践周期内交付一个真实可运行的家庭收支 Web 系统。第一版必须具备后端、SQLite 持久化、登录鉴权、家庭数据隔离、错误反馈和可重复验收，不做仅供展示的静态页面。

## 用户与使用场景

- 主要用户：需要共同记录和查看家庭财务的普通家庭。
- 课程场景：教师能够使用演示账户登录，新增或修改一笔收支，筛选记录，查看统计与分析，并重启服务验证数据仍然存在。
- 第一版账户模型：一个账户属于一个家庭；一个家庭可包含多个成员。系统保留多家庭隔离能力，但不开放注册和复杂角色权限。

## Sprint 1 功能范围

### 必须交付

1. 登录与会话
   - 用户名、密码登录和退出。
   - 密码使用 `scrypt` 哈希，浏览器只保存 HttpOnly 会话 Cookie。
   - 未登录访问 API 返回结构化 `401` 错误。

2. 家庭成员
   - 查看、新增、修改和删除家庭成员。
   - 已被收支记录使用的成员不能删除，系统给出可理解的原因。

3. 收支分类
   - 区分收入与支出分类。
   - 提供默认分类，并允许新增、修改和删除自定义分类。
   - 已被收支记录使用的分类不能删除。

4. 收支记录
   - 新增、查看、编辑和删除收入或支出。
   - 字段：类型、金额、日期、家庭成员、分类、商家、地点和备注。
   - 金额以整数分存储，API 使用十进制元字符串，避免浮点误差。
   - 可按月份、日期范围、类型、成员、分类和关键字筛选。

5. 统计看板
   - 展示所选月份的总收入、总支出和结余。
   - 展示按日收支趋势、支出分类占比和成员支出对比。
   - 首页默认展示当前月份；演示数据集中在 2026 年 9 月，并提供月份切换。

6. 规则分析
   - 将本月支出与前三个有数据月份的平均值比较。
   - 识别占比最高的支出分类和最大单笔支出。
   - 输出面向用户的简短结论；数据不足时明确说明，不伪造结论。

7. 数据导出与演示支持
   - 按当前筛选条件导出 UTF-8 CSV。
   - 首次启动自动创建演示家庭、成员、分类和 2026 年 6—9 月演示账目。
   - 登录页清楚显示本地演示账户 `demo / demo1234`；数据库只保存密码哈希。

### 本 Sprint 不做

- 公开注册、找回密码和复杂家庭角色权限。
- 预算、周期账单、附件、OCR、银行或支付平台同步。
- 房产、贷款、投资、股票和实时行情。
- 手机原生 App、消息推送和 AI 对话。

## 技术设计

### 架构

采用零外部运行依赖的 Node.js 22 单体应用：

- `node:http` 提供静态资源与 REST API。
- `node:sqlite` 提供文件型 SQLite 持久化。
- `node:crypto` 提供密码哈希、会话令牌和安全比较。
- 浏览器端使用原生 ES Modules、HTML 和 CSS；统计图表使用可访问的 SVG/CSS 绘制，不依赖 CDN。
- `node:test` 运行单元和 HTTP 集成测试。

启动命令为 `npm start`，默认监听 `127.0.0.1:4173`。数据库默认写入 `data/family-finance.db`；测试使用独立临时数据库。

### 文件边界

- `src/config.js`：环境配置和默认值。
- `src/security.js`：密码与会话令牌工具。
- `src/database.js`：建表、迁移、演示数据和仓储操作。
- `src/analytics.js`：与 HTTP 无关的统计和分析计算。
- `src/app.js`：路由、鉴权、输入校验和 JSON/CSV 响应。
- `src/server.js`：启动和优雅关闭。
- `public/index.html`：应用语义结构和登录/主界面容器。
- `public/styles.css`：视觉系统、响应式与可访问性状态。
- `public/ui-state.js`：金额、日期、查询参数和图表数据等纯函数。
- `public/app.js`：页面状态、API 调用、渲染和交互。

### 数据模型

- `households(id, name, created_at)`
- `users(id, household_id, username, password_hash, created_at)`
- `sessions(id_hash, user_id, expires_at, created_at)`
- `members(id, household_id, name, role_label, created_at)`
- `categories(id, household_id, kind, name, color, is_default, created_at)`
- `transactions(id, household_id, member_id, category_id, kind, amount_cents, occurred_on, merchant, location, note, created_at, updated_at)`

所有成员、分类、收支和聚合查询都必须带 `household_id` 条件。外键开启，金额必须大于零，日期使用 `YYYY-MM-DD`。

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

- `POST /api/auth/login`
- `POST /api/auth/logout`
- `GET /api/session`
- `GET|POST /api/members`
- `PATCH|DELETE /api/members/:id`
- `GET|POST /api/categories`
- `PATCH|DELETE /api/categories/:id`
- `GET|POST /api/transactions`
- `GET|PATCH|DELETE /api/transactions/:id`
- `GET /api/dashboard?month=YYYY-MM`
- `GET /api/analysis?month=YYYY-MM`
- `GET /api/export.csv`，接受与收支列表相同的筛选参数

## 交互与视觉方向

产品的单一任务是让家庭快速看懂“钱从哪里来、花到哪里去、哪里出现变化”。视觉采用“现代家庭账本”而非通用后台模板：

- 深墨蓝 `#17324D`、瓷白 `#F3F7F8`、玉石绿 `#3B7A72`、柿子红 `#D8664B`、黄铜色 `#C49A4A`。
- 中文标题使用 `Songti SC`，正文和数据使用 `PingFang SC`/系统无衬线字体。
- 桌面端使用账本式侧栏，移动端转为顶部和底部操作区。
- 标志性组件是“家庭现金流账轨”：在同一条时间轨上对照每日收入与支出，而不是堆叠通用渐变卡片。
- 所有交互支持键盘焦点、清楚的空状态和具体错误提示；尊重 `prefers-reduced-motion`。

## 错误与安全边界

- 请求体限制为 1 MiB；无效 JSON 返回 `400`。
- 所有写操作验证类型、金额、日期、成员和分类归属。
- 会话 Cookie 使用 `HttpOnly; SameSite=Lax; Path=/`，生产环境可通过配置启用 `Secure`。
- 登录失败只返回统一提示，不泄露用户名是否存在。
- 服务器不记录密码、会话原文或完整敏感请求体。
- 未知路由返回结构化 `404`；未处理异常返回通用 `500` 并在服务端记录请求 ID。

## Sprint 1 验收标准

1. `npm test` 全部通过，且测试使用临时数据库。
2. `npm start` 后浏览器可在 `http://127.0.0.1:4173` 使用演示账户登录。
3. 新增、编辑、筛选和删除收支均通过真实 API 与 SQLite 完成。
4. 重启服务后新增数据仍然存在。
5. 成员和分类不能跨家庭访问；被账目引用时不能删除。
6. 看板数字与账目列表可核对，结余等于收入减支出。
7. 分析结论来自规则和真实账目；无数据或历史不足时显示边界说明。
8. CSV 导出包含当前筛选结果并能正确显示中文。
9. 桌面和移动视口完成真实浏览器验收；无阻断性控制台错误、布局溢出或不可操作控件。

