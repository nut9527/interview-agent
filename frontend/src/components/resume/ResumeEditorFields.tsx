import { Form, Input, Select } from 'antd';
import { ContactFields, EducationFields, ProjectFields } from './ResumeDetailFields';
import AiTextOptimizer from './AiTextOptimizer';
const { TextArea } = Input;

export default function ResumeEditorFields() {
  return <>
            <Form.Item
              name="ownerName"
              label="姓名"
              rules={[{ required: true, whitespace: true, message: '请输入姓名' }]}
            >
              <Input placeholder="请输入姓名" />
            </Form.Item>

            <Form.Item
              name="targetRole"
              label="目标岗位"
              rules={[{ required: true, whitespace: true, message: '请输入目标岗位' }]}
            >
              <Input placeholder="请输入目标岗位" />
            </Form.Item>

            <ContactFields />


            <Form.Item name="summary" label="个人简介">
              <TextArea
                rows={4}
                placeholder="请输入个人简介，描述您的专业背景和核心优势"
              />
            </Form.Item>

            <AiTextOptimizer name={['summary']} label="个人简介" />

            <Form.Item name="skills" label="技能标签">
              <Select
                mode="tags"
                placeholder="请输入技能标签，回车确认"
                style={{ width: '100%' }}
              />
            </Form.Item>

            <EducationFields />
            <Form.Item name="educationExperiences" label="教育经历补充 / 旧文本">
              <TextArea
                rows={3}
                placeholder="请输入教育经历，每行一条，例如：2020-2024 本科 清华大学 计算机科学与技术"
              />
            </Form.Item>

            <Form.Item name="projectExperiences" label="项目经历补充 / 旧文本">
              <TextArea
                rows={3}
                placeholder="请输入项目经验，每行一条，例如：XX项目 - 担任前端开发，负责XX模块"
              />
            </Form.Item>

            <Form.Item name={['details', 'graduationDate']} label="预计毕业年月" rules={[{ max: 120 }]}>
              <Input placeholder="例如：2028 年 6 月；不确定可以留空" />
            </Form.Item>
            <Form.Item name={['details', 'availability']} label="实习安排" rules={[{ max: 200 }]}>
              <Input placeholder="可到岗日期、每周天数、持续月数；未知可留空" />
            </Form.Item>
            <ProjectFields allowOptimize />

            <Form.Item name="internshipExperiences" label="实习经历（可选）">
              <TextArea
                rows={3}
                placeholder="请输入实习经历，每行一条，例如：2023年暑期 字节跳动 前端实习生"
              />
            </Form.Item>

            <Form.Item name="certificates" label="证书（可选）">
              <Select
                mode="tags"
                placeholder="请输入证书名称，回车确认"
                style={{ width: '100%' }}
              />
            </Form.Item>
  </>;
}
