// Surefire 和 Failsafe 都会为每个测试类生成一份 TEST-*.xml。
export function passingSuite(xml, expectedName) {
  const markup = xml.replace(/<!\[CDATA\[[\s\S]*?\]\]>|<!--[\s\S]*?-->/g, '');
  const header = markup.match(/<testsuite\s[^>]+>/)?.[0] || '';
  const fields = Object.fromEntries([...header.matchAll(/\s([\w.-]+)="([^"]*)"/g)]
    .map(([, key, value]) => [key, value]));
  const outerClass = expectedName.split('$')[0];
  if (fields.name !== outerClass || !markup.trimEnd().endsWith('</testsuite>')) {
    throw new Error(`Incomplete or mismatched test report: ${expectedName}`);
  }
  const suite = { name: expectedName };
  for (const key of ['tests', 'errors', 'failures', 'skipped']) {
    const value = Number(fields[key]);
    if (!/^\d+$/.test(fields[key]) || !Number.isSafeInteger(value)) {
      throw new Error(`Invalid ${key} count in ${expectedName}`);
    }
    suite[key] = value;
  }
  const cases = markup.match(/<testcase\s[^>]+>/g) || [];
  if (!suite.tests || suite.errors || suite.failures || suite.skipped || cases.length !== suite.tests) {
    throw new Error(`Incomplete or unsuccessful test suite: ${expectedName}`);
  }
  // Failsafe combines selected @Nested groups in one XML report for their outer class.
  if (expectedName !== outerClass) {
    suite.tests = cases.filter(entry => entry.match(/\sclassname="([^"]*)"/)?.[1] === expectedName).length;
    if (!suite.tests) throw new Error(`Missing test group: ${expectedName}`);
  }
  return suite;
}
