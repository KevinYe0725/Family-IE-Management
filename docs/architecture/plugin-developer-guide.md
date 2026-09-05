# 插件开发、提交与部署指南

适用版本：插件协议 v1；基于 `ecb82ea` 引入的内置插件机制。日期：2026-09-05。

本文面向插件开发者和部署维护者。示例中的主机名、目录和提交号须按实际环境替换；不包含开发服务器地址或凭据。

## 1. 交付方式与支持范围

目前插件是经过审核、随主程序一起编译发布的源码模块。开发者提交 PR，维护者审核并接入，再部署包含本体和插件的完整 Spring Boot JAR。

```text
开发者编写插件 → 提交源码与测试 PR → 维护者审核/集成
→ 构建完整应用 JAR → 备份并更新服务器 → 重启 → 验收
```

支持：独立菜单和页面、启动配置启停、本体授权查询接口、统一登录会话、页面懒加载与错误边界。

尚不支持：直接上传插件 JAR/ZIP 安装、第三方远程 JS、热加载/热卸载、插件市场、每个家庭单独启停、总览卡片插槽、通用插件写入接口、独立插件数据库迁移管理。

关闭插件需重启。插件与本体在同一个 Java 进程中运行；页面错误边界不能隔离后端死循环、内存耗尽或恶意代码。

## 2. 开发前取得正确基线

请维护者提供**已经包含插件机制的远程分支或提交**。不要直接以旧 `main` 或旧静态 `app.js` 为开发基线；维护者若尚未推送插件分支，应先发布可供协作的基线。

macOS / Linux：

```bash
git clone https://github.com/KevinYe0725/Family-IE-Management.git
cd Family-IE-Management
git fetch origin
git switch --detach <维护者提供的提交或origin/分支>
git switch -c feat/plugin-cashflow-summary
```

Windows PowerShell 同样可执行这些 Git 命令。没有仓库写权限时，先 Fork，在自己的 Fork 创建分支并向维护者指定的集成分支提交 PR。

环境：Java 17；直接运行前端命令时使用 Node 22。仓库自带 Maven Wrapper，Maven 构建也会安装固定 Node/npm。测试使用 H2，生产使用 MySQL；运行测试不需要生产数据库密码。

## 3. 插件目录与命名

以新插件 `cashflow-summary` 为例：

```text
src/main/java/com/familyfinance/plugins/cashflowsummary/
  CashflowSummaryPlugin.java
frontend/src/plugins/cashflow-summary/
  CashflowSummaryPage.tsx
  CashflowSummaryPage.test.tsx
src/test/java/com/familyfinance/plugins/cashflowsummary/
  CashflowSummaryPluginTest.java
docs/plugins/cashflow-summary.md
```

后端位于 `com.familyfinance` 下，才能由现有 Spring Boot 扫描发现。插件 ID 使用小写字母开头，只含小写字母、数字和连字符。

| 项目 | 示例 / 规则 |
|---|---|
| ID | `cashflow-summary`，全局唯一 |
| 插件版本 | `1.0.0`，由开发者维护；目前不自动校验版本兼容范围 |
| 协议版本 | `apiVersion=1` |
| 页面路径 | 必须精确为 `/workspace/extensions/cashflow-summary` |
| API 路径 | 约定 `/api/plugins/cashflow-summary`，开发者自行声明 |
| 启停配置 | 约定 `app.plugins.cashflow-summary.enabled`，开发者自行实现 |
| 能力声明 | 当前可用 `ledger.read`；仅声明，不自动授权 |

注册中心会拒绝重复 ID、重复页面路径、不合法 ID 和不兼容协议版本。API 路径冲突由 Spring 检查；插件依赖顺序尚无自动解析机制。

## 4. 后端最小示例

```java
package com.familyfinance.plugins.cashflowsummary;

import com.familyfinance.extension.FinancePlugin;
import com.familyfinance.extension.PluginDescriptor;
import com.familyfinance.extension.LedgerReadPort;
import com.familyfinance.shared.ApiEnvelope;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
    name = "app.plugins.cashflow-summary.enabled",
    havingValue = "true", matchIfMissing = false)
public class CashflowSummaryPlugin implements FinancePlugin {
    private final LedgerReadPort ledger;

    public CashflowSummaryPlugin(LedgerReadPort ledger) {
        this.ledger = ledger;
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("cashflow-summary", "1.0.0", 1,
            "收支摘要", "查看年度各月收支", 
            "/workspace/extensions/cashflow-summary", List.of("ledger.read"));
    }

    @GetMapping("/api/plugins/cashflow-summary")
    public ApiEnvelope<List<Month>> read(
            Authentication authentication, @RequestParam int year) {
        return ApiEnvelope.data(ledger.readYear(authentication, year).stream()
            .map(row -> new Month(row.month(), row.incomeCents().toString(),
                row.expenseCents().toString())).toList());
    }

    public record Month(int month, String incomeCents, String expenseCents) {}
}
```

示例默认关闭，维护者显式启用。现有 `annual-stats` 默认开启，默认值由各插件的条件配置决定。

如果插件还有独立 Service、Job 等 Bean，须一起放进受条件控制的配置或加相同条件；只隐藏菜单不会停止后台任务，也不等于关闭 API。

### 数据权限与金额

- 当前 `LedgerReadPort.readYear(authentication, year)` 返回当前家庭 12 个月的收入/支出汇总，金额单位为分，Java 类型为 BigInteger，有效年份为 1900—2100。
- 将 Spring 提供的真实 Authentication 传给本体，由本体确定家庭；不接受客户端指定的家庭 ID 作为权限依据。
- 不直接注入本体 Repository、不从插件查询本体表；目前这是审核约束，不是技术沙箱。
- 当前只提供全年月度汇总。若需要流水详情、资产或写入能力，先与维护者约定新增本体端口，不能假设已有这些接口。
- `capabilities` 不执行权限检查。管理员专属操作需要后端明确鉴权，前端隐藏按钮不能代替鉴权。
- 财务 API 建议返回金额字符串，避免 JavaScript 大整数精度丢失。示例返回分字符串；真实页面应清楚标注单位，或由后端转换为元字符串。
- 使用现有 ApiEnvelope 和全局异常处理；不返回数据库凭据、堆栈或未经脱敏的日志。

## 5. 前端最小示例与注册

新建 `frontend/src/plugins/cashflow-summary/CashflowSummaryPage.tsx`：

```tsx
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { PageScaffold, QueryState, type RequestFn } from '../../features/common';

type Month = { month: number; incomeCents: string; expenseCents: string };

export default function CashflowSummaryPage({ request }: { request: RequestFn }) {
  const [year] = useState(new Date().getFullYear());
  const result = useQuery({
    queryKey: ['plugin', 'cashflow-summary', year],
    queryFn: () => request<Month[]>(`/api/plugins/cashflow-summary?year=${year}`)
  });
  return <PageScaffold title="收支摘要" description={`${year} 年每月已记账收支`}>
    <QueryState loading={result.isLoading} error={result.error}>
      <ul>{result.data?.map(row => <li key={row.month}>
        {row.month} 月：收入 {row.incomeCents} 分，支出 {row.expenseCents} 分
      </li>)}</ul>
    </QueryState>
  </PageScaffold>;
}
```

然后在 `frontend/src/extensions/registry.tsx` 的 `bundled` 对象中保留原有条目并加入：

```tsx
'cashflow-summary': lazy(() => import('../plugins/cashflow-summary/CashflowSummaryPage'))
```

当前宿主要求插件默认导出一个接收 `{ request: RequestFn }` 的 React 页面。后端已启用、前端已登记、ID/路径/协议都匹配时，桌面和移动菜单会自动出现入口。无需修改 `WorkspaceLayout` 或本体导航数组。

统一使用传入的 `request` 处理 Cookie、CSRF 和登录过期。查询键以 `['plugin', 插件ID, ...筛选条件]` 命名，避免覆盖其他插件或本体缓存。新页面至少处理加载、错误和空数据状态。

## 6. 测试与交付清单

提交 PR 时提供：源码、前端注册项、配置名/默认值、业务口径、接口说明、测试结果、页面截图。若新增依赖或数据库表，单独列出理由与影响。

必须覆盖与插件相关的行为：

- 未登录不可读取；不同家庭无法互相读取；管理员专属功能拒绝 MEMBER。
- 金额合计、零记录、日期边界和大金额处理正确。
- 插件关闭后 `/api/plugins` 不含该 ID、插件 API 不可调用，本体页面仍可用。
- 前端入口正确，数据加载失败有反馈；修改筛选时请求参数和内容更新。

macOS / Linux，在仓库根目录执行（插件测试类名按实际替换）：

```bash
./mvnw -Dtest=CashflowSummaryPluginTest test
cd frontend
npm run typecheck
npm test -- --run
cd ..
./mvnw package
```

Windows PowerShell：

```powershell
.\mvnw.cmd '-Dtest=CashflowSummaryPluginTest' test
if ($LASTEXITCODE -ne 0) { throw '插件测试失败' }
Set-Location frontend
npm run typecheck
if ($LASTEXITCODE -ne 0) { throw '类型检查失败' }
npm test -- --run
if ($LASTEXITCODE -ne 0) { throw '前端测试失败' }
Set-Location ..
.\mvnw.cmd package
if ($LASTEXITCODE -ne 0) { throw '构建失败' }
```

`mvnw package` 会运行前端测试/构建和 Java 测试。不要用旧工作区残留 JAR 当作本次产物。最终文件是 `target/family-finance-0.0.1-SNAPSHOT.jar`，包含完整本体和前端资源。

开发者应在隔离测试环境联调；不向普通开发者分发生产数据库或 root 凭据。当前 Vite 配置没有后端代理，单独 `npm run dev` 不保证 `/api` 连通；可由维护者配置开发代理，或在测试 MySQL 环境运行完整 JAR。

## 7. 维护者部署流程

以下示例假定沿用现有布局：systemd 服务名 `family-finance`，JAR 目录 `/root/Family-IE-Management/target`，由获授权的部署人员操作。不同布局先核对 `systemctl show family-finance -p ExecStart -p WorkingDirectory`，不要盲目复制路径。

### 7.1 上传并核验

构建机为 macOS：

```bash
shasum -a 256 target/family-finance-0.0.1-SNAPSHOT.jar
scp target/family-finance-0.0.1-SNAPSHOT.jar DEPLOY_USER@YOUR_SERVER_HOST:/tmp/family-finance-candidate.jar
```

构建机为 Windows PowerShell：

```powershell
Get-FileHash .\target\family-finance-0.0.1-SNAPSHOT.jar -Algorithm SHA256
scp .\target\family-finance-0.0.1-SNAPSHOT.jar DEPLOY_USER@YOUR_SERVER_HOST:/tmp/family-finance-candidate.jar
if ($LASTEXITCODE -ne 0) { throw '上传失败' }
```

登录服务器后执行 `sha256sum /tmp/family-finance-candidate.jar`，必须与构建机一致。记录来源提交、插件版本及哈希。只上传前端文件或只拉取 Git 源码不会更新正在运行的 JAR。

### 7.2 配置启用

如需启用示例插件，由维护者执行 `sudo systemctl edit family-finance`，加入：

```ini
[Service]
Environment="APP_PLUGINS_CASHFLOWSUMMARY_ENABLED=true"
```

Spring 环境变量规则为点转下划线、连字符去掉、转大写；因此 `cashflow-summary` 对应 `CASHFLOWSUMMARY`。现有年度统计对应 `APP_PLUGINS_ANNUALSTATS_ENABLED`。启动参数或其他高优先级配置若覆盖此值，应先统一配置来源。

这是人工编辑步骤，不会写入仓库；不要覆盖已有数据库环境配置。修改后执行 `sudo systemctl daemon-reload`。

### 7.3 备份、替换与启动

下面命令在服务器 **root shell** 中执行，仅适用于前述已确认布局。将哈希占位符替换为构建机实际值：

```bash
set -euo pipefail
expected_sha='替换为构建机输出的SHA256'
actual_sha=$(sha256sum /tmp/family-finance-candidate.jar | cut -d ' ' -f 1)
test "$actual_sha" = "$expected_sha"
backup_dir=$(mktemp -d /root/family-finance-rollback.XXXXXX)
cp -p /root/Family-IE-Management/target/family-finance-0.0.1-SNAPSHOT.jar "$backup_dir/previous.jar"
printf '回退目录：%s\n' "$backup_dir"
install -m 644 /tmp/family-finance-candidate.jar /root/Family-IE-Management/target/family-finance-next.jar
mv /root/Family-IE-Management/target/family-finance-next.jar /root/Family-IE-Management/target/family-finance-0.0.1-SNAPSHOT.jar
systemctl restart family-finance
```

保留输出的回退目录。服务重启可能短暂返回 502；`systemctl is-active` 只是进程状态，不能代替应用就绪检查。

```bash
systemctl is-active family-finance
journalctl -u family-finance -n 40 --no-pager
curl --fail --max-time 10 http://127.0.0.1:8080/ -o /dev/null
```

确认日志出现应用启动完成后再验收浏览器。若未启动完成，查看错误原因；本次年度统计插件没有新增迁移，不应修改数据库。

### 7.4 浏览器验收

登录服务器应用，在侧边栏进入插件页：检查菜单、默认数据、筛选、无数据年份和回到本体总览。浏览器网络面板检查 `/api/plugins` 和插件 API 返回 200。

接口需要登录会话；用未登录的 curl 请求插件 API 返回 401 属正常情况。不要为方便演示关闭认证。插件默认按全服务器启用，不是按用户/家庭独立开关。

## 8. 停用与回退

停用年度统计：将配置设为 `app.plugins.annual-stats.enabled=false`（对应 systemd 环境变量 `APP_PLUGINS_ANNUALSTATS_ENABLED=false`），重启服务，再刷新页面。确认菜单消失、已登录访问对应 API 返回 404；这不会删除业务数据。

若新版启动失败，使用部署时实际记录的回退路径，在 root shell 执行：

```bash
set -euo pipefail
previous_jar='/root/family-finance-rollback.实际后缀/previous.jar'
test -f "$previous_jar"
install -m 644 "$previous_jar" /root/Family-IE-Management/target/family-finance-next.jar
mv /root/Family-IE-Management/target/family-finance-next.jar /root/Family-IE-Management/target/family-finance-0.0.1-SNAPSHOT.jar
systemctl restart family-finance
```

同时恢复本次修改的配置，再检查就绪状态。涉及数据库迁移的插件必须事先另定数据库备份与兼容方案；回退 JAR 不会撤销 Flyway 迁移。现阶段迁移编号由维护者统一分配，关闭插件也不会自动停止已打包的 Flyway 脚本执行。

## 9. 常见问题

| 现象 | 检查方向 |
|---|---|
| 菜单不出现 | 后端启用配置、Spring 扫描、清单 ID、前端 bundled 注册、协议版本、是否部署完整新 JAR |
| API 401 | 登录会话过期；重新登录 |
| API 403 | 用户权限/CSRF；写操作使用统一 request 客户端 |
| API 404 | 插件关闭、接口路径错误、实际仍运行旧 JAR |
| 启动报重复插件 | 描述符 ID 或页面路径重复 |
| 菜单出现但页面失败 | 前端组件导出、懒加载资源、API 返回契约、浏览器错误日志 |
| 数据跨家庭 | 插件越过本体授权端口，或新增端口漏做身份检查；禁止带问题上线 |
| 本地正常但线上没变化 | 核对运行 JAR 哈希和来源提交，刷新浏览器缓存 |

## 10. 可参考的真实实现

- 后端协议：`src/main/java/com/familyfinance/extension/`
- 本体查询适配器：`src/main/java/com/familyfinance/transaction/LedgerReadAdapter.java`
- 年度统计后端：`src/main/java/com/familyfinance/plugins/annualstats/AnnualStatsPlugin.java`
- 前端注册：`frontend/src/extensions/registry.tsx`
- 年度统计页面：`frontend/src/plugins/annual-stats/AnnualStatsPage.tsx`
- 认证、隔离和关闭测试：`src/test/java/com/familyfinance/extension/`
- 架构说明：[本体与内置插件](extensions.md)

交付责任：开发者提交源码、说明和测试；维护者审核端口、权限与依赖并完成集成；部署人员负责完整 JAR、运行配置、备份、就绪检查和回退。
