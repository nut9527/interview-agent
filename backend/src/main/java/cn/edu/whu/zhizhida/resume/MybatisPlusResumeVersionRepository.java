package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeVersionSummary;
import cn.edu.whu.zhizhida.common.PageResult;
import cn.edu.whu.zhizhida.common.PageRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeVersionView;

import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.resume.persistence.ResumeVersionEntity;
import cn.edu.whu.zhizhida.resume.persistence.ResumeVersionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisPlusResumeVersionRepository implements ResumeVersionRepository {

    private final ResumeVersionMapper mapper;
    private final JsonColumnCodec jsonCodec;

    public MybatisPlusResumeVersionRepository(ResumeVersionMapper mapper, JsonColumnCodec jsonCodec) {
        this.mapper = mapper;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public ResumeVersionView save(Long userId, ResumeProfile snapshot, String changeType, String changeNote) {
        ResumeVersionEntity latest = mapper.selectOne(
                Wrappers.<ResumeVersionEntity>lambdaQuery()
                        .eq(ResumeVersionEntity::getUserId, userId)
                        .eq(ResumeVersionEntity::getResumeId, snapshot.id())
                        .orderByDesc(ResumeVersionEntity::getVersionNumber)
                        .last("LIMIT 1")
        );
        ResumeVersionEntity entity = new ResumeVersionEntity();
        entity.setResumeId(snapshot.id());
        entity.setUserId(userId);
        entity.setVersionNumber(latest == null ? 1 : latest.getVersionNumber() + 1);
        entity.setSnapshotJson(jsonCodec.write(snapshot));
        entity.setChangeType(changeType);
        entity.setChangeNote(changeNote == null ? "" : changeNote);
        mapper.insert(entity);
        return toView(mapper.selectById(entity.getId()));
    }

    @Override
    public List<ResumeVersionView> findAll(Long userId, Long resumeId) {
        return mapper.selectList(
                        Wrappers.<ResumeVersionEntity>lambdaQuery()
                                .eq(ResumeVersionEntity::getUserId, userId)
                                .eq(ResumeVersionEntity::getResumeId, resumeId)
                                .orderByDesc(ResumeVersionEntity::getVersionNumber)
                ).stream().map(this::toView).toList();
    }

    @Override
    public Optional<ResumeVersionView> findById(Long userId, Long resumeId, Long versionId) {
        ResumeVersionEntity entity = mapper.selectOne(
                Wrappers.<ResumeVersionEntity>lambdaQuery()
                        .eq(ResumeVersionEntity::getId, versionId)
                        .eq(ResumeVersionEntity::getResumeId, resumeId)
                        .eq(ResumeVersionEntity::getUserId, userId)
                        .last("LIMIT 1")
        );
        return Optional.ofNullable(entity).map(this::toView);
    }

    @Override
    public int deleteByResumeId(Long userId, Long resumeId) {
        return mapper.delete(Wrappers.<ResumeVersionEntity>lambdaQuery()
                .eq(ResumeVersionEntity::getUserId, userId)
                .eq(ResumeVersionEntity::getResumeId, resumeId));
    }

    @Override
    public int deleteAllByUserId(Long userId) {
        return mapper.delete(Wrappers.<ResumeVersionEntity>lambdaQuery()
                .eq(ResumeVersionEntity::getUserId, userId));
    }

    private ResumeVersionView toView(ResumeVersionEntity entity) {
        return new ResumeVersionView(
                entity.getId(), entity.getResumeId(), entity.getVersionNumber(), entity.getChangeType(),
                entity.getChangeNote(), entity.getCreatedAt(), jsonCodec.read(entity.getSnapshotJson(), ResumeProfile.class)
        );
    }
    @Override
    public PageResult<ResumeVersionSummary> page(Long userId, Long resumeId, PageRequest page) {
        long total = mapper.selectCount(Wrappers.<ResumeVersionEntity>lambdaQuery().eq(ResumeVersionEntity::getUserId, userId).eq(ResumeVersionEntity::getResumeId, resumeId));
        var items = mapper.selectList(Wrappers.<ResumeVersionEntity>lambdaQuery()
                .select(ResumeVersionEntity::getId, ResumeVersionEntity::getResumeId, ResumeVersionEntity::getVersionNumber, ResumeVersionEntity::getChangeType, ResumeVersionEntity::getChangeNote, ResumeVersionEntity::getCreatedAt)
                .eq(ResumeVersionEntity::getUserId, userId).eq(ResumeVersionEntity::getResumeId, resumeId).orderByDesc(ResumeVersionEntity::getVersionNumber, ResumeVersionEntity::getId).last(page.limitSql()))
                .stream().map(e -> new ResumeVersionSummary(e.getId(), e.getResumeId(), e.getVersionNumber(), e.getChangeType(), e.getChangeNote(), e.getCreatedAt())).toList();
        return new PageResult<>(items, total, page);
    }

}
