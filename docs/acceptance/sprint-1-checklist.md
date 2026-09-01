# Sprint 1 验收清单

| 验收条件 | 可重复证据 |
| --- | --- |
| 1. 测试全部通过且不触及生产数据 | 运行 `./mvnw test`；`SprintOneSmokeTest` 使用 `target/` 下随机文件 H2，常规测试使用随机内存 H2。 |
| 2. 可启动并以演示账户登录 | 运行 `./mvnw spring-boot:run`；浏览器打开 `http://127.0.0.1:8080`，使用 `demo / demo1234` 登录。 |
| 3. 收支增、改、筛、删走真实 API/JPA/H2 | 浏览器在“收支明细”新增一笔、编辑、用关键词筛选、删除；`SprintOneSmokeTest` 覆盖新增、筛选和删除。 |
| 4. 重启后仍保留新增数据 | 浏览器创建带唯一标记的记录，停止并以相同 `data/family-finance` 重启，登录后筛选该标记；随后仅删除该条记录。 |
| 5. 家庭隔离，已引用成员/分类不可删除 | `MemberApiTest`、`CategoryApiTest` 和冲突翻译测试；浏览器设置页删除已引用项时阅读具体错误。 |
| 6. 看板数字可与流水核对，结余正确 | `DashboardServiceTest`、`ReportingApiTest`；浏览器“总览”切换 2026-09，对照明细。 |
| 7. 分析来自规则且数据不足时说明边界 | `AnalysisServiceTest`、`ReportingApiTest`；浏览器“账目分析”查看提示与 2026-05 空账期。 |
| 8. CSV 含当前筛选且中文可读 | `CsvExportApiTest`、`SprintOneSmokeTest`；浏览器应用筛选后点击“下载 CSV”。 |
| 9. 桌面与移动真实可操作 | Playwright CLI 在 **1440×900** 执行登录、看板与退出；在 **390×844** 执行登录、看板、收支新增/编辑/筛选/删除、分析、设置、CSV 请求、退出、Escape/保存后的键盘焦点和水平溢出检查；截图保存在 `output/playwright/`。 |

验收时，若有写操作失败，先检查浏览器是否先请求了 `/api/csrf` 并发送 `X-XSRF-TOKEN`。登录后 Spring Security 会更新会话，因此后续写入应重新取得令牌。
