package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeVersionView;

import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.resume.persistence.ResumeVersionEntity;
import cn.edu.whu.zhizhida.resume.persistence.ResumeVersionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MybatisPlusResumeVersionRepositoryTest {

    @Mock
    private ResumeVersionMapper mapper;

    private MybatisPlusResumeVersionRepository repository;
    private JsonColumnCodec jsonCodec;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "version-test"),
                ResumeVersionEntity.class
        );
        jsonCodec = new JsonColumnCodec(new ObjectMapper());
        repository = new MybatisPlusResumeVersionRepository(mapper, jsonCodec);
    }

    @Test
    void shouldFindAllReturnsVersionsForUserAndResume() {
        ResumeVersionEntity entity = versionEntity(1L, 10L, 42L, 1, "UPDATE", "更新技能");
        when(mapper.selectList(any())).thenReturn(List.of(entity));

        List<ResumeVersionView> versions = repository.findAll(42L, 10L);

        assertThat(versions).hasSize(1);
        ResumeVersionView view = versions.get(0);
        assertThat(view.id()).isEqualTo(1L);
        assertThat(view.resumeId()).isEqualTo(10L);
        assertThat(view.versionNumber()).isEqualTo(1);
        assertThat(view.changeType()).isEqualTo("UPDATE");
        assertThat(view.changeNote()).isEqualTo("更新技能");

        ArgumentCaptor<Wrapper<ResumeVersionEntity>> captor = wrapperCaptor();
        verify(mapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("user_id")
                .containsIgnoringCase("resume_id")
                .containsIgnoringCase("ORDER BY version_number DESC");
    }

    @Test
    void shouldFindByIdWithUserScope() {
        ResumeVersionEntity entity = versionEntity(1L, 10L, 42L, 2, "UPDATE", "更新");
        when(mapper.selectOne(any())).thenReturn(entity);

        Optional<ResumeVersionView> result = repository.findById(42L, 10L, 1L);

        assertThat(result).isPresent();
        assertThat(result.get().versionNumber()).isEqualTo(2);

        ArgumentCaptor<Wrapper<ResumeVersionEntity>> captor = wrapperCaptor();
        verify(mapper).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("id")
                .containsIgnoringCase("resume_id")
                .containsIgnoringCase("user_id")
                .containsIgnoringCase("LIMIT 1");
    }

    @Test
    void shouldFindByIdReturnEmptyWhenNotFound() {
        when(mapper.selectOne(any())).thenReturn(null);

        Optional<ResumeVersionView> result = repository.findById(42L, 10L, 999L);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldSaveCreateFirstVersionWhenNoExistingVersions() {
        ResumeProfile snapshot = new ResumeProfile(10L, "张三", "Java后端", "summary",
                List.of("Java"), List.of("本科"), List.of("项目A"),
                List.of("实习A"), List.of("证书A"), "classic");
        when(mapper.selectOne(any())).thenReturn(null);
        // 模拟数据库自增主键，插入时将实体编号设为 1。
        doAnswer(inv -> {
            ResumeVersionEntity e = inv.getArgument(0);
            e.setId(1L);
            return 1;
        }).when(mapper).insert(any(ResumeVersionEntity.class));
        ResumeVersionEntity savedEntity = versionEntity(1L, 10L, 42L, 1, "CREATE", "初始版本");
        when(mapper.selectById(1L)).thenReturn(savedEntity);

        ResumeVersionView result = repository.save(42L, snapshot, "CREATE", "初始版本");

        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(result.changeType()).isEqualTo("CREATE");

        ArgumentCaptor<ResumeVersionEntity> captor = entityCaptor();
        verify(mapper).insert(captor.capture());
        ResumeVersionEntity entity = captor.getValue();
        assertThat(entity.getVersionNumber()).isEqualTo(1);
        assertThat(entity.getUserId()).isEqualTo(42L);
        assertThat(entity.getResumeId()).isEqualTo(10L);
        assertThat(entity.getSnapshotJson()).contains("张三");
    }

    @Test
    void shouldSaveIncrementVersionNumberWhenExistingVersionsPresent() {
        ResumeProfile snapshot = new ResumeProfile(10L, "张三", "Java后端", "summary",
                List.of("Java"), List.of("本科"), List.of("项目A"),
                List.of("实习A"), List.of("证书A"), "classic");
        ResumeVersionEntity latest = versionEntity(2L, 10L, 42L, 3, "UPDATE", "第三次更新");
        when(mapper.selectOne(any())).thenReturn(latest);
        // 模拟数据库自增主键，插入时将实体编号设为 5。
        doAnswer(inv -> {
            ResumeVersionEntity e = inv.getArgument(0);
            e.setId(5L);
            return 1;
        }).when(mapper).insert(any(ResumeVersionEntity.class));
        ResumeVersionEntity savedEntity = versionEntity(5L, 10L, 42L, 4, "UPDATE", "第四次更新");
        when(mapper.selectById(5L)).thenReturn(savedEntity);

        ResumeVersionView result = repository.save(42L, snapshot, "UPDATE", "第四次更新");

        assertThat(result.versionNumber()).isEqualTo(4);

        ArgumentCaptor<ResumeVersionEntity> captor = entityCaptor();
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getVersionNumber()).isEqualTo(4);
    }

    @Test
    void shouldSaveUseEmptyChangeNoteWhenNull() {
        ResumeProfile snapshot = new ResumeProfile(10L, "张三", "Java后端", "summary",
                List.of("Java"), List.of("本科"), List.of("项目A"),
                List.of("实习A"), List.of("证书A"), "classic");
        when(mapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> {
            ResumeVersionEntity e = inv.getArgument(0);
            e.setId(10L);
            return 1;
        }).when(mapper).insert(any(ResumeVersionEntity.class));
        ResumeVersionEntity savedEntity = versionEntity(10L, 10L, 42L, 1, "UPDATE", "");
        when(mapper.selectById(10L)).thenReturn(savedEntity);

        ResumeVersionView result = repository.save(42L, snapshot, "UPDATE", null);

        assertThat(result.changeNote()).isEmpty();
    }

    @Test
    void shouldDeleteByResumeId() {
        when(mapper.delete(any())).thenReturn(2);

        int deleted = repository.deleteByResumeId(42L, 10L);

        assertThat(deleted).isEqualTo(2);

        ArgumentCaptor<Wrapper<ResumeVersionEntity>> captor = wrapperCaptor();
        verify(mapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("user_id")
                .containsIgnoringCase("resume_id");
    }

    @Test
    void shouldDeleteAllByUserId() {
        when(mapper.delete(any())).thenReturn(5);

        int deleted = repository.deleteAllByUserId(42L);

        assertThat(deleted).isEqualTo(5);

        ArgumentCaptor<Wrapper<ResumeVersionEntity>> captor = wrapperCaptor();
        verify(mapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).containsIgnoringCase("user_id");
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Wrapper<ResumeVersionEntity>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class<Wrapper<ResumeVersionEntity>>) (Class<?>) Wrapper.class);
    }

    private ArgumentCaptor<ResumeVersionEntity> entityCaptor() {
        return ArgumentCaptor.forClass(ResumeVersionEntity.class);
    }

    private ResumeVersionEntity versionEntity(Long id, Long resumeId, Long userId,
                                               int versionNumber, String changeType, String changeNote) {
        ResumeVersionEntity entity = new ResumeVersionEntity();
        entity.setId(id);
        entity.setResumeId(resumeId);
        entity.setUserId(userId);
        entity.setVersionNumber(versionNumber);
        entity.setChangeType(changeType);
        entity.setChangeNote(changeNote);
        entity.setSnapshotJson("""
                {"id":%d,"ownerName":"张三","targetRole":"Java后端","summary":"test",\
                "skills":["Java"],"educationExperiences":[],"projectExperiences":[],\
                "internshipExperiences":[],"certificates":[],"templateKey":"classic"}\
                """.formatted(resumeId));
        entity.setCreatedAt(LocalDateTime.of(2026, 7, 13, 10, 0));
        return entity;
    }
}
