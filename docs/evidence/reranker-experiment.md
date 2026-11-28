# 固定候选池重排实验

本轮在更新后的 25 份资料、91 道题上生成了 Java 候选报告。Python 校验重算了每个问题在选定资料和全库两种范围的 RRF 排名、章节配额和质量指标，共 182 组排名。另用量化 ONNX 重排器对 JG-01 的两组真实 Top-30 各执行一轮推理，验证了模型、分词器和新诊断格式可配合工作。当前语料的整集重排质量和延迟尚未测量。[当前候选诊断](reference-retrieval-diagnostics.json) · [两次推理输出](reference-reranker.json)

## 历史整集对照

下表是旧语料、旧分块下实测的 27 份资料、103 道题的结果，其中 90 道有引文标注，13 道资料不足。量化 BGE-Reranker-base 对固定的 RRF Top-30 重排，每章节优先选择 2 块，取最终 Top-6。按有引文问题计算宏平均。

| 范围 | RRF 引文 Recall@6 | 重排引文 Recall@6 | 新增重排阶段 P95 |
| --- | ---: | ---: | ---: |
| 每题选定资料 | 80.00% | 86.11% | 8.90 秒 |
| 全部 27 份资料 | 75.00% | 85.00% | 6.89 秒 |

历史汇总保存旧输入和报告的 SHA-256、模型版本、配置、各分区指标与计时，当前单题推理另存于同一 JSON 的 `currentSmoke` 字段。[历史聚合值与当前推理记录](reference-reranker.json)

## 复跑当前整集

先生成当前语料的候选报告。准备好项目内的 Python 重排环境和模型后，将其路径传给评测脚本。默认每题、每种范围计时三轮，另可传 `--rounds 1` 运行单轮。

```text
node scripts/evaluate-reference-retrieval.mjs --diagnostics
.cache/reranker-venv/Scripts/python.exe -B scripts/prepare-reference-reranker.py
.cache/reranker-venv/Scripts/python.exe -B scripts/evaluate-reference-reranker.py --diagnostics <诊断报告.json>
```

`python -B scripts/test_reference_reranker.py` 使用仓库收录的当前诊断核对 Python 和 Java 排名；设置 `RAG_DIAGNOSTICS` 可以指定其他本轮报告。候选池覆盖率及扩展结果见[召回缺口核对](reference-recall-gaps.json)。
