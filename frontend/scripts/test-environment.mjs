// 仅在 Node 测试中解析 Vite 别名和环境，要求 Node 22.14 及以上。
import { register } from 'node:module';
register('./test-loader.mjs', import.meta.url);

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
