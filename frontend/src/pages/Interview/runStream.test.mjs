import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSseDecoder } from './runStream.ts';
test('every character boundary, CRLF and multiline data preserve one event', () => {
  const source = ': heartbeat\r\nid: 7\r\nevent: tool.completed\r\ndata: {"runId":"r1",\r\ndata: "seq":7,"type":"tool.completed","phase":"TOOL","message":"资料核对完成"}\r\n\r\n';
  for (let split = 0; split <= source.length; split++) {
    const events = [], consume = createSseDecoder(e => events.push(e));
    consume(source.slice(0, split)); consume(source.slice(split)); consume('', true);
    assert.equal(events.length, 1); assert.equal(events[0].seq, 7);
  }
});
test('incomplete final event is replayable and heartbeat has no sequence', () => {
  const events = [], consume = createSseDecoder(e => events.push(e));
  consume(': heartbeat\n\ndata: {"runId":"r","seq":1}\n', true);
  assert.equal(events.length, 0);
});
test('bounded buffer disconnects malformed streams', () => {
  assert.throws(() => createSseDecoder(() => {})('a'.repeat(65537)));
});

test('one network chunk may contain many individually bounded events', () => {
  const events = [], consume = createSseDecoder(event => events.push(event));
  const source = Array.from({ length: 3000 }, (_, seq) => `data: ${JSON.stringify({ runId: 'r', seq })}\n\n`).join('');
  assert.ok(source.length > 65536);
  consume(source);
  assert.equal(events.length, 3000);
  assert.equal(events.at(-1).seq, 2999);
});

test('multiline data cannot bypass the event limit across network chunks', () => {
  const consume = createSseDecoder(() => assert.fail('oversized event must not be dispatched'));
  consume('data: {"runId":"r",\n');
  for (let i = 0; i < 60; i++) consume(`data: ${' '.repeat(1024)}\n`);
  assert.throws(() => {
    for (let i = 0; i < 5; i++) consume(`data: ${' '.repeat(1024)}\n`);
  }, /大小限制/);
});

test('event size resets after dispatch and split CRLF survives empty chunks', () => {
  const events = [], consume = createSseDecoder(event => events.push(event));
  const payload = JSON.stringify({ runId: 'r', seq: 1, message: 'x'.repeat(40000) });
  for (let i = 0; i < 3; i++) {
    consume(`data: ${payload}\r`); consume(''); consume('\n\r'); consume('\n');
  }
  assert.equal(events.length, 3);
});
