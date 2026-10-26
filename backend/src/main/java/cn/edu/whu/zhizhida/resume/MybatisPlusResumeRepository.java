package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;

import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.resume.persistence.ResumeEntity;
import cn.edu.whu.zhizhida.resume.persistence.ResumeMapper;
import cn.edu.whu.zhizhida.resume.persistence.ResumeVersionEntity;
import cn.edu.whu.zhizhida.resume.persistence.ResumeVersionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 简历持久化适配器，负责数据库实体与公开简历快照之间的转换。
 * 查询、更新和删除均带用户条件；已有正文通过 revision 条件更新实现乐观并发控制。
 */
@Repository
public class MybatisPlusResumeRepository implements ResumeRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ResumeMapper resumeMapper;
    private final ResumeVersionMapper versionMapper;
    private final JsonColumnCodec jsonCodec;

    public MybatisPlusResumeRepository(ResumeMapper resumeMapper, ResumeVersionMapper versionMapper,
                                       JsonColumnCodec jsonCodec) {
        this.resumeMapper = resumeMapper;
        this.versionMapper = versionMapper;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public List<ResumeProfile> findAll(Long userId) {
        return resumeMapper.selectList(
                        Wrappers.<ResumeEntity>lambdaQuery()
                                .eq(ResumeEntity::getUserId, userId)
                                .orderByDesc(ResumeEntity::getId)
                ).stream()
                .map(this::toProfile)
                .toList();
    }

    @Override
    public Optional<ResumeProfile> findById(Long userId, Long id) {
        ResumeEntity entity = resumeMapper.selectOne(
                Wrappers.<ResumeEntity>lambdaQuery()
                        .eq(ResumeEntity::getId, id)
                        .eq(ResumeEntity::getUserId, userId)
                        .last("LIMIT 1")
        );
        return Optional.ofNullable(entity).map(this::toProfile);
    }

    @Override
    public ResumeProfile save(Long userId, ResumeProfile profile) {
        ResumeEntity entity = toEntity(userId, profile);
        if (profile.id() == null) {
            entity.setRevision(0L);
            resumeMapper.insert(entity);
        } else {
            entity.setRevision(Math.addExact(profile.revision(), 1L));
            // 用户、主键和旧修订号必须同时匹配；受影响行数为 0 表示本次修改不能提交。
            int affected = resumeMapper.update(
                    entity,
                    Wrappers.<ResumeEntity>lambdaUpdate()
                            .eq(ResumeEntity::getId, profile.id())
                            .eq(ResumeEntity::getUserId, userId)
                            .eq(ResumeEntity::getRevision, profile.revision())
            );
            if (affected != 1) {
                throw new ResumeConflictException();
            }
        }
        return toProfile(entity);
    }

    @Override
    public boolean deleteById(Long userId, Long id) {
        versionMapper.delete(Wrappers.<ResumeVersionEntity>lambdaQuery()
                .eq(ResumeVersionEntity::getResumeId, id)
                .eq(ResumeVersionEntity::getUserId, userId));
        return resumeMapper.delete(
                Wrappers.<ResumeEntity>lambdaQuery()
                        .eq(ResumeEntity::getId, id)
                        .eq(ResumeEntity::getUserId, userId)
        ) > 0;
    }

    @Override
    public int deleteAllByUserId(Long userId) {
        versionMapper.delete(Wrappers.<ResumeVersionEntity>lambdaQuery()
                .eq(ResumeVersionEntity::getUserId, userId));
        return resumeMapper.delete(
                Wrappers.<ResumeEntity>lambdaQuery()
                        .eq(ResumeEntity::getUserId, userId)
        );
    }

    private ResumeEntity toEntity(Long userId, ResumeProfile profile) {
        ResumeEntity entity = new ResumeEntity();
        entity.setId(profile.id());
        entity.setUserId(userId);
        entity.setOwnerName(profile.ownerName());
        entity.setTargetRole(profile.targetRole());
        entity.setSummary(profile.summary());
        entity.setSkillsJson(jsonCodec.write(profile.skills()));
        entity.setEducationJson(jsonCodec.write(profile.educationExperiences()));
        entity.setProjectsJson(jsonCodec.write(profile.projectExperiences()));
        entity.setInternshipsJson(jsonCodec.write(profile.internshipExperiences()));
        entity.setCertificatesJson(jsonCodec.write(profile.certificates()));
        entity.setTemplateKey(profile.templateKey());
        entity.setDetailsJson(jsonCodec.write(profile.details()));
        return entity;
    }

    private ResumeProfile toProfile(ResumeEntity entity) {
        return new ResumeProfile(
                entity.getId(),
                entity.getOwnerName(),
                entity.getTargetRole(),
                entity.getSummary(),
                jsonCodec.read(entity.getSkillsJson(), STRING_LIST),
                jsonCodec.read(entity.getEducationJson(), STRING_LIST),
                jsonCodec.read(entity.getProjectsJson(), STRING_LIST),
                jsonCodec.read(entity.getInternshipsJson(), STRING_LIST),
                jsonCodec.read(entity.getCertificatesJson(), STRING_LIST),
                entity.getTemplateKey(),
                entity.getRevision(),
                entity.getDetailsJson() == null ? null : jsonCodec.read(entity.getDetailsJson(), ResumeDetails.class)
        );
    }
}
