INSERT INTO job_postings (
    id, title, company, required_skills_json, description, location,
    source_type, source_name, source_url, published_at, fetched_at
)
SELECT 1, 'Java 后端实习生', '示例科技', '["Java","Spring Boot","MySQL"]',
       '参与业务接口与数据服务开发。', '武汉', 'LOCAL', '项目内置岗位', '', '', ''
WHERE NOT EXISTS (SELECT 1 FROM job_postings WHERE id = 1);

INSERT INTO job_postings (
    id, title, company, required_skills_json, description, location,
    source_type, source_name, source_url, published_at, fetched_at
)
SELECT 2, '前端开发实习生', '云端软件', '["Vue3","TypeScript","Element Plus"]',
       '负责 Web 端页面与组件开发。', '武汉', 'LOCAL', '项目内置岗位', '', '', ''
WHERE NOT EXISTS (SELECT 1 FROM job_postings WHERE id = 2);

INSERT INTO job_postings (
    id, title, company, required_skills_json, description, location,
    source_type, source_name, source_url, published_at, fetched_at
)
SELECT 3, 'AI 应用开发实习生', '智能实验室', '["Python","LLM","Prompt"]',
       '参与 AI 应用原型与工具链建设。', '武汉', 'LOCAL', '项目内置岗位', '', '', ''
WHERE NOT EXISTS (SELECT 1 FROM job_postings WHERE id = 3);

INSERT INTO job_postings (
    id, title, company, required_skills_json, description, location,
    source_type, source_name, source_url, published_at, fetched_at
)
SELECT 4, '产品运营实习生', '校园创新工坊', '["需求分析","数据分析","AIGC"]',
       '维护岗位画像与求职数据反馈。', '武汉', 'LOCAL', '项目内置岗位', '', '', ''
WHERE NOT EXISTS (SELECT 1 FROM job_postings WHERE id = 4);
