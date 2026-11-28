# 本地参考资料 RAG 测试集

来源：`D:\学习\实习` 中的 JavaGuide、小林 coding 图解和技术书籍。共 25 份原文、约 22.9 万字符、91 道题（开发 51、验证 40）。原有 `knowledge-retrieval.json` 保持独立。

## 资料性质与来源

JavaGuide 文件原样复制；PDF 按章节提取文字，统一 Unicode 排版字符，以 PDF 页码分节。`cases.json` 记录源路径、原文件 SHA-256、摘录页码、正文 SHA-256、资料家族和分区。问题与答案依据原文整理。

正文由应用与测试共用，位于 [reference-library](../../../main/resources/reference-library/)。JavaGuide 使用其随附的 [Apache-2.0 许可证](../../../main/resources/reference-library/javaguide/LICENSE)。PDF 摘录保留原作品版权。

| 目录 | 内容 | 问题标注 |
| --- | --- | --- |
| [javaguide](../../../main/resources/reference-library/javaguide/) | 14 篇 Java 后端、RAG、Agent、MCP 原文 | [50 题](javaguide/cases.json) |
| [xiaolin](../../../main/resources/reference-library/xiaolin/) | 8 份图解 MySQL、Redis、网络、系统章节 | [32 题](xiaolin/cases.json) |
| [books](../../../main/resources/reference-library/books/) | 3 份《Spring 实战》第 5 版章节 | [9 题](books/cases.json) |

其余四本经典书籍因扫描页或 OCR 错字较多，本批未选。

## 在网页里试用

1. 在「题目与资料 → 资料与出题」点击「参考资料」，选择原文，预览后确认入库。
2. 按 `cases.json` 的 `scope` 对照标题选择资料，输入 `question`。
3. 用 `answer`、`expected` 和 `evidence` 核对答案与引用；`shouldAbstain: true` 的问题应说明资料不足。

问题和答案文件不入知识库。development 用于调试，validation 用于验证；同一 PDF 的章节在同一分区。

每题 `scope` 最多选择 5 份资料，与生产向量接口一致。原先 55 道超限题已保留全部证据来源，优先补入同主题干扰资料，再按原顺序补满；变更前后范围见 [scope-adjustments.json](scope-adjustments.json)。旧范围的词项分数不能直接作为修正后混合检索的对照。

## 校验与检索评测

项目根目录执行，无需模型、数据库或网络：

```sh
node scripts/rag-corpus.mjs check
node --test scripts/rag-corpus.test.mjs
```

源资料目录仍在本机时，可运行 `node scripts/rag-corpus.mjs sources` 逐个核对原文件指纹。

在 `backend/` 执行（`-o` 使用项目已有的 Maven 离线依赖）：

```sh
# 验证引文能落在生产分块中，并保留原有检索回归。
mvn -o -B -ntp "-Dtest=KnowledgeCorpusFixtureTest,KnowledgeRetrievalEvaluationTest,KnowledgeHybridRetrievalEvaluationTest,KnowledgeRetrievalMetricsTest" test

# 扩展集词项检索；不调用回答模型。
mvn -o -B -ntp "-Dtest=KnowledgeHybridRetrievalEvaluationTest" "-Dknowledge.eval.fixture=/rag-corpus/manifest.json" test
```

报告写入 `backend/target/knowledge-retrieval-expanded.json`，包含资料指纹、逐题结果、按题型和分区统计的 Recall/MRR/nDCG，以及无答案题空检索率。

若本机 BGE 嵌入服务已经在 18092 就绪，可在第二条 Maven 命令中追加：

```text
-Dknowledge.eval.embeddingModel=bge-small-zh-v1.5-q -Dknowledge.eval.embeddingDimension=512 -Dknowledge.eval.embeddingPort=18092
```

此模式比较词项与本地 BGE 混合检索，使用内存索引。答案正确性与拒答需另外核对。

三轮性能对照在项目根目录运行 `node scripts/evaluate-reference-retrieval.mjs`。脚本使用独立构建目录与本机 BGE，输出带运行编号的 `docs/evidence/reference-retrieval-bge-<runId>.json`，保留每题范围、三轮延迟、分区质量指标以及语料和配置指纹。

Top-30 与全库诊断运行 `node scripts/evaluate-reference-retrieval.mjs --diagnostics`。同一批 91 题分别检索原范围和全部 25 份资料，输出两路 Top-30、RRF Top-30、最终 Top-6、章节与引文排名及分块正文。全库只用于内存实验，应用接口仍最多选择 5 份资料；结果见 [诊断报告](../../../../../docs/evidence/retrieval-candidate-diagnostics.md)。

新旧分块对照运行 `node scripts/evaluate-reference-retrieval.mjs --chunking`，比较原范围、全库下的词项与混合检索，每题预热后计时 3 次。[baseline/KnowledgeText.java](baseline/KnowledgeText.java) 冻结调整前的生产分块代码，脚本核对其指纹后在隔离目录分别编译新旧版本；语料和问题保持不变。结果见 [分块对照](../../../../../docs/evidence/chunking-comparison.md)。

[full-library-answerability.json](full-library-answerability.json) 记录 11 道资料不足题的全库复核理由和 25 份正文指纹，原有问题及证据标注保持不变。

固定候选的 cross-encoder 对照使用 `scripts/evaluate-reference-reranker.py`，原文相邻块审计使用 `node scripts/audit-reference-recall-gaps.mjs`。模型准备、依赖版本、对照路线和复跑步骤见 [重排实验](../../../../../docs/evidence/reranker-experiment.md)。

问题覆盖直接提问、同义问法、口语改写、跨文档、相似概念干扰和资料不足。检索按文档/章节计分，逐字引文用于核对具体证据。
