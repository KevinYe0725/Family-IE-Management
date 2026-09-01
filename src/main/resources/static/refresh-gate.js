export class RefreshGate {
  #generation = 0;

  invalidate() {
    this.#generation += 1;
  }

  async run(work, commit) {
    const generation = ++this.#generation;
    try {
      const value = await work();
      if (generation !== this.#generation) return { current: false };
      commit(value);
      return { current: true };
    } catch (error) {
      if (generation !== this.#generation) return { current: false };
      throw error;
    }
  }
}
