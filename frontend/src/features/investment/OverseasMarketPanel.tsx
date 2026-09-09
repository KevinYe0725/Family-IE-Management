import {InvestmentButton as Button} from './investment-ui';
import { useId, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import Select, { type OptionProps } from '@douyinfe/semi-ui/lib/es/select';
import { StockChart } from './StockChart';
import { StockLabel } from './StockLabel';
import { useStockDropdown } from './useStockDropdown';
import { isOverseasInstrument, type OverseasInstrument, type OverseasMarket, type OverseasSearch } from './overseas-market';
import { dateText, type RequestFn } from '../common';
import './stock-picker.scss';
import { useStockSearch } from './useStockSearch';

export function OverseasMarketPanel({ request, market,initial,onBuy,onSelected,busy=false,tradingEnabled=false }: { request: RequestFn; market: OverseasMarket;initial?:OverseasInstrument;onBuy?:(value:OverseasInstrument)=>void;onSelected?:(value:OverseasInstrument)=>void;busy?:boolean;tradingEnabled?:boolean }) {
  const id = useId();
  const pickerId = `${id}-picker`;
  const dropdown = useStockDropdown(pickerId);
  const {query,setQuery,debounced,composing,compositionProps} = useStockSearch();
  const [selected, setSelected] = useState<OverseasInstrument | null>(initial??null);
  const pollStarted = useRef(Date.now());
  const search = useQuery({ queryKey: ['overseas-search', market, debounced], queryFn: async () => {
    const value = await request<OverseasSearch>(`/api/overseas-market/search?market=${market}&q=${encodeURIComponent(debounced)}`);
    if (!value || !Array.isArray(value.items) || value.items.length > 20 || !['READY','SYNCING','ERROR'].includes(value.state)
      || typeof value.stale !== 'boolean' || typeof value.hasNext !== 'boolean' || value.items.some(item => !isOverseasInstrument(item, market))) throw new Error('股票目录响应不完整');
    return value;
  }, retry: false, staleTime: 60_000, refetchInterval: state => state.state.data?.state === 'SYNCING' && Date.now() - pollStarted.current < 180_000 ? 5000 : false });
  const preparing = search.data?.state === 'SYNCING';
  const waiting = query.trim() !== debounced || search.isFetching;
  const items = search.data?.items ?? [];
  const retry = () => { pollStarted.current = Date.now(); void search.refetch(); };
  return <section className="overseas-market" aria-label={market === 'HK' ? '港股只读行情' : '美股只读行情'}>
    <p className="overseas-readonly">{tradingEnabled?'行情不会自动修改持仓，请同步实际发生的交易。':'只读行情，暂不计入家庭资产'}</p>
    <div className="stock-explorer"><div className="stock-picker" id={pickerId} style={{ position: 'relative' }} {...compositionProps}>
      <span className="stock-picker__label" id={`${id}-label`}>证券</span>
      <Select ref={dropdown.selectRef} className="stock-picker__select" aria-labelledby={`${id}-label`} filter remote onChangeWithObject inputProps={{ maxLength: 80 }}
        style={{ width: '100%' }} disabled={busy} loading={waiting} placeholder="搜索股票代码或官方名称"
        value={selected ? { value: selected.symbol, label: `${selected.symbol} · ${selected.name}`, instrument: selected } : undefined}
        optionList={items.map(item => ({ value: item.symbol, instrument: item, label: <StockLabel name={item.name} code={item.symbol} exchange={item.exchange} accessibleLabel={`${item.symbol} · ${item.name}`}/> }))}
        onSearch={setQuery} onSelect={(_value, option) => { if(composing)return;const instrument = option.instrument as OverseasInstrument; if (isOverseasInstrument(instrument, market)) {setSelected(instrument);onSelected?.(instrument);} }}
        getPopupContainer={() => document.getElementById(pickerId)!} onDropdownVisibleChange={dropdown.setMenuOpen} rePosKey={dropdown.controlWidth}
        dropdownClassName="stock-picker-dropdown" dropdownMatchSelectWidth dropdownStyle={{ width: dropdown.controlWidth || '100%', minWidth: 0, boxSizing: 'border-box' }}
        renderSelectedItem={(option: OptionProps) => { const item = option.instrument as OverseasInstrument | undefined; return item ? `${item.name} · ${item.symbol}` : option.label; }}
        emptyContent={waiting ? '正在查找股票…' : '没有找到匹配股票'}/>
      <div className="stock-picker-status" role="status">
        {search.error ? <><span>股票搜索暂时不可用</span><button type="button" className="text-action" onClick={retry}>重试搜索</button></>
          : preparing ? <><span>正在准备股票目录，首次读取可能需要一会儿。</span><button type="button" className="text-action" onClick={retry}>重试目录</button></>
            : search.data?.state === 'ERROR' ? <><span>{items.length ? '目录刷新失败，正在使用上次目录。' : '股票目录暂时不可用。'}</span><button type="button" className="text-action" onClick={retry}>重试目录</button></>
              : !search.isLoading && !waiting && !items.length ? <span>没有找到匹配股票，请检查代码或官方名称。</span>
                : search.data?.hasNext ? <span>显示前 20 条，请输入更完整的代码或名称。</span> : null}
        {search.data?.stale && <span>缓存目录 · {dateText(search.data.updatedAt)}</span>}
      </div>
    </div></div>
    {selected&&onBuy&&<Button variant="primary" disabled={busy} onClick={()=>onBuy(selected)}>{busy?'正在核对股票…':`记录买入${selected.name}`}</Button>}
    {selected?.market === market ? <StockChart key={`${market}/${selected.symbol}`} request={request} security={selected}/> : <div className="investment-market-empty"><p>选择股票，查看参考收盘价与历史走势。</p></div>}
  </section>;
}
