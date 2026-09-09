# JAR 与行情适配器的版本化发布

本说明是待授权后执行的发布方案；本分支只交付代码和测试，不代表服务器已变更。

## 设计边界

复用现有 GitHub artifact、SSH forced-command、SHA-256、flock 和 systemd；不引入新部署平台或安装守护进程。比较过单独复制 Python 文件（会继续漏文件、无法成对回滚）和容器化（需要改运行环境及权限），选择固定成员的整包交付。无需数据库迁移，不涉及多币种记账。

发布包包含 `app.jar`、`market/server.py`、`market/overseas.py`、`market/overseas_sources.py`、`market/requirements.txt` 和 schema=1 的 `release.json`。清单逐文件记录 SHA-256，并绑定完整 Git 提交；接收器验证 JAR 内提交相同。SSH 原有严格命令 `deploy <40位commit> <64位摘要> <run-id>` 不变，摘要现在针对解压后的整个 ZIP。缺成员、多成员、重复成员、符号链接、路径穿越、摘要不符和超限输入都拒绝；不使用 extractall，不执行上传的 shell。

这是经过密钥认证及主机验证的 SSH 加摘要校验，并未新增独立的离线制品签名。可信分支和密钥权限边界与以前相同。

先验证、备份和记录恢复信息，然后停止 Java 与适配器，切换两者后先启动适配器再启动 Java。两次文件替换不是跨文件系统的原子事务，停机窗口避免正常运行期间混用版本；异常会成对恢复。断电/SIGKILL 仍需要人工检查 `pending.json`。已发布目录与 runtime 不得手工原地改动或删除；备份不自动清理。

## 服务器引导（管理员另行授权后）

先暂停部署，确认没有运行中的工作流，保存现有接收器、配置和 unit 的副本。以下在审核后的仓库目录执行，示例路径不含服务器连接信息。原应用必须已有可备份的 JAR；新接收器有意拒绝未引导的机器。

1. 准备系统 Python 3.10 + venv、现有低权限用户。新建机器才执行 useradd；已有用户不要重复创建。对 `/opt/family-finance/market-data` 及版本/依赖目录使用 root:root、目录 0755、文件不可被服务用户写入。先审核现有目录，无关文件不要改权限。
2. 在受控 Linux 构建环境锁定 requirements 的完整传递依赖和 wheel 哈希，生成运维保管的 `requirements.lock` 与 `wheelhouse/`，要求包含 requests/openpyxl/pandas/py-mini-racer。该步骤是前置条件，不宣称当前仓库已有完整传递依赖锁。按下例离线安装到新 runtime；禁止覆盖已使用的 runtime：

```bash
set -euo pipefail
market_root=/opt/family-finance/market-data
requirements_sha=$(sha256sum scripts/market-data/requirements.txt | cut -d ' ' -f 1)
runtime="$market_root/runtimes/$requirements_sha"
sudo install -d -o root -g root -m 0755 "$market_root" "$market_root/runtimes" "$market_root/releases"
test ! -e "$runtime"
sudo install -d -o root -g root -m 0755 "$runtime"
sudo python3.10 -m venv "$runtime/.venv"
sudo "$runtime/.venv/bin/python" -m pip install --no-index --find-links wheelhouse --require-hashes -r requirements.lock
sudo "$runtime/.venv/bin/python" -m pip check
sudo "$runtime/.venv/bin/python" -c 'from importlib.metadata import version; assert version("akshare") == "1.18.88"; assert version("baostock") == "0.9.3"'
sudo "$runtime/.venv/bin/python" -c 'import akshare, baostock, requests, openpyxl, pandas; from py_mini_racer import MiniRacer; MiniRacer.v8_flags=["--single-threaded", "--jitless"]; assert MiniRacer().eval("1+1") == 2'
sudo install -o root -g root -m 0644 requirements.lock "$runtime/requirements.lock"
# 仅依赖检查成功后写入接收器要求的完成标记。
sudo install -o root -g root -m 0644 scripts/market-data/requirements.txt "$runtime/requirements.txt"
```

确认完整锁中的 AKShare/BaoStock 版本与仓库要求一致；requirements 文件摘要是运维预备环境的标识，不是对整个 venv 内容的密码学证明。保留 lock、wheel 哈希和安装记录。新建目录若安装失败，先人工检查，不能补一个 requirements 标记绕过检查。

已有经过验证且由 root 独占维护的 venv，也可在依赖声明完全一致时复制为独立、不可原地更新的 runtime 快照，避免部署现场升级依赖。必须保留原环境供 legacy 回退，归档完整包版本、原安装来源/哈希报告及新快照的逐文件 SHA-256；用新快照再次执行 pip check、指定版本检查与低权限/jitless/MemoryDenyWriteExecute 导入验证，最后才写入 requirements 完成标记。此方式记录已验证安装物，不等于新生成了可重建 wheelhouse；后续依赖变更仍需前述受控锁定流程。使用严格 umask 时，应显式核对 runtime 和 legacy 目录均为 root:root 0755，保证服务用户可读而不可写。

3. 旧目录迁移：保留正在使用的代码和 venv，创建 legacy 目录。不要用本次新源文件冒充旧版，也不要给缺文件旧版伪造 deployment.json。下面适用于旧布局在根目录有 server.py 和 .venv：

```bash
legacy="$market_root/legacy-$(date -u +%Y%m%dT%H%M%SZ)"
test -f "$market_root/server.py"
test -x "$market_root/.venv/bin/python"
test ! -e "$market_root/current"
sudo install -d -o root -g root -m 0755 "$legacy"
for file in server.py overseas.py overseas_sources.py requirements.txt; do
  if test -f "$market_root/$file"; then
    sudo install -o root -g root -m 0644 "$market_root/$file" "$legacy/$file"
  fi
done
sudo ln -s "$market_root/.venv" "$legacy/.venv"
sudo ln -s "$legacy" "$market_root/current"
```

全新安装没有旧目录时，由管理员先完成一次受控初始发布并确认基础服务可恢复，再启用自动接收器；不要绕过“已有 JAR / current 指针”门禁。

4. 安装审核后的接收器与 unit，配置新增 `market_root`、`market_service`、`market_url`（示例见 github-auto-deploy.md），保持原 jar/state/service/base_url。配置 root:root 0600，接收器 root:root 0755。**先确认没有发布运行，再更新接收器**；不通过 CI 自动安装特权代码。

```bash
sudo install -o root -g root -m 0755 scripts/ci_deploy.py /usr/local/sbin/family-finance-ci-deploy
sudo install -o root -g root -m 0644 docs/operations/market-data-adapter.service /etc/systemd/system/family-finance-market.service
sudo systemctl daemon-reload
sudo systemctl restart family-finance-market
sudo systemctl is-active family-finance-market
sudo systemctl show family-finance-market -p User -p ExecStart -p MemoryDenyWriteExecute -p NoNewPrivileges
```

核对应用已有 `MARKET_DATA_URL=http://127.0.0.1:8091`。不得放宽 MemoryDenyWriteExecute、改变 jitless 解码策略、扩大入站/出站规则或把适配器暴露到公网。首次 legacy 健康接口可能 404；这只是保存旧基线，海外功能仍待整包发布验证。

5. 保留 authorized_keys 的 restrict、forced-command、1800 秒 timeout 与 210 秒 kill grace，以及 GitHub host-key 严格校验。Actions 部署任务时限为 40 分钟，给恢复留下余量。只有这些步骤和 legacy 恢复路径审核完成后，才允许新版工作流合入部署分支。

## 发布与验收

CI 自动构建，可在受控构建环境复现制品：

```bash
python3 scripts/build_release.py --jar target/family-finance-0.0.1-SNAPSHOT.jar \
  --adapter scripts/market-data --output release --commit "$(git rev-parse HEAD)"
```

必须使用同一次干净 checkout 构建且已写入同一提交标记的 JAR。接收器从 stdin 接收 release.zip.gz；不要用旧 app.jar.gz 重跑发布。标准工作流仍只允许部署分支与 development 环境。

服务器门禁检查前端 HTML、CSRF API、Java deployment.json、适配器 /health，以及 HK 00700 和 US AAPL 的 READY 搜索与非空 SINA 未复权日线，拒绝 stale。适配器 /health 在启动时检查全部源文件与 requirements 摘要，报告该进程实际启动版本。双市场搜索先触发后台准备，再重试检查。外部源暂时失败也会使发布失败并回退，这是有意保守的门禁。

GitHub runner 继续验证公网 deployment.json 和 CSRF。正式验收另需登录用户确认港、美股搜索与图表，检查币种、日期和来源，并确认无记账入口。不新增未经授权的网络访问来绕过失败。若目录初次同步超过重试窗口，保留失败日志，排查后重试最新提交。

## 自动回退与人工恢复

普通异常：脚本停止两项服务、恢复旧 JAR 与 current 指针，重新启动并检查旧版本；发布仍失败。无版本标记的 legacy 只检查原 Java 版本/页面/API 和适配器 active，**不将旧版恢复解读为海外可用**。恢复检查失败则保留 pending.json 并拒绝后续发布。

管理员先暂停 CI 并持有同一 deploy.lock，查看 pending.json 的 backup、previous_adapter、previous 元数据。成功发布后人工回退可从 current.json 取得 backup 与 previous_adapter。核对这些文件属于同一发布的旧版本，然后在管理员 Python 会话执行以下骨架；不要将模板直接交给 CI 密钥：

```python
import fcntl, json, os, shutil, subprocess, tempfile
from pathlib import Path
config = json.loads(Path('/etc/family-finance/ci-deploy.json').read_text())
state = Path(config['state'])
with (state / 'deploy.lock').open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    recovery = json.loads((state / 'pending.json').read_text())
    jar = Path(config['jar'])
    adapter = Path(config['market_root'])
    subprocess.run(['systemctl', 'stop', config['service'], config['market_service']], check=True)
    with tempfile.TemporaryDirectory(dir=jar.parent) as staging:
        copy = Path(staging) / 'rollback.jar'
        shutil.copy2(recovery['backup'], copy)
        os.replace(copy, jar)
    link = adapter / '.operator-rollback'
    link.symlink_to(recovery['previous_adapter'])
    os.replace(link, adapter / 'current')
    subprocess.run(['systemctl', 'restart', config['market_service'], config['service']], check=True)
```

以上只恢复文件和启动；继续按前述检查验证旧版本，确认后再在持锁状态恢复 previous 到 current.json（旧基线为空则移除 current.json），归档 pending.json。不得未检查就删除中断标记。若旧版本也无法恢复，保留文件和日志并人工处理；不要清空数据库或运行 flyway repair。本次无数据库迁移，未来涉及不兼容迁移时仍须独立协调数据库恢复。
