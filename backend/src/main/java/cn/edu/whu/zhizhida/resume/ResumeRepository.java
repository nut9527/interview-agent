package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeProfile;

import java.util.List;
import java.util.Optional;

public interface ResumeRepository {

    List<ResumeProfile> findAll(Long userId);

    Optional<ResumeProfile> findById(Long userId, Long id);

    ResumeProfile save(Long userId, ResumeProfile profile);

    boolean deleteById(Long userId, Long id);

    int deleteAllByUserId(Long userId);
}
