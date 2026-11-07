export interface RunEvent {
  runId: string; seq: number; type: string; phase: string; message: string; sessionId?: string;
}

/** 增量解析 SSE；按 UTF-16 单位限制单个事件缓冲，畸形 JSON 或超限时抛错交给调用方恢复。 */
export function createSseDecoder(onEvent: (event: RunEvent) => void) {
  let buffer = '', data: string[] = [], dataLength = 0, skipLf = false;
  const append = (part: string) => {
    if (dataLength + buffer.length + part.length > 65536) throw new Error('进度事件超出大小限制');
    buffer += part;
  };
  const line = (value: string) => {
    if (!value) {
      if (data.length) {
        const event: unknown = JSON.parse(data.join('\n'));
        if (event && typeof event === 'object' && 'runId' in event && 'seq' in event
            && typeof event.runId === 'string' && typeof event.seq === 'number' && Number.isSafeInteger(event.seq)) {
          onEvent(event as RunEvent);
        }
      }
      data = [];
      dataLength = 0;
    } else if (value.startsWith('data:')) {
      const content = value.slice(5).replace(/^ /, '');
      data.push(content);
      dataLength += content.length + 1;
    }
  };
  return (chunk: string, end = false) => {
    let start = 0;
    for (let i = 0; i < chunk.length; i++) {
      if (skipLf) {
        skipLf = false;
        if (chunk[i] === '\n') { start = i + 1; continue; }
      }
      if (chunk[i] !== '\r' && chunk[i] !== '\n') continue;
      append(chunk.slice(start, i));
      line(buffer);
      buffer = '';
      skipLf = chunk[i] === '\r';
      start = i + 1;
    }
    append(chunk.slice(start));
    // 半截事件不可交付；持久事件按游标重放。
    if (end) { buffer = ''; data = []; dataLength = 0; skipLf = false; }
  };
}

