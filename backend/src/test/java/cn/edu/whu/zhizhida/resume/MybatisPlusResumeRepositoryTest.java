package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeProfile;

import cn.edu.whu.zhizhida.common.persistence.JsonColumnCodec;
import cn.edu.whu.zhizhida.resume.persistence.ResumeEntity;
import cn.edu.whu.zhizhida.resume.persistence.ResumeMapper;
import cn.edu.whu.zhizhida.resume.persistence.ResumeVersionEntity;
import cn.edu.whu.zhizhida.resume.persistence.ResumeVersionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import tools.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MybatisPlusResumeRepositoryTest {

    @Mock
    private ResumeMapper resumeMapper;

    @Mock
    private ResumeVersionMapper versionMapper;

    private MybatisPlusResumeRepository repository;
    private JsonColumnCodec jsonCodec;

    @BeforeEach
    void setUp() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, "resume-test"),
                ResumeEntity.class
        );
        jsonCodec = new JsonColumnCodec(new tools.jackson.databind.json.JsonMapper());
        repository = new MybatisPlusResumeRepository(resumeMapper, versionMapper, jsonCodec);
    }

    @Test
    void shouldFindAllReturnsMappedProfilesForUser() {
        ResumeEntity entity = resumeEntity(1L, 42L, "张三", "Java后端");
        when(resumeMapper.selectList(any())).thenReturn(List.of(entity));

        List<ResumeProfile> profiles = repository.findAll(42L);

        assertThat(profiles).hasSize(1);
        ResumeProfile profile = profiles.get(0);
        assertThat(profile.id()).isEqualTo(1L);
        assertThat(profile.ownerName()).isEqualTo("张三");
        assertThat(profile.targetRole()).isEqualTo("Java后端");
        assertThat(profile.skills()).containsExactly("Java", "Spring");

        ArgumentCaptor<Wrapper<ResumeEntity>> captor = wrapperCaptor();
        verify(resumeMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("user_id")
                .containsIgnoringCase("ORDER BY id DESC");
    }

    @Test
    void shouldFindByIdWithUserScope() {
        ResumeEntity entity = resumeEntity(1L, 42L, "张三", "Java后端");
        when(resumeMapper.selectOne(any())).thenReturn(entity);

        Optional<ResumeProfile> result = repository.findById(42L, 1L);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(1L);

        ArgumentCaptor<Wrapper<ResumeEntity>> captor = wrapperCaptor();
        verify(resumeMapper).selectOne(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("id")
                .containsIgnoringCase("user_id")
                .containsIgnoringCase("LIMIT 1");
    }

    @Test
    void shouldFindByIdReturnEmptyWhenNotFound() {
        when(resumeMapper.selectOne(any())).thenReturn(null);

        Optional<ResumeProfile> result = repository.findById(42L, 999L);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldSaveCreateNewEntityWhenIdIsNull() {
        ResumeProfile profile = new ResumeProfile(null, "张三", "Java后端", "summary",
                List.of("Java"), List.of("本科"), List.of("项目A"),
                List.of("实习A"), List.of("证书A"), "classic");

        ResumeProfile saved = repository.save(42L, profile);

        assertThat(saved.ownerName()).isEqualTo("张三");
        assertThat(saved.targetRole()).isEqualTo("Java后端");

        ArgumentCaptor<ResumeEntity> captor = entityCaptor();
        verify(resumeMapper).insert(captor.capture());
        ResumeEntity entity = captor.getValue();
        assertThat(entity.getId()).isNull();
        assertThat(entity.getUserId()).isEqualTo(42L);
        assertThat(entity.getOwnerName()).isEqualTo("张三");
        assertThat(entity.getSkillsJson()).contains("Java");
    }

    @Test
    void shouldSaveUpdateExistingEntityWhenIdIsNotNull() {
        when(resumeMapper.update(any(ResumeEntity.class), any())).thenReturn(1);
        ResumeProfile profile = new ResumeProfile(1L, "张三", "Java后端", "summary",
                List.of("Java"), List.of("本科"), List.of("项目A"),
                List.of("实习A"), List.of("证书A"), "modern");

        ResumeProfile saved = repository.save(42L, profile);

        assertThat(saved.id()).isEqualTo(1L);
        assertThat(saved.revision()).isEqualTo(1L);

        verify(resumeMapper).update(any(ResumeEntity.class), any());

        ArgumentCaptor<Wrapper<ResumeEntity>> captor = wrapperCaptor();
        verify(resumeMapper).update(any(ResumeEntity.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("id")
                .containsIgnoringCase("user_id")
                .containsIgnoringCase("revision");
    }

    @Test
    void shouldRejectLostUpdateInsteadOfReturningUnsavedData() {
        ResumeProfile profile = new ResumeProfile(1L, "张三", "Java", "summary",
                List.of(), List.of(), List.of(), List.of(), List.of(), "classic").withRevision(4);
        when(resumeMapper.update(any(ResumeEntity.class), any())).thenReturn(0);

        assertThatThrownBy(() -> repository.save(42L, profile)).isInstanceOf(ResumeConflictException.class);
    }

    @Test
    void shouldDeleteByIdWithUserScope() {
        // 先调用版本映射器删除历史版本。
        when(versionMapper.delete(any())).thenReturn(1);
        when(resumeMapper.delete(any())).thenReturn(1);

        boolean deleted = repository.deleteById(42L, 1L);

        assertThat(deleted).isTrue();

        verify(versionMapper).delete(any());
        ArgumentCaptor<Wrapper<ResumeEntity>> captor = wrapperCaptor();
        verify(resumeMapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .containsIgnoringCase("id")
                .containsIgnoringCase("user_id");
    }

    @Test
    void shouldDeleteByIdReturnFalseWhenNoMatch() {
        when(versionMapper.delete(any())).thenReturn(0);
        when(resumeMapper.delete(any())).thenReturn(0);

        boolean deleted = repository.deleteById(42L, 999L);

        assertThat(deleted).isFalse();
    }

    @Test
    void shouldDeleteAllByUserId() {
        when(versionMapper.delete(any())).thenReturn(2);
        when(resumeMapper.delete(any())).thenReturn(3);

        int deleted = repository.deleteAllByUserId(42L);

        assertThat(deleted).isEqualTo(3);

        verify(versionMapper).delete(any());
        verify(resumeMapper).delete(any());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Wrapper<ResumeEntity>> wrapperCaptor() {
        return ArgumentCaptor.forClass((Class<Wrapper<ResumeEntity>>) (Class<?>) Wrapper.class);
    }

    private ArgumentCaptor<ResumeEntity> entityCaptor() {
        return ArgumentCaptor.forClass(ResumeEntity.class);
    }

    private ResumeEntity resumeEntity(Long id, Long userId, String ownerName, String targetRole) {
        ResumeEntity entity = new ResumeEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setOwnerName(ownerName);
        entity.setTargetRole(targetRole);
        entity.setSummary("test summary");
        entity.setSkillsJson("[\"Java\",\"Spring\"]");
        entity.setEducationJson("[\"本科\"]");
        entity.setProjectsJson("[\"项目A\"]");
        entity.setInternshipsJson("[\"实习A\"]");
        entity.setCertificatesJson("[\"证书A\"]");
        entity.setTemplateKey("classic");
        return entity;
    }
}
