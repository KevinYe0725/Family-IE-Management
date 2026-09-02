# 第二阶段：身份与家庭基础验收清单

| 验收条件 | 可重复证据 |
| --- | --- |
| 第一阶段数据可原地升级 | 以第一阶段 H2 副本启动应用；`StageTwoFoundationSmokeTest` 从该副本迁移后验证 `demo@local.family`、`OWNER` 与原有账本结构。 |
| 旧演示登录保持兼容 | 登录 `demo / demo1234`，再请求 `GET /api/session`，确认邮箱为 `demo@local.family`、角色为 `OWNER`。 |
| 邮箱创建家庭 | 用 `POST /api/auth/register` 发送 `mode=CREATE`、邮箱、显示名、密码和家庭名；响应为 `201` 且角色为 `OWNER`。 |
| 邀请加入家庭 | 所有者创建 `MEMBER` 邀请，使用一次性显示的 Token 发送 `mode=JOIN` 注册；受邀用户登录后可在成员列表中看到自己的有效成员关系。 |
| 成员不能调整角色 | 使用受邀 `MEMBER` 的会话 `PATCH /api/family/memberships/{id}` 修改角色；响应为 `403 FORBIDDEN`。 |
| 旧库先备份再迁移 | 在 Windows 上放入非空且未含 Flyway history 的 `data/family-finance.*.db`，运行 `start-local.cmd -NoBrowser`。在应用开始迁移前，确认出现 `data-backups/<时间戳>/`，其中完整复制全部 `family-finance.*.db`，并有 `RESTORE.txt`。 |
| 备份可恢复 | 停止应用，将当前 `data/` 保留到安全位置；从一个已完成（非 `.partial`）备份复制全部数据库文件回 `data/`，重新启动并确认演示账户和既有账目可读取。 |
| Smoke 不碰生产数据 | 记录 `data/` 和 `data-backups/` 状态，运行 `start-local.cmd -NoBrowser -Smoke`，确认成功探活、进程停止，且生产目录和备份目录均未新增或修改；临时库只出现在 `target/`。 |
| 不相关端口保持被拒绝 | 让非本应用的 HTTP 服务监听 8080，运行 `start-local.cmd -NoBrowser -Smoke`；命令应失败并提示端口被占用。 |

## 自动化门槛

```bash
./mvnw -q -Dtest=StageTwoFoundationSmokeTest test
./mvnw test
```

Windows runner 还需执行：

```bat
start-local.cmd -NoBrowser -Smoke
```

该脚本的 Windows 行为（预迁移备份、专用 Smoke 数据库和进程树停止）必须在 Windows 环境实际复核；macOS/Linux 只可完成 Java 测试与静态检查。
