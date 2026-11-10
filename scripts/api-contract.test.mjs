import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { compareContracts, generateTypes, interviewFocusBaseline, springBoot41Baseline, registeredErrorCodeBaseline, resumeImportResultBaseline } from './api-contract.mjs';

const baseline = JSON.parse(readFileSync(new URL('../contracts/openapi.json', import.meta.url), 'utf8'));
const dto = 'cn.edu.whu.zhizhida.resume.api.ResumeProfile';
const changed = mutation => { const copy = structuredClone(baseline); mutation(copy); return copy; };
const breaks = mutation => assert.notDeepEqual(compareContracts(baseline, changed(mutation)), []);

const errorCodeRegistration = JSON.parse(readFileSync(new URL('../contracts/error-code-additions.json', import.meta.url), 'utf8'));
const importResultRegistration = JSON.parse(readFileSync(new URL('../contracts/resume-import-result.json', import.meta.url), 'utf8'));
const registeredCodes = ['RUN_USER_CAPACITY', 'RUN_QUEUE_FULL', 'AI_TIMEOUT', 'AI_NETWORK_ERROR', 'AI_AUTH_FAILED',
  'AI_CONFIGURATION_ERROR', 'AI_RATE_LIMITED', 'AI_INVALID_RESPONSE', 'AI_CIRCUIT_OPEN', 'RESUME_IMPORT_VALIDATION_FAILED'];
const atPointer = (document, pointer) => pointer.slice(1).split('/').reduce(
  (node, part) => node?.[part.replaceAll('~1', '/').replaceAll('~0', '~')], document);
const beforeRegisteredCodes = () => changed(document => {
  for (const pointer of errorCodeRegistration.enumPointers) {
    const values = atPointer(document, pointer);
    for (const code of registeredCodes) {
      assert.ok(values.includes(code), pointer);
      values.splice(values.indexOf(code), 1);
    }
  }
});

test('registered error codes cover only reviewed values and enum locations without mutating inputs', () => {
  assert.deepEqual(Object.entries(errorCodeRegistration.additions).map(([code, entry]) => [code, entry.httpStatus]),
    [['RUN_USER_CAPACITY', 429], ['RUN_QUEUE_FULL', 503], ['AI_TIMEOUT', 504], ['AI_NETWORK_ERROR', 502], ['AI_AUTH_FAILED', 502],
      ['AI_CONFIGURATION_ERROR', 503], ['AI_RATE_LIMITED', 503], ['AI_INVALID_RESPONSE', 502], ['AI_CIRCUIT_OPEN', 503],
      ['RESUME_IMPORT_VALIDATION_FAILED', 502]]);
  assert.equal(new Set(errorCodeRegistration.enumPointers).size, errorCodeRegistration.enumPointers.length);
  for (const pointer of errorCodeRegistration.enumPointers) {
    assert.ok(Array.isArray(atPointer(baseline, pointer)), pointer);
    for (const code of registeredCodes) assert.ok(atPointer(baseline, pointer).includes(code), pointer);
  }
  const old = beforeRegisteredCodes(), savedOld = structuredClone(old), savedCurrent = structuredClone(baseline);
  assert.notDeepEqual(compareContracts(old, baseline), []);
  assert.deepEqual(compareContracts(registeredErrorCodeBaseline(old, baseline), baseline), []);
  const adjusted = registeredErrorCodeBaseline(old, baseline);
  assert.deepEqual(registeredErrorCodeBaseline(adjusted, baseline), adjusted);
  assert.deepEqual(old, savedOld);
  assert.deepEqual(baseline, savedCurrent);
});

test('error-code registration does not excuse unknown additions, removed values, renamed values or duplicates', () => {
  const old = beforeRegisteredCodes();
  for (const pointer of errorCodeRegistration.enumPointers) {
    for (const mutate of [
      values => values.push('UNREVIEWED_CODE'),
      values => values.splice(values.indexOf('INVALID_REQUEST'), 1),
      values => values.splice(values.indexOf('INVALID_REQUEST'), 1, 'RENAMED_INVALID_REQUEST'),
      values => values.push('RUN_QUEUE_FULL'),
      values => values.push('AI_TIMEOUT'),
    ]) {
      assert.ok(atPointer(baseline, pointer).includes('INVALID_REQUEST'), pointer);
      const next = changed(document => mutate(atPointer(document, pointer)));
      assert.notDeepEqual(compareContracts(registeredErrorCodeBaseline(old, next), next), [], pointer);
    }
  }
});

test('registered error codes cannot change unregistered enums and cannot be removed once present in the baseline', () => {
  const old = beforeRegisteredCodes();
  old.components.schemas.UnrelatedStatus = { type: 'string', enum: ['OPEN'] };
  const next = structuredClone(old);
  next.components.schemas.UnrelatedStatus.enum.push(...registeredCodes);
  assert.notDeepEqual(compareContracts(registeredErrorCodeBaseline(old, next), next), []);
  // 名称像统一响应也不构成授权，必须逐位置登记。
  const unregistered = 'cn.edu.whu.zhizhida.common.ApiResponseUnregistered';
  old.components.schemas[unregistered] = { type: 'object', properties: { code: { type: 'string', enum: ['INVALID_REQUEST'] } } };
  const inline = structuredClone(old);
  inline.components.schemas[unregistered].properties.code.enum.push(...registeredCodes);
  assert.notDeepEqual(compareContracts(registeredErrorCodeBaseline(old, inline), inline), []);
  for (const pointer of errorCodeRegistration.enumPointers) {
    for (const code of registeredCodes) {
      const removed = changed(document => {
        const values = atPointer(document, pointer); values.splice(values.indexOf(code), 1);
      });
      assert.notDeepEqual(compareContracts(registeredErrorCodeBaseline(baseline, removed), removed), [], pointer);
    }
  }
});

test('error-code baseline keeps response structure, validation and authentication changes visible', () => {
  const old = beforeRegisteredCodes();
  for (const mutation of [
    document => { delete document.paths['/api/resumes'].post.responses['200']; },
    document => { document.paths['/api/resumes'].get.security = []; },
    document => { document.components.schemas[dto].properties.ownerName.minLength = 8; },
    document => { document.components.schemas.ErrorCode.type = 'integer'; },
    document => { document.components.schemas.ErrorCode.nullable = true; },
  ]) {
    const next = changed(mutation);
    assert.notDeepEqual(compareContracts(registeredErrorCodeBaseline(old, next), next), []);
  }
});

test('registered timeout response permits only the new 504 uniform error response', () => {
  assert.equal(errorCodeRegistration.timeoutResponse.status, '504');
  const old = changed(document => {
    delete document.paths['/api/resumes/import/parse'].post.responses['504'];
  });
  assert.notDeepEqual(compareContracts(old, baseline), []);
  assert.deepEqual(compareContracts(registeredErrorCodeBaseline(old, baseline), baseline), []);
  for (const mutation of [
    responses => { responses['504'].content['application/json'].schema.$ref = '#/components/schemas/ErrorCode'; },
    responses => { responses['504'].headers = { 'Retry-After': { schema: { type: 'string' } } }; },
    responses => { responses['505'] = structuredClone(responses['504']); },
  ]) {
    const next = changed(document => mutation(document.paths['/api/resumes/import/parse'].post.responses));
    assert.notDeepEqual(compareContracts(registeredErrorCodeBaseline(old, next), next), []);
  }
  assert.notDeepEqual(compareContracts(registeredErrorCodeBaseline(baseline, old), old), []);
  const rewritten = changed(document => {
    document.paths['/api/resumes/import/parse'].post.responses['504'].content['application/json'].schema.type = 'string';
  });
  assert.notDeepEqual(compareContracts(registeredErrorCodeBaseline(baseline, rewritten), rewritten), []);
});

const beforeImportResult = () => changed(document => {
  const change = importResultRegistration;
  document.paths[change.path][change.method].responses[change.status].content[change.mediaType].schema.$ref =
    '#/components/schemas/' + change.previousEnvelope;
  document.components.schemas[change.previousEnvelope] = structuredClone(document.components.schemas[change.envelope]);
  document.components.schemas[change.previousEnvelope].properties.data.$ref = '#/components/schemas/' + change.draftSchema;
  delete document.components.schemas[change.envelope];
  for (const name of Object.keys(change.schemas)) delete document.components.schemas[name];
});

test('reviewed import result migrates only its response wrapper and preserves input snapshots', () => {
  assert.equal(importResultRegistration.path, '/api/resumes/import/parse');
  assert.equal(importResultRegistration.method, 'post');
  assert.equal(importResultRegistration.status, '200');
  const old = beforeImportResult(), savedOld = structuredClone(old), savedCurrent = structuredClone(baseline);
  assert.notDeepEqual(compareContracts(old, baseline), []);
  const adjusted = resumeImportResultBaseline(old, baseline);
  assert.deepEqual(compareContracts(adjusted, baseline), []);
  assert.deepEqual(adjusted.components.schemas[importResultRegistration.draftSchema],
    old.components.schemas[importResultRegistration.draftSchema]);
  assert.deepEqual(resumeImportResultBaseline(adjusted, baseline), adjusted);
  assert.deepEqual(old, savedOld);
  assert.deepEqual(baseline, savedCurrent);
});

test('import migration requires the reviewed draft, warning and recognition mode schemas', () => {
  const old = beforeImportResult(), result = importResultRegistration.resultSchema;
  for (const mutate of [
    schemas => { schemas[result].properties.draft.$ref = '#/components/schemas/' + dto; },
    schemas => { schemas[result].properties.recognitionMode.enum.push('GUESS'); },
    schemas => { schemas[result].required = ['draft']; },
    schemas => { schemas[result + '$Draft'].properties.ownerName.minLength = 1; },
    schemas => { schemas[result + '$Warning'].properties.code.enum.push('UNKNOWN'); },
    schemas => { schemas[result + '$Warning'].properties.message.type = 'object'; },
  ]) {
    const next = changed(document => mutate(document.components.schemas));
    assert.notDeepEqual(compareContracts(resumeImportResultBaseline(old, next), next), []);
    assert.notDeepEqual(compareContracts(resumeImportResultBaseline(baseline, next), next), []);
  }
});

test('import response permits blank identity while create and update still require nonblank identity', () => {
  const schemaAt = ref => baseline.components.schemas[ref.slice('#/components/schemas/'.length)];
  const envelope = schemaAt(baseline.paths['/api/resumes/import/parse'].post.responses['200'].content['application/json'].schema.$ref);
  const result = schemaAt(envelope.properties.data.$ref);
  const draft = schemaAt(result.properties.draft.$ref);
  for (const field of ['ownerName', 'targetRole']) {
    assert.ok(draft.required.includes(field));
    assert.deepEqual(draft.properties[field], { type: 'string' });
    for (const [path, method] of [['/api/resumes', 'post'], ['/api/resumes/{id}', 'put']]) {
      const request = schemaAt(baseline.paths[path][method].requestBody.content['application/json'].schema.$ref);
      assert.ok(request.required.includes(field));
      assert.equal(request.properties[field].minLength, 1);
    }
  }
});

test('import migration does not excuse envelope, request, auth or other response changes', () => {
  const old = beforeImportResult(), change = importResultRegistration;
  for (const mutation of [
    document => { document.components.schemas[change.envelope].required = ['data']; },
    document => { document.components.schemas[change.envelope].properties.code.enum.push('UNREVIEWED_CODE'); },
    document => { document.components.schemas[change.draftSchema].properties.ownerName.minLength = 8; },
    document => { document.paths[change.path].post.parameters[0].required = true; },
    document => { document.paths[change.path].post.security = []; },
    document => { delete document.paths[change.path].post.responses['504']; },
    document => { document.paths['/api/resumes'].post.responses['200'] = structuredClone(document.paths[change.path].post.responses['200']); },
  ]) {
    const next = changed(mutation);
    assert.notDeepEqual(compareContracts(registeredErrorCodeBaseline(resumeImportResultBaseline(old, next), next), next), []);
  }
});

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
test('obsolete optimization and import routes retire without exempting their active replacements', () => {
  const retired = ['/api/ai/resume/optimize', '/api/knowledge/upload', '/api/knowledge/imports/preview',
    '/api/knowledge/imports', '/api/resumes/import'];
  const active = ['/api/ai/resume/optimize-draft', '/api/knowledge/imports/jobs',
    '/api/knowledge/imports/jobs/{id}/confirm', '/api/resumes/import/parse'];
  const old = structuredClone(baseline);
  for (const path of retired) {
    assert.equal(baseline.paths[path], undefined);
    old.paths[path] = structuredClone(baseline.paths['/api/resumes']);
  }
  assert.deepEqual(compareContracts(interviewFocusBaseline(old, baseline), baseline), []);
  for (const path of retired) {
    const reintroduced = changed(d => { d.paths[path] = old.paths[path]; });
    assert.throws(() => interviewFocusBaseline(baseline, reintroduced), /reintroduced/);
  }
  for (const path of active) {
    assert.ok(baseline.paths[path], path);
    const broken = changed(d => { delete d.paths[path]; });
    assert.notDeepEqual(compareContracts(interviewFocusBaseline(old, broken), broken), [], path);
  }
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
  shared.paths['/api/interviews/start-commands'].post.responses['202'].content['application/json'].schema = { $ref: `#/components/schemas/${request}` };
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

test('response generics preserve payload nullability and keep divergent envelopes explicit', () => {
  const prefix = 'cn.edu.whu.zhizhida.common.ApiResponse';
  const code = { type: 'string', enum: ['INVALID', 'CONFLICT'] };
  const wrapper = data => ({ type: 'object', properties: {
    code: { ...code, nullable: true }, data, message: { type: 'string' }, success: { type: 'boolean' },
  } });
  const schemas = {
    ErrorCode: code,
    Item: { type: 'string' },
    [prefix + 'A']: wrapper({ $ref: '#/components/schemas/Item' }),
    [prefix + 'B']: wrapper({ type: 'array', items: { $ref: '#/components/schemas/Item' }, nullable: true }),
    [prefix + 'Required']: { ...wrapper({ type: 'string' }), required: ['data'] },
    [prefix + 'DifferentCode']: wrapper({ type: 'string' }),
  };
  schemas[prefix + 'DifferentCode'].properties.code = { type: 'string', enum: ['OTHER'] };
  const document = { openapi: '3.0.1', paths: {}, components: { schemas } };
  const before = structuredClone(document);
  const output = generateTypes(document);
  assert.match(output, /export type ApiResponse<T> = \{ "code"\?: \(ErrorCode\) \| null; "data"\?: T;/);
  assert.ok(output.includes(`"${prefix}A": ApiResponse<Schemas["Item"]>;`));
  assert.ok(output.includes(`"${prefix}B": ApiResponse<(Array<Schemas["Item"]>) | null>;`));
  assert.ok(output.includes(`"${prefix}Required": { "code"?: (ErrorCode) | null; "data": string;`));
  assert.ok(output.includes(`"${prefix}DifferentCode": { "code"?: "OTHER";`));
  assert.equal(output.split('"INVALID" | "CONFLICT"').length - 1, 1);
  assert.deepEqual(document, before);
  schemas[prefix + 'B'].properties.data.not = {};
  assert.throws(() => generateTypes(document), /Unsupported schema keyword/);
});

test('production response wrappers reuse a single generic envelope and error-code union', () => {
  const output = generateTypes(baseline);
  const wrappers = Object.keys(baseline.components.schemas).filter(name => name.startsWith('cn.edu.whu.zhizhida.common.ApiResponse'));
  assert.ok(wrappers.length > 20);
  for (const name of wrappers) assert.ok(output.includes(`"${name}": ApiResponse<`), name);
  assert.equal(output.split('"INVALID_REQUEST" | "VALIDATION_FAILED"').length - 1, 1);
});
