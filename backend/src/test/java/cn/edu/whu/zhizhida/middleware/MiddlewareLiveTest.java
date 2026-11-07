package cn.edu.whu.zhizhida.middleware;

import cn.edu.whu.zhizhida.quota.QuotaExceededException;
import cn.edu.whu.zhizhida.quota.RedisSubmissionRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 显式启用才连接真实中间件；只写本次 UUID 命名空间并清理，不 FLUSHDB、不碰业务队列。 */
@EnabledIfEnvironmentVariable(named="RUN_MIDDLEWARE_TESTS",matches="true")
class MiddlewareLiveTest {
    static String env(String key,String fallback) { return System.getenv().getOrDefault(key,fallback); }
    LettuceConnectionFactory redisFactory() {
        var config=new RedisStandaloneConfiguration(env("REDIS_RATE_HOST",env("REDIS_HOST","127.0.0.1")),
                Integer.parseInt(env("REDIS_RATE_PORT",env("REDIS_PORT","6379"))));
        String password=env("REDIS_RATE_PASSWORD",env("REDIS_PASSWORD",""));
        if(!password.isEmpty()) config.setPassword(password);
        var factory=new LettuceConnectionFactory(config,LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        factory.afterPropertiesSet(); factory.start(); return factory;
    }
    @Test void twoIndependentClientsShareOneAtomicWindowAndDoNotExtendTtl() throws Exception {
        var first=redisFactory(); var second=redisFactory();
        var one=new StringRedisTemplate(first); var two=new StringRedisTemplate(second);
        String namespace="zhizhida:test:"+UUID.randomUUID();
        var keys=List.of(namespace+":rate:ai-submit:42",namespace+":rate:ai-submit:43");
        try(var pool=Executors.newFixedThreadPool(8)) {
            var a=new RedisSubmissionRateLimiter(one,new SimpleMeterRegistry(),namespace,10,60000);
            var b=new RedisSubmissionRateLimiter(two,new SimpleMeterRegistry(),namespace,10,60000);
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<40;i++) {
                var client=i%2==0?a:b;
                futures.add(pool.submit(()->{ try {client.require(42);return true;} catch(QuotaExceededException ex){return false;} }));
            }
            int accepted=0;
            for(var future:futures) if(future.get(10,TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(10);
            assertThat(one.opsForValue().get(keys.getFirst())).isEqualTo("10");
            long ttl=one.getExpire(keys.getFirst(),TimeUnit.MILLISECONDS);
            assertThat(ttl).isBetween(1L,60000L);
            assertThatThrownBy(()->a.require(42)).isInstanceOf(QuotaExceededException.class);
            assertThat(two.getExpire(keys.getFirst(),TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttl);
            b.require(43);
            one.opsForValue().set(keys.get(1),"9");
            a.require(43);
            assertThat(two.opsForValue().get(keys.get(1))).isEqualTo("10");
            assertThat(two.getExpire(keys.get(1),TimeUnit.MILLISECONDS)).isBetween(1L,60000L);
            one.persist(keys.get(1));
            assertThatThrownBy(()->b.require(43)).isInstanceOf(QuotaExceededException.class);
            assertThat(one.getExpire(keys.get(1),TimeUnit.MILLISECONDS)).isBetween(1L,60000L);
        } finally {
            try { one.delete(keys); } finally { first.destroy(); second.destroy(); }
        }
    }
    @Test void brokerConfirmsRoutedAndReturnedMessagesAndRedeliversUnackedWork() throws Exception {
        var connection=new CachingConnectionFactory(env("RABBITMQ_HOST","127.0.0.1"),Integer.parseInt(env("RABBITMQ_PORT","5672")));
        connection.setUsername(env("RABBITMQ_USERNAME","zhizhida"));
        connection.setPassword(env("RABBITMQ_PASSWORD","zhizhida-local"));
        connection.setVirtualHost(env("RABBITMQ_VHOST","/zhizhida-lab"));
        connection.setConnectionTimeout(2000);
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        var admin=new RabbitAdmin(connection);
        String name="zhizhida.test."+UUID.randomUUID();
        boolean exchangeCreated=false,queueCreated=false;
        try {
            var exchange=new DirectExchange(name,false,true);
            var queue=new Queue(name,false,true,true);
            admin.declareExchange(exchange); exchangeCreated=true;
            admin.declareQueue(queue); queueCreated=true;
            admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("ready"));
            var template=new RabbitTemplate(connection); template.setMandatory(true);
            var properties=new MessageProperties(); properties.setMessageId(UUID.randomUUID().toString());
            var message=new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),properties);
            var routed=new CorrelationData(UUID.randomUUID().toString());
            template.send(name,"ready",message,routed);
            assertThat(routed.getFuture().get(5,TimeUnit.SECONDS).ack()).isTrue();
            assertThat(routed.getReturned()).isNull();
            template.execute(channel->{
                var received=channel.basicGet(name,false);
                assertThat(received).isNotNull();
                channel.basicNack(received.getEnvelope().getDeliveryTag(),false,true);
                var again=channel.basicGet(name,false);
                assertThat(again).isNotNull();
                assertThat(again.getEnvelope().isRedeliver()).isTrue();
                channel.basicAck(again.getEnvelope().getDeliveryTag(),false);
                return null;
            });
            var returned=new CorrelationData(UUID.randomUUID().toString());
            template.send(name,"missing",message,returned);
            assertThat(returned.getFuture().get(5,TimeUnit.SECONDS).ack()).isTrue();
            assertThat(returned.getReturned()).isNotNull();
        } finally {
            try { if(queueCreated) admin.deleteQueue(name); if(exchangeCreated) admin.deleteExchange(name); }
            finally { connection.destroy(); }
        }
    }
}
