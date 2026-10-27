import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve, dirname } from 'node:path';
import { isDeepStrictEqual } from 'node:util';

const root = fileURLToPath(new URL('../', import.meta.url));
const snapshot = resolve(root, 'contracts/openapi.json');
const generated = resolve(root, 'frontend/src/types/generated/openapi.ts');
const retirements = JSON.parse(readFileSync(resolve(root, 'contracts/interview-focus-retirements.json'), 'utf8'));
const boot41Documentation = JSON.parse(readFileSync(resolve(root, 'contracts/spring-boot-4.1-documentation.json'), 'utf8'));
const methods = new Set(['get', 'post', 'put', 'patch', 'delete', 'head', 'options', 'trace']);
const annotations = new Set(['description', 'summary', 'title', 'example', 'examples', 'externalDocs', 'tags']);
const schemaKeys = new Set(['$ref', 'type', 'enum', 'nullable', 'properties', 'required', 'items',
  'additionalProperties', 'allOf', 'oneOf', 'anyOf', 'format', 'minimum', 'maximum', 'exclusiveMinimum',
  'exclusiveMaximum', 'minLength', 'maxLength', 'pattern', 'minItems', 'maxItems', 'uniqueItems', 'default',
  'readOnly', 'writeOnly', 'deprecated', ...annotations]);

/** Small, deliberately bounded OpenAPI 3.0 emitter. Unsupported schema constructs fail closed. */
export function generateTypes(document) {
  if (!/^3\.0\./.test(document.openapi)) throw new Error('Only OpenAPI 3.0 is supported; review the generator before upgrading.');
  const schemas = document.components.schemas;
  function type(schema) {
    if (!schema || typeof schema !== 'object' || Array.isArray(schema)) throw new Error('Invalid schema');
    for (const key of Object.keys(schema)) if (!schemaKeys.has(key)) throw new Error(`Unsupported schema keyword: ${key}`);
    let result;
    if (schema.$ref) {
      const prefix = '#/components/schemas/';
      const name = schema.$ref.slice(prefix.length).replaceAll('~1', '/').replaceAll('~0', '~');
      if (!schema.$ref.startsWith(prefix) || !Object.hasOwn(schemas, name)) throw new Error(`Unresolved local schema: ${schema.$ref}`);
      if (Object.keys(schema).some(key => !['$ref', 'nullable', ...annotations].includes(key)))
        throw new Error(`Unsupported structural sibling of $ref: ${schema.$ref}`);
      result = `Schemas[${JSON.stringify(name)}]`;
    } else if (schema.enum) {
      result = schema.enum.map(value => JSON.stringify(value)).join(' | ') || 'never';
    } else if (schema.allOf || schema.oneOf || schema.anyOf) {
      if (schema.type || schema.properties) throw new Error('Composition with sibling schema requires explicit support');
      result = `(${(schema.allOf ?? schema.oneOf ?? schema.anyOf).map(type).join(schema.allOf ? ' & ' : ' | ')})`;
    } else if (schema.type === 'array') {
      result = `Array<${type(schema.items)}>`;
    } else if (schema.type === 'object' || schema.properties) {
      const fields = Object.entries(schema.properties ?? {}).sort(([a], [b]) => a.localeCompare(b, 'en'))
        .map(([key, value]) => `${JSON.stringify(key)}${schema.required?.includes(key) ? '' : '?'}: ${type(value)};`);
      if (schema.additionalProperties) fields.push(`[key: string]: ${schema.additionalProperties === true ? 'unknown' : type(schema.additionalProperties)};`);
      result = fields.length ? `{ ${fields.join(' ')} }` : 'Record<string, unknown>';
    } else if (schema.type === 'integer' || schema.type === 'number') result = 'number';
    else if (schema.type === 'string') result = schema.format === 'binary' ? 'Blob' : 'string';
    else if (schema.type === 'boolean') result = 'boolean';
    else if (schema.type === undefined) result = 'unknown';
    else throw new Error(`Unsupported schema type: ${schema.type}`);
    return schema.nullable && result !== 'unknown' ? `(${result}) | null` : result;
  }
  const schemaLines = Object.entries(schemas).sort(([a], [b]) => a.localeCompare(b, 'en'))
    .map(([name, schema]) => `  ${JSON.stringify(name)}: ${type(schema)};`);
  const operationLines = [];
  for (const [path, item] of Object.entries(document.paths)) for (const [method, operation] of Object.entries(item)) {
    if (!methods.has(method)) continue;
    const body = operation.requestBody;
    if (body?.$ref) throw new Error('Referenced request bodies require explicit support');
    const contentType = content => content && Object.keys(content).length
      ? `{ ${Object.entries(content).map(([mime, value]) => `${JSON.stringify(mime)}: ${type(value.schema)};`).join(' ')} }` : 'Record<string, never>';
    const parameters = [...(item.parameters ?? []), ...(operation.parameters ?? [])];
    if (parameters.some(parameter => parameter.$ref || !parameter.schema)) throw new Error('Referenced/content parameters require explicit support');
    const parameterTypes = ['path', 'query', 'header', 'cookie'].map(location => {
      const values = parameters.filter(parameter => parameter.in === location);
      return `${location}: ${values.length ? `{ ${values.map(p => `${JSON.stringify(p.name)}${p.required ? '' : '?'}: ${type(p.schema)};`).join(' ')} }` : 'Record<string, never>'};`;
    }).join(' ');
    operationLines.push(`  ${JSON.stringify(`${method.toUpperCase()} ${path}`)}: { parameters: { ${parameterTypes} }; requestBody${body?.required ? '' : '?'}: ${body ? contentType(body.content) : 'never'}; responses: { ${Object.entries(operation.responses).map(([status, response]) => {
      if (response.$ref) throw new Error('Referenced responses require explicit support');
      return `${JSON.stringify(status)}: ${contentType(response.content)};`;
    }).join(' ')} } };`);
  }
  return '// Generated by node scripts/api-contract.mjs generate. Do not edit.\n'
    + '// Required/nullable follow OpenAPI; legacy UI snapshots are checked separately.\n'
    + `export interface Schemas {\n${schemaLines.join('\n')}\n}\n`
    + `export interface Operations {\n${operationLines.sort().join('\n')}\n}\n`
    + 'export type ErrorCode = Schemas["ErrorCode"];\n';
}

function structural(value, namedMap = false) {
  if (Array.isArray(value)) return value.map(item => structural(item)).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b), 'en'));
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value).filter(([key]) => namedMap || !annotations.has(key))
    .sort(([a], [b]) => a.localeCompare(b, 'en')).map(([key, child]) => [key, structural(child,
      !namedMap && ['properties', 'schemas', 'paths', 'headers', 'responses', 'content', 'securitySchemes', 'encoding'].includes(key))]));
}

/** Conservative compatibility policy: preserve existing shapes/constraints, allow additive optional fields and routes. */
export function compareContracts(before, after) {
  const errors = [];
  const pointer = key => key.replaceAll('~', '~0').replaceAll('/', '~1');
  // Widening request-only numeric ranges is compatible; shared/output ranges stay conservative.
  function references(document, response) {
    const found = new Set();
    function visit(node) {
      if (!node || typeof node !== 'object') return;
      if (node.$ref?.startsWith('#/components/schemas/')) {
        const name = node.$ref.slice('#/components/schemas/'.length).replaceAll('~1', '/').replaceAll('~0', '~');
        if (!found.has(name)) { found.add(name); visit(document.components.schemas[name]); }
      }
      Object.values(node).forEach(visit);
    }
    for (const item of Object.values(document.paths)) for (const [method, operation] of Object.entries(item)) {
      if (methods.has(method)) visit(response ? operation.responses : operation.requestBody);
    }
    return found;
  }
  const inputs = references(before, false);
  const outputs = new Set([...references(before, true), ...references(after, true)]);
  const requestOnly = new Set([...inputs].filter(name => !outputs.has(name)));
  function compare(old, next, path, parentNext) {
    if (isDeepStrictEqual(old, next)) return;
    const schemaName = path.split('/')[3]?.replaceAll('~1', '/').replaceAll('~0', '~');
    if (path.startsWith('/components/schemas/') && requestOnly.has(schemaName)
      && typeof old === 'number' && typeof next === 'number'
      && ((/\/(maximum|maxLength)$/.test(path) && next >= old) || (/\/(minimum|minLength)$/.test(path) && next <= old))) return;
    if (!old || !next || typeof old !== 'object' || typeof next !== 'object' || Array.isArray(old) || Array.isArray(next)) {
      errors.push(`${path}: changed or removed`); return;
    }
    for (const key of Object.keys(old)) {
      if (!Object.hasOwn(next, key)) errors.push(`${path}/${key}: removed`);
      else compare(old[key], next[key], `${path}/${pointer(key)}`, next);
    }
    for (const key of Object.keys(next)) if (!Object.hasOwn(old, key)) {
      const additive = path === '/paths' || path === '/components/schemas'
        || (/^\/paths\/[^/]+$/.test(path) && methods.has(key))
        || (path.endsWith('/properties') && !(parentNext?.required ?? []).includes(key));
      if (!additive) errors.push(`${path}/${key}: added constraint or changed contract`);
    }
  }
  // Ignore descriptive metadata, but retain all validation, security, parameters and response content.
  compare(structural({ openapi: before.openapi, paths: before.paths, components: before.components, security: before.security }),
    structural({ openapi: after.openapi, paths: after.paths, components: after.components, security: after.security }), '', undefined);
  return errors;
}

/** Apply only the reviewed product retirement. All surviving API constraints remain gated. */
export function interviewFocusBaseline(before, after) {
  const baseline = structuredClone(before);
  const retiredPath = path => retirements.pathPrefixes.some(prefix => path === prefix || path.startsWith(prefix + '/'));
  if (Object.keys(after.paths).some(retiredPath)) throw new Error('A retired product API was reintroduced.');
  for (const path of Object.keys(baseline.paths)) if (retiredPath(path)) delete baseline.paths[path];
  for (const [name, fields] of Object.entries(retirements.removedRequestFields)) {
    const previous = baseline.components.schemas[name], current = after.components.schemas[name];
    if (!previous) continue;
    for (const field of fields) {
      if (current?.properties?.[field]) throw new Error('A retired interview source was reintroduced: ' + field);
      delete previous.properties[field];
      if (previous.required) previous.required = previous.required.filter(key => key !== field);
    }
  }
  // Existing preparation_json remains readable as a legacy payload, without a diagnosis API dependency.
  const preparation = 'cn.edu.whu.zhizhida.interview.api.InterviewPreparation';
  const oldDiagnostic = baseline.components.schemas[preparation]?.properties?.diagnostic;
  const diagnostic = after.components.schemas[preparation]?.properties?.diagnostic;
  if (oldDiagnostic?.$ref?.endsWith('/cn.edu.whu.zhizhida.scoring.api.ResumeDiagnostic')
      && diagnostic?.type === 'object' && !diagnostic.properties) {
    baseline.components.schemas[preparation].properties.diagnostic = structuredClone(diagnostic);
  }
  // Retired controllers' DTOs are no longer exported. Keep every schema reachable from a retained route.
  const reachable = new Set(['ErrorCode', 'ErrorResponse']);
  function visit(node) {
    if (!node || typeof node !== 'object') return;
    if (node.$ref?.startsWith('#/components/schemas/')) {
      const name = node.$ref.slice('#/components/schemas/'.length).replaceAll('~1', '/').replaceAll('~0', '~');
      if (!reachable.has(name)) { reachable.add(name); visit(baseline.components.schemas[name]); }
    }
    Object.values(node).forEach(visit);
  }
  visit(baseline.paths);
  for (const name of Object.keys(baseline.components.schemas)) if (!reachable.has(name)) delete baseline.components.schemas[name];
  return baseline;
}

/** springdoc 3 now documents existing validation; permit only the reviewed first additions. */
export function springBoot41Baseline(before, after) {
  const baseline = structuredClone(before);
  for (const [pointer, expected] of Object.entries(boot41Documentation.additions)) {
    const parts = pointer.slice(1).split('/').map(part => part.replaceAll('~1', '/').replaceAll('~0', '~'));
    const key = parts.pop();
    let previous = baseline, current = after;
    for (const part of parts) { previous = previous?.[part]; current = current?.[part]; }
    if (previous && current && !Object.hasOwn(previous, key) && isDeepStrictEqual(current[key], expected)) {
      previous[key] = structuredClone(expected);
    }
  }
  return baseline;
}

function checkBase(document, reference) {
  execFileSync('git', ['cat-file', '-e', `${reference}^{commit}`], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
  const tracked = execFileSync('git', ['ls-tree', '--name-only', reference, '--', 'contracts/openapi.json'], { cwd: root, encoding: 'utf8' }).trim();
  if (!tracked) {
    console.log(`No contract in ${reference}: initial baseline addition (snapshot/type checks still apply).`);
    return;
  }
  const before = JSON.parse(execFileSync('git', ['show', `${reference}:contracts/openapi.json`], { cwd: root, encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 }));
  const errors = compareContracts(springBoot41Baseline(interviewFocusBaseline(before, document), document), document);
  if (errors.length) throw new Error(`Incompatible API change against ${reference}:\n${errors.join('\n')}`);
  console.log(`Existing contract preserved against ${reference}.`);
}

export function main(args) {
  const document = JSON.parse(readFileSync(snapshot, 'utf8'));
  const output = generateTypes(document);
  if (args[0] === 'generate') {
    mkdirSync(dirname(generated), { recursive: true });
    writeFileSync(generated, output);
    console.log('Generated frontend/src/types/generated/openapi.ts');
  } else if (args[0] === 'check') {
    if (readFileSync(generated, 'utf8').replaceAll('\r\n', '\n') !== output) throw new Error('Generated TypeScript is stale. Run node scripts/api-contract.mjs generate.');
    const reference = args[1] ?? process.env.CONTRACT_BASE_REF;
    if (reference) checkBase(document, reference);
    else if (process.env.CI) throw new Error('CONTRACT_BASE_REF is required in CI; compatibility must not silently skip.');
    console.log('OpenAPI-generated TypeScript is current.');
  } else throw new Error('Usage: node scripts/api-contract.mjs generate | check [base-ref]');
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
