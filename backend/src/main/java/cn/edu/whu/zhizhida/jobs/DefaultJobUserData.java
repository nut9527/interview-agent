package cn.edu.whu.zhizhida.jobs;

import cn.edu.whu.zhizhida.jobs.api.JobUserData;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class DefaultJobUserData implements JobUserData {
    private final TargetJobRepository targets;
    public DefaultJobUserData(TargetJobRepository targets) { this.targets = targets; }
    @Override public void initializeDemoJobs(Long sandboxUserId) {
        DemoTargetJobs.create().forEach(job -> targets.insert(sandboxUserId, job));
    }
    @Override public void deleteOwnedData(Long userId) { targets.deleteAllByUserId(userId); }
}
