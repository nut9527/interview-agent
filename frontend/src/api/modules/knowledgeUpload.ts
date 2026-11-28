import { ApiError } from '../ApiError.ts';

// 发送前与 KnowledgeDocumentImporter 的体积上限保持一致。
// 请求超大时连接可能因未读字节关闭，导致前端收不到拒绝信息。
export const validateKnowledgeUpload = (file: Pick<File, 'name' | 'size'>) => {
  const name = file.name.split(/[\\/]/).pop() ?? '';
  const extension = name.slice(name.lastIndexOf('.') + 1).toLowerCase();
  const hasControlCharacter = Array.from(name).some(
    (char) => char.charCodeAt(0) < 32 || (char.charCodeAt(0) >= 127 && char.charCodeAt(0) <= 159),
  );
  if (name.length > 255 || hasControlCharacter || !/^.+\.(pdf|docx|txt|md|markdown)$/i.test(name)) {
    throw new ApiError('请上传 PDF、DOCX、TXT 或 Markdown 文件，文件名不得超过 255 字。');
  }
  if (file.size === 0) throw new ApiError('文件为空，没有可导入的正文。');
  const limit = extension === 'pdf' ? 100 * 1024 * 1024 : extension === 'docx' ? 30 * 1024 * 1024 : 400000;
  if (file.size > limit) {
    const message =
      extension === 'pdf'
        ? 'PDF 文件不能超过 100 MB。'
        : extension === 'docx'
          ? 'DOCX 文件不能超过 30 MB。'
          : 'TXT / Markdown 文件不能超过 400 KB。';
    throw new ApiError(message + '请拆分文件后重新上传。', { code: 'FILE_TOO_LARGE' });
  }
};

export const knowledgeUploadData = (file: File) => {
  validateKnowledgeUpload(file);
  const data = new FormData();
  data.append('file', file);
  return data;
};
