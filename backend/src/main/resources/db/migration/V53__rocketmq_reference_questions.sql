UPDATE question_bank SET published=FALSE
WHERE source_type='EXTERNAL_REFERENCE' AND version=1
  AND id IN ('qb-ref-reliability-001','qb-ref-reliability-002','qb-ref-reliability-004','qb-ref-reliability-005');

INSERT INTO question_bank
(id,title,question,topic,competency_id,difficulty,tags_json,key_points_json,reference_answer,common_mistakes_json,follow_ups_json,source_type,source_title,source_url)
VALUES
('qb-ref-mq-001','消费确认丢失后的重复投递','RocketMQ 消费者提交业务事务后退出，来不及确认消息，业务如何避免重复执行？','RELIABILITY','reliability','BEGINNER',
 JSON_ARRAY('RocketMQ','消费确认','幂等'),JSON_ARRAY('消息可再次投递','用稳定的业务标识检查结果','业务提交后再确认'),
 '消费者可能在数据库提交后、确认消息前退出。消息重新可见时，其他消费者可以再次领取。应当在业务事务内用任务状态或唯一标识识别已经完成的操作，确认原有结果后再确认消息。',
 JSON_ARRAY('把发送回执当成消费完成','先确认再提交结果'),JSON_ARRAY('业务标识取自哪里？','如何验证提交后退出的场景？'),
 'EXTERNAL_REFERENCE','Apache RocketMQ · 消费重试','https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/'),
('qb-ref-mq-002','长任务的不可见时间','SimpleConsumer 正在执行长任务，消息的不可见时间即将结束时应怎样处理？','RELIABILITY','reliability','STANDARD',
 JSON_ARRAY('RocketMQ','SimpleConsumer','不可见时间'),JSON_ARRAY('到期后消息可再次领取','在到期前延长不可见时间','使用数据库租约保护业务提交'),
 'SimpleConsumer 领取后，消息在指定时间内对其他消费者不可见。处理可能超过该时限时，应提前续期。续期失败仍可能产生重复投递，因此业务提交要校验执行资格，完成后再确认消息。',
 JSON_ARRAY('认为领取等于永久独占','任务刚开始就确认消息'),JSON_ARRAY('进程退出后消息如何恢复？','数据库租约与消息不可见时间分别控制什么？'),
 'EXTERNAL_REFERENCE','Apache RocketMQ · SimpleConsumer','https://rocketmq.apache.org/docs/featureBehavior/06consumertype/'),
('qb-ref-mq-003','延迟消息的交付时间','RocketMQ 5 如何发送一条在指定时间后交付的任务通知？','RELIABILITY','reliability','BEGINNER',
 JSON_ARRAY('RocketMQ','延迟消息','调度'),JSON_ARRAY('创建 DELAY 主题','设置目标交付时间','到期后由消费者领取'),
 '生产者把通知发到 DELAY 主题，并设置目标交付时间。Broker 保存通知，到期后消费者可以领取。发送回执确认服务端接收了通知，任务完成时间取决于消费者的执行和业务提交。',
 JSON_ARRAY('把发送成功当成任务完成','把目标时间理解成业务完成时刻'),JSON_ARRAY('Broker 重启后怎样验证通知能够交付？','消费者满载会怎样影响执行时间？'),
 'EXTERNAL_REFERENCE','Apache RocketMQ · 延迟消息','https://rocketmq.apache.org/docs/featureBehavior/02delaymessage/'),
('qb-ref-mq-004','消费重试耗尽后的处理','RocketMQ 的消费组达到重试上限后，怎样定位失败任务并恢复处理？','RELIABILITY','reliability','BEGINNER',
 JSON_ARRAY('RocketMQ','死信队列','消费组'),JSON_ARRAY('按消费组查询死信','用业务标识核对状态','修复原因后定向补发'),
 '达到消费组的重试上限后，Broker 将消息放入该组的死信队列。可用业务标识关联消息、日志和数据库状态，检查失败原因，修复后仅补发仍需执行的任务。消费者仍应能够处理重复通知。',
 JSON_ARRAY('无限重试同一条坏消息','无差别重放整组已处理任务'),JSON_ARRAY('怎样排除已经成功的任务？','应该监控哪些积压指标？'),
 'EXTERNAL_REFERENCE','Apache RocketMQ · 消费重试','https://rocketmq.apache.org/docs/featureBehavior/10consumerretrypolicy/');

INSERT INTO question_bank_versions (question_id,version,content_json,change_note)
SELECT id,version,JSON_OBJECT(
 'id',id,'version',version,'title',title,'question',question,'topic',topic,'competencyId',competency_id,
 'difficulty',difficulty,'tags',tags_json,'keyPoints',key_points_json,'referenceAnswer',reference_answer,
 'commonMistakes',common_mistakes_json,'followUps',follow_ups_json,'sourceType',source_type,
 'sourceTitle',source_title,'sourceUrl',source_url),'新增 RocketMQ 参考题'
FROM question_bank WHERE id IN ('qb-ref-mq-001','qb-ref-mq-002','qb-ref-mq-003','qb-ref-mq-004');
