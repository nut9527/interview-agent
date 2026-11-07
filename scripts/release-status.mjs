// 只读核对候选版本的登记和历史记录；不启动服务、不读取 .env、不调用模型。
import { readFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { checkFixtures } from './release-fixtures.mjs';
import { projectRoot, sha256 } from './release-candidate.mjs';

export function readBudgetHistory(raw) {
  if (!raw || !raw.endsWith('\n')) throw new Error('Budget journal is empty or incomplete');
  let previous = '0'.repeat(64), sequence = 0, state;
  for (const line of raw.split('\n').filter(Boolean)) {
    const row = JSON.parse(line);
    if (row.sequence !== ++sequence || row.previous !== previous
        || row.sha256 !== sha256(JSON.stringify({ sequence, previous, state: row.state })))
      throw new Error('Budget journal hash mismatch');
    state = row.state;
    previous = row.sha256;
  }
  if (state?.campaign !== 'v1-closeout' || !Array.isArray(state.batches)
      || new Set(state.batches.map(batch => batch.id)).size !== state.batches.length
      || state.batches.some(batch => !['RUNNING', 'REVIEW_PENDING', 'PASS', 'FAIL'].includes(batch.status)))
    throw new Error('Invalid closeout campaign history');
  return state;
}

export function describeCandidate(registration, history, kernel, citationFix) {
  // 此检查器只支持当前未验收登记；新发布决定需要完整证据和相应的验收实现。
  // 即使专项摘要为 PASSED，或历史单批为 PASS，也不能把它提升为完整发布通过。
  if (registration.schemaVersion !== 'closeout-registration-v1'
      || registration.status !== 'CANDIDATE_NOT_ACCEPTED')
    throw new Error('Unsupported release decision: complete acceptance evidence must be verified before promotion');
  return {
    checkMeaning: 'EVIDENCE_INVENTORY_ONLY',
    registrationStatus: registration.status,
    releaseAccepted: false,
    candidateFreeze: registration.candidate.freezeStatus,
    candidateSourceManifest: registration.candidate.sourceManifestSha256,
    registeredSamples: registration.samples.length,
    registeredFlow: { ...registration.flow },
    historicalCloseoutBatches: history.batches.map(batch => ({
      id: batch.id, status: batch.status, sourceManifestSha256: batch.sourceManifestSha256,
    })),
    partialCitationFix: { result: citationFix.result, samples: citationFix.runs.length,
      scope: 'Recorded worker retrieval/citation scenario only; not full release acceptance' },
    kernelHeldoutExecuted: kernel.heldoutExecuted,
    remainingRunnerWork: [...registration.paidExecution.remainingRunnerWork],
    nextAcceptanceRequirements: [
      'Freeze the final source, configuration, model and 6+3 flow inputs.',
      'Complete the registered runner work and review failed scenarios on that candidate.',
      'Retain all 12 registered sample results and the 6+3 flow with evidence, usage and human review.',
      'Resolve blocking issues and verify the complete evidence before changing the release decision.',
    ],
  };
}

export async function readReleaseStatus(root = projectRoot) {
  await checkFixtures(root);
  const json = async path => JSON.parse(await readFile(join(root, path), 'utf8'));
  const [registration, rawHistory, kernel, citationFix] = await Promise.all([
    json('contracts/release-v1.0.json'),
    readFile(join(root, 'docs/evidence/closeout-model-budget.jsonl'), 'utf8'),
    json('docs/evidence/kernel-model-budget.json'),
    json('docs/evidence/langchain4j-worker-citation-fix.json'),
  ]);
  return describeCandidate(registration, readBudgetHistory(rawHistory), kernel, citationFix);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const args = process.argv.slice(2);
    if (args.length !== 1 || !['check', 'require-accepted'].includes(args[0]))
      throw new Error('Usage: node scripts/release-status.mjs check | require-accepted');
    const result = await readReleaseStatus();
    console.log(JSON.stringify(result, null, 2));
    if (args[0] === 'require-accepted' && !result.releaseAccepted) {
      console.error('AI release acceptance is incomplete; evidence inventory checks are not acceptance.');
      process.exitCode = 1;
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
