# LangChain4j 兼容性验证

本模块使用 JDK 21 和本机 HTTP 模型端点验证 LangChain4j SDK 与应用适配边界。测试覆盖工具调用消息往返、JSON 与 SSE 字段保留、截断响应、调用截止时间和取消请求，分别检查 SDK 默认行为与边界适配逻辑。

## 独立兼容性测试

在项目根目录运行：

```sh
node scripts/verify-langchain4j.mjs
```

运行环境为 Node.js 22.14+、Maven 和 JDK 21。Maven 依赖保存在项目的 `.m2/repository/`，缓存齐全时可追加 `--offline`。测试使用本机 HTTP 服务，无需数据库或模型密钥。

测试报告位于本模块的 `target/surefire-reports/`。脚本核对测试套件、依赖树和报告有效性，通过后将测试数量、实际依赖版本和源码 SHA-256 写入项目的 `docs/evidence/agent-langchain4j-compatibility.json`。

## 主应用集成验证

主应用通过 LangChain4j 适配 DeepSeek，使用完整 JSON 响应执行模型调用。在项目根目录运行：

```sh
node scripts/verify-langchain4j-runtime.mjs
```

该验证使用本机 HTTP 模拟服务和一次性 MySQL，PATH 中需提供 `mysqld`、`mysql` 和 `mysqladmin`。检查范围包括响应大小限制、JSON 取消与截止时间、原始字段保留、工具回执恢复、持久终态复用、模块依赖约束及跨线程追踪。通过后，测试套件统计和源码 SHA-256 写入 `docs/evidence/agent-langchain4j-runtime.json`。缓存齐全时可追加 `--offline`。

两个脚本的模型调用均在本机模拟服务中完成。真实供应商的业务结果、用量和质量由[供应商验收脚本](../../../scripts/verify-provider-acceptance.mjs)及[评测记录](../../../docs/evidence/README.md)管理。验证入口见[兼容性脚本](../../../scripts/verify-langchain4j.mjs)和[运行时脚本](../../../scripts/verify-langchain4j-runtime.mjs)。
