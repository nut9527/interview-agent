import { Button, Card, Form, Input, Popconfirm, Select, Space, Typography } from 'antd';
import styles from './ResumeDetailFields.module.css';
import AiTextOptimizer from './AiTextOptimizer';

const required = (label: string, max: number) => [{ required: true, whitespace: true, max, message: `请填写${label}（最多 ${max} 字）` }];
const months = /^[0-9]{4}-(0[1-9]|1[0-2])$/;
const tagsRule = (maxItems: number, maxLength: number) => ({
  validator: (_: unknown, values?: string[]) => (values?.length ?? 0) <= maxItems
    && (values ?? []).every(value => value.trim().length > 0 && value.length <= maxLength)
    ? Promise.resolve() : Promise.reject(new Error(`最多 ${maxItems} 项，每项 1–${maxLength} 字`)),
});

function RemoveEntry({ label, onRemove }: { label: string; onRemove: () => void }) {
  return <Popconfirm title={`移除此${label}？保存后生效。`} onConfirm={onRemove}>
    <Button danger size="small">移除{label}</Button>
  </Popconfirm>;
}

export function ContactFields() {
  return <section aria-label="联系方式">
    <Typography.Title level={5}>联系方式</Typography.Title>
    <Typography.Paragraph type="secondary">填写可供招聘方联系你的电话、邮箱，以及作品集、代码仓库或个人网站。</Typography.Paragraph>
    <div className={styles.grid}>
      <Form.Item name={['details', 'contact', 'phone']} label="联系电话"
        rules={[{ pattern: /^$|^[+0-9() -]{6,40}$/, message: '请输入有效的联系电话，可包含国际区号' }]}>
        <Input type="tel" autoComplete="tel" maxLength={40} placeholder="例如：+86 138 0000 0000" />
      </Form.Item>
      <Form.Item name={['details', 'contact', 'email']} label="联系邮箱"
        rules={[{ type: 'email', message: '请输入有效的邮箱地址' }, { max: 254 }]}>
        <Input type="email" autoComplete="email" maxLength={254} placeholder="例如：name@example.com" />
      </Form.Item>
      <Form.Item name={['details', 'contact', 'location']} label="所在城市" rules={[{ max: 120 }]}>
        <Input maxLength={120} placeholder="例如：武汉" />
      </Form.Item>
    </div>
    <Form.List name={['details', 'contact', 'links']}>
      {(fields, { add, remove }) => <>
        {fields.map(field => <Card size="small" key={field.key} className={styles.entry}>
          <div className={styles.grid}>
            <Form.Item name={[field.name, 'label']} label="链接名称" rules={required('链接名称', 80)}>
              <Input maxLength={80} placeholder="作品集 / GitHub / 个人网站" />
            </Form.Item>
            <Form.Item name={[field.name, 'url']} label="链接地址" rules={[
              ...required('链接地址', 1000),
              { pattern: /^https?:\/\/[^\s/]+(?:\/[^\s]*)?$/i, message: '请输入完整的 HTTP/HTTPS 地址' },
            ]}>
              <Input type="url" maxLength={1000} placeholder="https://example.com/portfolio" />
            </Form.Item>
          </div>
          <RemoveEntry label="链接" onRemove={() => remove(field.name)} />
        </Card>)}
        <Button className={styles.add} disabled={fields.length >= 8} onClick={() => add({})}>添加作品或个人链接</Button>
      </>}
    </Form.List>
  </section>;
}

export function EducationFields() {
  return <section aria-label="教育经历">
    <Typography.Title level={5}>教育经历</Typography.Title>
    <Form.List name={['details', 'education']}>
      {(fields, { add, remove }) => <>
        {fields.map(field => <Card size="small" key={field.key} className={styles.entry}>
          <Space className={styles.heading}><strong>教育经历 {field.name + 1}</strong>
            <RemoveEntry label="教育经历" onRemove={() => remove(field.name)} /></Space>
          <div className={styles.grid}>
            <Form.Item name={[field.name, 'school']} label="学校" rules={required('学校', 160)}><Input maxLength={160} /></Form.Item>
            <Form.Item name={[field.name, 'major']} label="专业" rules={required('专业', 160)}><Input maxLength={160} /></Form.Item>
            <Form.Item name={[field.name, 'degree']} label="学历" rules={required('学历', 80)}><Input maxLength={80} placeholder="例如：本科" /></Form.Item>
            <Form.Item name={[field.name, 'startDate']} label="入学年月" rules={[
              { required: true, pattern: months, message: '请填写 YYYY-MM 格式的年月' },
            ]}><Input placeholder="2024-09" maxLength={7} /></Form.Item>
            <Form.Item name={[field.name, 'endDate']} label="毕业年月 / 至今"
              dependencies={[[ 'details', 'education', field.name, 'startDate' ]]}
              rules={[{ required: true, pattern: /^[0-9]{4}-(0[1-9]|1[0-2])$|^至今$/, message: '请填写 YYYY-MM 或至今' },
                ({ getFieldValue }) => ({ validator: (_, value?: string) => {
                  const start = getFieldValue(['details', 'education', field.name, 'startDate']) as string | undefined;
                  return !value || value === '至今' || !start || value >= start ? Promise.resolve()
                    : Promise.reject(new Error('毕业时间不能早于入学时间'));
                } }),
              ]}><Input placeholder="2028-06 或 至今" maxLength={7} /></Form.Item>
          </div>
        </Card>)}
        <Button className={styles.add} disabled={fields.length >= 10} onClick={() => add({})}>添加教育经历</Button>
      </>}
    </Form.List>
  </section>;
}

export function ProjectFields({ allowOptimize = false }: { allowOptimize?: boolean }) {
  return <section aria-label="结构化项目经历">
    <Typography.Title level={5}>项目经历</Typography.Title>
    <Typography.Paragraph type="secondary">每个项目单独展示名称、角色、时间和技术栈，贡献逐条列出。</Typography.Paragraph>
    <Form.List name={['details', 'projects']}>
      {(fields, { add, remove }) => <>
        {fields.map(field => <Card size="small" key={field.key} className={styles.entry} id={`resume-project-${field.name}`} tabIndex={-1}>
          <Space className={styles.heading}><strong>项目 {field.name + 1}</strong>
            <RemoveEntry label="项目" onRemove={() => remove(field.name)} /></Space>
          <div className={styles.grid}>
            <Form.Item name={[field.name, 'name']} label="项目名称" rules={required('项目名称', 120)}><Input maxLength={120} /></Form.Item>
            <Form.Item name={[field.name, 'role']} label="本人角色" rules={[{ max: 120 }]}><Input maxLength={120} placeholder="例如：后端开发" /></Form.Item>
            <Form.Item name={[field.name, 'period']} label="起止时间" rules={[{ max: 120 }]}><Input maxLength={120} placeholder="例如：2026.07 - 至今" /></Form.Item>
          </div>
          <Form.Item name={[field.name, 'techStack']} label="技术栈" rules={[tagsRule(30, 80)]}>
            <Select mode="tags" tokenSeparators={[',', '，']} placeholder="输入后按回车，例如 Java、Spring Boot、MySQL" />
          </Form.Item>
          <Form.Item name={[field.name, 'background']} label="项目背景" rules={[{ max: 2000 }]}><Input.TextArea rows={2} maxLength={2000} /></Form.Item>
          <Form.Item name={[field.name, 'contributions']} label="个人贡献（每行一条）"
            getValueFromEvent={(event: React.ChangeEvent<HTMLTextAreaElement>) => event.target.value.split('\n')}
            getValueProps={(value?: string[]) => ({ value: value?.join('\n') ?? '' })}
            rules={[tagsRule(30, 3000)]}>
            <Input.TextArea rows={4} placeholder="做了什么、采用什么方案、交付什么结果；只填写实际经历" />
          </Form.Item>
          {allowOptimize && <AiTextOptimizer name={['details', 'projects', field.name, 'contributions']}
            label={`项目 ${field.name + 1} 贡献`} lines />}
        </Card>)}
        <Button className={styles.add} disabled={fields.length >= 20} onClick={() => add({ contributions: [], techStack: [] })}>添加项目经历</Button>
      </>}
    </Form.List>
  </section>;
}
