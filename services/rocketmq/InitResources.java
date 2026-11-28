import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;

/** 在初始化容器内创建缺失的 Topic 和消费组，并校验已有资源是否满足派发要求。 */
public class InitResources {
    public static void main(String[] args) throws Exception {
        String prefix = System.getenv("ROCKETMQ_RESOURCE_PREFIX");
        if (prefix == null || !prefix.matches("[A-Za-z0-9_-]{1,48}")) {
            throw new IllegalArgumentException("Invalid ROCKETMQ_RESOURCE_PREFIX");
        }
        String broker = "mqbroker:10911";
        DefaultMQAdminExt admin = new DefaultMQAdminExt();
        admin.setNamesrvAddr("mqnamesrv:9876");
        admin.start();
        try {
            Map<String, TopicConfig> topics = admin.getAllTopicConfig(broker, 5000).getTopicConfigTable();
            boolean isolatedTest = "true".equals(System.getenv("ROCKETMQ_INIT_TEST_RESOURCES"));
            if (isolatedTest && !prefix.matches("it-[0-9a-f]{16}")) {
                throw new IllegalArgumentException("Test resources require an isolated test prefix");
            }
            List<String> topicNames = new ArrayList<>(Arrays.asList(
                    "run-ready", "run-delay", "knowledge-import", "knowledge-index", "dispatch-invalid"));
            if (isolatedTest) topicNames.addAll(Arrays.asList("test-invalid-source", "test-exhaust-source"));
            for (String suffix : topicNames) {
                String name = prefix + "-" + suffix;
                String type = suffix.equals("run-delay") ? "DELAY" : "NORMAL";
                // 重启时保留已有 Topic 的配置。若消息类型不匹配，初始化直接报错。
                if (!topics.containsKey(name)) {
                    TopicConfig config = new TopicConfig(name);
                    config.setAttributes(Collections.singletonMap("+message.type", type));
                    config.setReadQueueNums(suffix.startsWith("test-") ? 1 : 4);
                    config.setWriteQueueNums(config.getReadQueueNums());
                    admin.createAndUpdateTopicConfig(broker, config);
                }
                TopicConfig actual = admin.getAllTopicConfig(broker, 5000).getTopicConfigTable().get(name);
                if (actual == null || !type.equals(actual.getAttributes().getOrDefault("message.type", "NORMAL"))) {
                    throw new IllegalStateException("Unexpected topic configuration for " + name);
                }
            }
            Map<String, SubscriptionGroupConfig> groups = admin.getAllSubscriptionGroup(broker, 5000).getSubscriptionGroupTable();
            List<String> groupNames = new ArrayList<>(Arrays.asList("run-workers", "import-workers", "index-workers"));
            if (isolatedTest) groupNames.addAll(Arrays.asList("test-validators", "test-diagnostics", "test-auditors", "test-dlq"));
            for (String suffix : groupNames) {
                String name = prefix + "-" + suffix;
                if (!groups.containsKey(name)) {
                    SubscriptionGroupConfig group = new SubscriptionGroupConfig();
                    group.setGroupName(name);
                    // 专用消费组只重试一次，故障验收可直接观察消息进入死信队列。
                    if (suffix.equals("test-dlq")) group.setRetryMaxTimes(1);
                    admin.createAndUpdateSubscriptionGroupConfig(broker, group);
                }
                SubscriptionGroupConfig group = admin.examineSubscriptionGroupConfig(broker, name);
                if (group == null || !group.isConsumeEnable() || (suffix.equals("test-dlq") && group.getRetryMaxTimes() != 1)) {
                    throw new IllegalStateException("Consumer group is unavailable: " + name);
                }
            }
            System.out.println("Verified dispatch topics and consumer groups for " + prefix);
        } finally {
            admin.shutdown();
        }
    }
}
