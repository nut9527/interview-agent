import assert from 'node:assert/strict';
import test from 'node:test';
import { passingSuite } from './maven-test-report.mjs';

const name = 'example.TransportIT';
const xml = `<testsuite name="${name}" tests="1" errors="0" skipped="0" failures="0">
  <testcase name="recoversSavedResponse" classname="${name}"/>
</testsuite>`;

test('reports the executed suite and all four counters', () => {
  assert.deepEqual(passingSuite(xml, name), { name, tests: 1, errors: 0, failures: 0, skipped: 0 });
  const log = '<system-out><![CDATA[<testcase name="fixture"/>]]></system-out>';
  assert.equal(passingSuite(xml.replace('</testsuite>', log + '</testsuite>'), name).tests, 1);
});

test('rejects incomplete, mismatched, or unsuccessful Maven evidence', () => {
  for (const field of ['errors', 'skipped', 'failures']) {
    const counter = `${field}="0"`;
    for (const bad of ['', `${field}=""`, `${field}="NaN"`, `${field}="-1"`, `${field}="1"`]) {
      assert.throws(() => passingSuite(xml.replace(counter, bad), name));
    }
  }
  assert.throws(() => passingSuite(xml, 'example.OtherIT'));
  assert.throws(() => passingSuite(xml.replace('</testsuite>', ''), name));
  assert.throws(() => passingSuite(xml.replace('tests="1"', 'tests="2"'), name));
  assert.throws(() => passingSuite(xml.replace('tests="1"', 'tests="0"'), name));
});

test('counts only the selected nested group and detects missing groups', () => {
  const grouped = xml.replace('tests="1"', 'tests="2"').replace(`classname="${name}"`, `classname="${name}$Http"`)
    .replace('</testsuite>', `<testcase classname="${name}$Mcp" name="usesReceipt"/></testsuite>`);
  assert.equal(passingSuite(grouped, name).tests, 2);
  assert.equal(passingSuite(grouped, name + '$Mcp').tests, 1);
  assert.throws(() => passingSuite(grouped, name + '$Missing'));
});
