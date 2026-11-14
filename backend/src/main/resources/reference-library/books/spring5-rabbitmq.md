# 8.2 使用RabbitMQ和AMQP

## PDF第331页

8.2    使用RabbitMQ和AMQP
  RabbitMQ可以说是AMQP最杰出的实现,它提供了比JMS更高级的消息路由
策略。JMS消息使用目的地名称来寻址,接收者要从这里检索消息,而AMQP消
息使用Exchange和routing key来寻址,这样消息就与接收者要监听的队列解耦
了。Exchange和队列的关系如图8.1所示。




 图8.1 发送到RabbitMQ Exchange的消息会基于routing key和binding被路由到一个或多个队列上
   当消息抵达RabbitMQ代理的时候,它会进入为其设置的Exchange上。
Exchange负责将它路由到一个或多个队列中,这个过程会根据Exchange的类
型、Exchange和队列之间的binding以及消息的routing key进行路由。
   这方面有多个不同类型的Exchange,包括以下内容。
   Default:这是代理创建的特殊Exchange。它会将消息路由至名字与消息
   routing key相同的队列。所有的队列都会自动绑定至Default Exchange。
   Direct:如果消息的routing key与队列的binding key相同,那么消息将会
   路由到该队列上。
   Topic:如果消息的routing key与队列binding key(可能会包含通配符)
   匹配,那么消息将会路由到一个或多个这样的队列上。
   Fanout:不管routing key和binding key是什么,消息都将会路由到所有
   绑定队列上。

## PDF第332页

Headers:与Topic Exchange类似,只不过要基于消息的头信息进行路
  由,而不是routing key。
  Dead letter:捕获所有无法投递(也就是它们无法匹配所有已定义的
  Exchange和队列的binding关系)的消息。

  最简单的Exchange形式是Default和Fanout,因为它们大致对应了JMS中
的队列和主题,但是其他的Exchange允许我们定义更加灵活的路由模式。
  这里最重要的是要明白消息会通过routing key发送至Exchange,而消息要
在队列中被消费。它们如何从Exchange路由至队列取决于binding的定义以及哪
种方式最适合我们的使用场景。
  至于使用哪种Exchange类型以及如何定义从Exchange到队列的binding,
这本身与如何在Spring应用中发送和接收消息关系不大。因此,我们更加关心如
何编写使用Rabbit发送和接收消息的代码。

      注意:关于如何绑定队列到Exchange的更详细讨论,请参考Alvaro
  Videla和Jason J.W. Williams编写的RabbitMQ in Action
  (Manning,2012)。

8.2.1       添加RabbitMQ到Spring中
    在使用Spring发送和接收RabbitMQ消息之前,我们需要将Spring Boot的
AMQP starter依赖添加到构建文件中,替换上文中Artemis或ActiveMQ
starter的位置:

<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
