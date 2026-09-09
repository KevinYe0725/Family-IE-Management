import { useState } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DateField } from './DateField';
import { Drawer } from '../features/common';

function ControlledDate({ mode = 'date' }: { mode?: 'date' | 'month' }) {
  const [value, setValue] = useState(mode === 'date' ? '2024-02-28' : '2024-02');
  return (
    <form data-testid="date-form">
      <label>{mode === 'date' ? '交易日期' : '账期'}
        <DateField
          mode={mode}
          name="occurredOn"
          required
          value={value}
          onChange={event => setValue(event.target.value)}
        />
      </label>
      <output>{value}</output>
    </form>
  );
}

it('keeps controlled ISO day values and native form names compatible', async () => {
  const user = userEvent.setup();
  render(<ControlledDate />);

  const input = screen.getByRole('textbox', { name: '交易日期' });
  await user.clear(input);
  await user.type(input, '2024-02-29');

  expect(screen.getByText('2024-02-29')).toBeInTheDocument();
  expect(new FormData(screen.getByTestId('date-form') as HTMLFormElement).get('occurredOn')).toBe('2024-02-29');
  expect(input).toBeRequired();
});

it('opens the real Chinese Semi calendar inside a drawer and selects a leap day', async () => {
  const user = userEvent.setup();
  render(<aside className="side-sheet"><ControlledDate /></aside>);

  await user.click(screen.getByRole('textbox', { name: '交易日期' }));
  const leapDay = await screen.findByRole('gridcell', { name: '2024-02-29' });

  expect(screen.getByRole('button', { name: '2024年 2月' })).toBeInTheDocument();
  expect(screen.getAllByRole('columnheader').map(header => header.textContent)).toEqual(['日', '一', '二', '三', '四', '五', '六']);
  expect(leapDay.closest('.unified-date-calendar')).toBeInTheDocument();
  // 日历挂在 body：不再被抽屉容器剪切/遮挡
  expect(leapDay.closest('.side-sheet')).not.toBeInTheDocument();
  await user.click(leapDay);
  expect(screen.getByText('2024-02-29')).toBeInTheDocument();
});

it('emits an ISO month string without converting through a timezone', async () => {
  const user = userEvent.setup();
  render(<ControlledDate mode="month" />);

  const input = screen.getByRole('textbox', { name: '账期' });
  await user.clear(input);
  await user.type(input, '2025-01');

  expect(screen.getByText('2025-01')).toBeInTheDocument();
  expect(input).toHaveValue('2025-01');
});

it('restores the controlled value on blur after invalid manual text', async () => {
  const user = userEvent.setup();
  render(<label>账期<DateField mode="month" value="2026-09" onChange={() => undefined} /></label>);

  const input = screen.getByRole('textbox', { name: '账期' });
  input.focus();
  await user.clear(input);
  await user.type(input, '2026-13');
  expect(screen.getByText(/请输入有效月份（YYYY-MM）/)).toBeInTheDocument();

  await user.tab();
  expect(input).toHaveValue('2026-09');
  expect(screen.queryByText(/请输入有效月份（YYYY-MM）/)).not.toBeInTheDocument();
});

it('does not expose or emit clearing when allowClear is false', async () => {
  const user = userEvent.setup();
  const changed = vi.fn();
  render(<label>筛选月份<DateField mode="month" allowClear={false} value="2026-09" onChange={changed} /></label>);

  const input = screen.getByRole('textbox', { name: '筛选月份' });
  expect(screen.queryByRole('button', { name: '清除月份' })).not.toBeInTheDocument();
  input.focus();
  await user.clear(input);
  expect(changed).not.toHaveBeenCalled();
  expect(screen.getByText('请选择月份；当前仍使用 2026-09')).toBeInTheDocument();

  await user.tab();
  expect(input).toHaveValue('2026-09');
});

it('opens from the keyboard and moves focus into the selected calendar day', async () => {
  const user = userEvent.setup();
  render(<label>交易日期<DateField value="2026-09-09" onChange={() => undefined} /></label>);

  const input = screen.getByRole('textbox', { name: '交易日期' });
  input.focus();
  await user.keyboard('{ArrowDown}');

  const selectedDay = await screen.findByRole('gridcell', { name: '2026-09-09' });
  await waitFor(() => expect(selectedDay).toHaveFocus());
  fireEvent.keyDown(selectedDay, { key: 'Escape' });
  expect(input).toHaveFocus();
});

it('allows Enter to submit when the focused field is not editing an open calendar', async () => {
  const user = userEvent.setup();
  const submitted = vi.fn((event: React.FormEvent) => event.preventDefault());
  render(<form onSubmit={submitted}><label>交易日期<DateField name="tradedOn" value="2026-09-09" onChange={() => undefined} /></label></form>);

  screen.getByRole('textbox', { name: '交易日期' }).focus();
  await user.keyboard('{Enter}');

  expect(submitted).toHaveBeenCalledOnce();
});

it('rejects impossible and out-of-range manual dates while allowing a bounded leap day', async () => {
  const user = userEvent.setup();

  function BoundedDate() {
    const [value, setValue] = useState('2024-02-28');
    return <><label>到账日<DateField min="2024-02-10" max="2024-02-29" value={value} onChange={event => setValue(event.target.value)} /></label><output>{value}</output></>;
  }

  render(<BoundedDate />);
  const input = screen.getByRole('textbox', { name: '到账日' });

  await user.clear(input);
  await user.type(input, '2024-02-30');
  expect(input).toBeInvalid();
  expect(screen.getByRole('alert')).toHaveTextContent('请输入有效日期（YYYY-MM-DD）');
  expect(screen.queryByText('2024-02-30')).not.toBeInTheDocument();

  await user.clear(input);
  await user.type(input, '2024-03-01');
  expect(input).toBeInvalid();
  expect(screen.queryByText('2024-03-01')).not.toBeInTheDocument();

  await user.clear(input);
  await user.type(input, '2024-02-29');
  expect(input).toBeValid();
  expect(screen.getByText('2024-02-29')).toBeInTheDocument();

  await user.click(input);
  expect(await screen.findByRole('gridcell', { name: '2024-02-09' })).toHaveAttribute('aria-disabled', 'true');
});

it('supports explicit clear and keeps ids and error associations on the focusable input', async () => {
  const user = userEvent.setup();
  const changed = vi.fn();
  render(
    <label htmlFor="settled-on">结算日期
      <DateField
        id="settled-on"
        name="settledOn"
        aria-label="结算日期"
        aria-describedby="settled-on-error"
        aria-invalid="true"
        value="2026-09-09"
        onChange={changed}
      />
    </label>
  );

  const input = screen.getByRole('textbox', { name: '结算日期' });
  expect(input).toHaveAttribute('name', 'settledOn');
  expect(input).toHaveAttribute('aria-describedby', 'settled-on-error');
  expect(input).toHaveAttribute('aria-invalid', 'true');
  expect(input.closest('label')).toHaveProperty('control', input);
  expect(input.closest('label')?.querySelectorAll('input, button, select, textarea')).toHaveLength(1);

  await user.click(screen.getByRole('button', { name: '清除日期' }));
  expect(changed).toHaveBeenCalledOnce();
  expect(changed.mock.calls[0][0].target.value).toBe('');
  expect(input).toHaveFocus();
  expect(screen.queryByRole('grid')).not.toBeInTheDocument();
});

it('does not open or mutate disabled, read-only, or disabled-fieldset fields', async () => {
  const user = userEvent.setup();
  const changed = vi.fn();
  render(
    <>
      <label>停用日期<DateField disabled value="2026-09-09" onChange={changed} /></label>
      <label>只读日期<DateField readOnly value="2026-09-09" onChange={changed} /></label>
      <fieldset disabled><label>忙碌日期<DateField value="2026-09-09" onChange={changed} /></label></fieldset>
    </>
  );

  const disabledInput = screen.getByRole('textbox', { name: '停用日期' });
  const readOnlyInput = screen.getByRole('textbox', { name: '只读日期' });
  const busyInput = screen.getByRole('textbox', { name: '忙碌日期' });
  expect(disabledInput).toBeDisabled();
  expect(readOnlyInput).toHaveAttribute('readonly');
  expect(busyInput).toBeDisabled();

  await user.click(readOnlyInput);
  expect(screen.queryByRole('grid')).not.toBeInTheDocument();
  expect(changed).not.toHaveBeenCalled();
});

it('closes the calendar before the enclosing drawer and restores trigger focus', async () => {
  const user = userEvent.setup();

  function DrawerDate() {
    const [drawerOpen, setDrawerOpen] = useState(true);
    const [value, setValue] = useState('2026-09-09');
    return <><output aria-label="drawer-state">{drawerOpen ? 'open' : 'closed'}</output><Drawer open={drawerOpen} title="记录交易" onClose={() => setDrawerOpen(false)}><form><label>交易日期<DateField value={value} onChange={event => setValue(event.target.value)} /></label></form></Drawer></>;
  }

  render(<DrawerDate />);
  const input = screen.getByRole('textbox', { name: '交易日期' });
  await user.click(input);
  const calendar = await screen.findByRole('grid');
  const focusedDate = within(calendar).getByRole('gridcell', { name: '2026-09-09' });
  focusedDate.focus();
  expect(focusedDate).toHaveFocus();

  fireEvent.keyDown(focusedDate, { key: 'Escape' });
  expect(screen.getByRole('dialog', { name: '记录交易' })).toBeInTheDocument();
  expect(input).toHaveFocus();
  expect(screen.queryByRole('grid')).not.toBeInTheDocument();

  fireEvent.keyDown(input, { key: 'Escape' });
  expect(screen.queryByRole('dialog', { name: '记录交易' })).not.toBeInTheDocument();
  expect(screen.getByRole('status', { name: 'drawer-state' })).toHaveTextContent('closed');
});

it('calendar navigation buttons never submit their parent form', async () => {
  const user = userEvent.setup();
  const submitted = vi.fn((event: React.FormEvent) => event.preventDefault());
  render(<aside className="side-sheet"><form onSubmit={submitted}><label>日期<DateField value="2026-09-09" onChange={() => undefined} /></label></form></aside>);

  await user.click(screen.getByRole('textbox', { name: '日期' }));
  await user.click(await screen.findByRole('button', { name: 'Previous month' }));
  expect(submitted).not.toHaveBeenCalled();
});
