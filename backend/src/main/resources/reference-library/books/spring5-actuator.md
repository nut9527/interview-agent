# 16.1 Actuator概览

## PDF第618页

16.1 Actuator             概览
    在机器领域中,执行机构(Actuator)指的是负责控制和移动装置的组
件。在Spring Boot应用中,Spring Boot Actuator扮演了相同的角色,它能
够让我们看到一个运行中的应用的内部状况,而且能够在一定程度上控制应用
的行为。
    通过Actuator暴露的端点,我们可以获取一个正在运行中的应用的内部状
态。
    在应用环境中,都有哪些可用的配置属性?
    在应用中,各个源码包的日志级别是什么?
    应用消耗了多少内存?
    给定的HTTP端点被请求了多少次?
    应用本身以及与它协作的外部服务的健康状况如何?
    为了在Spring Boot应用中启用Actuator,我们需要在构建文件中添加对
Actuator starter的依赖。在Spring Boot应用的Maven pom.xml文件中,
添加如下的<dependency>条目就能完成该任务:
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>


  将Actuator starter添加到项目的构建文件中之后,应用就会具备一些开
箱即用的Actuator端点,其中一部分如表16.1所示。
       表16.1 探查运行中Spring Boot应用的状态并对其进行操作的Actuator端点

## PDF第619页

方法           路径                   描述           默认是
HTTP
                                                      否启用

GET         /auditevents     生成所有已触发的审计事件的报告         否

GET         /beans           描述Spring应用上下文中所有的bean   否

                             生成一个自动配置条件通过或失败的报告,这些 否
GET         /conditions
                             条件会指导应用上下文中bean的创建

GET         /configprops      描述所有的配置属性以及当前的值         否

GET、POST、                    生成Spring应用可用的所有属性源以及可用属性 否
DELETE
            /env
                             的报告

GET         /env/{toMatch}   描述某个环境属性的值              否

                             返回聚合的应用健康状态,可能的话,还会包含 是
GET         /health
                             外部依赖应用的健康状态

GET         /heapdump        下载堆dump文件               否

GET         /httptrace       生成最近100个请求的跟踪结果         否

GET         /info            返回开发人员定义的关于该应用的信息       是

                             生成应用中源码包的列表,其中会包含配置的以 否
GET         /loggers
                             及生效的日志级别

## PDF第620页

方法         路径                      描述           默认是
    HTTP
                                                           否启用

                                  返回指定logger配置的和生效的日志级别,生效 否
   GET、POST     /loggers/{name}
                                  的日志级别可以通过POST请求修改

   GET          /mappings         生成所有HTTP映射及其对应处理器方法的报告 否

   GET          /metrics          返回所有指标分类的列表             否

   GET          /metrics/{name}   返回给定指标的多维度值集            否

   GET          /scheduledtasks   列出所有的调度任务               否

   GET          /threaddump       返回所有应用线程的报告             否

   除了基于HTTP的端点之外,表16.1中除“/heapdump”的其他端点都以
JMX MBean的形式对外暴露了出来。我们将会在第19章学习JMX侧的
Actuator。


16.1.1          配置Actuator的基础路径
   默认情况下,表16.1中所有端点的路径都会带有“/actuator”。这意味着,
如果我们想要通过Actuator获取应用的健康信息,那么
向“/actuator/health”发送GET请求就能返回所需的信息。
   Actuator的前缀可以通过设置management.endpoint.web.base-path
属性来修改。例如,想要将前缀设置为“/management”,那么我们可以通过

## PDF第621页

如下的方式来设置management. endpoint.web.base-path属性:
management:
  endpoints:
    web:
      base-path: /management


   按照上述属性,要获取应用的健康信息,我们需要
向“/management/health”发送GET请求。
16.1.2        启用和禁用Actuator端点
    你可能已经发现,默认情况下,只有“/health”和“/info”端点是启用的。
大多数Actuator端点会携带敏感信息,所以应该保护起来。我们可以使用
Spring Security来锁定Actuator,但是因为Actuator本身没有安全保护,所
以大多数端点默认都是禁用的,需要我们来选择对外暴露哪些端点。
    有两个配置属性能够控制对外暴露哪些端点,它们分别是
management.endpoints.web.exposure.include和
management.endpoints.web.exposure.exclude。通过
management.endpoints.web. exposure.include属性,我们可以指定哪些
端点想要暴露出来。例如,想要暴
露“/health”“/info”“/beans”和“/conditions”的话,我们可以通过如下的配置
来声明:
management:
  endpoints:
    web:
      exposure:
         include: health,info,beans,conditions


                             属性也可以接受星号
   management.endpoints.web.exposure.include
(*)作为通配符,表明所有的Actuator端点都会对外暴露:

## PDF第622页

management:
  endpoints:
    web:
      exposure:
         include: '*'


   如果除了个别端点之外,我们想暴露其他的所有端点,那么一般来讲更简
单的方式是通过通配符将它们全部包含进来,然后明确排除一部分。例如,我
们想要暴露除了“/threaddump”和“/heapdump”之外的端点,那么可以按照
如下的形式同时设置management.endpoints.web.exposure.include和
management.endpoints.web.exposure.exclude属性:

management:
  endpoints:
    web:
      exposure:
         include: '*'
         exclude: threaddump,heapdump


    如果你决定对外公开比“/health”和“/info”更多的信息,那么最好配置
Spring Security来限制对其他端点的访问。我们将会在16.4节中了解如何保
护Actuator端点。现在,我们看一下如何消费Actuator对外暴露的HTTP端
点。
