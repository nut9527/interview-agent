# 自定义目标岗位与 JD

更新：2026-10-26。目标岗位是面试材料，入口为 `/target-jobs`。

## 用户流程

添加岗位名称和完整 JD，可选填写公司、来源名称、HTTP/HTTPS 来源链接及截止日期（`YYYY-MM-DD`）。支持读取 UTF-8 TXT/Markdown。来源链接只保存，后端不自动抓取网页；截止日期没有提醒任务。

点击「从 JD 提取要求」后逐条核对必须要求、加分项和未知条件。可纠正文字、分类、检索词及原文引文，也可手动增删要求。重新提取前需要确认；修改 JD 后应重新核对要求，保存时会检查引文仍属于当前 JD。

保存后选择本人已保存的简历，查看每条要求关联的原文、字段位置和未找到的检索词。点击「重新核对」读取最新保存版本；分析结果包含当次岗位与简历 revision，仅在当前页面展示，不保存分析历史。

点击「编辑这份简历」进入 `/resumes/edit?resumeId=…`，补充事实或使用 [AI 优化](AI优化比较与撤销.md)。点击「使用这份简历与岗位开始面试」进入 `/interview?resumeId=…&targetJobId=…`，再选择难度与轮次。训练室读取当前岗位详情，启动时校验本人归属和 `targetJobRevision`，并固定完整 JD 文本快照；之后修改或删除源岗位不改变本场材料。会话目前不保存可反查岗位的 ID/revision 列。

只需要临时练习时，可直接在训练室选择「本场自定义 JD」，填写岗位名称和 JD；这不会创建目标岗位记录。完整流程见 [面试 Agent](面试Agent.md)。

## 提取与证据边界

- `target-jd-v1` 是本地确定性规则，不调用模型。按换行、分号和中文句号拆分，识别要求、加分项和职责标题，保留原文引文；未明确的信息保持 UNKNOWN。
- 复用 `SkillLexicon` 别名和词边界，并补充学历、毕业和安排词。保留英文名称中的句点，不把年份当作列表编号删除。
- JD 最多 20,000 字、100 条要求，每条最多 1,000 字和 20 个检索词；超出提取上限时明确提示，完整 JD 仍保留。
- `MUST/BONUS/UNKNOWN` 是要求分类；`REFERENCED/UNKNOWN` 是简历原文关联状态。关联原文不代表满足要求，无证据也不代表不符合；组合条件、熟练度和年限需人工核对。
- 每条要求最多展示 8 处原文，附字段路径、含义和命中词。支持旧文本和结构化项目、教育、毕业时间及实习安排，区分技能声明、背景与个人贡献。
- 规则不计分，不验证经历真实性，也不推断录用概率；复杂否定和跨句关系可能误关联。用户纠正结果按原样保存，下次读取不会自动重新提取。

## 后端与数据

`TargetJobController → TargetJobService → TargetJobRepository` 管理 V18 的 `user_target_jobs`；规则位于 `jobs.domain.TargetJobRules`，读取简历仅通过 `resume.api.ResumeSnapshotReader.requireOwned`。

每次查询、更新和删除均限定当前用户，访问他人或不存在的资源统一返回 404。创建 revision=0，更新与删除按 `id + user_id + revision` 条件执行，过期修订返回 409。列表只读摘要，按 `updated_at DESC, id DESC` 分页；COUNT 与条目在同一可重复读事务中读取。

注销和过期演示空间回收通过 `JobUserData` 清理本人岗位，外键级联作为兜底。演示空间复制模板简历及其版本，不复制其他账号的私有目标岗位。

## 前端与失败恢复

请求通过 `api/modules/targetJob.ts`，沿用账号作用域隔离。列表、详情和分析使用 AbortSignal，切换材料、账号或离开页面后忽略迟到响应。保存、提取期间禁用编辑并加提交锁。

409 保留输入，提示重新打开最新版核对，不强制覆盖。创建没有服务端幂等键，网络超时后应检查列表，不能直接断言未创建。该页面需要后端；无密钥演示使用后端 `AI_PROVIDER=mock`。

## 验证入口

```powershell
# backend/
mvn '-Dtest=TargetJobRulesTest,TargetJobServiceTest,TargetJobApiTest,ModuleBoundaryTest' test
# 项目根目录：创建并清理隔离 MySQL
node scripts/verify-backend.mjs --offline
# frontend/：需已有 Playwright，可用 PLAYWRIGHT_MODULE 指定模块
node scripts/verify-target-jobs-browser.mjs
node scripts/verify-interview-browser.mjs
```

产品收敛时的验证范围与限制见 [2026-10-26 记录](evidence/2026-10-26-interview-focus.json)。浏览器验收使用生产前端和合成 API；真实后端由隔离 MySQL 测试覆盖，两者不等于真实模型质量或部署验收。
