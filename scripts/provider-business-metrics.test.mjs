import {test} from 'node:test';
import assert from 'node:assert/strict';
import {summarizeBusiness} from './provider-business-metrics.mjs';
const fixture=()=>({attempts:[1,2,3,4].map(caseId=>({caseId,status:'RETURNED',qualityProxyPassed:true,elapsedMillis:20,usage:{status:'REPORTED',inputTokens:10,outputTokens:5,totalTokens:15}}))});
test('unknown/failed samples remain in denominators and invalidate complete cost totals',()=>{
  const report=fixture(); Object.assign(report.attempts[0],{status:'FAILED_OR_UNKNOWN',qualityProxyPassed:false,usage:{status:'MISSING'}});
  const result=summarizeBusiness(report);
  assert.deepEqual(result.qualityProxy,{passed:3,denominator:4});
  assert.equal(result.usage.totalTokens,null);
  assert.equal(result.monetaryCost,null);
  assert.equal(summarizeBusiness(fixture()).usage.totalTokens,60);
});
test('incomplete and duplicate cases are rejected',()=>{
  const report=fixture(); report.attempts.pop(); assert.throws(()=>summarizeBusiness(report));
  const duplicate=fixture(); duplicate.attempts[0].caseId=2; assert.throws(()=>summarizeBusiness(duplicate));
});
