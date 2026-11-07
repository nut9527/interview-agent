package cn.edu.whu.zhizhida.jobs;

import cn.edu.whu.zhizhida.jobs.api.TargetJob;
import cn.edu.whu.zhizhida.jobs.domain.TargetJobRules;
import java.util.List;
import java.util.UUID;

/** 虚构岗位；随新演示空间创建私有副本，不依赖外部招聘网站或模型。 */
final class DemoTargetJobs {
    private DemoTargetJobs() {}

    static List<TargetJob> create() {
        return List.of(
                job("Java 后端实习生｜校园服务", "示例校园科技（虚构）", """
                        【演示样例，非真实招聘】Java 后端实习生，工作地点：武汉
                        岗位职责
                        参与校园活动报名、签到与统计接口开发，维护接口文档和自动化测试
                        配合前端定位联调问题，说明报名重复提交和人数上限的处理方式
                        任职要求
                        本科在读，计算机或软件工程相关专业，每周到岗至少 3 天，连续实习 3 个月
                        掌握 Java 集合、异常处理和面向对象设计，了解常用数据结构
                        熟悉 Spring Boot、HTTP 和 REST 接口设计
                        熟悉 MySQL 基础查询、索引与事务，能解释唯一约束如何防止重复报名
                        具备 Git 协作和 JUnit 单元测试经验，能够清楚介绍个人项目贡献
                        加分项
                        有 Redis 缓存或 Docker 部署实践优先
                        有接口压测、慢 SQL 排查经验优先
                        福利待遇
                        实习补贴和转正安排待确认，招聘截止日期未说明
                        """),
                job("Java 后端校招生｜订单与库存", "示例云购科技（虚构）", """
                        【演示样例，非真实招聘】Java 后端校招生，工作地点：杭州
                        岗位职责
                        参与订单、库存和支付回调模块建设，完善异常处理与自动化测试
                        分析重复请求、并发超卖和缓存不一致问题，记录可复现的验证过程
                        任职要求
                        本科及以上学历，面向应届毕业生，计算机相关专业
                        掌握 Java 集合、多线程和线程池，了解 JVM 内存与垃圾回收
                        熟悉 Spring Boot、Spring MVC、MyBatis 和事务边界
                        熟悉 MySQL 索引、事务隔离级别和乐观锁
                        熟悉 Redis 缓存失效策略，能够说明缓存与数据库一致性的取舍
                        具备 JUnit 测试和 Git 协作经验，能够提供订单或库存项目的具体设计说明
                        加分项
                        有 RabbitMQ 消息重试、幂等消费实践优先
                        有 Docker、Linux 部署和监控经验优先
                        福利待遇
                        薪资、入职时间与招聘截止日期待确认
                        """),
                job("Java 后端工程师｜高并发交易", "示例交易平台（虚构）", """
                        【演示样例，非真实招聘】Java 后端工程师，工作地点：深圳
                        岗位职责
                        负责交易订单与异步履约服务，设计故障补偿和可观测性方案
                        根据压测与线上指标定位瓶颈，说明容量评估、回滚和故障恢复策略
                        任职要求
                        本科及以上学历，至少 2 年 Java 后端开发经验
                        熟悉 Java 并发工具、线程池隔离和 JVM 问题排查
                        熟悉 Spring Boot、MySQL 索引优化、事务与并发控制
                        熟悉 Redis 热点缓存、缓存穿透与缓存一致性处理
                        熟悉 RabbitMQ 投递确认、重试、死信和幂等消费
                        具备 Linux、Docker 部署及故障排查经验，能够解释接口延迟与吞吐量数据
                        加分项
                        有 Kubernetes 或 Kafka 实践优先
                        有分布式链路追踪、限流和熔断实践优先
                        福利待遇
                        薪资范围、值班安排和截止日期待确认
                        """),
                job("Java AI 应用开发实习生｜知识助手", "示例智研实验室（虚构）", """
                        【演示样例，非真实招聘】Java AI 应用开发实习生，工作地点：武汉
                        岗位职责
                        基于 Java 服务实现知识问答、资料导入与回答引用，保留可追溯的证据
                        维护评测样例，处理模型超时、结构化输出错误和资料权限隔离
                        任职要求
                        本科在读，每周到岗至少 4 天，连续实习 4 个月
                        熟悉 Java、Spring Boot、HTTP 和 JSON，能实现稳定的 API 调用
                        熟悉 MySQL，具备文档分段、检索和权限校验的项目实践
                        了解 LLM、Prompt 和 RAG，能够说明检索不足时的回答策略
                        具备自动化测试经验，能够设计超时重试、引用校验与错误回退用例
                        加分项
                        有 Python 数据处理、向量检索或模型评测实践优先
                        有 Redis、Docker 或 SSE 流式接口开发经验优先
                        福利待遇
                        实习补贴、远程安排和截止日期待确认
                        """)
        );
    }

    private static TargetJob job(String title, String company, String jd) {
        return new TargetJob(UUID.randomUUID().toString(), 0, title, company, "内置演示样例（虚构）",
                "", null, jd, TargetJobRules.extract(jd).requirements(), null);
    }
}
