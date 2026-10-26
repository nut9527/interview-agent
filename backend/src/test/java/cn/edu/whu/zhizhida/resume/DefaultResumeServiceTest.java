package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.resume.api.ResumeCompareRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeComparison;
import cn.edu.whu.zhizhida.resume.api.ResumeFieldDiff;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;
import cn.edu.whu.zhizhida.resume.api.ResumeProfileRequest;
import cn.edu.whu.zhizhida.resume.api.ResumeReference;
import cn.edu.whu.zhizhida.resume.api.ResumeVersionView;

import cn.edu.whu.zhizhida.auth.api.CurrentUserProvider;
import cn.edu.whu.zhizhida.common.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DefaultResumeServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long RESUME_ID = 10L;
    private static final Long VERSION_ID = 100L;

    @Mock
    private ResumeRepository resumeRepository;
    @Mock
    private ResumeVersionRepository versionRepository;
    @Mock
    private ResumeDocumentImporter documentImporter;
    @Mock
    private CurrentUserProvider currentUserProvider;

    private DefaultResumeService service;

    private ResumeProfile sampleProfile;
    private ResumeProfileRequest sampleRequest;

    @BeforeEach
    void setUp() {
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);

        service = new DefaultResumeService(resumeRepository, versionRepository, documentImporter,
                currentUserProvider);

        sampleProfile = new ResumeProfile(RESUME_ID, "张三", "Java 工程师", "个人简介",
                List.of("Java", "Spring"), List.of("武汉大学"), List.of("项目A"),
                List.of("实习A"), List.of("证书A"), "classic");

        sampleRequest = new ResumeProfileRequest("张三", "Java 工程师", "个人简介",
                List.of("Java", "Spring"), List.of("武汉大学"), List.of("项目A"),
                List.of("实习A"), List.of("证书A"), "classic");
    }

    // 查询简历列表

    @Test
    void parsingRequiresIdentityAndDoesNotCreateResumeOrVersion() {
        MultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1});
        when(documentImporter.importDocument(file, "Java")).thenReturn(sampleRequest);
        assertThat(service.parseDocument(file, "Java")).isSameAs(sampleRequest);
        verify(currentUserProvider).requireUserId();
        verifyNoInteractions(resumeRepository, versionRepository);
    }

    @Test
    void parsingFailureDoesNotLeavePersistedData() {
        MultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1});
        when(documentImporter.importDocument(file, null)).thenThrow(new IllegalArgumentException("invalid document"));
        assertThatThrownBy(() -> service.parseDocument(file, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(resumeRepository, versionRepository);
    }

    @Test
    void listReturnsResumesForCurrentUser() {
        List<ResumeProfile> profiles = List.of(sampleProfile);
        when(resumeRepository.findAll(USER_ID)).thenReturn(profiles);

        List<ResumeProfile> result = service.list();

        assertThat(result).isSameAs(profiles);
        verify(resumeRepository).findAll(USER_ID);
    }

    @Test
    void listReturnsEmptyWhenNoResumes() {
        when(resumeRepository.findAll(USER_ID)).thenReturn(List.of());

        List<ResumeProfile> result = service.list();

        assertThat(result).isEmpty();
    }

    // 按编号查询简历

    @Test
    void findByIdReturnsProfileWhenFound() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(sampleProfile));

        ResumeProfile result = service.findById(RESUME_ID);

        assertThat(result).isSameAs(sampleProfile);
    }

    @Test
    void findByIdThrowsResourceNotFoundExceptionWhenMissing() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(RESUME_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resume not found: " + RESUME_ID);
    }

    // 创建简历

    @Test
    void createSavesResumeAndCreatesCreatedVersion() {
        when(resumeRepository.save(eq(USER_ID), any(ResumeProfile.class))).thenReturn(sampleProfile);
        when(versionRepository.save(eq(USER_ID), any(ResumeProfile.class), eq("CREATED"), eq("手动创建简历")))
                .thenReturn(versionView(RESUME_ID, 1, "CREATED"));

        ResumeProfile result = service.create(sampleRequest);

        assertThat(result).isSameAs(sampleProfile);
        verify(resumeRepository).save(eq(USER_ID), any(ResumeProfile.class));
        verify(versionRepository).save(eq(USER_ID), any(ResumeProfile.class), eq("CREATED"), eq("手动创建简历"));
    }

    @Test
    void createPassesBuiltProfileToRepository() {
        ArgumentCaptor<ResumeProfile> profileCaptor = ArgumentCaptor.forClass(ResumeProfile.class);
        when(resumeRepository.save(eq(USER_ID), profileCaptor.capture())).thenReturn(sampleProfile);
        when(versionRepository.save(eq(USER_ID), any(), any(), any())).thenReturn(versionView(RESUME_ID, 1, "CREATED"));

        service.create(sampleRequest);

        ResumeProfile captured = profileCaptor.getValue();
        assertThat(captured.id()).isNull();
        assertThat(captured.ownerName()).isEqualTo("张三");
        assertThat(captured.targetRole()).isEqualTo("Java 工程师");
        assertThat(captured.skills()).containsExactly("Java", "Spring");
        assertThat(captured.templateKey()).isEqualTo("classic");
    }

    @Test
    void createHandlesNullListsInRequest() {
        ResumeProfileRequest requestWithNullLists = new ResumeProfileRequest("张三", "Java 工程师", null,
                null, null, null, null, null, null);
        when(resumeRepository.save(eq(USER_ID), any())).thenReturn(sampleProfile);
        when(versionRepository.save(eq(USER_ID), any(), any(), any())).thenReturn(versionView(RESUME_ID, 1, "CREATED"));

        ResumeProfile result = service.create(requestWithNullLists);

        assertThat(result).isSameAs(sampleProfile);
        verify(resumeRepository).save(eq(USER_ID), any());
    }

    // 更新简历

    @Test
    void updateVerifiesExistingResumeSavesAndCreatesUpdatedVersion() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(sampleProfile));
        when(resumeRepository.save(eq(USER_ID), any(ResumeProfile.class))).thenReturn(sampleProfile);
        when(versionRepository.save(eq(USER_ID), eq(sampleProfile), eq("UPDATED"), eq("编辑并保存简历")))
                .thenReturn(versionView(RESUME_ID, 2, "UPDATED"));

        ResumeProfile result = service.update(RESUME_ID, sampleRequest);

        assertThat(result).isSameAs(sampleProfile);
        verify(resumeRepository).findById(USER_ID, RESUME_ID);
        verify(resumeRepository).save(eq(USER_ID), any(ResumeProfile.class));
        verify(versionRepository).save(eq(USER_ID), any(), eq("UPDATED"), eq("编辑并保存简历"));
    }

    @Test
    void updatePreservesResumeIdInSavedProfile() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(sampleProfile));
        ArgumentCaptor<ResumeProfile> profileCaptor = ArgumentCaptor.forClass(ResumeProfile.class);
        when(resumeRepository.save(eq(USER_ID), profileCaptor.capture())).thenReturn(sampleProfile);
        when(versionRepository.save(eq(USER_ID), any(), any(), any())).thenReturn(versionView(RESUME_ID, 2, "UPDATED"));

        service.update(RESUME_ID, sampleRequest);

        assertThat(profileCaptor.getValue().id()).isEqualTo(RESUME_ID);
    }

    @Test
    void updateThrowsWhenResumeNotFound() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(RESUME_ID, sampleRequest))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resume not found: " + RESUME_ID);
    }

    // 文档导入

    @Test
    void staleEditorIsRejectedWithoutWritingResumeOrHistory() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(sampleProfile.withRevision(3)));
        ResumeProfileRequest stale = new ResumeProfileRequest("张三", "Java", "edited",
                List.of(), List.of(), List.of(), List.of(), List.of(), "classic", 2L);

        assertThatThrownBy(() -> service.update(RESUME_ID, stale)).isInstanceOf(ResumeConflictException.class);
        verify(resumeRepository, never()).save(any(), any());
        verifyNoInteractions(versionRepository);
    }

    @Test
    void legacyUpdateStillUsesReadRevisionInDatabaseCompareAndSet() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(sampleProfile.withRevision(3)));
        when(resumeRepository.save(eq(USER_ID), any())).thenThrow(new ResumeConflictException());

        assertThatThrownBy(() -> service.update(RESUME_ID, sampleRequest)).isInstanceOf(ResumeConflictException.class);
        ArgumentCaptor<ResumeProfile> captor = ArgumentCaptor.forClass(ResumeProfile.class);
        verify(resumeRepository).save(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().revision()).isEqualTo(3L);
        verifyNoInteractions(versionRepository);
    }

    @Test
    void restoreUsesCurrentRevisionInsteadOfHistoricalRevision() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(sampleProfile.withRevision(7)));
        when(versionRepository.findById(USER_ID, RESUME_ID, VERSION_ID))
                .thenReturn(Optional.of(versionViewWithSnapshot(RESUME_ID, 1, "CREATED", sampleProfile)));
        ResumeProfile saved = sampleProfile.withRevision(8);
        when(resumeRepository.save(eq(USER_ID), any())).thenReturn(saved);

        assertThat(service.restore(RESUME_ID, VERSION_ID, 7L)).isEqualTo(saved);
        ArgumentCaptor<ResumeProfile> captor = ArgumentCaptor.forClass(ResumeProfile.class);
        verify(resumeRepository).save(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().revision()).isEqualTo(7L);
        verify(versionRepository).save(USER_ID, saved, "RESTORED", "恢复至版本 V1");
    }

    @Test
    void staleRestoreIsRejectedWithoutReadingOrSavingSnapshots() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(sampleProfile.withRevision(7)));
        assertThatThrownBy(() -> service.restore(RESUME_ID, VERSION_ID, 6L))
                .isInstanceOf(ResumeConflictException.class);
        verify(resumeRepository, never()).save(any(), any());
        verifyNoInteractions(versionRepository);
    }

    @Test
    void importDocumentCallsImporterThenSavesWithImportedType() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf",
                "test".getBytes());
        String targetRole = "Java 工程师";
        when(documentImporter.importDocument(file, targetRole)).thenReturn(sampleRequest);
        when(resumeRepository.save(eq(USER_ID), any(ResumeProfile.class))).thenReturn(sampleProfile);
        when(versionRepository.save(eq(USER_ID), any(ResumeProfile.class), eq("IMPORTED"), any()))
                .thenReturn(versionView(RESUME_ID, 1, "IMPORTED"));

        ResumeProfile result = service.importDocument(file, targetRole);

        assertThat(result).isSameAs(sampleProfile);
        verify(documentImporter).importDocument(file, targetRole);
        verify(versionRepository).save(eq(USER_ID), any(), eq("IMPORTED"), any());
    }

    @Test
    void importDocumentShortensLongFileName() {
        String longName = "a".repeat(200) + ".pdf";
        MockMultipartFile file = new MockMultipartFile("file", longName, "application/pdf",
                "test".getBytes());
        when(documentImporter.importDocument(file, null)).thenReturn(sampleRequest);
        when(resumeRepository.save(eq(USER_ID), any())).thenReturn(sampleProfile);
        when(versionRepository.save(eq(USER_ID), any(), eq("IMPORTED"), any()))
                .thenReturn(versionView(RESUME_ID, 1, "IMPORTED"));

        ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
        service.importDocument(file, null);

        verify(versionRepository).save(eq(USER_ID), any(), eq("IMPORTED"), noteCaptor.capture());
        assertThat(noteCaptor.getValue()).startsWith("从 ");
        assertThat(noteCaptor.getValue()).endsWith(" 导入");
    }

    @Test
    void importDocumentUsesFallbackNameWhenOriginalFilenameNull() {
        MultipartFile file = new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return null; }
            @Override public String getContentType() { return "application/pdf"; }
            @Override public boolean isEmpty() { return false; }
            @Override public long getSize() { return 4L; }
            @Override public byte[] getBytes() { return "test".getBytes(); }
            @Override public java.io.InputStream getInputStream() { return null; }
            @Override public void transferTo(java.io.File dest) { }
        };
        when(documentImporter.importDocument(file, null)).thenReturn(sampleRequest);
        when(resumeRepository.save(eq(USER_ID), any())).thenReturn(sampleProfile);
        when(versionRepository.save(eq(USER_ID), any(), eq("IMPORTED"), any()))
                .thenReturn(versionView(RESUME_ID, 1, "IMPORTED"));

        ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
        service.importDocument(file, null);

        verify(versionRepository).save(eq(USER_ID), any(), eq("IMPORTED"), noteCaptor.capture());
        assertThat(noteCaptor.getValue()).isEqualTo("从 外部文档 导入");
    }

    // 通过模板创建简历





    // 查询历史版本

    @Test
    void versionsReturnsVersionListForResume() {
        List<ResumeVersionView> expected = List.of(versionView(RESUME_ID, 1, "CREATED"));
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(sampleProfile));
        when(versionRepository.findAll(USER_ID, RESUME_ID)).thenReturn(expected);

        List<ResumeVersionView> result = service.versions(RESUME_ID);

        assertThat(result).isSameAs(expected);
        verify(resumeRepository).findById(USER_ID, RESUME_ID);
        verify(versionRepository).findAll(USER_ID, RESUME_ID);
    }

    @Test
    void versionsThrowsWhenResumeNotFound() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.versions(RESUME_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resume not found: " + RESUME_ID);
    }

    // 恢复历史版本

    @Test
    void restoreReturnsRestoredResumeWithRestoredVersionRecord() {
        ResumeVersionView version = versionViewWithSnapshot(RESUME_ID, 1, "CREATED", sampleProfile);
        ResumeProfile savedProfile = withId(sampleProfile, RESUME_ID);
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(sampleProfile));
        when(versionRepository.findById(USER_ID, RESUME_ID, VERSION_ID)).thenReturn(Optional.of(version));
        when(resumeRepository.save(eq(USER_ID), any(ResumeProfile.class))).thenReturn(savedProfile);
        when(versionRepository.save(eq(USER_ID), eq(savedProfile), eq("RESTORED"), eq("恢复至版本 V1")))
                .thenReturn(versionView(RESUME_ID, 3, "RESTORED"));

        ResumeProfile result = service.restore(RESUME_ID, VERSION_ID);

        assertThat(result).isSameAs(savedProfile);
        verify(versionRepository).save(eq(USER_ID), eq(savedProfile), eq("RESTORED"), eq("恢复至版本 V1"));
    }

    @Test
    void restoreThrowsWhenResumeNotFound() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(RESUME_ID, VERSION_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resume not found: " + RESUME_ID);
    }

    @Test
    void restoreThrowsWhenVersionNotFound() {
        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(sampleProfile));
        when(versionRepository.findById(USER_ID, RESUME_ID, VERSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(RESUME_ID, VERSION_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resume version not found: " + VERSION_ID);
    }

    // 比较简历内容

    @Test
    void compareResolvesBothReferencesAndReturnsComparisonWithFieldDiffs() {
        ResumeProfile leftProfile = sampleProfile;
        ResumeProfile rightProfile = new ResumeProfile(RESUME_ID + 1, "李四", "Python 工程师", "另一简介",
                List.of("Python"), List.of("清华"), List.of("项目B"),
                List.of("实习B"), List.of("证书B"), "modern");

        ResumeReference leftRef = new ResumeReference(RESUME_ID, null);
        ResumeReference rightRef = new ResumeReference(RESUME_ID + 1, null);
        ResumeCompareRequest request = new ResumeCompareRequest(leftRef, rightRef);

        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(leftProfile));
        when(resumeRepository.findById(USER_ID, RESUME_ID + 1)).thenReturn(Optional.of(rightProfile));

        ResumeComparison result = service.compare(request);

        assertThat(result).isNotNull();
        assertThat(result.fields()).isNotEmpty();
        assertThat(result.fields().stream().filter(ResumeFieldDiff::changed).count())
                .isEqualTo(result.changedFieldCount());
        assertThat(result.right()).isNotNull();
        assertThat(result.left().resumeId()).isEqualTo(RESUME_ID);
    }

    @Test
    void compareDetectsChangedFieldsBetweenProfiles() {
        ResumeProfile left = sampleProfile;
        ResumeProfile right = new ResumeProfile(RESUME_ID + 1, "张三", "Java 工程师", "简介A",
                List.of("Java", "Spring"), List.of("武汉大学"), List.of("项目A"),
                List.of("实习A"), List.of("证书A"), "classic");

        ResumeCompareRequest request = new ResumeCompareRequest(
                new ResumeReference(RESUME_ID, null),
                new ResumeReference(RESUME_ID + 1, null));

        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(left));
        when(resumeRepository.findById(USER_ID, RESUME_ID + 1)).thenReturn(Optional.of(right));

        ResumeComparison result = service.compare(request);

        long changedCount = result.fields().stream().filter(ResumeFieldDiff::changed).count();
        assertThat(changedCount).isEqualTo(result.changedFieldCount());
    }

    @Test
    void compareResolvesVersionReferenceWhenVersionIdPresent() {
        ResumeProfile leftProfile = sampleProfile;
        ResumeProfile rightCurrent = new ResumeProfile(RESUME_ID + 1, "李四", "Python 工程师", "当前简介",
                List.of("Python"), List.of("清华"), List.of(), List.of(), List.of(), "classic");
        ResumeProfile snapshotProfile = new ResumeProfile(RESUME_ID + 1, "李四", "Python 工程师", "旧简介",
                List.of("Python"), List.of("清华"), List.of(), List.of(), List.of(), "classic");
        ResumeVersionView rightVersion = versionViewWithSnapshot(RESUME_ID + 1, 1, "CREATED", snapshotProfile);

        ResumeCompareRequest request = new ResumeCompareRequest(
                new ResumeReference(RESUME_ID, null),
                new ResumeReference(RESUME_ID + 1, VERSION_ID));

        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(leftProfile));
        when(resumeRepository.findById(USER_ID, RESUME_ID + 1)).thenReturn(Optional.of(rightCurrent));
        when(versionRepository.findById(USER_ID, RESUME_ID + 1, VERSION_ID)).thenReturn(Optional.of(rightVersion));

        ResumeComparison result = service.compare(request);

        assertThat(result.right()).isSameAs(rightVersion);
    }

    @Test
    void compareThrowsWhenLeftResumeNotFound() {
        ResumeCompareRequest request = new ResumeCompareRequest(
                new ResumeReference(RESUME_ID, null),
                new ResumeReference(RESUME_ID + 1, null));

        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.compare(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resume not found: " + RESUME_ID);
    }

    @Test
    void compareThrowsWhenRightResumeNotFound() {
        ResumeProfile leftProfile = sampleProfile;
        ResumeCompareRequest request = new ResumeCompareRequest(
                new ResumeReference(RESUME_ID, null),
                new ResumeReference(RESUME_ID + 1, null));

        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(leftProfile));
        when(resumeRepository.findById(USER_ID, RESUME_ID + 1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.compare(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resume not found: " + (RESUME_ID + 1));
    }

    @Test
    void compareHandlesNullFieldsInProfiles() {
        ResumeProfile left = new ResumeProfile(RESUME_ID, "张三", "Java", null,
                null, null, null, null, null, "classic");
        ResumeProfile right = new ResumeProfile(RESUME_ID + 1, "李四", "Python", "有简介",
                List.of("Go"), List.of("北大"), List.of("项目X"),
                List.of("实习X"), List.of("证书X"), "modern");

        ResumeCompareRequest request = new ResumeCompareRequest(
                new ResumeReference(RESUME_ID, null),
                new ResumeReference(RESUME_ID + 1, null));

        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(left));
        when(resumeRepository.findById(USER_ID, RESUME_ID + 1)).thenReturn(Optional.of(right));

        ResumeComparison result = service.compare(request);

        assertThat(result).isNotNull();
        assertThat(result.fields()).hasSize(10);
    }

    @Test
    void compareDetectsAddedAndRemovedItems() {
        ResumeProfile left = new ResumeProfile(RESUME_ID, "张三", "Java", "旧简介",
                List.of("Java", "Spring"), List.of("武汉大学"), List.of(),
                List.of(), List.of(), "classic");
        ResumeProfile right = new ResumeProfile(RESUME_ID + 1, "张三", "Java", "新简介",
                List.of("Java", "Python"), List.of("武汉大学", "华中科技"), List.of(),
                List.of(), List.of(), "classic");

        ResumeCompareRequest request = new ResumeCompareRequest(
                new ResumeReference(RESUME_ID, null),
                new ResumeReference(RESUME_ID + 1, null));

        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(left));
        when(resumeRepository.findById(USER_ID, RESUME_ID + 1)).thenReturn(Optional.of(right));

        ResumeComparison result = service.compare(request);

        ResumeFieldDiff skillsDiff = result.fields().stream()
                .filter(f -> "skills".equals(f.field()))
                .findFirst().orElseThrow();
        assertThat(skillsDiff.added()).containsExactly("Python");
        assertThat(skillsDiff.removed()).containsExactly("Spring");
        assertThat(skillsDiff.changed()).isTrue();

        ResumeFieldDiff eduDiff = result.fields().stream()
                .filter(f -> "educationExperiences".equals(f.field()))
                .findFirst().orElseThrow();
        assertThat(eduDiff.added()).containsExactly("华中科技");
        assertThat(eduDiff.changed()).isTrue();
    }

    // 删除简历

    @Test
    void deleteRemovesResumeById() {
        when(resumeRepository.deleteById(USER_ID, RESUME_ID)).thenReturn(true);

        service.delete(RESUME_ID);

        verify(resumeRepository).deleteById(USER_ID, RESUME_ID);
    }

    @Test
    void deleteThrowsWhenResumeNotFound() {
        when(resumeRepository.deleteById(USER_ID, RESUME_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(RESUME_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("resume not found: " + RESUME_ID);
    }

    // 辅助方法

    @Test
    void buildProfileWithNullListsCreatesEmptyListFields() {
        ResumeProfileRequest request = new ResumeProfileRequest("王五", "架构师", null,
                null, null, null, null, null, null);

        when(resumeRepository.save(eq(USER_ID), any(ResumeProfile.class))).thenReturn(sampleProfile);
        when(versionRepository.save(eq(USER_ID), any(), any(), any())).thenReturn(versionView(RESUME_ID, 1, "CREATED"));

        service.create(request);

        ArgumentCaptor<ResumeProfile> captor = ArgumentCaptor.forClass(ResumeProfile.class);
        verify(resumeRepository).save(eq(USER_ID), captor.capture());
        ResumeProfile built = captor.getValue();
        assertThat(built.skills()).isEmpty();
        assertThat(built.educationExperiences()).isEmpty();
        assertThat(built.projectExperiences()).isEmpty();
        assertThat(built.internshipExperiences()).isEmpty();
        assertThat(built.certificates()).isEmpty();
    }



    @Test
    void scalarReturnsEmptyListForNull() {
        // 通过摘要为空的比较场景间接验证。
        ResumeProfile left = new ResumeProfile(RESUME_ID, "张三", "Java", null,
                List.of(), List.of(), List.of(), List.of(), List.of(), "classic");
        ResumeProfile right = new ResumeProfile(RESUME_ID + 1, "张三", "Java", null,
                List.of(), List.of(), List.of(), List.of(), List.of(), "classic");

        ResumeCompareRequest request = new ResumeCompareRequest(
                new ResumeReference(RESUME_ID, null),
                new ResumeReference(RESUME_ID + 1, null));

        when(resumeRepository.findById(USER_ID, RESUME_ID)).thenReturn(Optional.of(left));
        when(resumeRepository.findById(USER_ID, RESUME_ID + 1)).thenReturn(Optional.of(right));

        ResumeComparison result = service.compare(request);

        ResumeFieldDiff summaryDiff = result.fields().stream()
                .filter(f -> "summary".equals(f.field()))
                .findFirst().orElseThrow();
        assertThat(summaryDiff.changed()).isFalse();
    }

    // 测试数据工厂

    private ResumeVersionView versionView(Long resumeId, int versionNumber, String changeType) {
        return new ResumeVersionView(VERSION_ID, resumeId, versionNumber, changeType, "说明",
                LocalDateTime.now(), null);
    }

    private ResumeVersionView versionViewWithSnapshot(Long resumeId, int versionNumber, String changeType,
                                                       ResumeProfile snapshot) {
        return new ResumeVersionView(VERSION_ID, resumeId, versionNumber, changeType, "说明",
                LocalDateTime.now(), snapshot);
    }

    private ResumeProfile withId(ResumeProfile source, Long id) {
        return new ResumeProfile(id, source.ownerName(), source.targetRole(), source.summary(),
                source.skills(), source.educationExperiences(), source.projectExperiences(),
                source.internshipExperiences(), source.certificates(), source.templateKey());
    }
}
