import { useCallback, useId, useLayoutEffect, useRef, useState, type ChangeEvent } from 'react';
import DatePicker from '@douyinfe/semi-ui/lib/es/datePicker';
import { CalendarDays, X } from 'lucide-react';
import './DateField.scss';

export type DateFieldProps = {
  value?: string;
  onChange: (event: ChangeEvent<HTMLInputElement>) => void;
  mode?: 'date' | 'month';
  name?: string;
  id?: string;
  'aria-label'?: string;
  'aria-labelledby'?: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean | 'false' | 'true';
  required?: boolean;
  min?: string;
  max?: string;
  disabled?: boolean;
  readOnly?: boolean;
  allowClear?: boolean;
  className?: string;
  placeholder?: string;
};

type TriggerProps = {
  inputValue?: string | null;
  onChange?: (value: string, event: ChangeEvent<HTMLInputElement>) => void;
  onEnterPress?: (value: string) => void;
  onBlur?: (event: React.FocusEvent<HTMLInputElement>) => void;
  onFocus?: (event: React.FocusEvent<HTMLInputElement>) => void;
};

function valueFromDate(date: Date, mode: 'date' | 'month') {
  const year = String(date.getFullYear()).padStart(4, '0');
  const month = String(date.getMonth() + 1).padStart(2, '0');
  if (mode === 'month') return `${year}-${month}`;
  return `${year}-${month}-${String(date.getDate()).padStart(2, '0')}`;
}

function validityMessage(value: string, mode: 'date' | 'month', min?: string, max?: string) {
  if (!value) return '';
  const parts = value.split('-').map(Number);
  const [year, month, day] = parts;
  const shapeMatches = mode === 'date' ? /^\d{4}-\d{2}-\d{2}$/.test(value) : /^\d{4}-\d{2}$/.test(value);
  const validMonth = shapeMatches && year >= 1 && month >= 1 && month <= 12;
  const validDay = mode === 'month' || Boolean(validMonth && day >= 1 && new Date(year, month - 1, day).getFullYear() === year && new Date(year, month - 1, day).getMonth() === month - 1 && new Date(year, month - 1, day).getDate() === day);
  if (!validMonth || !validDay) return mode === 'date' ? '请输入有效日期（YYYY-MM-DD）' : '请输入有效月份（YYYY-MM）';
  if (min && value < min) return `${mode === 'date' ? '日期' : '月份'}不能早于 ${min}`;
  if (max && value > max) return `${mode === 'date' ? '日期' : '月份'}不能晚于 ${max}`;
  return '';
}

function manualValidityMessage(value: string, mode: 'date' | 'month', min: string | undefined, max: string | undefined, required: boolean, allowClear: boolean, appliedValue: string) {
  const message = !value && (required || !allowClear)
    ? `请选择${mode === 'date' ? '日期' : '月份'}`
    : validityMessage(value, mode, min, max);
  return message && !allowClear && appliedValue ? `${message}；当前仍使用 ${appliedValue}` : message;
}

function changeEventFor(input: HTMLInputElement | null, value: string): ChangeEvent<HTMLInputElement> {
  const target = input ? input.cloneNode(false) as HTMLInputElement : document.createElement('input');
  target.value = value;
  return {
    target,
    currentTarget: target,
    type: 'change',
    bubbles: true,
    cancelable: false,
    defaultPrevented: false,
    eventPhase: Event.AT_TARGET,
    isTrusted: false,
    nativeEvent: new Event('change'),
    preventDefault: () => undefined,
    isDefaultPrevented: () => false,
    stopPropagation: () => undefined,
    isPropagationStopped: () => false,
    persist: () => undefined,
    timeStamp: Date.now()
  };
}

export function DateField({
  value,
  onChange,
  mode = 'date',
  name,
  id,
  required,
  min,
  max,
  disabled = false,
  readOnly = false,
  allowClear = true,
  className = '',
  placeholder,
  'aria-describedby': ariaDescribedBy,
  'aria-invalid': ariaInvalid,
  ...ariaProps
}: DateFieldProps) {
  const normalizedValue = value ?? '';
  const errorId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [fieldsetDisabled, setFieldsetDisabled] = useState(false);
  const [manualText, setManualText] = useState<string | null>(null);
  const [manualError, setManualError] = useState('');
  const openRef = useRef(open);
  const keyboardOpenRef = useRef(false);
  openRef.current = open;
  const effectiveDisabled = disabled || fieldsetDisabled;

  const emitValue = useCallback((nextValue: string) => {
    inputRef.current?.setCustomValidity(validityMessage(nextValue, mode, min, max));
    if (!effectiveDisabled && !readOnly) {
      setManualText(null);
      setManualError('');
      onChange(changeEventFor(inputRef.current, nextValue));
    }
  }, [effectiveDisabled, max, min, mode, onChange, readOnly]);

  const disabledDate = useCallback((date?: Date) => {
    if (!date) return false;
    const candidate = valueFromDate(date, mode);
    return Boolean((min && candidate < min) || (max && candidate > max));
  }, [max, min, mode]);

  useLayoutEffect(() => {
    const ancestors: HTMLFieldSetElement[] = [];
    let parent = rootRef.current?.parentElement;
    while (parent) {
      if (parent instanceof HTMLFieldSetElement) ancestors.push(parent);
      parent = parent.parentElement;
    }
    const sync = () => setFieldsetDisabled(ancestors.some(fieldset => fieldset.disabled));
    const observer = new MutationObserver(sync);
    ancestors.forEach(fieldset => observer.observe(fieldset, { attributes: true, attributeFilter: ['disabled'] }));
    sync();
    return () => observer.disconnect();
  }, []);

  useLayoutEffect(() => {
    const input = inputRef.current;
    input?.setCustomValidity(validityMessage(normalizedValue, mode, min, max));
  }, [max, min, mode, normalizedValue]);

  useLayoutEffect(() => {
    if (!open || !keyboardOpenRef.current) return;
    // Keep centered dialogs' focus/keyboard scope; scrolling side drawers use body.
    const container = rootRef.current?.closest<HTMLElement>('.action-dialog, .semi-modal-content') ?? document.body;
    const focusCalendar = () => {
      const selected = container.querySelector<HTMLElement>('.unified-date-calendar [role="gridcell"][aria-selected="true"]:not([aria-disabled="true"])');
      const firstEnabled = container.querySelector<HTMLElement>('.unified-date-calendar [role="gridcell"][aria-label]:not([aria-disabled="true"])');
      const target = selected ?? firstEnabled;
      if (!target) return false;
      keyboardOpenRef.current = false;
      target.focus({ preventScroll: true });
      return true;
    };
    if (focusCalendar()) return;
    const observer = new MutationObserver(() => {
      if (focusCalendar()) observer.disconnect();
    });
    observer.observe(container, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, [open]);

  useLayoutEffect(() => {
    const closeCalendarFirst = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || !openRef.current) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      keyboardOpenRef.current = false;
      setOpen(false);
      inputRef.current?.focus({ preventScroll: true });
    };
    document.addEventListener('keydown', closeCalendarFirst, true);
    return () => document.removeEventListener('keydown', closeCalendarFirst, true);
  }, []);

  useLayoutEffect(() => {
    if ((effectiveDisabled || readOnly) && open) setOpen(false);
  }, [effectiveDisabled, open, readOnly]);

  return (
    <div ref={rootRef} className={`unified-date-field ${className}`.trim()}>
      <DatePicker
        type={mode}
        motion={false}
        format={mode === 'date' ? 'yyyy-MM-dd' : 'yyyy-MM'}
        value={normalizedValue}
        open={open}
        disabled={effectiveDisabled}
        inputReadOnly={readOnly}
        disabledDate={disabledDate}
        dropdownClassName="unified-date-calendar"
        position="bottomLeft"
        zIndex={1200}
        getPopupContainer={() =>
          rootRef.current?.closest<HTMLElement>('.action-dialog, .semi-modal-content') ??
          document.body
        }
        onOpenChange={nextOpen => {
          if (nextOpen) keyboardOpenRef.current = false;
          setOpen(readOnly ? false : nextOpen);
        }}
        onChange={(_date, dateText) => emitValue(typeof dateText === 'string' ? dateText : '')}
        triggerRender={rawTrigger => {
          const trigger = rawTrigger as unknown as TriggerProps;
          const displayValue = manualText ?? normalizedValue;
          const describedBy = [ariaDescribedBy, manualError ? errorId : ''].filter(Boolean).join(' ') || undefined;
          return (
            <div className="unified-date-field__trigger">
              <input
                ref={inputRef}
                type="text"
                inputMode="numeric"
                autoComplete="off"
                value={displayValue}
                name={name}
                id={id}
                required={required}
                min={min}
                max={max}
                disabled={effectiveDisabled}
                readOnly={readOnly}
                placeholder={placeholder ?? (mode === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM')}
                {...ariaProps}
                aria-describedby={describedBy}
                aria-invalid={manualError ? true : ariaInvalid}
                onChange={event => {
                  const nextValue = event.target.value;
                  const message = manualValidityMessage(nextValue, mode, min, max, Boolean(required), allowClear, normalizedValue);
                  setManualText(nextValue);
                  setManualError(message);
                  event.currentTarget.setCustomValidity(message);
                  if (!nextValue && !allowClear) return;
                  trigger.onChange?.(event.target.value, event);
                }}
                onKeyDown={event => {
                  if (event.key === 'ArrowDown') {
                    event.preventDefault();
                    keyboardOpenRef.current = true;
                    setOpen(true);
                  } else if (event.key === 'Enter' && open) {
                    event.preventDefault();
                    trigger.onEnterPress?.(event.currentTarget.value);
                  }
                }}
                onBlur={event => {
                  trigger.onBlur?.(event);
                  if (!manualError) return;
                  setManualText(null);
                  setManualError('');
                  event.currentTarget.setCustomValidity(validityMessage(normalizedValue, mode, min, max));
                }}
                onFocus={trigger.onFocus}
              />
              {normalizedValue && allowClear && !effectiveDisabled && !readOnly && (
                <span
                  role="button"
                  tabIndex={0}
                  className="unified-date-field__clear"
                  aria-label={mode === 'date' ? '清除日期' : '清除月份'}
                  onClick={event => {
                    event.preventDefault();
                    event.stopPropagation();
                    trigger.onChange?.('', event as unknown as ChangeEvent<HTMLInputElement>);
                    inputRef.current?.focus();
                  }}
                  onKeyDown={event => {
                    if (event.key !== 'Enter' && event.key !== ' ') return;
                    event.preventDefault();
                    event.stopPropagation();
                    trigger.onChange?.('', event as unknown as ChangeEvent<HTMLInputElement>);
                    inputRef.current?.focus();
                  }}
                ><X size={15} aria-hidden="true" /></span>
              )}
              <CalendarDays className="unified-date-field__icon" size={17} aria-hidden="true" />
            </div>
          );
        }}
      />
      {manualError && <p id={errorId} className="unified-date-field__error" role="alert">{manualError}</p>}
    </div>
  );
}
