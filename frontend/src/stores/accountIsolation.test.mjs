import test, { beforeEach, afterEach, mock } from 'node:test';
import assert from 'node:assert/strict';
import { useAuthStore } from './authStore.ts';
import { useResumeStore } from './resumeStore.ts';
import { resumeService } from '../api/modules/resume.ts';
import { isRequestCanceled } from '../api/ApiError.ts';
import { captureAccountScope } from '../api/accountScope.ts';

const login = (id, token = `token-${id}`) => useAuthStore.getState().setSession({ token, user: { id, username: `user-${id}` } });
beforeEach(() => login(1));
afterEach(() => { mock.restoreAll(); useAuthStore.getState().clearUser(); });
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
};

test('login/logout clears every shared private-data store; same-identity refresh keeps the current scope', () => {
  useResumeStore.setState({ resumes: [{ id: 1 }], versions: [{ id: 2 }], compareResult: {}, versionTotal: 2 });
  const previous = captureAccountScope();
  localStorage.setItem('user', JSON.stringify({ id: 1, username: 'updated' }));
  useAuthStore.getState().loadUserFromStorage();
  assert.equal(previous.isCurrent(), true);
  login(2);
  assert.equal(previous.signal.aborted, true);
  assert.deepEqual(useResumeStore.getState().resumes, []);
  assert.deepEqual(useResumeStore.getState().versions, []);
  assert.equal(useResumeStore.getState().compareResult, null);
  useAuthStore.getState().clearUser();
  assert.equal(localStorage.getItem('token'), null);
  assert.equal(useAuthStore.getState().user, null);
});

test('refresh restores identity before navigating directly to an interview; same-user token rotation isolates data', () => {
  localStorage.setItem('token', 'refreshed-token');
  localStorage.setItem('user', JSON.stringify({ id: 9, username: 'direct-link' }));
  useAuthStore.getState().loadUserFromStorage();
  assert.equal(useAuthStore.getState().user.id, 9);
  const scope = captureAccountScope();
  useResumeStore.setState({ versionTotal: 4 });
  login(9, 'another-token');
  assert.equal(scope.isCurrent(), false);
  assert.equal(useResumeStore.getState().versionTotal, 0);
});

test('late resume deletion cannot modify the next account cache', async () => {
  const old = deferred(); mock.method(resumeService, 'delete', () => old.promise);
  const pending = useResumeStore.getState().deleteResume(1);
  login(2);
  const current = [{ id: 1, ownerName: 'private-b' }];
  useResumeStore.setState({ resumes: current });
  old.resolve(null);
  await assert.rejects(pending, isRequestCanceled);
  assert.deepEqual(useResumeStore.getState().resumes, current);
});

test('late resume list response cannot repopulate the cache after logout', async () => {
  const resumes = deferred();
  mock.method(resumeService, 'getList', () => resumes.promise);
  const pending = useResumeStore.getState().fetchResumes();
  useAuthStore.getState().clearUser();
  resumes.resolve([{ id: 1 }]);
  await pending;
  assert.deepEqual(useResumeStore.getState().resumes, []);
});

test('late failure cannot unset a new account loading state', async () => {
  const old = deferred(); mock.method(resumeService, 'getList', () => old.promise);
  const pending = useResumeStore.getState().fetchResumes();
  login(2); useResumeStore.setState({ loading: true });
  old.reject(new Error('old failure')); await pending;
  assert.equal(useResumeStore.getState().loading, true);
});

test('pagination still discards an older page response within the same account', async () => {
  const first = deferred();
  mock.method(resumeService, 'getVersionHistory', (_, page) => page === 1 ? first.promise
    : Promise.resolve({ items: [{ id: 22 }], total: 40 }));
  const pending = useResumeStore.getState().fetchVersions(1, 1);
  await useResumeStore.getState().fetchVersions(1, 2);
  first.resolve({ items: [{ id: 11 }], total: 40 }); await pending;
  assert.deepEqual(useResumeStore.getState().versions, [{ id: 22 }]);
});

test('an explicit new login invalidates caches even if a Mock provider reuses its token', () => {
  const scope = captureAccountScope();
  useResumeStore.setState({ versionTotal: 3 });
  login(1);
  assert.equal(scope.isCurrent(), false);
  assert.equal(useResumeStore.getState().versionTotal, 0);
});
