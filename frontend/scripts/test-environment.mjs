const storage = () => {
  const values = new Map();
  return {
    get length() { return values.size; }, key: (index) => [...values.keys()][index] ?? null,
    getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key), clear: () => values.clear(),
  };
};
globalThis.localStorage = storage();
globalThis.sessionStorage = storage();
