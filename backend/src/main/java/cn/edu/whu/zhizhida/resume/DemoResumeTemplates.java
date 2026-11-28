package cn.edu.whu.zhizhida.resume;

import java.util.List;

import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile;

/** 不含真实联系方式的虚构履历，覆盖基础、校招、业务开发和 AI 应用场景。 */
final class DemoResumeTemplates {
        private DemoResumeTemplates() {
        }

        static List<ResumeProfile> samples() {
                return List.of(
                                new ResumeProfile(
                                                null,
                                                "林晨（演示）",
                                                "Java 后端实习生",
                                                "虚构演示简历。软件工程本科在读，完成校园活动报名系统，能独立编写基础接口与测试；尚无企业实习经历，希望从业务开发实习起步。",
                                                List.of("Java", "Spring Boot", "MySQL", "HTTP", "Git", "JUnit"),
                                                List.of("2024.09—2028.06 示例大学（虚构） 软件工程 本科；主修数据结构、计算机网络、数据库系统。"),
                                                List.of("校园活动报名系统：负责活动查询、报名和取消接口；通过用户与活动联合唯一索引防止重复报名，编写 18 个接口测试。"),
                                                List.of(),
                                                List.of("CET-4"),
                                                "classic",
                                                0L,
                                                details(
                                                                "2028-06",
                                                                "每周 3 天，可连续实习 3 个月",
                                                                "lin.chen",
                                                                "武汉",
                                                                "软件工程",
                                                                "2024-09",
                                                                "2028-06",
                                                                new ResumeDetails.Project(
                                                                                "校园活动报名系统",
                                                                                "后端开发",
                                                                                "2026.03—2026.06",
                                                                                "为课程实践实现活动发布、报名和签到，使用 200 条虚构记录验证业务流程。",
                                                                                List.of(
                                                                                                "使用 Spring Boot 实现 6 个 REST 接口，统一参数校验和异常响应。",
                                                                                                "使用 MySQL 联合唯一索引处理重复报名，事务内校验名额并更新报名记录。",
                                                                                                "使用 JUnit 编写 18 个用例，覆盖重复报名、无效活动和取消后重新报名。"),
                                                                                List.of("Java", "Spring Boot", "MySQL",
                                                                                                "JUnit")))),
                                new ResumeProfile(
                                                null,
                                                "周宁（演示）",
                                                "Java 后端校招生",
                                                "虚构演示简历。计算机专业应届生，具备订单与库存项目和后端实习经历，关注事务边界、缓存和接口幂等；所有性能数据均来自本地模拟环境。",
                                                List.of(
                                                                "Java",
                                                                "Spring Boot",
                                                                "Spring MVC",
                                                                "MyBatis",
                                                                "MySQL",
                                                                "Redis",
                                                                "RocketMQ",
                                                                "Docker",
                                                                "Git",
                                                                "JUnit"),
                                                List.of("2023.09—2027.06 示例大学（虚构） 计算机科学与技术 本科；主修操作系统、数据库、软件测试。"),
                                                List.of("校园商城订单服务：设计下单、库存扣减和取消流程；使用 MySQL 乐观锁与 Redis 缓存，RocketMQ 驱动超时取消。"),
                                                List.of("2026.07—2026.09 示例云购科技（虚构） 后端实习：完成订单查询接口、补充 24 个回归用例，排查重复支付回调问题。"),
                                                List.of("CET-6"), "classic", 0L,
                                                details("2027-06", "毕业后全职到岗；当前每周可实习 4 天", "zhou.ning", "杭州",
                                                                "计算机科学与技术", "2023-09", "2027-06",
                                                                new ResumeDetails.Project("校园商城订单服务", "后端开发负责人",
                                                                                "2026.03—2026.08",
                                                                                "课程商城模拟订单、库存和支付回调，所有订单均为测试数据。",
                                                                                List.of(
                                                                                                "使用 Spring Boot、Spring MVC 和 MyBatis 实现订单接口，在事务内通过版本号更新库存。",
                                                                                                "以支付流水号唯一索引实现回调幂等，补充并发下单与重复通知测试。",
                                                                                                "使用 Redis 缓存商品详情，更新数据库后删除缓存，并记录失效与回源过程。",
                                                                                                "使用 RocketMQ 触发超时取消，消费前检查订单状态；通过 Docker 复现重复消息场景。"),
                                                                                List.of(
                                                                                                "Java",
                                                                                                "Spring Boot",
                                                                                                "MyBatis",
                                                                                                "MySQL",
                                                                                                "Redis",
                                                                                                "RocketMQ",
                                                                                                "Docker")))),
                                new ResumeProfile(
                                                null,
                                                "陈屿（演示）",
                                                "Java 后端工程师",
                                                "虚构演示简历。具有两年多 Java 业务开发经历，参与交易履约服务，能够结合压测数据解释索引、线程池和消息重试设计；关注故障恢复与可观测性。",
                                                List.of(
                                                                "Java",
                                                                "Spring Boot",
                                                                "MySQL",
                                                                "Redis",
                                                                "RocketMQ",
                                                                "Linux",
                                                                "Docker",
                                                                "JUnit",
                                                                "Git"),
                                                List.of("2020.09—2024.06 示例大学（虚构） 软件工程 本科。"),
                                                List.of("交易履约服务：负责订单状态机、消息幂等与失败补偿；在固定本地数据集下将订单查询 P95 从 420ms 降至 170ms。"),
                                                List.of("2024.07—至今 示例交易平台（虚构） Java 后端工程师：维护订单与履约模块，处理慢查询和消息积压，参与发布回滚与故障复盘。"),
                                                List.of("CET-6"), "classic", 0L,
                                                details("2024-06", "全职，预计确认录用后 1 个月到岗", "chen.yu", "深圳",
                                                                "软件工程", "2020-09", "2024-06",
                                                                new ResumeDetails.Project("交易履约服务", "核心后端开发",
                                                                                "2024.09—2026.10",
                                                                                "演示交易服务包含订单、支付回调和履约任务，使用合成流量进行压测。",
                                                                                List.of(
                                                                                                "使用 MySQL EXPLAIN 分析订单查询，调整联合索引；在 10 万条测试数据、50 并发下记录 P95 从 420ms 到 170ms 的变化。",
                                                                                                "使用 RocketMQ 投递确认与死信队列处理失败消息，通过业务唯一键避免重复履约。",
                                                                                                "为外部接口设置独立线程池、超时和限流，结合 JVM 线程转储定位阻塞。",
                                                                                                "为 Redis 热点缓存设置错峰过期，使用 Linux、Docker 日志和链路标识复现故障。"),
                                                                                List.of(
                                                                                                "Java",
                                                                                                "Spring Boot",
                                                                                                "MySQL",
                                                                                                "Redis",
                                                                                                "RocketMQ",
                                                                                                "Linux",
                                                                                                "Docker")))),
                                new ResumeProfile(
                                                null,
                                                "许言（演示）",
                                                "Java AI 应用开发实习生",
                                                "虚构演示简历。软件工程本科在读，使用 Java 实现带引用的知识问答原型，关注模型调用稳定性与资料隔离；有基础检索实践，尚无大规模部署经验。",
                                                List.of(
                                                                "Java",
                                                                "Spring Boot",
                                                                "MySQL",
                                                                "HTTP",
                                                                "JSON",
                                                                "LLM",
                                                                "Prompt",
                                                                "RAG",
                                                                "Python",
                                                                "JUnit",
                                                                "Git"),
                                                List.of("2023.09—2027.06 示例大学（虚构） 软件工程 本科；主修信息检索、数据库、软件工程。"),
                                                List.of("课程知识助手：实现资料分段、关键词检索、LLM 回答和引用校验；使用 30 条人工编写问题检查拒答与引用行为。"),
                                                List.of("2026.07—2026.09 示例智研实验室（虚构） 项目助理：整理测试文档，维护模型超时与结构化输出回归样例。"),
                                                List.of("CET-6"),
                                                "classic",
                                                0L,
                                                details(
                                                                "2027-06",
                                                                "每周 4 天，可连续实习 4 个月",
                                                                "xu.yan",
                                                                "武汉",
                                                                "软件工程",
                                                                "2023-09",
                                                                "2027-06",
                                                                new ResumeDetails.Project(
                                                                                "课程知识助手",
                                                                                "Java 服务与评测开发",
                                                                                "2026.04—2026.09",
                                                                                "面向课程笔记构建 RAG 问答原型，材料和问题均为可公开的虚构样例。",
                                                                                List.of(
                                                                                                "使用 Java、Spring Boot 和 HTTP 客户端调用 LLM，约束 JSON 输出并设置超时与有界重试。",
                                                                                                "使用 MySQL 存储文档分段和所属用户，检索前校验权限，回答中保留片段引用。",
                                                                                                "编写 Prompt 约束证据不足时说明缺失信息，校验返回引用确实来自本次检索。",
                                                                                                "使用 Python 整理 30 条评测问题，使用 JUnit 覆盖模型超时、无效 JSON、空检索和越权读取。"),
                                                                                List.of(
                                                                                                "Java",
                                                                                                "Spring Boot",
                                                                                                "MySQL",
                                                                                                "LLM",
                                                                                                "Prompt",
                                                                                                "RAG",
                                                                                                "Python",
                                                                                                "JUnit")))));
        }

        private static ResumeDetails details(
                        String graduation,
                        String availability,
                        String emailName,
                        String location,
                        String major,
                        String start,
                        String end,
                        ResumeDetails.Project project) {
                return new ResumeDetails(
                                graduation,
                                availability,
                                List.of(project),
                                new ResumeDetails.Contact("", emailName + "@example.com", location, List.of()),
                                List.of(new ResumeDetails.Education("示例大学（虚构）", major, "本科", start, end)));
        }
}
