import { Button, Modal } from 'antd';
import { PrinterOutlined } from '@ant-design/icons';
import type { ResumeProfile } from '@/types/api.d';
import ResumeDocument from './ResumeDocument';
import styles from './ResumePreviewModal.module.css';

export default function ResumePreviewModal({ open, resume, onClose }: {
  open: boolean; resume: ResumeProfile; onClose: () => void;
}) {
  return <Modal className={styles.previewModal} open={open} width={920} centered destroyOnHidden
    onCancel={onClose} title="简历内容预览" footer={[
      <Button key="close" onClick={onClose}>关闭预览</Button>,
      <Button key="print" type="primary" icon={<PrinterOutlined />} onClick={() => window.print()}>浏览器打印</Button>,
    ]}>
    <div className={styles.previewStage} data-resume-preview><ResumeDocument resume={resume} /></div>
  </Modal>;
}
