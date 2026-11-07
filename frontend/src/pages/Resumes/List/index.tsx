import { useState, useEffect, useRef } from 'react';
import { Card, Button, Tag, Modal, Empty, message } from 'antd';
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  AimOutlined,
  EyeOutlined,
  FileTextOutlined,
  UploadOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { resumeService } from '@/api/modules/resume';
import { captureAccountScope } from '@/api/accountScope';
import type { ResumeProfile } from '@/types/api.d';
import ResumePreviewModal from '@/components/resume/ResumePreviewModal';
import { useResumeStore } from '@/stores/resumeStore';
import ImportResumeModal from './components/ImportResumeModal';
import styles from './index.module.css';

function ResumeList() {
  const navigate = useNavigate();
  const [resumes, setResumes] = useState<ResumeProfile[]>([]);
  const [loading, setLoading] = useState(false);
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [previewResume, setPreviewResume] = useState<ResumeProfile | null>(null);
  const mounted = useRef(false);
  const listGeneration = useRef(0);
  const deleteLock = useRef(false);

  useEffect(() => {
    mounted.current = true;
    fetchResumes();
    return () => { mounted.current = false; };
  }, []);

  const fetchResumes = async () => {
    const sequence = ++listGeneration.current;
    const scope = captureAccountScope();
    const current = () => mounted.current && scope.isCurrent() && sequence === listGeneration.current;
    setLoading(true);
    try {
      const data = await resumeService.getList();
      if (!current()) return;
      setResumes(data);
    } catch (error) {
      if (current()) message.error(error instanceof Error ? error.message : '获取简历列表失败');
    } finally {
      if (current()) setLoading(false);
    }
  };

  const handleDelete = (id: number | null) => {
    if (!id) return;
    const scope = captureAccountScope();
    const current = () => mounted.current && scope.isCurrent();
    Modal.confirm({
      title: '确认删除',
      content: '删除后不可恢复，确定要继续吗？',
      onOk: async () => {
        if (deleteLock.current || !current()) return;
        deleteLock.current = true;
        try {
          await useResumeStore.getState().deleteResume(id);
          if (!current()) return;
          message.success('删除成功');
          fetchResumes();
          void useResumeStore.getState().fetchResumes().catch(() => undefined);
        } catch (error) {
          if (current()) message.error(error instanceof Error ? error.message : '删除失败');
        } finally {
          deleteLock.current = false;
        }
      },
    });
  };

  const handleEdit = (id: number | null) => {
    if (id) {
      navigate(`/resumes/edit?resumeId=${id}`);
    }
  };

  return (
    <main className={styles.container}>
      <header className={styles.heading}>
        <div>
          <h1>简历管理</h1>
          <p>按目标岗位整理简历，编辑经历或查看已保存的版本。</p>
        </div>
        <div className={styles.actions}>
          <Button icon={<UploadOutlined aria-hidden="true" />} onClick={() => setImportModalOpen(true)}>导入简历</Button>
          <Button type="primary" icon={<PlusOutlined aria-hidden="true" />} onClick={() => navigate('/resumes/edit')}>新建简历</Button>
        </div>
      </header>

      <div className={styles.listHeading}>
        <h2>我的简历</h2>
        {!loading && <span>{resumes.length} 份简历 · {new Set(resumes.map(r => r.targetRole).filter(Boolean)).size} 个岗位方向</span>}
      </div>
      {loading ? (
        <Card loading />
      ) : resumes.length === 0 ? (
        <Card className={styles.emptyCard}>
          <Empty
            description="暂无简历，点击新建创建第一份简历"
            image={<FileTextOutlined aria-hidden="true" className={styles.emptyIcon} />}
          />
          <div className={styles.emptyAction}>
            <Button
              type="primary"
              icon={<PlusOutlined aria-hidden="true" />}
              onClick={() => navigate('/resumes/edit')}
            >
              新建简历
            </Button>
          </div>
        </Card>
      ) : (
        <div className={styles.grid}>
          {resumes.map((resume) => (
            <Card
              key={resume.id}
              className={styles.resumeCard}
              actions={[
                <Button
                  type="link"
                  icon={<EyeOutlined aria-hidden="true" />}
                  onClick={() => setPreviewResume(resume)}
                >
                  预览
                </Button>,
                <Button
                  type="link"
                  icon={<EditOutlined aria-hidden="true" />}
                  onClick={() => handleEdit(resume.id)}
                >
                  编辑
                </Button>,
                <Button
                  type="link"
                  danger
                  icon={<DeleteOutlined aria-hidden="true" />}
                  onClick={() => handleDelete(resume.id)}
                >
                  删除
                </Button>,
              ]}
            >
              <div>
                <div className={styles.cardHeader}>
                  <div>
                    <span className={styles.fileIcon}><FileTextOutlined aria-hidden="true" /></span>
                    <h3 className={styles.cardName}>{resume.ownerName}</h3>
                    <div className={styles.cardRole}>
                      <AimOutlined aria-hidden="true" />
                      目标岗位：{resume.targetRole}
                    </div>
                  </div>
                  <Tag>修订 {resume.revision ?? 0}</Tag>
                </div>
                <div className={styles.skillTags}>
                  {resume.skills.slice(0, 4).map((skill, index) => (
                    <Tag key={index}>{skill}</Tag>
                  ))}
                  {resume.skills.length > 4 && (
                    <Tag color="default">+{resume.skills.length - 4}</Tag>
                  )}
                </div>
                <div className={styles.cardMeta}>
                  <span>
                    <strong>{(resume.details?.education?.length ?? 0) + resume.educationExperiences.length}</strong> 教育经历
                  </span>
                  <span>
                    <strong>{(resume.details?.projects?.length ?? 0) + resume.projectExperiences.length}</strong> 项目经验
                  </span>
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}

      <ImportResumeModal
        open={importModalOpen}
        onClose={() => setImportModalOpen(false)}
      />

      {previewResume && (
        <ResumePreviewModal
          open
          resume={previewResume}
          onClose={() => setPreviewResume(null)}
        />
      )}
    </main>
  );
}

export default ResumeList;
