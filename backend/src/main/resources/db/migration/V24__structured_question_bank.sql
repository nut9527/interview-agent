-- System-owned original learning material. IDs are permanent; later edits use new migrations.
CREATE TABLE question_bank (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    question TEXT NOT NULL,
    topic VARCHAR(32) NOT NULL,
    competency_id VARCHAR(32) NOT NULL,
    difficulty VARCHAR(16) NOT NULL,
    tags_json JSON NOT NULL,
    key_points_json JSON NOT NULL,
    reference_answer TEXT NOT NULL,
    common_mistakes_json JSON NOT NULL,
    follow_ups_json JSON NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_title VARCHAR(255) NULL,
    source_url VARCHAR(2048) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    INDEX idx_question_topic_id (topic, id),
    INDEX idx_question_difficulty_id (difficulty, id),
    INDEX idx_question_topic_difficulty_id (topic, difficulty, id),
    CONSTRAINT chk_question_difficulty CHECK (difficulty IN ('BEGINNER','STANDARD','ADVANCED')),
    CONSTRAINT chk_question_source CHECK (source_type IN ('ORIGINAL','EXTERNAL_REFERENCE')),
    CONSTRAINT chk_question_competency CHECK (
        (topic = 'JAVA' AND competency_id = 'foundation') OR
        (topic IN ('DATABASE','REDIS') AND competency_id = 'storage') OR
        (topic = 'CONCURRENCY' AND competency_id = 'concurrency') OR
        (topic = 'PERFORMANCE' AND competency_id = 'performance') OR
        (topic = 'RELIABILITY' AND competency_id = 'reliability')),
    CONSTRAINT chk_question_arrays CHECK (
        JSON_TYPE(tags_json) = 'ARRAY' AND JSON_TYPE(key_points_json) = 'ARRAY' AND
        JSON_TYPE(common_mistakes_json) = 'ARRAY' AND JSON_TYPE(follow_ups_json) = 'ARRAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-java-001',
    '对象作为 HashMap 的键',
    '订单对象以订单号计算 equals 和 hashCode，放入 HashMap 后又修改订单号。查询为什么可能失败？你会怎样设计这个键？',
    'JAVA',
    'foundation',
    'BEGINNER',
    JSON_ARRAY('Java', 'HashMap', 'equals'),
    JSON_ARRAY('相等性与哈希契约', '键的可变性', '不可变标识设计'),
    'HashMap 先根据哈希定位桶，再比较键。改变参与哈希的字段后，新哈希可能指向另一桶；即便恰好同桶，也可能破坏相等性语义。使用不可变订单号或只含稳定标识的值对象作为键，并同时实现 equals/hashCode。用两个相等对象查找、不同对象碰撞及修改原业务对象的测试验证；不要依赖某次修改后恰好还能查到。',
    JSON_ARRAY('只重写 equals 就足够', '认为使用同一对象引用查询必然成功'),
    JSON_ARRAY('只修改不参与 equals 的字段会怎样？', '哈希碰撞与相等对象有什么区别？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-java-002',
    '泛型集合的读写边界',
    '编写汇总函数，将多个 Integer 或 Double 集合中的数值读取到 List<Number>。参数用 List<Number>、List<? extends Number> 还是 List<? super Number>？说明哪些位置可以写入。',
    'JAVA',
    'foundation',
    'BEGINNER',
    JSON_ARRAY('Java', '泛型', '集合'),
    JSON_ARRAY('泛型不变性', '生产者与消费者边界', '编译期类型安全'),
    '源集合采用 List<? extends Number>，允许读取为 Number，但不能随意写入某个具体 Number 子类；目标可用 List<? super Number>，可写入 Integer 和 Double，读取时只能保证 Object。List<Integer> 不是 List<Number> 的子类型，否则调用方可能把 Double 写进整数列表。用编译期示例验证允许和禁止的调用，不用强制转换绕过约束。',
    JSON_ARRAY('把泛型继承等同于类继承', '认为 extends 通配符集合完全不可变'),
    JSON_ARRAY('如果方法需要返回同一种数值子类，应如何声明类型参数？', '为什么 List<?> 可以移除元素却不能添加任意对象？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-java-003',
    '导入文件时的异常与资源释放',
    '批量导入 CSV 时，解析可能失败，关闭流也可能失败。如何关闭资源、保留根因，并向调用方报告哪一行失败？是否应该捕获所有异常后返回空列表？',
    'JAVA',
    'foundation',
    'STANDARD',
    JSON_ARRAY('Java', '异常', '资源管理'),
    JSON_ARRAY('try-with-resources', '异常语义与上下文', '部分成功策略'),
    '用 try-with-resources 管理流，解析异常作为主异常，关闭异常作为 suppressed 异常保留。将行号和业务字段等安全上下文包装进导入异常，保留 cause，避免日志输出整行隐私数据。明确全量原子导入或逐行校验后提交的语义，不把失败伪装成空结果。用损坏行、读取中断和关闭失败的测试替身验证资源释放及主异常可追溯。',
    JSON_ARRAY('finally 中抛新异常覆盖原异常', 'catch Exception 后返回空集合表示成功'),
    JSON_ARRAY('逐行容错时如何报告多行错误？', '关闭异常被 suppressed 后如何在监控中保留线索？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-java-004',
    '不可变快照的防御性复制',
    '服务把配置 List<Rule> 存进 record 并提供给多个请求。调用方随后修改原列表或 Rule 字段。这个 record 是否构成不可变快照？如何修复并验证？',
    'JAVA',
    'foundation',
    'STANDARD',
    JSON_ARRAY('Java', 'record', '不可变对象'),
    JSON_ARRAY('浅层与深层不可变', '防御性复制', '快照发布'),
    'record 使组件引用不可重新赋值，并不保证列表及元素不可变。构造时使用 List.copyOf 隔离列表结构，再把可变 Rule 转成只含不可变字段的值对象；访问器不暴露可变内部对象。更新配置时整体替换快照，通过 volatile 或 AtomicReference 安全发布。测试原集合增删、原元素修改及并发更新，确认一次请求始终读取同一版本；若字段含嵌套集合还需逐层处理。',
    JSON_ARRAY('record 自动提供深度不可变', 'unmodifiableList 一定隔离原集合修改'),
    JSON_ARRAY('如果 Rule 中包含 byte[]，如何保护它？', '复制大量配置的成本如何测量和控制？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-java-005',
    '插件类加载与内存回收',
    '系统反复热加载插件，插件实例看似已经丢弃，但元空间持续增长。不同插件的同名类为什么不能直接互转？如何在不中断主服务的条件下排查和治理？',
    'JAVA',
    'foundation',
    'ADVANCED',
    JSON_ARRAY('Java', '类加载', '内存'),
    JSON_ARRAY('类身份由名称和加载器共同决定', '类加载器引用链', '热更新隔离与回收边界'),
    '类身份包括二进制名称和定义它的类加载器，因此同名类不一定可赋值。先观察加载类数量、卸载数量和元空间趋势，结合堆转储检查加载器的 GC root 引用链，重点检查线程上下文加载器、ThreadLocal、静态注册表和未关闭线程池。插件共享接口由父加载器加载，卸载协议撤销注册、结束任务并释放资源；类卸载还取决于加载器可达性和 GC 条件。若无法可信地清理第三方插件，可采用独立进程，接受通信和启动成本。用多轮加载卸载压测确认平台期。',
    JSON_ARRAY('把同名类视为同一种类型', '调用 System.gc 就能保证卸载所有插件'),
    JSON_ARRAY('如何发现插件启动但未终止的线程？', '父优先与隔离加载如何影响依赖冲突？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-database-001',
    '联合索引如何匹配查询',
    '订单表常按 tenant_id、status 查询，并按 created_at、id 排序取前 20 条。你会如何设计索引？省略 status 后是否还能直接利用同一索引完成排序？',
    'DATABASE',
    'storage',
    'BEGINNER',
    JSON_ARRAY('MySQL', '索引', 'EXPLAIN'),
    JSON_ARRAY('联合索引前缀', '过滤与排序关系', '执行计划验证'),
    '可从 (tenant_id,status,created_at,id) 开始，前两列等值过滤后按后两列扫描；是否覆盖索引取决于返回列。省略 status 时，结果按 status 分段，通常不能直接得到跨状态的 created_at 全局顺序。若该路径频繁，可评估 (tenant_id,created_at,id)，并权衡写入成本。以真实数据分布运行 EXPLAIN，比较扫描行数、排序和延迟，再决定是否保留两个索引。',
    JSON_ARRAY('联合索引包含字段就一定命中全部条件', '只看索引名称而忽略扫描量'),
    JSON_ARRAY('低选择性的 status 放在前面一定错误吗？', '深分页时怎样使用 created_at 和 id 游标？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-database-002',
    '转账事务的原子性',
    '同一 MySQL 实例中，从账户 A 扣除 100 元并为 B 增加 100 元。第二步失败时怎样避免钱丢失？余额不足和并发转账分别如何处理？',
    'DATABASE',
    'storage',
    'BEGINNER',
    JSON_ARRAY('MySQL', '事务', '原子性'),
    JSON_ARRAY('提交与回滚', '余额条件更新', '并发锁定顺序'),
    '将扣款、加款和流水放在同一数据库事务内，任何一步失败都回滚。扣款使用带余额条件的原子 UPDATE 并检查影响行数，金额使用定点类型。多个账户按稳定 ID 顺序加锁，降低死锁概率；遇死锁应重试完整事务并结合业务幂等键。提交前模拟第二步失败，验证余额和流水一起回滚，再用并发测试验证总额不变及余额约束。',
    JSON_ARRAY('事务会自动处理余额不足的业务判断', '只回滚扣款而忽略已写入的流水'),
    JSON_ARRAY('客户端在提交后超时应怎样查询结果？', '跨数据库转账为何不能直接沿用本地事务？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-database-003',
    '重复请求下的订单创建',
    '用户双击提交，网关超时也会重试。如何保证同一个下单意图只创建一张订单，同时允许用户随后再次购买同一商品？',
    'DATABASE',
    'storage',
    'STANDARD',
    JSON_ARRAY('MySQL', '幂等', '唯一约束'),
    JSON_ARRAY('请求身份与业务身份', '唯一约束竞争', '结果回放'),
    '让客户端为一次下单意图生成幂等键，在用户范围内建立唯一约束，并保存请求摘要和最终订单关联。订单及幂等记录在同一事务提交；并发竞争以数据库约束为准，失败方读取已提交结果。相同键但不同参数拒绝处理，新购买使用新键。若处理未结束，返回可查询状态而不是虚构成功。验证并发重复、不同参数复用、提交后响应丢失及新购买四条路径。',
    JSON_ARRAY('先查不存在再插入就能避免重复', '用用户和商品作为永久幂等键'),
    JSON_ARRAY('幂等记录何时可清理？', '下单包含外部支付调用时如何划分事务？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-database-004',
    '库存预留的隔离级别陷阱',
    '库存剩余 1 件，两个事务各自 SELECT 后都看到 1，再分别创建预留记录。仅开启事务或可重复读是否足够？请设计可验证的预留流程。',
    'DATABASE',
    'storage',
    'STANDARD',
    JSON_ARRAY('MySQL', '隔离级别', '库存'),
    JSON_ARRAY('快照读与锁定读', '条件更新', '事务内业务约束'),
    '普通 SELECT 可能是快照读，事务本身不把读取后写入的业务判断变成互斥。可用 UPDATE stock SET available=available-1 WHERE id=? AND available>=1，并按影响行数决定是否写预留记录，两步放在同一事务。也可锁定库存行再检查，但需限制持锁时间。用屏障同时启动两个事务，验证仅一条成功且库存不为负；还要测试预留写入失败后的回滚和超时释放的幂等性。',
    JSON_ARRAY('可重复读会自动阻止所有超卖', '在 Java 内 synchronized 就能覆盖多实例'),
    JSON_ARRAY('多 SKU 预留怎样降低死锁？', '过期释放与支付确认竞争时怎样避免重复回补？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-database-005',
    '在线增加字段与回填',
    '大订单表持续写入，要增加 NOT NULL 的归属字段并由旧字段计算回填。新旧版本会并行运行且要求快速回滚。如何安排迁移、发布和验证？',
    'DATABASE',
    'storage',
    'ADVANCED',
    JSON_ARRAY('MySQL', '在线迁移', '兼容发布'),
    JSON_ARRAY('扩展与收缩阶段', '回填和新写竞争', '回滚边界'),
    '先增加兼容的可空列，确认具体 MySQL 版本与 DDL 算法的锁和重建行为，在副本或压测库演练。发布兼容新旧读写的代码，再按主键分批、可续跑回填，更新时防止覆盖已写入的新值。观察锁等待、复制延迟和失败率，逐步验证空值数和语义一致性后切读。旧版本退出且回滚窗口结束后才收紧约束或移除旧字段。每个阶段有暂停和恢复点，不能把删除旧列后的回滚当成简单回退代码。',
    JSON_ARRAY('所有 ADD COLUMN 都完全无锁', '一次大事务回填既最快又最安全'),
    JSON_ARRAY('回填期间计算规则变化该怎样处理？', '如何确定可以安全添加 NOT NULL 约束？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-redis-001',
    '缓存命中不等于数据永远有效',
    '商品详情读多写少，允许最多一分钟的陈旧数据。如何使用缓存、设置过期时间，并区分缓存未命中与商品不存在？',
    'REDIS',
    'storage',
    'BEGINNER',
    JSON_ARRAY('Redis', 'TTL', 'Cache Aside'),
    JSON_ARRAY('缓存旁路读取', '过期与陈旧窗口', '空值语义'),
    '先读缓存，未命中则读数据库并写入带 TTL 的值。对不存在的商品使用明确的空值标记与较短 TTL，区分空记录和 Redis 错误。商品更新后使缓存失效，TTL 作为最终收敛保障；一分钟要求还受并发回填、处理延迟影响，应根据业务时效留余量。统计命中率、空值率和回源延迟，用可控时钟测试过期、更新和不存在三种路径。',
    JSON_ARRAY('缓存未命中就等同于商品不存在', '设置 TTL 后无需考虑更新失效'),
    JSON_ARRAY('空值缓存会如何影响刚创建的商品？', '访问随机不存在 ID 时还能增加哪些保护？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-redis-002',
    '热点过期造成数据库突刺',
    '某热门商品缓存过期后，同一时刻有几千次请求回源。怎样降低并发回源？失败者应该一直自旋等锁吗？',
    'REDIS',
    'storage',
    'BEGINNER',
    JSON_ARRAY('Redis', '缓存击穿', '限流'),
    JSON_ARRAY('同键请求合并', '等待与超时上限', '回源保护'),
    '可在单实例用 single-flight 合并同键加载，多实例视规模使用短期协调锁或逻辑过期值。竞争失败者短暂等待后重读，达到预算就降级或返回可重试错误，避免无限自旋；数据库仍需并发上限。锁持有者必须在 finally 释放并考虑租期及崩溃。TTL 随机化主要分散多个键同时过期，不能单独解决单热键击穿。用集中到期和回源失败压测验证数据库峰值。',
    JSON_ARRAY('随机 TTL 可以独自解决所有热点问题', '加锁后可以无限等待'),
    JSON_ARRAY('允许返回旧值时怎样做异步刷新？', '单实例合并在多实例部署下有什么边界？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-redis-003',
    '数据库提交后的缓存失效',
    '先提交数据库再删除缓存时，删除失败或并发旧查询回填会导致什么？如果业务允许几秒陈旧，你会如何实现并验证？',
    'REDIS',
    'storage',
    'STANDARD',
    JSON_ARRAY('Redis', '一致性', '事务消息'),
    JSON_ARRAY('数据库与缓存非原子', '失效任务可靠重试', '并发回填时序'),
    '明确数据库是事实来源，提交后删除缓存；删除失败写入可靠失效任务，通过本地事务 outbox 或已验证的变更订阅重试，配合短 TTL。并发旧查询可能在删除后回填旧值，需分析时间线，可通过版本检查、延迟再次失效或限制加载窗口缩短风险，但这些方案都有条件。测量最坏陈旧时间并演练删除失败、消费者延迟、旧读迟到；若场景要求强一致，应让关键读绕过缓存或使用更严格协议。',
    JSON_ARRAY('先删缓存再提交一定强一致', '延迟双删在任意故障下都保证正确'),
    JSON_ARRAY('怎样避免重试任务永久积压？', '版本号由谁生成，怎样防止旧值覆盖新值？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-redis-004',
    'Redis 锁释放的所有权',
    '进程 A 的锁租期到期后，B 获得同一锁；A 恢复执行并释放锁。怎样防止 A 删除 B 的锁？这是否已经保证业务写入安全？',
    'REDIS',
    'storage',
    'STANDARD',
    JSON_ARRAY('Redis', '分布式锁', '租约'),
    JSON_ARRAY('唯一令牌与原子释放', '租约过期后的旧持有者', '下游防护'),
    '获取时使用带随机令牌和租期的原子 SET NX，释放时用原子脚本比较令牌再删除，避免删掉新持有者的锁。但这只保护锁记录，A 仍可能在租期后向业务存储写入。关键写入需数据库版本条件、唯一约束或下游校验的单调 fencing token；续租也无法消除长停顿和网络分区。测试暂停 A、租期到期、B 写入、A 恢复的时序，检查陈旧写是否被拒绝。',
    JSON_ARRAY('先 GET 再 DEL 就是原子释放', '自动续租等于永不失去锁'),
    JSON_ARRAY('fencing token 如何生成并在存储端验证？', '如果下游不支持版本校验，应如何调整方案？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-redis-005',
    '缓存故障时的容量保护',
    'Redis 部分节点故障，缓存命中率从 95% 降到 30%，数据库最多承受平时回源量的三倍。系统既要提供核心查询又要避免连锁故障，如何取舍？',
    'REDIS',
    'storage',
    'ADVANCED',
    JSON_ARRAY('Redis', '降级', '容量规划'),
    JSON_ARRAY('回源放大测算', '隔离和限流', '恢复时流量控制'),
    '先按总 QPS 估算回源从 5% 升到 70% 带来的约十四倍压力，不能直接全量穿透数据库。为核心查询保留独立并发预算，非核心请求限流或返回明确降级结果；允许陈旧的内容可用有期限的本地快照，关键一致性读仍受预算保护。设置短缓存超时、熔断和有抖动的探测，恢复时分批预热并限制填充。通过故障注入观察数据库连接池、尾延迟、拒绝率及恢复波峰，验证业务允许的可用性与新鲜度。',
    JSON_ARRAY('Redis 出错就无限制回源', '恢复后立刻全量预热没有风险'),
    JSON_ARRAY('哪些数据不能使用陈旧快照？', '如何防止多个实例同时触发恢复探测风暴？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-concurrency-001',
    'volatile 计数器为何丢更新',
    '多个线程对 volatile int count 执行 count++，最终值小于请求数。volatile 提供了什么保证？怎样选择正确的计数方式？',
    'CONCURRENCY',
    'concurrency',
    'BEGINNER',
    JSON_ARRAY('Java', 'volatile', '原子性'),
    JSON_ARRAY('可见性与有序性', '复合操作原子性', '计数语义'),
    'count++ 包含读取、加一和写回，volatile 不将三步合为原子操作。需要精确递增返回值可用 AtomicInteger/AtomicLong；竞争很高且只需统计汇总时可评估 LongAdder，但其并发 sum 不保证同一瞬间的线性一致快照。复杂不变量使用锁保护整个操作。用多个线程同步起跑验证总数，并测量不同竞争度下吞吐和延迟。',
    JSON_ARRAY('volatile 使所有访问都原子化', 'LongAdder 可以直接生成严格递增唯一序号'),
    JSON_ARRAY('只需单线程写、多线程读时是否需要原子类？', '两个计数器必须一起更新时怎么办？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-concurrency-002',
    '线程池排队与拒绝',
    '接口把任务交给固定大小线程池，使用无界队列。流量持续大于处理能力时会出现什么？怎样为可丢弃的统计任务配置保护？',
    'CONCURRENCY',
    'concurrency',
    'BEGINNER',
    JSON_ARRAY('Java', '线程池', '背压'),
    JSON_ARRAY('排队延迟和内存', '有界资源', '拒绝策略语义'),
    '无界队列让请求积压，内存和等待时间持续增长，即使线程数稳定也可能失效。根据任务耗时和可接受延迟设置有界队列及工作线程上限，统计任务可以采用明确记录丢弃数量的拒绝策略或聚合采样。CallerRuns 会把压力推回提交线程，若提交者是关键请求线程需谨慎。记录活跃线程、队列长度、最老任务等待时间和拒绝率，用持续过载测试验证资源有界。',
    JSON_ARRAY('线程数固定就不会耗尽内存', '把队列调大就解决吞吐不足'),
    JSON_ARRAY('不可丢弃任务应采用怎样的持久化方案？', 'CPU 密集和 I/O 密集任务为何应隔离？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-concurrency-003',
    '超时后的异步任务取消',
    '接口同时调用两个下游，预算总计 300 毫秒。一个 Future 超时后，底层任务是否必然停止？如何控制资源和处理迟到结果？',
    'CONCURRENCY',
    'concurrency',
    'STANDARD',
    JSON_ARRAY('Java', 'Future', '超时'),
    JSON_ARRAY('请求总预算', '协作式取消', '迟到结果隔离'),
    '为请求建立绝对截止时间，每个下游仅使用剩余预算，并在 HTTP 客户端设置连接和读取等超时。Future 超时只结束等待，cancel(true) 是中断请求，任务可能忽略中断或阻塞在不可中断操作。任务需检查取消状态并在 finally 释放资源，提交结果时校验请求代次或版本，避免迟到结果覆盖新状态。限制并发并用不可取消的测试替身验证资源上限、部分失败与最终清理。',
    JSON_ARRAY('Future 超时表示远程操作已回滚', '取消成功就能忽略所有迟到响应'),
    JSON_ARRAY('一个下游成功另一个失败时返回什么语义？', '提交外部写操作超时后怎样确认结果？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-concurrency-004',
    '多资源加锁与死锁定位',
    '两个操作分别以 A→B、B→A 顺序锁定同一对对象，偶尔所有请求卡住。如何确认是死锁并修复？改用 tryLock 有什么代价？',
    'CONCURRENCY',
    'concurrency',
    'STANDARD',
    JSON_ARRAY('Java', '死锁', '锁顺序'),
    JSON_ARRAY('等待环证据', '全局加锁顺序', '超时重试与活锁'),
    '抓取多次线程转储，查找互相持有并等待对方锁的环，结合请求路径核对锁顺序。为资源定义稳定全局顺序，并让所有路径按同一顺序获取和逆序释放；持锁期间避免慢网络调用。tryLock 加超时能放弃等待，但必须释放已持有锁，并用退避和有限重试避免活锁及饥饿。用相反顺序的并发压测、异常注入和等待时间指标验证。',
    JSON_ARRAY('只增加线程数量即可解除死锁', '使用 tryLock 就不再需要失败处理'),
    JSON_ARRAY('动态创建资源无法自然排序时怎么办？', '数据库锁和 Java 锁混合时如何排查等待环？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-concurrency-005',
    '租约任务与迟到提交',
    '两台工作节点处理同一导出任务。A 获取租约后长时间暂停，B 接管完成任务，A 恢复后也想提交。如何既允许接管又阻止覆盖和重复副作用？',
    'CONCURRENCY',
    'concurrency',
    'ADVANCED',
    JSON_ARRAY('并发控制', 'CAS', '租约'),
    JSON_ARRAY('领取令牌与版本', '提交原子条件', '副作用幂等边界'),
    '领取任务时原子更新租约、单调版本和唯一令牌，提交时同时校验任务状态、预期版本、令牌和有效租期，更新影响行数为零就丢弃迟到结果。租约判断尽量使用同一权威时钟。导出文件先写入本次尝试的独立位置，成功提交后才发布引用；发送通知等副作用通过事务 outbox 和幂等消费处理。演练暂停接管、续租失败、提交响应丢失和重复消费，不把数据库 CAS 等同于外部副作用恰好一次。',
    JSON_ARRAY('只校验任务 ID 就能安全提交', '租约过期后旧线程自然停止'),
    JSON_ARRAY('输出文件清理如何避免删除新节点产物？', '任务真正完成但提交响应丢失时怎样恢复？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-performance-001',
    '平均延迟掩盖了什么',
    '接口平均耗时 80 毫秒，但用户持续反馈卡顿。你会补充哪些指标？怎样避免把服务端处理时间当作用户完整等待时间？',
    'PERFORMANCE',
    'performance',
    'BEGINNER',
    JSON_ARRAY('性能', '延迟分位数', '监控'),
    JSON_ARRAY('尾延迟', '分层测量', '样本与窗口'),
    '补充 p50/p95/p99、错误率、吞吐和超时比例，并按端点、状态和时间窗口观察，避免平均数掩盖少量长请求。通过浏览器和链路追踪区分排队、网络、服务处理及渲染时间，记录端到端预算。注意低样本分位数波动，以及只统计成功请求导致超时被排除的偏差。先复现相同用户路径，再定位最耗时阶段。',
    JSON_ARRAY('平均延迟低就说明所有用户体验好', '不同服务的 p99 可以直接相加得到端到端 p99'),
    JSON_ARRAY('超时请求应怎样计入延迟指标？', '低流量接口如何解释 p99 的变化？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-performance-002',
    '先确认 CPU 还是等待',
    '服务响应变慢，但总 CPU 使用率只有 20%。能否排除 CPU 瓶颈？怎样结合线程和容器指标判断下一步？',
    'PERFORMANCE',
    'performance',
    'BEGINNER',
    JSON_ARRAY('性能', 'CPU', '线程分析'),
    JSON_ARRAY('总体与单核差异', 'CPU 限额', '等待状态证据'),
    '总 CPU 低不能排除单线程热点或容器限流，应查看单核利用率、进程配额和 throttling。结合线程转储区分运行、锁等待和 I/O 等待，观察连接池、线程池队列及下游延迟。若单核饱和再做采样剖析；若大量线程等待同一连接池，追踪连接占用原因。保持采样时段与慢请求一致，先建立证据再调整线程或副本数量。',
    JSON_ARRAY('CPU 低时加线程必然有效', '只看宿主机 CPU 就知道容器可用容量'),
    JSON_ARRAY('短时尖峰怎样被分钟平均指标掩盖？', '如何区分连接泄漏与下游本身变慢？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-performance-003',
    '验证一次索引优化',
    '团队新增索引后，开发机查询从 100 毫秒变为 5 毫秒。怎样证明线上收益，并识别写入和存储代价？',
    'PERFORMANCE',
    'performance',
    'STANDARD',
    JSON_ARRAY('MySQL', '性能验证', '基线'),
    JSON_ARRAY('可比测试条件', '读写混合负载', '执行计划与资源成本'),
    '固定查询参数分布和数据规模，分别测量冷暖缓存及读写混合负载，保存执行计划、实际扫描行数和延迟分布。观察索引体积、插入更新吞吐和锁等待，避免只用单次热缓存查询作结论。在可回退的灰度范围监测新旧路径，确认收益超过写放大和维护成本。若索引选择随参数倾斜变化，需补充热点与长尾参数而非只验证一个用户。',
    JSON_ARRAY('单次查询变快就能证明整体吞吐提升', '索引只占磁盘而没有写入代价'),
    JSON_ARRAY('大部分查询只访问一个租户时如何设计数据集？', '优化器没有选择新索引时如何排查？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-performance-004',
    'Java 堆持续增长的诊断',
    '发布后堆占用呈锯齿上升，GC 更频繁，但尚无 OOM。如何区分正常缓存增长、分配过快和内存泄漏，并控制诊断对生产的影响？',
    'PERFORMANCE',
    'performance',
    'STANDARD',
    JSON_ARRAY('Java', 'GC', '内存诊断'),
    JSON_ARRAY('GC 后存活集', '分配速率与引用链', '诊断成本'),
    '观察同类 GC 后存活集是否持续增长，结合请求量、分配速率、暂停时间和缓存容量判断。先用低开销采样定位高分配路径，再在可控副本采集堆信息，通过 dominator 与 GC root 引用链找保留对象。缓存未设容量与真正无用对象被长期引用都可能持续增长，需结合业务生命周期识别。堆转储可能带来暂停和敏感数据，安排容量与访问保护；修复后以相同负载长跑验证存活集平台期。',
    JSON_ARRAY('看到锯齿就能断定内存泄漏', '扩大堆是所有泄漏的永久解决方案'),
    JSON_ARRAY('对象分配很快但存活集稳定时优先优化什么？', '堆外内存增长应补充哪些指标？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-performance-005',
    '压力测试中的虚假容量',
    '压测工具每个虚拟用户等待响应后再发送下一次请求，系统变慢时请求量自动下降。怎样避免高估容量，并为突发流量制定边界？',
    'PERFORMANCE',
    'performance',
    'ADVANCED',
    JSON_ARRAY('压测', '容量', '排队'),
    JSON_ARRAY('闭环负载与协调遗漏', '开放到达率', '容量和降级边界'),
    '闭环模型适合部分交互场景，但响应变慢会压低到达率，可能掩盖排队压力。增加受控开放到达率测试，记录计划发送时间到完成的延迟及工具自身积压，确认施压端容量充足。按生产请求分布做阶梯负载、突发和持续过载，定义满足尾延迟及错误率目标的容量，再留故障和波动余量。超出边界时验证限流、队列上限和恢复速度，不能只报告峰值 QPS。',
    JSON_ARRAY('压测工具显示低延迟就代表没有排队', '短时间无错误峰值就是可持续容量'),
    JSON_ARRAY('如何验证压测机本身不是瓶颈？', '少量慢请求占用连接时容量曲线会怎样变化？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-reliability-001',
    '重试应该在哪一层',
    '一次查询依次经过网关、业务服务和下游客户端，各层都允许重试两次。下游故障时会发生什么？如何设置可控的重试策略？',
    'RELIABILITY',
    'reliability',
    'BEGINNER',
    JSON_ARRAY('可靠性', '重试', '超时'),
    JSON_ARRAY('重试放大', '总截止时间', '可重试错误分类'),
    '若每层两次重试意味着三次尝试，最坏可能放大到 27 次下游调用，具体取决于调用路径。明确负责重试的一层，限制总次数与总时间预算，采用指数退避和抖动，只对合理的瞬时错误重试。非幂等写入先建立幂等机制，鉴权失败等确定性错误不盲目重试。用持续故障测试确认实际调用量、请求截止和恢复行为。',
    JSON_ARRAY('所有异常都应该重试', '每层增加少量重试不会放大流量'),
    JSON_ARRAY('服务返回 Retry-After 时客户端怎样处理？', '查询重试也可能造成哪些资源问题？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-reliability-002',
    '健康检查与优雅下线',
    '实例收到停止信号时仍有请求正在处理。存活检查、就绪检查和优雅停机分别承担什么职责？怎样验证发布过程中请求不被随意中断？',
    'RELIABILITY',
    'reliability',
    'BEGINNER',
    JSON_ARRAY('可靠性', '健康检查', '优雅停机'),
    JSON_ARRAY('存活与就绪区分', '流量摘除和在途请求', '终止宽限期'),
    '存活检查用于发现需要重启的失效进程，就绪检查用于判断是否接收新流量。停机先撤销就绪并等待负载均衡传播，再停止接收新请求并在有限宽限期内完成在途请求，最后释放连接和线程资源。宽限期需与请求最大预算和平台终止期限协调。滚动发布时用长短请求、连接复用和终止超时演练验证错误率，不能只观察进程正常退出。',
    JSON_ARRAY('下游短暂故障都应让存活检查失败', '撤销就绪会瞬间移除所有在途请求'),
    JSON_ARRAY('长连接或流式响应怎样排空？', '宽限期结束仍未完成的任务如何恢复？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-reliability-003',
    '消息重复消费与业务落库',
    '消息队列可能重复投递付款成功事件。消费者更新订单并发送通知，在哪一步确认消息？如何避免重复业务更新，并处理落库成功但确认失败？',
    'RELIABILITY',
    'reliability',
    'STANDARD',
    JSON_ARRAY('消息队列', '幂等', '事务'),
    JSON_ARRAY('至少一次投递', '本地事务去重', '副作用可靠交付'),
    '在数据库事务内按事件 ID 建唯一消费记录，并以订单状态条件更新业务；事务提交后才确认消息。提交成功而确认失败会导致重复投递，唯一记录帮助识别已处理结果。通知等外部副作用通过同事务 outbox 记录，再由独立发送者重试并使用下游幂等键。演练提交前崩溃、提交后确认丢失及通知超时，不承诺仅靠队列确认实现端到端恰好一次。',
    JSON_ARRAY('收到消息先确认能提高可靠性', '数据库去重自动覆盖短信等外部副作用'),
    JSON_ARRAY('不同事件 ID 表示同一业务意图时怎样去重？', '毒消息应如何隔离并保留诊断信息？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-reliability-004',
    '熔断与资源隔离的配合',
    '推荐服务响应从 50 毫秒变成 5 秒，拖慢主订单查询。如何使用超时、熔断和资源隔离保护核心功能？恢复时如何避免再次冲击？',
    'RELIABILITY',
    'reliability',
    'STANDARD',
    JSON_ARRAY('可靠性', '熔断', '隔离'),
    JSON_ARRAY('调用预算', '独立并发资源', '半开探测和恢复'),
    '为推荐调用设短于核心请求剩余预算的超时，并用独立线程池或并发信号量限制资源，超时取消还要确保底层连接最终释放。按最小样本和失败率配置熔断，降级为空推荐且保留核心订单结果。半开状态限制探测数量，恢复后逐步放量并加入抖动。通过持续慢响应和间歇失败注入，验证核心延迟、隔离池占用及恢复振荡。',
    JSON_ARRAY('只有熔断就不需要设置超时', '降级必须伪造推荐结果保持页面完整'),
    JSON_ARRAY('共享 HTTP 连接池会怎样削弱隔离？', '怎样避免低流量服务因单次失败长期熔断？'),
    'ORIGINAL'
);

INSERT INTO question_bank (id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type) VALUES (
    'qb-reliability-005',
    '跨区域故障恢复演练',
    '核心订单数据库跨区域异步复制，目标 RPO 为 1 分钟、RTO 为 15 分钟。主区域失联时怎样决策切换，并避免双主写入与错误的零丢失承诺？',
    'RELIABILITY',
    'reliability',
    'ADVANCED',
    JSON_ARRAY('可靠性', '灾难恢复', 'RPO'),
    JSON_ARRAY('恢复点与恢复时间', '写入围栏和一致性', '演练与对账'),
    '先测量可确认的复制进度与积压，区分目标 RPO 和实际可恢复点；异步复制无法在任意分区下保证零丢失。切换前通过可靠仲裁或围栏阻止旧主继续写入，无法隔离时需按业务风险决定暂停写入。按运行手册提升副本、切流并校验关键账目，保留可能未复制事件用于恢复后对账。通过定期演练计时验证检测、决策、提升和验证的总 RTO，记录实际丢失窗口与回切条件。',
    JSON_ARRAY('配置了异步副本就保证零数据丢失', 'DNS 切换本身就能阻止旧主写入'),
    JSON_ARRAY('旧主恢复后如何合并冲突记录？', '如果业务不能接受一分钟数据损失，应怎样调整架构与成本？'),
    'ORIGINAL'
);


