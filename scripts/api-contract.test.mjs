import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { compareContracts, generateTypes, interviewFocusBaseline, springBoot41Baseline } from './api-contract.mjs';

const baseline = JSON.parse(readFileSync(new URL('../contracts/openapi.json', import.meta.url), 'utf8'));
const dto = 'cn.edu.whu.zhizhida.resume.api.ResumeProfile';
const changed = mutation => { const copy = structuredClone(baseline); mutation(copy); return copy; };
const breaks = mutation => assert.notDeepEqual(compareContracts(baseline, changed(mutation)), []);

test('Boot 4 documentation additions allow exact reviewed values without hiding stricter or unrelated changes', () => {
  const old = structuredClone(baseline);
  const owner = old.components.schemas[dto].properties.ownerName;
  delete owner.minLength;
  assert.deepEqual(compareContracts(springBoot41Baseline(old, baseline), baseline), []);
  const stricter = changed(d => { d.components.schemas[dto].properties.ownerName.minLength = 8; });
  assert.notDeepEqual(compareContracts(springBoot41Baseline(old, stricter), stricter), []);
  const unrelated = changed(d => { delete d.paths['/api/resumes']; });
  assert.notDeepEqual(compareContracts(springBoot41Baseline(old, unrelated), unrelated), []);
  assert.notDeepEqual(compareContracts(springBoot41Baseline(baseline, old), old), []);
});

test('existing routes, methods and successful responses cannot disappear', () => {
  breaks(d => { delete d.paths['/api/resumes']; });
  breaks(d => { delete d.paths['/api/resumes'].post; });
  breaks(d => { delete d.paths['/api/resumes'].post.responses['200']; });
});
test('field deletion, rename, type/enum changes and nested arrays fail', () => {
  breaks(d => { delete d.components.schemas[dto].properties.ownerName; });
  breaks(d => { d.components.schemas[dto].properties.name = d.components.schemas[dto].properties.ownerName; delete d.components.schemas[dto].properties.ownerName; });
  breaks(d => { d.components.schemas[dto].properties.id.type = 'string'; });
  breaks(d => { d.components.schemas[dto].properties.skills.items.type = 'integer'; });
  breaks(d => { d.components.schemas.ErrorCode.enum.push('NEW_CODE'); });
});
test('required input, tighter validation, nullability and auth changes fail', () => {
  breaks(d => { d.components.schemas[dto].required.push('summary'); });
  breaks(d => { d.components.schemas[dto].properties.ownerName.minLength = 8; });
  breaks(d => { d.components.schemas[dto].properties.ownerName.nullable = true; });
  breaks(d => { d.paths['/api/resumes'].get.security = []; });
  breaks(d => { d.paths['/api/resumes'].get.parameters = [{ name: 'mandatory', in: 'query', required: true, schema: { type: 'string' } }]; });
});
test('description/title property names are data, not removable documentation', () => {
  const job = 'cn.edu.whu.zhizhida.jobs.api.TargetJob';
  const source = changed(d => { d.components.schemas[job].properties.description = { type: 'string' }; });
  const removed = structuredClone(source); delete removed.components.schemas[job].properties.description;
  assert.notDeepEqual(compareContracts(source, removed), []);
  breaks(d => { delete d.components.schemas[job].properties.title; });
});

test('reviewed interview-only retirement excludes old APIs but keeps active routes and schemas protected', () => {
  const old = structuredClone(baseline);
  old.paths['/api/jobs'] = structuredClone(old.paths['/api/resumes']);
  const request = 'cn.edu.whu.zhizhida.interview.api.InterviewStartRequest';
  old.components.schemas[request].properties.jobId = { type: 'integer' };
  assert.deepEqual(compareContracts(interviewFocusBaseline(old, baseline), baseline), []);
  const broken = changed(d => { delete d.paths['/api/resumes']; });
  assert.notDeepEqual(compareContracts(interviewFocusBaseline(old, broken), broken), []);
  assert.throws(() => interviewFocusBaseline(baseline, old), /reintroduced/);
  assert.deepEqual(compareContracts(baseline, changed(d => {
    d.components.schemas[request].properties.jobDescription.maxLength += 1000;
  })), []);
});
test('new paths, operations, optional fields and docs are additive', () => {
  const next = changed(d => {
    d.paths['/api/new-feature'] = structuredClone(d.paths['/api/resumes']);
    d.paths['/api/resumes'].patch = structuredClone(d.paths['/api/resumes'].post);
    d.components.schemas[dto].properties.optionalNote = { type: 'string' };
    d.components.schemas[dto].description = 'New prose';
  });
  assert.deepEqual(compareContracts(baseline, next), []);
});
test('freshly regenerating a breaking snapshot does not make it compatible with the base branch', () => {
  const next = changed(d => { delete d.components.schemas[dto].properties.summary; });
  assert.ok(generateTypes(next));
  assert.notDeepEqual(compareContracts(baseline, next), []);
});

test('request-only ranges may widen; tighter or shared/output ranges still fail', () => {
  const request = 'cn.edu.whu.zhizhida.interview.api.InterviewStartRequest';
  assert.deepEqual(compareContracts(baseline, changed(d => { d.components.schemas[request].properties.totalRounds.maximum = 20; })), []);
  breaks(d => { d.components.schemas[request].properties.totalRounds.maximum = 2; });
  breaks(d => { d.components.schemas[request].properties.totalRounds.minimum = 2; });
  assert.deepEqual(compareContracts(baseline, changed(d => { d.components.schemas[request].properties.totalRounds.minimum = 0; })), []);
  const shared = structuredClone(baseline);
  shared.paths['/api/interviews/sessions'].post.responses['200'].content['application/json'].schema = { $ref: `#/components/schemas/${request}` };
  const next = structuredClone(shared);
  next.components.schemas[request].properties.totalRounds.maximum = 20;
  assert.notDeepEqual(compareContracts(shared, next), []);
});
test('generator preserves enum/required/array/ref/null and rejects unknown schema constructs', () => {
  const d = { openapi: '3.0.1', paths: {}, components: { schemas: {
    State: { type: 'string', enum: ['A', 'B'] },
    Example: { type: 'object', required: ['state'], properties: {
      state: { $ref: '#/components/schemas/State' },
      count: { type: 'integer', nullable: true },
      rows: { type: 'array', items: { type: 'boolean' } },
    } },
  } } };
  const output = generateTypes(d);
  assert.match(output, /"state": Schemas\["State"\]/);
  assert.match(output, /"count"\?: \(number\) \| null/);
  assert.match(output, /"rows"\?: Array<boolean>/);
  assert.match(output, /"A" \| "B"/);
  d.components.schemas.Example.not = { type: 'string' };
  assert.throws(() => generateTypes(d), /Unsupported schema keyword/);
  delete d.components.schemas.Example.not;
  d.components.schemas.Example.properties.state.$ref = '#/components/schemas/Missing';
  assert.throws(() => generateTypes(d), /Unresolved/);
});
