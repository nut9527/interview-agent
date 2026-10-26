import type { OptimizeResumeRequest, OptimizeResumeResponse } from '@/types/api.d';
import { post } from '@/api/request';
import { USE_MOCK } from '@/config/runtime';
import { CanceledError } from 'axios';

export const aiService = {
  optimize: (data: OptimizeResumeRequest, signal?: AbortSignal): Promise<OptimizeResumeResponse> => {
    if (USE_MOCK) {
      const mockResponse: OptimizeResumeResponse = {
        optimizedText: data.rawText.split('\n').map(line => {
          const text = line.trim();
          return !text || /[。！？.!?]$/.test(text) ? text : `${text}。`;
        }).join('\n'),
        suggestions: ['建议增加具体的项目经验数据', '可以强调与目标岗位相关的技能', '建议添加量化成果'],
        improvements: [
          {
            category: '表达优化',
            issue: '原文较为平铺直叙',
            action: '使用更专业的术语和结构化表达',
            example: '将"我做了XX项目"改为"负责XX项目的XX模块，实现了XX功能"',
          },
          {
            category: '关键词匹配',
            issue: '缺少岗位相关关键词',
            action: '仅使用原文中确实掌握、且与目标岗位相关的技能',
            example: '说明已使用的技术如何帮助完成个人负责的工作',
          },
        ],
        targetKeywords: [],
        optimizationSummary: 'Mock 模式仅整理行首尾空白和句末标点，请核对后选择采用。',
      };
      return new Promise((resolve, reject) => {
        if (signal?.aborted) { reject(new CanceledError()); return; }
        const cancel = () => { clearTimeout(timer); reject(new CanceledError()); };
        const timer = setTimeout(() => {
          signal?.removeEventListener('abort', cancel);
          resolve(mockResponse);
        }, 1500);
        signal?.addEventListener('abort', cancel, { once: true });
      });
    }
    return post<OptimizeResumeResponse>('/api/ai/resume/optimize', data, { signal });
  },
};
