# 当前分支自动部署

## 日常使用

向 `codex/family-finance-stage-2` 执行 `git push` 后，GitHub Actions 中的 **Stage 2 CI and deploy** 自动运行：

1. 检查部署安全逻辑、前端类型和测试，构建前端。
2. 执行 Java 测试，打包包含前端的 Spring Boot JAR，写入提交版本标记。
3. 将 JAR、完整 Python 适配器和逐文件摘要清单封装为同一提交的 release.zip.gz，保存整体 SHA-256 校验值（GitHub 制品保留 7 天）。
4. 使用专用 SSH 密钥传输至现有开发服务器，校验内容和提交号。
5. 备份旧 JAR、记录旧适配器目录，停止两项服务后切换同一版本并重启；在 150 秒重试窗口内检查前端、CSRF、两项版本以及 HK/US 目录与日线（单次网络请求仍有独立超时）。
6. 从 GitHub runner 再检查公网版本和 API。Actions 全部成功才算本次发布通过。

本地 `git commit` 不会触发，必须推送至 GitHub。其他分支不部署；向本分支提交的 PR 只检查，不获取部署 Secrets。密集推送时只保证最新提交部署，过时任务可能被跳过；正在部署的任务不会被新推送强制取消。

这是单实例重启部署，会有短暂不可用，并非零停机。前端改版及可靠性修复已归入 Stage 2，不再单独维护 Clarity 开发分支。

## CI 范围：以服务器使用为准

自 2026-09-08 起，不再执行独立的 Windows 和 macOS/Linux 本地启动认证流程。对应的两个 workflow 配置已移除，GitHub 中的历史工作流也停用；既有运行记录保留，不删除失败证据。启动脚本与认证脚本保留在仓库，日后如需恢复可从 Git 历史找回配置，不代表当前仍提供本地启动认证。

`Stage 2 CI and deploy` 保持不变，继续执行前端类型检查、前端测试、Java 业务及集成测试、部署安全检查、打包、服务器更新和公网验证。停用本地启动流程不等于关闭这些必要检查。

两条本地启动流程原本与部署流程并行，不是部署的前置依赖；移除后减少重复任务与失败提示，但主部署的测试、打包耗时仍存在。

推送确认成功后，提供 Actions 运行链接即可，不等待或持续轮询部署结果。成功和失败由 GitHub Actions 显示；主动通知由用户的 GitHub Actions 通知设置控制，成功通知需要未启用“仅失败时通知”。除非用户明确要求排查或等待，不另建部署监控任务。

## 首次配置（维护者）

仓库工作流：`.github/workflows/deploy-stage2.yml`。

GitHub **Settings → Environments → development** 只允许 `codex/family-finance-stage-2` 部署，并设置以下环境 Secrets：

| 名称 | 内容 |
| --- | --- |
| `DEPLOY_HOST` | 服务器地址，仅填写主机名/IP |
| `DEPLOY_PORT` | SSH 端口 |
| `DEPLOY_USER` | 现有服务部署用户 |
| `DEPLOY_SSH_KEY` | 本项目专用 Ed25519 私钥，不使用个人登录密钥 |
| `DEPLOY_KNOWN_HOSTS` | 经管理员核对的服务器 SSH 主机公钥记录 |
| `DEPLOY_URL` | 公网访问根地址，不含末尾斜杠 |

禁止将真实服务器地址、密码、私钥写进仓库。数据库配置继续留在服务器原有环境文件，不经过 GitHub。

服务器应已有 Python 3、systemd、Java 17、Nginx，以及可正常运行的应用。由管理员审核并将 `scripts/ci_deploy.py` 安装为 root 所有的 `/usr/local/sbin/family-finance-ci-deploy`（0755），将以下配置保存为 `/etc/family-finance/ci-deploy.json`（root:root、0600）：

```json
{
  "jar": "/root/Family-IE-Management/target/family-finance-0.0.1-SNAPSHOT.jar",
  "state": "/var/lib/family-finance-ci",
  "service": "family-finance.service",
  "base_url": "http://127.0.0.1",
  "market_root": "/opt/family-finance/market-data",
  "market_service": "family-finance-market.service",
  "market_url": "http://127.0.0.1:8091"
}
```

**启用新工作流前必须完成 [版本目录引导与成对恢复](versioned-market-release.md)**，先安装新版接收器、准备依赖环境并更新 unit，再允许部署分支发布。接收器不兼容旧 JAR-only 制品；不得先推送后补服务器配置。

路径只是沿用现有服务布局；迁移环境先核对实际 `ExecStart`。安装前检查是否存在旧配置，不要盲目覆盖。

专用公钥的 `authorized_keys` 记录采用：

```text
restrict,command="/usr/bin/timeout --kill-after=210 1800 /usr/local/sbin/family-finance-ci-deploy" ssh-ed25519 PUBLIC_KEY family-finance-github-actions
```

此密钥不能开 shell、PTY、端口转发或 SFTP，只接收格式严格的部署命令和压缩的完整发布包。旧 JAR-only 输入会被拒绝。脚本更新需要管理员通过正常管理连接审核安装，CI 不会自动更新服务器上的部署脚本。

接收器总时限为 30 分钟，保留 210 秒强制结束前的恢复宽限；Actions 部署任务为 40 分钟，避免抢先中断恢复。stderr 输出 `[deploy]` 阶段和已接收的解压后发布包字节数，上传期间每约 15 秒报告一次（有数据到达时）。`receiving` 后未出现 `received` 表示接收未完成；只有校验及备份完成后才会停止服务。

**信任边界：**获准推送部署分支的人可以部署程序代码，并获得应用进程本身的权限。现有应用以 root 运行，限制 SSH 命令并不等于隔离恶意应用代码。仅允许可信维护者写入该分支；生产化时应另行迁移至非 root 应用账户并配置分支审核保护。

## 失败与恢复

- 检查或打包失败：不接触服务器。
- 上传中断、校验不一致、提交标记不符：不替换旧 JAR 或适配器。
- 启动/本机健康检查失败：自动恢复旧 JAR 与旧适配器指针、重启并检查恢复结果。任务保持失败，不能把回退当发布成功。
- 公网检查失败但服务器本机正常：任务失败，保留已部署版本；检查安全组、Nginx和网络，不因外网故障盲目回退程序。
- 备份位于 `/var/lib/family-finance-ci/backups`；最近成功版本及回退位置记录在 `current.json`。备份不自动删除，维护者定期检查磁盘并按需保留。
- 自动回退针对 JAR 与适配器版本对，**不会撤销 MySQL/Flyway 数据迁移**。不兼容迁移必须先备份数据库并按专门发布方案处理；恢复旧 JAR 不保证能兼容新结构。
- 系统断电、强制杀进程或磁盘损坏不能保证自动恢复。此时由管理员使用 pending.json 中记录的版本对人工恢复；存在 pending.json 时拒绝后续部署。

GitHub 的 Re-run jobs 可重试当前分支最新提交；旧提交的重跑会被跳过。修改触发分支时，须同步修改工作流中的分支过滤、部署判断、最新提交检查和环境分支白名单，避免旧版本覆盖。

## 验证命令

```bash
python3 -m unittest discover -s scripts/tests -v
cd frontend
npm ci
npm run typecheck
npm test -- --run
npm run build
cd ..
./mvnw -B -Dskip.npm=true -Dskip.installnodenpm=true verify
```

本地验证不使用服务器数据库。自动部署是否成功，以实际 Actions 记录和公网返回的提交号为准。

参考：[GitHub 部署环境及并发控制](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/control-deployments)。
