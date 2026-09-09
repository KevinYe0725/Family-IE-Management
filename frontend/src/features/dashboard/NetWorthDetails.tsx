import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { NetWorth } from '../../api/contracts';
import { ActionDialog, QueryState, money, type RequestFn } from '../common';
import { historyBasisLabel, historyValuationLabel } from '../visuals';

export function NetWorthDetails({ request, data, onClose }: { request: RequestFn; data?: NetWorth; onClose: () => void }) {
  const [day, setDay] = useState<string | null>(null);
  const versions = useQuery({ queryKey: ['snapshot-revisions', day], queryFn: () => request<Array<{ id: number; asset: string; liability: string; netWorth: string; recordedAt: string }>>('/api/net-worth/snapshot-revisions?on='+day), enabled: day !== null });
  return <ActionDialog open title={day ? `${day} · 快照版本` : '净资产详情'} onClose={onClose} size="wide">
    {day ? <><button className="home-link" onClick={() => setDay(null)}>返回净资产详情</button><QueryState loading={versions.isLoading} error={versions.error} empty={!versions.data?.length}><div className="home-detail-table"><table><thead><tr><th>记录时间</th><th>资产</th><th>负债</th><th>净资产</th></tr></thead><tbody>{versions.data?.map(row => <tr key={row.id}><td>{row.recordedAt}</td><td>{money(row.asset)}</td><td>{money(row.liability)}</td><td>{money(row.netWorth)}</td></tr>)}</tbody></table></div></QueryState></> : <>
      <h3>资产配置</h3>
      {data?.netWorth == null ? <div role="status"><p>资产配置待补齐估值</p>{!!data?.unconverted?.length && <a className="home-link" href="/workspace/investments?tab=rates">补充汇率</a>}</div> : !data.allocation.length ? <p>暂无资产配置</p> : <div className="home-allocation">{data.allocation.map(row => <div key={row.type}><span>{({ACCOUNT:'现金账户', PROPERTY:'房产', VEHICLE:'车辆', OTHER:'其他资产', INVESTMENT:'投资'} as Record<string,string>)[row.type] ?? row.type}</span><strong>{money(row.amount)}</strong><span>{row.sharePercent}%</span><div aria-hidden="true"><i style={{width:`${Math.max(0,Math.min(100,Number(row.sharePercent)||0))}%`}}/></div></div>)}</div>}
      <h3>净资产历史</h3>{!data?.history.length ? <p>暂无历史快照</p> : <div className="home-detail-table"><table><thead><tr><th>日期</th><th>净资产</th><th>已保存快照</th><th>统计口径</th></tr></thead><tbody>{[...data.history].sort((a,b) => b.snapshotOn.localeCompare(a.snapshotOn)).map(row => <tr key={row.snapshotOn}><td>{row.snapshotOn}</td><td>{money(row.netWorth)}</td><td><button className="home-link" onClick={() => setDay(row.snapshotOn)}>{money(row.recordedNetWorth ?? row.netWorth)}</button></td><td>{historyValuationLabel(row)} · {historyBasisLabel(row)}</td></tr>)}</tbody></table></div>}
      <details><summary>计算说明</summary><p>净资产 = 总资产 − 总负债。累计资产估值变动 {money(data?.cumulativeAssetValuationChange)} 已包含在净资产中，不重复加计。</p><p>缺价持仓可能使用成本估算；缺少汇率时，净资产不会展示为完整数值。</p></details>
    </>}
  </ActionDialog>;
}
