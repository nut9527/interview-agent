import { isRequestCanceled, toApiError } from '../ApiError.ts';
import type { InterviewRun } from './interviewRuns';

/** 断网时无法判断服务端是否受理。只查询原幂等键，查无记录时由用户决定是否重试。 */
export async function acceptRunCommand(
  submit: () => Promise<InterviewRun>,
  lookup: () => Promise<InterviewRun>,
  onRejected: () => void,
): Promise<InterviewRun> {
  try {
    return await submit();
  } catch (error) {
    if (isRequestCanceled(error)) throw error;
    if (!toApiError(error).transient || toApiError(error).status === 429) {
      onRejected();
      throw error;
    }
    try {
      return await lookup();
    } catch (lookupError) {
      if (toApiError(lookupError).status === 404) throw error;
      throw lookupError;
    }
  }
}
