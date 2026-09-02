# 第二阶段：身份与家庭基础验收清单

| 验收条件 | 可重复证据 |
| --- | --- |
| 第一阶段数据可原地升级 | `StageTwoFoundationSmokeTest` 从第一阶段 H2 副本迁移后验证 `demo@local.family`、`OWNER` 与家庭身份流程；`FlywayStageOneUpgradeTest` 验证原有 12 条账目在迁移后仍保留。 |
| 旧演示登录保持兼容 | 登录 `demo / demo1234`，再请求 `GET /api/session`，确认邮箱为 `demo@local.family`、角色为 `OWNER`。 |
| 邮箱创建家庭 | 用 `POST /api/auth/register` 发送 `mode=CREATE`、邮箱、显示名、密码和家庭名；响应为 `201` 且角色为 `OWNER`。 |
| 邀请加入家庭 | 所有者创建 `MEMBER` 邀请，使用一次性显示的 Token 发送 `mode=JOIN` 注册；受邀用户登录后可在成员列表中看到自己的有效成员关系。 |
| 成员不能调整角色 | 使用受邀 `MEMBER` 的会话 `PATCH /api/family/memberships/{id}` 修改角色；响应为 `403 FORBIDDEN`。 |
| 旧库或落后版本先备份再迁移 | 放入非空主库 `data/family-finance.mv.db`：无 Flyway history 或 history 落后仓库最新数字 `V*.sql`（例如 V6 等待 V7）时，Windows 运行 `start-local.cmd -NoBrowser`，macOS/Linux 运行 `./start-local.sh`。在应用迁移前确认 `data-backups/<时间戳>/` 完整复制全部 `family-finance.*.db`（包括零字节跟随文件），并含清单、哈希和 `RESTORE.txt`；只有当前版本跳过备份。 |
| 失败迁移可安全人工恢复 | 合成 V6 数据触发 V7 具名守卫失败后，下一次启动必须先备份失败状态并拒绝运行，提示先修复数据再显式执行 Flyway `repair`，且不得自动 repair；完成两步后重试应重新执行 V7、清理陈旧守卫表并成功启动。 |
| 备份可恢复 | 停止应用，将当前 `data/` 保留到安全位置；从一个已完成（非 `.partial`）备份复制全部数据库文件回 `data/`，重新启动并确认演示账户和既有账目可读取。 |
| Windows Smoke 不碰生产数据 | 在 Windows 上记录 `data/` 和 `data-backups/` 状态，运行 `start-local.cmd -NoBrowser -Smoke`，确认成功探活、进程停止，且生产目录和备份目录均未新增或修改；临时库只出现在 `target/`。 |
| Windows 不相关端口保持被拒绝 | 在 Windows 上让非本应用的 HTTP 服务监听目标端口，再运行 `start-local.cmd -NoBrowser -Smoke -Port <目标端口>`；命令应失败并提示端口被占用。 |

## 自动化门槛

```bash
./mvnw -q -Dtest=StageTwoFoundationSmokeTest test
./mvnw test
/bin/sh scripts/unix-startup-gates.sh
```

Unix 脚本的 13 个隔离场景依次验证：Java 17 与项目 Wrapper；无数据库路径；检查/H2 解析失败；复制/哈希失败；发布目标冲突；活动锁与归属未知的陈旧锁；所有者标记损坏；旧库主文件、伴随文件、清单与冲突处理；V6 待迁移备份；V7 当前态跳过；恢复后的演示登录与 12 条账目；失败 V7 的状态备份、拒绝、人工修复与无陈旧守卫重试；以及前台退出状态传播。当前本机 macOS 运行只证明 macOS 路径；`.github/workflows/unix-startup-smoke.yml` 会在 `ubuntu-latest` 上重跑完整 Java 测试和这 13 个 Unix 场景，作为 Linux 证据。

Windows runner 需独立执行：

```powershell
.\scripts\windows-cmd-quote-regression.ps1
.\scripts\windows-startup-gates.ps1
```

Windows 的 `cmd.exe` 引号边界和 6 个隔离启动场景只能由 Windows 实机或 `.github/workflows/windows-startup-smoke.yml` 证明；Unix 成功不能替代 Windows 证据。同样，Windows 成功也不能替代 macOS/Linux 的 shell、锁和文件发布语义。验收结论必须注明运行平台、提交 SHA 和对应工作流结果，不能把静态检查写成跨平台运行认证。
