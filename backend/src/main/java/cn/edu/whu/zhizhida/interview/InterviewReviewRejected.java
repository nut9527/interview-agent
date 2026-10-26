package cn.edu.whu.zhizhida.interview;

/** Only output validation failures are repairable; provider and persistence failures are not. */
final class InterviewReviewRejected extends IllegalStateException {
    InterviewReviewRejected(String reason) { super(reason); }
}
