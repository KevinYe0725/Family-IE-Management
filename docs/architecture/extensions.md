# 本体与内置插件：第一版

现有账本、预算、资产、贷款、认证和家庭权限保持本体职责。新增年度统计作为可信内置插件，随应用构建部署，配置变更需重启。

开发者接入与服务器操作见 [插件开发、提交与部署指南](plugin-developer-guide.md)。

## 接入协议

- 后端实现 `extension.FinancePlugin`；启动时 `PluginRegistry` 收集 Bean，检查唯一 ID、路由和协议版本。`GET /api/plugins` 返回启用插件清单。
- 插件页面路径固定 `/workspace/extensions/{id}`，业务 API 使用 `/api/plugins/{id}`。
- 前端 `extensions/registry.tsx` 显式登记受信任的懒加载组件，只有与服务器清单匹配的插件才显示菜单和页面。未知插件或不兼容协议不会加载。
- 页面使用已有登录会话和 API 客户端。插件异常由页面错误边界处理；清单读取失败不会阻塞本体页面。
- `capabilities` 是能力声明，不是沙箱授权。当前只支持团队审核过的进程内代码，不支持第三方 JAR、远程 JS 或热卸载。

## 年度统计

`plugins.annualstats` 只依赖 `LedgerReadPort`，不直接引用 Repository 或实体。本体 `transaction.LedgerReadAdapter` 从认证上下文获取家庭 ID，按稳定顺序分批读取流水，用 BigInteger 累计月度金额。插件不能通过请求参数选择别的家庭。

GET `/api/plugins/annual-stats?year=2026` 返回全年合计和完整 12 个月数据。统计口径是已记账流水（不包含尚未确认的周期发生项）；结余为收入减支出，不等于净资产。月平均按全年合计除以 12，空月份按零，四舍五入至分。有效年份 1900—2100。

插件默认启用。使用 Spring 配置 `app.plugins.annual-stats.enabled=false` 或启动参数 `--app.plugins.annual-stats.enabled=false` 关闭；关闭后清单不含该插件、接口返回 404、本体仍运行。无需新增数据库表或迁移。

## 后续扩展

新增插件时实现描述接口、声明启动条件、通过本体公开端口访问业务数据，并在前端注册受信任组件。菜单与工作区路由入口已统一接入，无需逐个修改本体页面。总览卡片插槽、写入能力、插件独立表和迁移等在出现实际需求后增加。

插件源码不应依赖 `transaction`、`ledger` 等本体内部包。当前为编译期约定，并非操作系统级隔离。同进程插件仍可能影响服务资源。
