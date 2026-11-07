import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout } from 'node:timers/promises';

async function request(origin, token, path, body, expected = 200) {
  const response = await fetch(origin + path, {
    method: body ? 'POST' : 'GET', signal: AbortSignal.timeout(30000),
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: body ? JSON.stringify(body) : undefined,
  });
  const result = await response.json();
  assert.equal(response.status, expected, JSON.stringify(result));
  return result.data;
}

/** Acceptance clients follow the same 202/status/session contract as the UI. */
export async function awaitInterviewRun(origin, token, initial) {
  let run = initial;
  const deadline = Date.now() + 180000;
  while (['ACCEPTED', 'RUNNING'].includes(run.status) && Date.now() < deadline) {
    await setTimeout(500);
    run = await request(origin, token, `/api/interview-runs/${run.runId}`);
  }
  assert.equal(run.status, 'SUCCEEDED', JSON.stringify(run));
  assert.ok(run.sessionId);
  return request(origin, token, `/api/interviews/sessions/${run.sessionId}`);
}

export async function startInterview(origin, token, configuration) {
  const run = await request(origin, token, '/api/interviews/start-commands', { requestId: randomUUID(), configuration }, 202);
  return awaitInterviewRun(origin, token, run);
}

export async function answerInterview(origin, token, sessionId, answer) {
  const run = await request(origin, token, `/api/interviews/sessions/${sessionId}/answer-commands`, answer, 202);
  return awaitInterviewRun(origin, token, run);
}

export async function startTrainingTask(origin, token, id) {
  const run = await request(origin, token, `/api/interviews/training-tasks/${id}/start-commands`, { requestId: randomUUID() }, 202);
  return awaitInterviewRun(origin, token, run);
}
