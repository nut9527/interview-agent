// A generation also changes when the same account receives a new login token.
let controller = new AbortController();
let generation = 0;
const listeners = new Set<() => void>();

export const captureAccountScope = () => {
  const current = controller;
  return {
    signal: current.signal,
    isCurrent: () => current === controller && !current.signal.aborted,
  };
};

export const resetAccountScope = () => {
  const previous = controller;
  controller = new AbortController();
  generation += 1;
  previous.abort();
  listeners.forEach((listener) => listener());
  return generation;
};

export const onAccountReset = (listener: () => void) => {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
};
