import {useCallback,useEffect,useState} from 'react';

/** A new successful action restarts the timer, even if its message is identical. */
export function useTransientNotice(duration=3000){
 const [notice,setNotice]=useState<{message:string}|null>(null);
 const show=useCallback((message:string)=>setNotice({message}),[]);
 const dismiss=useCallback(()=>setNotice(null),[]);
 useEffect(()=>{if(!notice)return;const timer=window.setTimeout(()=>setNotice(null),duration);return ()=>window.clearTimeout(timer);},[notice,duration]);
 return {message:notice?.message??'',show,dismiss};
}
