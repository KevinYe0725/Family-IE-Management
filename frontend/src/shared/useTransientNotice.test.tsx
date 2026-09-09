import {act,fireEvent,render,screen} from '@testing-library/react';
import {useTransientNotice} from './useTransientNotice';
function Harness(){const n=useTransientNotice();return <><button onClick={()=>n.show('已保存')}>保存</button><button onClick={n.dismiss}>关闭</button>{n.message&&<p role="status">{n.message}</p>}</>;}
afterEach(()=>vi.useRealTimers());
it('dismisses success after three seconds, restarting for an identical new success',()=>{
 vi.useFakeTimers();render(<Harness/>);fireEvent.click(screen.getByText('保存'));
 act(()=>vi.advanceTimersByTime(2000));fireEvent.click(screen.getByText('保存'));
 act(()=>vi.advanceTimersByTime(2000));expect(screen.getByRole('status')).toHaveTextContent('已保存');
 act(()=>vi.advanceTimersByTime(1000));expect(screen.queryByRole('status')).not.toBeInTheDocument();
});
it('can be dismissed manually',()=>{render(<Harness/>);fireEvent.click(screen.getByText('保存'));fireEvent.click(screen.getByText('关闭'));expect(screen.queryByRole('status')).not.toBeInTheDocument();});
