package cn.edu.whu.zhizhida.jobs.api;

/** 仅读取当前认证用户已保存的岗位说明。 */
public interface TargetJobSnapshotReader {
    TargetJob find(String id);
}
