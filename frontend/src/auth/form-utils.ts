import { ApiError } from '../api/client';

export function errorMessage(error: unknown): { message: string; requestId?: string; fields?: Record<string, string> } {
  if (error instanceof ApiError) {
    return { message: error.message, requestId: error.requestId, fields: error.fields };
  }
  return { message: '暂时无法完成操作，请稍后重试' };
}

export function focusField(field: string): void {
  queueMicrotask(() => document.getElementById(field)?.focus());
}
