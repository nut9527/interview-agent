# Agent 追问质量证据

本页记录三路线追问质量对照。v5 单轮执行与引用专项的结果见[运行验收](runtime-acceptance.md)，两者按各自样本与指标统计。

当前证据不足以写“追问质量提升”。保留的 LEGACY / FIXED / TOOL 各 2 个开发案例，共 6 次成功运行；这只证明这些输入下流程可完成。新增离线审计显示人工评分为 0，30 个案例 × 3 条路线共需 90 条输出，现有输出覆盖 6/90。没有新增模型调用，没有补写人工分数。

## 已准备的材料

`agent-quality-smoke/plan.json` 固定了现有数据集中的 30 个独立材料家族（20 开发、10 验证），记录完整 fixture 和材料的 SHA-256。10 个保留家族不进入该计划。历史 6 条输出保留原样，计划不是已执行实验，也不是新一轮调用授权。

`agent-quality-smoke/blind-review.json` 是现有 6 条输出的待评分材料：随机编号、打乱顺序，仅保留问题、回答、材料、追问和面向人的评估文本；不提供路线、运行 ID、工具回执、配置、时延。评分表均为空。组织者只把这个文件交给评分者；不要同时交付 `key.json`、`evidence.json` 或 `summary.json`。文本本身仍可能让人猜测路线，盲法不能保证不可识别。

人工按 1–5 分评“追问相关性、证据支撑、具体可回答性”，并标注无依据纠错、原问题重复，填写评分依据、评分者和时间。`method` 必须填写 `HUMAN_BLIND`；脚本只能验证声明和字段完整，不能认证评分者身份或保证实际盲评。建议由未参与实现的人独立评分，有条件时增加第二位评分者核对分歧。不要用自动评分冒充人工评分。

`agent-quality-smoke/summary.json` 按路线列出缺失、失败、已评分数量；比较同一案例上的 LEGACY→FIXED、FIXED→TOOL 分数差，开发/验证分开。配置不同的配对会被排除；失败和缺失运行保留在覆盖率分母中。当前所有语义均分差均为 `null`，状态为 `INCOMPLETE_NO_QUALITY_CLAIM`。

## 复现与后续评分

```powershell
node --test scripts/agent-quality-review.test.mjs

# 用新目录重新导出；目录已存在则拒绝，不覆盖人工工作。
node scripts/agent-quality-review.mjs prepare docs/evidence/agent-quality-review-2 docs/evidence/agent-evaluation-legacy.jsonl docs/evidence/agent-evaluation-fixed.jsonl docs/evidence/agent-evaluation-tool.jsonl

# 人工只修改 blind-review.json 的 review 字段，完成后汇总到新文件。
node scripts/agent-quality-review.mjs summarize docs/evidence/agent-quality-review-2 docs/evidence/agent-quality-reviewed-2.json
```

后续要补全 30 案例对照，应先冻结同一代码版本、模型与参数、提示词、工具配置、输入可见性和调用预算，按计划为每条路线导出新的 JSONL，再用上述 `prepare` 导入。三条路线必须覆盖相同案例；每个案例/路线只能有一条记录，不能挑选表现最好的重跑。现有真实运行入口可接收 `RUN_EVAL_IDS` 和独立的 `RUN_EVAL_REPORT`，但本工具不调用它，也不绕过既有预算机制。历史 6 条是冒烟证据，不应拼入不同候选版本的新实验当作同一轮结果。

补全后也应写成“在固定的 10 个验证家族上，人工盲评的某维度平均分由 X 到 Y，配对差值为 Z”，同时给出样本量、失败数和评分方法；开发集结果只用于诊断。单轮合成案例不足以外推生产、多轮追问或未见材料质量，也不能仅凭均分上升宣称统计显著。

## 简历可写的机制

“围绕能力缺口组织证据检索与追问，通过工具白名单、参数校验、有限重试和结构化输出校验约束执行；建立三路线对照与人工盲评入口。”

目前不能写“追问质量提升”“TOOL 优于 FIXED”或把 6/6 运行成功当成质量分。30 案例完整真实输出与人工评分尚未完成。
