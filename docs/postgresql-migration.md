# 家账 PostgreSQL 迁移

## 目标

家账生产环境只使用 PostgreSQL 17。沿用服务器已有的 PostgreSQL 实例，家账使用独立的 `family_finance` 数据库和 `family_finance` 登录角色，雅思应用继续使用自己的数据库。原有前端和 HTTP 接口保持一致。

## 实施顺序

1. 在隔离工作目录适配 PostgreSQL 驱动、Flyway、生成 ID 的读取方式和行锁；不改原有 H2/MySQL 历史迁移。
2. 执行真实 PostgreSQL 上的现金、换汇、贷款、资产出售、定投、预算和权限并发测试。CI 同样执行这些用例。
3. 私有目录备份 MySQL、当前发布、部署状态、环境文件和服务文件；创建独立 PostgreSQL 数据库和角色。
4. 使用 `PostgresqlSchemaBootstrap` 执行 47 个 PostgreSQL 迁移，不启动应用、任务或种子数据。
5. 停止家账服务，保持 MySQL 运行，执行最终快照和迁移。所有业务表在一个 PostgreSQL 事务内写入，外键仅暂时延迟检查，提交前恢复。身份序列保留原 MySQL 下一 ID 和现有最大 ID。
6. 核对所有业务表的行数和逐行 SHA256：精确金额、UTC 时间、空值、账号密码哈希、生成列均参与比较。Flyway 历史保留 PostgreSQL 自己的记录。
7. 配置 JDBC PostgreSQL 地址、切换发布和服务依赖，启动家账并检查页面、登录和原有账目。
8. 确认没有其他 MySQL 业务库后停用 MySQL，保留其数据目录和备份。

## 操作接口

初始化 schema：环境变量沿用 Spring datasource 三项，运行发布 JAR 的 PropertiesLauncher，并将 `loader.main` 设置为 `com.familyfinance.migration.PostgresqlSchemaBootstrap`。

数据迁移：`python3 scripts/migrate_mysql_to_postgresql.py --backup-dir <root-only-directory> --apply`。仅在家账服务停止、目标库已完成迁移且业务表为空时允许执行。工具禁止目标为 `iwc` 等现有数据库。

实际数据库测试：设置 `FAMILY_PG_TEST_URL`、`FAMILY_PG_TEST_USER`、`FAMILY_PG_TEST_PASSWORD` 指向一次性测试库，执行 `./mvnw -Dskip.installnodenpm=true -Dskip.npm=true '-Dtest=Postgresql*Test' test`。每个测试上下文创建独立 schema；完成后删除任务自己的测试实例。

## 回退与运维

切换后如尚未接受新写入，可停止家账，恢复原环境文件、systemd 单元、发布和部署状态，启动 MySQL，再启动家账。切换后已有新写入时必须先备份 PostgreSQL 并迁移差异，禁止直接退回旧数据。

PostgreSQL 的既有容器现在承担雅思与家账的数据存储。禁止对其执行 `docker compose down -v` 或删除数据库卷。备份应同时覆盖两个独立数据库。每个应用使用自己的连接池和角色，家账连接池上限继续为 5。

旧 `migration-mysql` 文件只用于历史追溯和旧发布回退；当前发布不包含 MySQL JDBC/Flyway 运行依赖。
