// 同一账号获得新的登录令牌时，也会切换账号代次。
let controller = new AbortController();
let generation = 0;
const listeners = new Set<() => void>();

/** 捕获当前登录代次；请求取消是协作式的，异步结果写回前仍须检查 isCurrent。 */
export const captureAccountScope = () => {
  const current = controller;
  return {
    signal: current.signal,
    isCurrent: () => current === controller && !current.signal.aborted,
  };
};

export const resetAccountScope = () => {
  const previous = controller;
  // 先发布新作用域，确保 abort 回调或清理监听器不会重新捕获旧身份。
  controller = new AbortController();
  generation += 1;
  previous.abort();
  listeners.forEach((listener) => listener());
  return generation;
};

/** 注册账号切换时的私有状态清理；返回值用于解除订阅。 */
export const onAccountReset = (listener: () => void) => {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
};
