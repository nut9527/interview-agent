import { create } from 'zustand';
import { onAccountReset } from '@/api/accountScope';

// Used by the layout before logout clears private drafts and unmounts the editor.
export const useResumeEditorStatus = create<{ unsaved: boolean }>(() => ({ unsaved: false }));
onAccountReset(() => useResumeEditorStatus.setState({ unsaved: false }));
