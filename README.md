# 家账：家庭收支管理系统

一个用于课程演示的 Spring Boot 家庭收支 Web 应用。它把每笔收入和支出放在同一条家庭现金流账轨上，并提供成员与分类管理、筛选、统计、规则分析和 CSV 导出。

## 运行

前提：Java 17。

### Windows 一键启动

在 CMD 或 PowerShell 中进入项目目录，然后运行：

```bat
start-local.cmd
```

脚本会检查 Java 版本和 8080 端口，使用项目自带的 Maven Wrapper 启动 Spring Boot，并在服务就绪后自动打开浏览器。停止应用时，在启动终端中按 `Ctrl+C`。

如果 8080 端口已被其他程序使用，可以指定另一个端口：

```bat
start-local.cmd -Port 8090
```

### macOS / Linux

```bash
./mvnw spring-boot:run
```

打开 [http://127.0.0.1:8080](http://127.0.0.1:8080)，使用本地演示账户登录：

- 用户名：`demo`
- 密码：`demo1234`

首次启动会写入演示家庭及 2026 年 6—9 月账目。界面默认打开浏览器本地时间对应的当前月份；演示时可在账期控件中切换到 `2026-09`。默认数据文件是 `data/family-finance.mv.db`（H2 还可能创建同目录的跟随文件）。

## 测试

```bash
./mvnw test
```

测试使用独立数据库；不会读取或写入 `data/family-finance.mv.db`。其中的端到端冒烟测试以真实随机端口、HTTP 会话 Cookie 与 CSRF 令牌验证完整流程。

## 安全地重置本地数据

先停止应用，再将整个 `data/` 目录移动到一个带日期的备份目录，例如 `data-backup-2026-09-02/`。确认备份可恢复后，再启动应用；系统会创建全新的演示数据。

不要在应用运行时删除数据库文件，也不要把重置命令用于其他目录。

## 结构

- `src/main/java/com/familyfinance/auth`：登录、会话与当前用户。
- `src/main/java/com/familyfinance/household`：家庭与成员。
- `src/main/java/com/familyfinance/category`：收入和支出分类。
- `src/main/java/com/familyfinance/transaction`：收支记录与筛选。
- `src/main/java/com/familyfinance/reporting`：看板、规则分析与 CSV。
- `src/main/resources/static`：原生 HTML、CSS、JavaScript 单页界面。

## API 概览

- `GET /api/csrf`、`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/session`
- `GET|POST /api/members`、`PATCH|DELETE /api/members/{id}`
- `GET|POST /api/categories`、`PATCH|DELETE /api/categories/{id}`
- `GET|POST /api/transactions`、`GET|PATCH|DELETE /api/transactions/{id}`
- `GET /api/dashboard?month=YYYY-MM`、`GET /api/analysis?month=YYYY-MM`
- `GET /api/export.csv`（接受收支列表的筛选参数）

所有写操作都需要先从 `/api/csrf` 取得令牌，并在 `X-XSRF-TOKEN` 请求头中发送。浏览器界面会在每次写入后重新读取服务器的权威状态。写入、CSV 导出或退出遇到 401 时，界面会统一清除本地会话标记并返回登录页。

API 响应通过 `X-Request-ID` 返回请求关联 ID；未预期的服务器异常只向客户端返回通用 500 内容，并在服务端以该 ID 记录堆栈，便于排查而不记录请求体、密码或 CSRF 令牌。

## Sprint 1 不包含

公开注册、密码找回、复杂家庭角色、预算、周期账单、附件、OCR、银行或支付平台同步、投资/行情、原生 App、推送通知和 AI 对话均不在本 Sprint 范围内。
