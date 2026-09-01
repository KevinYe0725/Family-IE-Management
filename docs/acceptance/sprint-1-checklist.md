# Sprint 1 验收清单

| 验收条件 | 可重复证据 |
| --- | --- |
| 1. 测试全部通过且不触及生产数据 | 运行 `./mvnw test`；`SprintOneSmokeTest` 使用 `target/` 下随机文件 H2，常规测试使用随机内存 H2。 |
| 2. 可启动并以演示账户登录 | 运行 `./mvnw spring-boot:run`；浏览器打开 `http://127.0.0.1:8080`，使用 `demo / demo1234` 登录。 |
| 3. 收支增、改、筛、删走真实 API/JPA/H2 | 浏览器在“收支明细”新增一笔、编辑、用关键词筛选、删除；`SprintOneSmokeTest` 覆盖完整流程，`TransactionApiTest` 覆盖 500 字备注和 `%`/`_` 字面筛选。 |
| 4. 重启后仍保留新增数据 | 浏览器创建带唯一标记的记录，停止并以相同 `data/family-finance` 重启，登录后筛选该标记；随后仅删除该条记录。 |
| 5. 家庭隔离，已引用成员/分类不可删除 | `MemberApiTest`、`CategoryApiTest` 和冲突翻译测试；浏览器设置页删除已引用项时阅读具体错误。 |
| 6. 看板数字可与流水核对，结余正确 | `DashboardServiceTest`、`ReportingApiTest`；浏览器“总览”切换 2026-09，对照明细。 |
| 7. 分析来自规则且数据不足时说明边界 | `AnalysisServiceTest` 覆盖跨空档月份选取最近三个有支出账期、家庭范围及未来数据边界，`ReportingApiTest` 覆盖 API；浏览器“账目分析”查看提示与 2026-05 空账期。 |
| 8. CSV 含当前筛选且中文可读 | `CsvExportApiTest`、`SprintOneSmokeTest`；浏览器应用筛选后点击“下载 CSV”。 |
| 9. 桌面与移动真实可操作 | Playwright CLI 在 **1440×900** 执行登录、看板、收支写入与分析；在 **390×844** 核对单一导航可访问名称。最终复核截图保存在 `output/playwright/final-fix/`。 |

### 浏览器控制台检查

- [ ] 正常流程控制台清洁：完成登录、收支操作和退出后运行 Playwright CLI `console`，确认 `Total messages: 0 (Errors: 0, Warnings: 0)`，并保存到 `output/playwright/task6r2-normal-console.log`。
- [ ] 负向网络路径单独记录：仅在故意停止服务时观察连接失败；不把它计入正常流程控制台结果。证据文件为 `output/playwright/task6r2-negative-stopped-server-console.log`。
- [ ] 最终复核：正常桌面和移动会话均为 `0 errors / 0 warnings`；过期会话写入的唯一错误是预期的 `POST /api/transactions => 401`。证据保存在 `output/playwright/final-fix/*-console.log`、`expired-write-network.log` 和 `expired-write-state.log`。

验收时，若有写操作失败，先检查浏览器是否先请求了 `/api/csrf` 并发送 `X-XSRF-TOKEN`。登录后 Spring Security 会更新会话，因此后续写入应重新取得令牌。
