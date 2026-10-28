# 职智达 · Agent 模拟面试平台

围绕“准备简历与岗位 JD → 选择难度 → Agent 面试 → 复盘”组织产品。登录后直接进入 `/interview`，导航只保留面试训练室、简历管理、目标岗位、账号设置。

React + TypeScript 前端；Java 21 + Spring Boot 4.1.2 后端；MyBatis-Plus + MySQL；Flyway 管理迁移。AI 支持无密钥 Mock 与 DeepSeek/OpenAI 兼容接口。

## 当前能力

| 模块           | 能力                                                                                                                                           |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 账号与演示空间 | 登录、注册、独立限时演示空间、资料设置、密码修改与注销；按账号隔离数据                                                                         |
| 简历管理       | 列表、PDF/DOC/DOCX 导入解析与核对、新建、编辑、删除、内容预览；保留草稿恢复、并发冲突处理、版本历史与恢复                                      |
| 简历 AI 优化   | 在简历编辑页优化个人简介和项目贡献；比较后逐项采用，可撤销；不自动覆盖原文                                                                     |
| 目标岗位       | 本人私有 JD 的 CRUD、TXT/Markdown 导入、要求提取与纠正、对照已保存简历定位原文；可携带材料进入面试                                             |
| 面试训练室     | 已保存岗位或本场自定义 JD（二选一，最多 20,000 字）、简历选择、入门/标准/挑战三档难度、练习方向与轮次、动态追问、历史继续/删除、能力证据与复盘 |
| 专项训练与复练 | 数据库、并发、性能、项目深挖主题，3～6 轮动态追问；复盘卡片一键复练，保留原能力、回答缺口与材料快照，关联前后会话并对照回答和证据 |
| 简历打印       | 内容预览使用浏览器打印，可在浏览器选择保存为 PDF；无模板选择、jsPDF 或打包中文字体                                                             |

面试难度默认 `STANDARD`，和 JD、简历快照一起固定在本场会话中；续答与重新打开不会丢失。入门侧重基础和职责，标准侧重岗位实践与验证，挑战增加复杂约束、原理、替代方案和边界。难度不改变总轮次和服务端允许动作。

已删除首页概览、公共岗位广场/采集/匹配/收藏、投递跟踪、职业报告、简历诊断评分、诊断项目练习入口、模板与专用 PDF 导出、独立求职准备 Agent；相关前后端实现、专属测试和 `langchain4j`/Jsoup/jsPDF 依赖已清理。旧功能地址回到训练室；旧编辑链接 `/app/analysis` 保留跳转到 `/resumes/edit`。

## 本地运行

需要 JDK 21、Maven 3.8+、Node 22.14+，以及 Docker Compose 或现有 MySQL 8。POM、CI、Docker 构建与运行镜像统一使用 Java 21，Maven 与启动器会校验 JDK 主版本。前端使用 pnpm 9.15.9 与 `pnpm-lock.yaml`。私密配置放在根目录 `.env`，配置示例见 [.env.example](.env.example)。

```powershell
# 根目录；Windows 也可双击 start.cmd
node scripts/dev.mjs
node scripts/dev.mjs check
# 已缓存依赖与数据库镜像时，无密钥运行完整面试 Agent
node scripts/dev.mjs --offline --mock-ai
# 使用已有 MySQL
node scripts/dev.mjs --external-db
```

默认前端 http://127.0.0.1:5173，后端 http://127.0.0.1:8080，健康检查 `/api/health`。前端通过同源 `/api` 代理后端。完整面试请连接后端；`AI_PROVIDER=mock` 不调用付费模型，`AI_PROVIDER=deepseek` 使用 `.env` 的模型配置。无需独立 Agent 开关。

```powershell
docker compose --profile app up -d --build
```

运行日志位于 `.runtime/`。Ctrl+C 停止启动器创建的前后端，保留数据库及数据卷。更多说明见 [启动与故障排查](docs/启动与故障排查.md)。

## 数据与兼容

V23 为面试记录添加 `difficulty`，旧记录默认标准难度。已执行的 V1–V22 Flyway 历史保持可验证，不对旧业务库自动 DROP TABLE 或清空资料。旧功能表仅作为历史迁移/数据兼容保留，不再有对应业务接口或后台任务；注销与演示空间回收仍清理本人数据。历史项目面试的 `preparation_json` 保留只读兼容。

本次移除的 API 与启动请求旧来源字段明确记录在 [契约退役清单](contracts/interview-focus-retirements.json)。CI 仅允许明确列出的产品退役变更，其余保留接口继续执行兼容检查。

## 验证

```powershell
cd backend
mvn test
cd ..
# 隔离 MySQL，执行单元/Web、实库和双 80% 覆盖率门禁
node scripts/verify-backend.mjs --offline
cd frontend
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm test
corepack pnpm build
corepack pnpm contract:check
# 使用已安装的 Playwright，可设置 PLAYWRIGHT_MODULE 指向现有模块
node scripts/verify-interview-browser.mjs
```

2026-10-26 产品收敛回归通过后端单元/Web、隔离 MySQL 和覆盖率门禁，以及前端测试、类型、lint、生产构建与契约检查；历史数量以对应记录为准。浏览器使用真实生产构建与合成 API 验证难度/JD 提交、会话重开、简历编辑/预览/打印、版本入口、旧地址和移动端；真实模型质量、旧业务库升级和部署未在本次验证。详见 [验证记录](docs/evidence/2026-10-26-interview-focus.json)。

## 目录

```text
backend/       # 账号、简历、私有目标岗位、面试 Agent、模型与公共基础设施
frontend/      # 面试训练室、Resumes/List、Resumes/Editor、目标岗位、账号页面
contracts/     # OpenAPI 快照与明确退役的 API 清单
scripts/       # 启动、隔离数据库验证、契约检查
docs/          # 当前设计与历史验证记录
```

机制说明见 [面试 Agent](docs/面试Agent.md)、[架构](docs/architecture.md) 和 [文档导航](docs/README.md)。
