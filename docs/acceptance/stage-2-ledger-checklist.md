# 第二阶段：账本、预算与周期账单验收清单

## 自动化主流程

`StageTwoLedgerSmokeTest` 使用 `target/` 之外的 JUnit 临时目录创建独立文件型 H2，并启动真实随机端口 HTTP 服务。它不读取或修改本地正式库 `data/family-finance.mv.db`。

| 验收条件 | 可重复证据 |
| --- | --- |
| Flyway 新库迁移完整 | 首次启动后读取 Flyway 历史，成功版本必须依次为 V1、V2、V3、V4、V5、V6。 |
| Plan 1 登录与安全边界兼容 | 未登录读取账本返回 401；邮箱 `CREATE` 注册得到 `OWNER`；登录使用真实会话 Cookie；缺少 CSRF 请求头的账户写入返回 403。 |
| 注册默认值与账户 API | `CREATE` 家庭自动产生一个默认账户；所有者再通过真实 HTTP 创建验收银行卡并记录其 ID。 |
| 两级支出分类 | 通过 HTTP 创建“居家服务”一级分类和“物业费”二级分类；树投影核对父子 ID、层级和类型。 |
| 预算与不可变修订 | 为二级分类创建 500.00 元月预算，再用版本号修改为 1000.00 元；修订记录必须保留旧值、新值和修改人 ID。 |
| 固定时钟生成待确认项 | 测试范围内注入上海时区固定时钟，由仅存在于测试上下文的受保护触发器调用生产 `RecurringService`；月度规则只生成一个 2026-09-03 `PENDING` 项，不等待系统时间。 |
| 确认幂等且形成双向关联 | 同一待确认项连续确认两次，返回相同流水 ID；数据库必须恰有一笔 `RECURRING` 流水，且流水 `source_id` 与发生项 `confirmed_transaction_id` 互相指向。 |
| 预算只计已确认支出 | 确认前分类预算已用金额为 0.00；确认后为 123.45，剩余 876.55。 |
| 完整停机与同库重启 | 首个 Spring 上下文、HTTP 服务器、JPA 和连接池完全关闭后，以同一 H2 文件启动第二个随机端口服务并重新登录。 |
| 重启后业务状态未丢失 | 逐项核对家庭/用户、成员、账户、父子分类、预算、修订、周期规则、已确认发生项、流水及预算使用额的原 ID 与状态。 |

运行主流程：

```bash
./mvnw -q -Dtest=StageTwoLedgerSmokeTest test
```

## 完整回归门槛

```bash
./mvnw test
node --check src/main/resources/static/app.js
node --test src/test/javascript/*.test.js
/bin/sh scripts/unix-startup-gates.sh
git diff --check
```

同时保留以下既有证据：

- `StageTwoFoundationSmokeTest`：第一阶段文件库升级、旧演示登录、创建/邀请加入家庭和成员越权拒绝。
- `SprintOneSmokeTest`：第一阶段真实 HTTP 收支、筛选、看板、分析、CSV 和退出流程。
- `FlywayFreshDatabaseTest`、`FlywayStageOneUpgradeTest`、`LedgerStageTwoMigrationTest`、`BudgetRevisionMigrationTest`：新库与旧库迁移以及 V1–V6 结构/数据约束。
- `scripts/unix-startup-gates.sh`：macOS/Linux 的 10 个隔离启动、备份、恢复和失败保护场景。

Windows 启动仍必须由 Windows runner 单独执行 `scripts/windows-cmd-quote-regression.ps1` 与 `scripts/windows-startup-gates.ps1`；本机 macOS 的成功不能替代 Windows 运行证据。

## 当前界面边界

当前交付的原生 HTML/JavaScript 界面可选择和筛选账户，也可维护两级分类；它尚未提供独立账户管理、预算或周期账单页面。上述账本、预算和周期规则能力当前以 API 与自动化测试为验收入口。React + TypeScript + Semi Design 工作区属于后续前端计划，本清单不声明其已经实现。
