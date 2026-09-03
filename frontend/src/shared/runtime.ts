export function localYearMonth(date: Date = new Date()): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  return `${year}-${month}`;
}

export class RefreshGate {
  private generation = 0;

  invalidate(): void {
    this.generation += 1;
  }

  async run<T>(work: () => Promise<T>, commit: (value: T) => void): Promise<{ current: boolean }> {
    const generation = ++this.generation;
    try {
      const value = await work();
      if (generation !== this.generation) return { current: false };
      commit(value);
      return { current: true };
    } catch (error) {
      if (generation !== this.generation) return { current: false };
      throw error;
    }
  }
}

export interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T | PromiseLike<T>) => void;
  reject: (reason?: unknown) => void;
}

export function deferred<T>(): Deferred<T> {
  let resolve!: Deferred<T>['resolve'];
  let reject!: Deferred<T>['reject'];
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}
