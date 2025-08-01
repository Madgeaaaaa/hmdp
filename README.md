# dianping
黑马点评
简化版大众点评
项目简介:本项目是一个类似于大众点评等打卡类APP的点评类项目。实现了登陆、探店点评、优惠券秒杀、每日签到、好友关注、粉丝关注博主后，主动推送博主的相关博客等多个模块。项目可以分为用户模块、缓存模块、秒杀模块、订单模块以及限流模块。

## 克隆完整项目
git clone https://github.com/Madgeaaaaa/hmdp.git
## 前端环境部署
nginx-1.18.0   启动nginx.exe    
## 后端环境部署
在application.yaml文件中，Mysql、Redis、RabbitMQ相关的配置需要自行更改

代码使用的Redis为3.2，推荐使用6.2以上，只影响商铺GEO部分

RabbitMQ版本为3.8

技术栈：SpringBoot + MySQL + Redis + Lua + MyBatisPlus + RabbitMQ + Caffeine + Nginx等



## 后端部分功能做了优化
### 优化点1:缓存模块
使用Caffeine本地缓存和Redis缓存搭建二级缓存架构，提高热点数据访问速度，降低数据库压力。

### 优化点2:秒杀模块
基于lua保证判断库存是否充足、判断用户是否下单、扣库存、下单并保存用户事件的原子性，同时采用RabbitMQ异步处理高并发情况下的请求。

### 优化点3:限流模块
使用Redis + AOP + 注解实现限流，支持全局、IP、用户多维度，从而防止系统过载、刷券、爬虫。

### 优化点4:用户模块
实现了无状态 JWT 认证登录的方式，但是尚未实现用户登录后刷新令牌有效期

### TODO优化点5:订单模块
使用Spring Task定时任务实现未支付订单的到期自动关闭，使用乐观锁解决支付和关单的并发问题。


