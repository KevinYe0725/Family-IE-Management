import {cents,decimal} from '../accounting';
export interface DailyFxRow {currency:string;cnyPerUnit?:string|null;effectiveOn?:string|null;state?:string;source?:string|null;batchId?:number|null}
export interface DailyFxTable {asOf:string;rows:DailyFxRow[];refreshState?:string}
function units(raw:string|undefined|null):bigint|null{
 if(!raw||!/^\d{1,12}(\.\d{1,12})?$/.test(raw))return null;
 const [whole,fraction='']=raw.split('.');const result=BigInt(whole)*1000000000000n+BigInt(fraction.padEnd(12,'0'));
 return result>0n?result:null;
}
export function estimateFxArrival(amount:string,fromRate:string,toRate:string):string|null{
 const value=cents(amount),from=units(fromRate),to=units(toRate);
 if(value==null||value<=0n||from==null||to==null)return null;
 const result=(value*from+to/2n)/to;
 return result>0n&&result<=99999999999n?decimal(result):null;
}
export function dailyFxPair(table:DailyFxTable|undefined,fromCurrency:string|undefined,toCurrency:string|undefined,day:string|undefined){
 if(!table||!Array.isArray(table.rows)||!day||table.asOf!==day||!fromCurrency||!toCurrency||fromCurrency===toCurrency)return null;
 const from=table.rows.find(r=>r.currency===fromCurrency),to=table.rows.find(r=>r.currency===toCurrency);
 if(!from||!to||!units(from.cnyPerUnit)||!units(to.cnyPerUnit)||from.state!=='READY'||to.state!=='READY')return null;
 if([from,to].some(r=>r.currency==='CNY'&&units(r.cnyPerUnit)!==1000000000000n))return null;
 const publications=[from,to].filter(r=>r.currency!=='CNY');
 if(publications.some(r=>!r.effectiveOn||!/^\d{4}-\d{2}-\d{2}$/.test(r.effectiveOn)||r.effectiveOn>day||r.batchId==null))return null;
 if(publications.some(r=>r.effectiveOn!==publications[0].effectiveOn||r.batchId!==publications[0].batchId))return null;
 return {fromRate:from.cnyPerUnit!,toRate:to.cnyPerUnit!,effectiveOn:publications[0].effectiveOn!,source:publications[0].source};
}
