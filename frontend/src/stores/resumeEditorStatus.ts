import { create } from 'zustand';
import { onAccountReset } from '@/api/accountScope';

// 布局退出登录前先确认，以便清理私有草稿并卸载编辑器。
export const useResumeEditorStatus = create<{ unsaved: boolean }>(() => ({ unsaved: false }));
onAccountReset(() => useResumeEditorStatus.setState({ unsaved: false }));
