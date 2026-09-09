import {useState,type ComponentProps} from 'react';
import Button from '@douyinfe/semi-ui/lib/es/button';
import Dropdown from '@douyinfe/semi-ui/lib/es/dropdown';
import {MoreHorizontal,CircleAlert} from 'lucide-react';
import './investment-ui.scss';

type ButtonProps=ComponentProps<typeof Button>&{variant?:'primary'|'secondary'|'quiet'|'danger'};
export function InvestmentButton({variant,theme,type,className='',...props}:ButtonProps){
 const intent=variant??(type==='danger'?'danger':theme==='solid'?'primary':'secondary');
 return <Button {...props} theme={intent==='primary'?'solid':intent==='quiet'?'borderless':'outline'} type={intent==='danger'?'danger':intent==='primary'?'primary':'tertiary'} className={`inv-button inv-button--${intent} ${className}`}/>;
}
export function InvestmentActions({label,triggerLabel,actions,disabled=false}:{disabled?:boolean;label:string;triggerLabel?:string;actions:Array<{label:string;onClick:()=>void;danger?:boolean}>}){
 const [open,setOpen]=useState(false);
 if(!actions.length)return null;
 return <Dropdown trigger="click" visible={open} onVisibleChange={setOpen} motion={false} position="bottomRight" contentClassName="investment-menu" menu={actions.map(action=>({node:'item',disabled,name:action.label,type:action.danger?'danger':undefined,onClick:()=>{setOpen(false);action.onClick();}}))}>
  <InvestmentButton disabled={disabled} variant="quiet" aria-label={label} aria-haspopup="menu" aria-expanded={open}>{triggerLabel??<MoreHorizontal size={18}/>}</InvestmentButton>
 </Dropdown>;
}
export function InvestmentValuationStatus({failed,busy,onRefresh}:{failed:boolean;busy:boolean;onRefresh:()=>void}){
 if(!failed)return null;
 return <div className="investment-data-issue" role="status"><CircleAlert size={17} aria-hidden="true"/><div><strong>报价更新失败</strong><p>保留最近可用数据，请勿将其当作当前成交价。</p></div><InvestmentButton variant="quiet" size="small" disabled={busy} onClick={onRefresh}>重试报价</InvestmentButton></div>;
}
