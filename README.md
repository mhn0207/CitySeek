<div align="center">

# CitySeek

基于 Spring Boot、MySQL 与 Redis 构建的本地生活点评平台，聚焦高并发秒杀、可靠消息投递与缓存一致性。

[![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.4-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-8.4-4479A1?logo=mysql&logoColor=white)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7.4-DC382D?logo=redis&logoColor=white)](https://redis.io/)
[![Vue](https://img.shields.io/badge/Vue-2-4FC08D?logo=vuedotjs&logoColor=white)](https://v2.vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[项目亮点](#项目亮点) · [系统架构](#系统架构) · [快速启动](#快速启动) · [测试与压测](#测试与压测) · [核心接口](#核心接口)

</div>

<p align="center">
  <img src="docs/images/cityseek-home.png" width="31%" alt="CitySeek 首页">
  <img src="docs/images/cityseek-shops.png" width="31%" alt="CitySeek 商铺列表">
  <img src="docs/images/cityseek-shop-detail.png" width="31%" alt="CitySeek 商铺详情">
</p>

<p align="center"><sub>首页推荐 · 附近商铺 · 商铺详情与优惠券</sub></p>

## 项目简介

CitySeek 是一个前后端完整的本地生活项目，覆盖商铺检索、达人探店、关注 Feed、用户签到、图片上传和优惠券秒杀等业务。项目不仅实现业务功能，还围绕并发一致性、故障恢复、权限边界、可观测性和自动化测试进行了工程化改造。

### 项目来源与改造

本项目基于 Redis 实战课程项目进行扩展，重点改造了秒杀故障恢复、Feed 事务 Outbox、权限与图片所有权、GEO 同步、测试及容器交付。

它适合作为以下方向的学习或作品集项目：

- Redis 数据结构与典型业务场景实践；
- 高并发秒杀、异步下单和最终一致性设计；
- MySQL Outbox、租约与 claim token 条件更新；
- Spring Boot 3 项目的测试、监控与容器化交付。

## 项目亮点

| 方向 | 实现 |
| --- | --- |
| 高并发秒杀 | Lua 原子预检与扣库存、Redis Stream 异步削峰、Redisson 用户锁、MySQL 条件扣库存与唯一索引 |
| 故障恢复 | `XAUTOCLAIM` 自动接管 Pending、有限重试、幂等补偿、有界 DLQ、`PENDING/SUCCESS/FAILED` 状态查询 |
| Feed 可靠投递 | Blog 与 Outbox 同事务写入，Worker 使用租约、claim token 条件更新、分页与 Redis Pipeline 幂等投递 |
| 缓存与检索 | 缓存空值防穿透，互斥锁/逻辑过期防击穿，Redis GEO 5 公里范围检索及全量对账 |
| 安全与权限 | 默认鉴权、显式公开接口、简单 RBAC、验证码频控与原子消费、上传文件所有权校验 |
| 工程质量 | Docker Compose 一键部署、Testcontainers 真实依赖测试、Prometheus 指标、健康检查与 HTTP 压测 |

## 系统架构

```mermaid
flowchart LR
    U["浏览器"] --> N["Nginx :8080"]
    N -->|"/api/*"| A["Spring Boot :8081"]
    N -->|"/blogs/*"| A
    A --> M[("MySQL 8")]
    A --> R[("Redis 7")]
    A --> F["本地图片存储"]

    subgraph Async["异步可靠链路"]
        S["Redis Stream"] --> C["订单消费者"]
        O[("MySQL Outbox")] --> W["Feed Worker"]
    end

    A --> S
    C --> M
    C --> R
    A --> O
    W --> R
```

### 秒杀下单链路

```mermaid
sequenceDiagram
    participant Client as 客户端
    participant API as Spring Boot
    participant Redis as Redis + Lua
    participant Stream as Redis Stream
    participant Worker as 订单消费者
    participant MySQL as MySQL

    Client->>API: 提交秒杀请求
    API->>Redis: 原子校验时间、库存、一人一单
    Redis->>Stream: 写入订单消息与 PENDING 状态
    Redis-->>API: 返回受理结果
    API-->>Client: 返回字符串订单 ID
    Worker->>Stream: 消费或接管 Pending 消息
    Worker->>MySQL: 事务扣库存并创建订单
    Worker->>Redis: 写 SUCCESS 并 ACK
    Client->>API: 轮询订单状态
```

秒杀请求成功只代表系统已经受理，最终结果由状态查询接口返回。订单 ID 在 JSON 中按字符串输出，避免 JavaScript 对 64 位整数的精度丢失。

### 一致性与恢复策略

| 风险 | 处理方式 |
| --- | --- |
| Redis 并发超卖 | Lua 将时间校验、扣库存、用户标记和消息投递放在一次原子执行中 |
| 数据库超卖 | `stock > 0` 条件更新 |
| 同一用户重复下单 | Redis 用户集合 + Redisson 用户锁 + MySQL 唯一索引三层保障 |
| 消费者宕机 | 周期性 `XAUTOCLAIM` 接管超时 Pending 消息 |
| 消息持续失败 | 最多重试 3 次，Lua 幂等补偿 Redis 库存与购买标记，并写入 DLQ |
| 补偿时订单已落库 | 以数据库记录为准修正为 `SUCCESS`，不错误回补库存 |
| Blog 与 Feed 双写不一致 | MySQL Outbox 与 Blog 同事务提交，后台按租约异步投递 |
| Worker 重复投递 | Feed 以 blogId 作为 ZSet member，重复 `ZADD` 保持幂等 |

## 功能矩阵

- **用户系统**：手机验证码登录、Token 滑动续期、退出登录、资料编辑、Bitmap 签到统计；
- **商铺服务**：分类/名称查询、缓存详情、GEO 附近检索、管理员维护与 GEO 对账；
- **探店社区**：发布笔记、点赞排行、评论、关注/取关、共同关注、Feed 滚动分页；
- **优惠券**：普通券、秒杀券、异步下单、订单状态恢复查询；
- **图片服务**：类型与内容校验、UUID 分目录、所有权/绑定状态、路径穿越防护；
- **运维能力**：健康检查、Prometheus 指标、Outbox 失败查询与人工重放。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.4.4、Spring MVC、Jakarta Validation |
| 数据访问 | MyBatis-Plus 3.5.7、MySQL 8 |
| Redis | Spring Data Redis、Redisson 3.40.2、Lua、String/Hash/Set/ZSet/Bitmap/GEO/Stream |
| 可靠性 | Redis Stream、MySQL Outbox、租约、claim token、DLQ |
| 可观测性 | Spring Boot Actuator、Micrometer、Prometheus |
| 前端 | Vue 2、Element UI、Axios、Nginx |
| 测试 | JUnit 5、Mockito、Testcontainers、真实 HTTP 并发压测 |
| 部署 | Docker、Docker Compose、Nginx |

## 快速启动

### Docker Compose（推荐）

环境要求：Docker Engine 24+、Docker Compose v2。

```bash
git clone https://github.com/mhn0207/CitySeek.git
cd CitySeek
cp .env.example .env
docker compose up -d --build
```

Windows PowerShell 复制环境变量文件：

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

启动后访问：

| 服务 | 地址 |
| --- | --- |
| Web 前端 | <http://localhost:8080> |
| 后端 API | <http://127.0.0.1:8081> |
| 健康检查 | <http://127.0.0.1:8081/actuator/health> |

```bash
# 查看容器状态
docker compose ps

# 查看应用日志
docker compose logs -f app

# 停止服务但保留数据卷
docker compose down
```

> 首次运行前请修改 `.env` 中的数据库和 Redis 密码。执行 `docker compose down -v` 会永久删除数据库、Redis 和上传图片的数据卷，请谨慎使用。

### 本地开发

<details>
<summary>使用本机 JDK、MySQL、Redis 与 Nginx 启动</summary>

1. 准备 JDK 17、Maven 3.9+、MySQL 8、Redis 6.2+ 和 Nginx；
2. 创建 `cityseek` 数据库并导入 [`cityseek.sql`](src/main/resources/db/cityseek.sql)；
3. 配置连接信息并启动后端；

```powershell
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/cityseek?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "your-password"
$env:REDIS_HOST = "127.0.0.1"
$env:REDIS_PORT = "6379"
mvn spring-boot:run
```

4. 将 [`web/nginx.conf.example`](web/nginx.conf.example) 中的 `__CITYSEEK_WEB_ROOT__` 替换为 `web/` 的绝对路径；
5. 启动 Nginx 并访问 <http://localhost:8080>。

完整前端说明见 [`web/README.md`](web/README.md)。已有数据库应按顺序执行 [`db/migration`](src/main/resources/db/migration/) 中的增量脚本，不要重新导入完整初始化 SQL。

</details>

### 获取本地验证码

项目未接入真实短信平台。开发环境可将 `.env` 中的日志级别改为：

```dotenv
APP_LOG_LEVEL=debug
```

请求验证码后，通过 `docker compose logs app` 查看验证码。生产环境必须接入真实短信服务，并禁止在日志中输出验证码。

## 配置说明

所有敏感配置都可以通过环境变量覆盖，模板见 [`.env.example`](.env.example)。常用配置如下：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | 后端端口 |
| `DB_URL` | 本机 `3306/cityseek` | JDBC 地址 |
| `DB_USERNAME` / `DB_PASSWORD` | `root` / 空 | MySQL 凭据 |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` | Redis 地址 |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `IMAGE_UPLOAD_DIR` | `./data/images` | 图片存储目录 |
| `REDIS_STREAM_CONSUMER` | `cityseek-consumer` | Stream 消费者名称前缀，每个应用实例须使用唯一前缀 |
| `REDIS_STREAM_CONSUMER_CONCURRENCY` | `4` | 每个应用实例的订单消费者数量 |
| `REDIS_STREAM_READ_BATCH_SIZE` | `16` | 每个消费者单次读取的消息数上限 |
| `REDIS_PENDING_MIN_IDLE` | `30s` | 消息可被接管前的最短空闲时间 |
| `REDIS_PENDING_RECOVERY_INTERVAL` | `5s` | Pending 消息扫描间隔 |
| `REDIS_DLQ_MAX_LENGTH` | `10000` | 订单 DLQ 近似最大长度 |
| `FEED_OUTBOX_ENABLED` | `true` | 是否启用 Feed Outbox Worker |
| `FEED_OUTBOX_LEASE_DURATION` | `30s` | Outbox 处理租约 |
| `APP_LOG_LEVEL` | `info` | 项目日志级别 |

更多参数见 [`application.yaml`](src/main/resources/application.yaml)。

## 核心接口

接口统一返回：

```json
{
  "success": true,
  "errorMsg": null,
  "data": {},
  "total": null
}
```

登录成功后，受保护接口需要携带 `Authorization: Bearer <token>`。为兼容当前静态前端，也支持直接传 Token。

<details>
<summary>展开主要 API</summary>

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/user/code?phone=...` | 公开 | 获取验证码 |
| POST | `/user/login` | 公开 | 验证码登录 |
| GET | `/user/me` | 登录 | 当前用户 |
| POST | `/user/sign` | 登录 | 当日签到 |
| GET | `/shop/{id}` | 公开 | 商铺详情 |
| GET | `/shop/of/type` | 公开 | 分类/GEO 分页查询 |
| POST | `/shop/geo/reconcile` | 管理员 | 重建商铺 GEO |
| GET | `/blog/hot` | 公开 | 热门笔记 |
| POST | `/blog` | 登录 | 发布笔记 |
| GET | `/blog/of/follow` | 登录 | 关注 Feed 滚动分页 |
| POST | `/voucher/seckill` | 管理员 | 新增秒杀券 |
| POST | `/voucher-order/seckill/{id}` | 登录 | 提交秒杀订单 |
| GET | `/voucher-order/status/{id}` | 登录 | 查询订单处理状态 |
| POST | `/upload/blog` | 登录 | 上传笔记图片 |
| GET | `/admin/outbox?status=FAILED` | 管理员 | 查询失败 Outbox 事件 |
| POST | `/admin/outbox/{id}/replay` | 管理员 | 重放 Outbox 事件 |

</details>

未标记 `@PublicEndpoint` 的控制器方法默认要求登录；管理接口额外通过 `@RequireRole(ADMIN)` 限制权限。

## Redis 数据设计

| Key | 类型 | 用途 |
| --- | --- | --- |
| `login:code:{phone}` | String | 登录验证码 |
| `login:token:{token}` | Hash | 用户会话 |
| `cache:shop:{id}` | String | 商铺缓存 |
| `shop:geo:{typeId}` | GEO | 分类商铺坐标 |
| `blog:liked:{blogId}` | ZSet | 笔记点赞用户与时间 |
| `feed:{userId}` | ZSet | 用户关注动态 |
| `sign:{userId}:{yyyyMM}` | Bitmap | 月度签到 |
| `seckill:stock:{voucherId}` | String | 秒杀库存 |
| `seckill:order:{voucherId}` | Set | 已下单用户 |
| `seckill:order:status:{orderId}` | Hash | 订单受理状态 |
| `stream.orders` | Stream | 秒杀订单消息 |
| `stream.orders.dlq` | Stream | 失败订单死信消息 |

应用启动时会补齐关注关系、秒杀时间、缺失库存与历史下单标记。GEO 由管理员对账接口分页、Pipeline 重建，避免启动线程逐条全量写入。

## 测试与压测

### 自动化测试

```bash
mvn test
```

测试覆盖请求校验、权限拦截、缓存逻辑、图片所有权、GEO 类型迁移、Feed Outbox 幂等与租约 token 校验、秒杀可靠性等场景。集成测试使用 Testcontainers 启动 MySQL 8.0.36 和 Redis 7.2。

2026-11-05 重新执行 `mvn -B test`：**27 tests，0 failures，0 errors，0 skipped**，包含 8 个真实 MySQL/Redis 容器用例。[逐类统计](docs/performance/2026-11-05-regression.txt)留存了本轮结果。该统计不包含需显式指定的 HTTP 压测 `VoucherSeckillLoadIT`；Docker 不可用时容器测试会跳过，因此验收时需要同时检查 `Skipped` 数量。

2026-11-24 在 Docker 可用的环境执行常规回归，**32 项通过，0 失败，0 跳过**，其中 8 项使用真实 MySQL / Redis 容器。另用 Compose 验证了 Nginx 入口的验证码登录、发券、下单、重复下单拦截和库存一致性。 [复测记录](docs/performance/2026-11-24-seckill-retest.md)

单独执行秒杀可靠性测试：

```bash
mvn -Dtest=VoucherOrderReliabilityIntegrationTest test
```

### HTTP 并发压测

```bash
mvn -B -Dtest=VoucherSeckillLoadIT \
  -Dload.requests=500 -Dload.stock=200 -Dload.concurrency=100 \
  -Dapp.redis.stream-consumer-concurrency=4 \
  -Dapp.redis.pending-min-idle=30s -Dapp.redis.pending-recovery-interval=5s test
```

2026-11-24 在本机 Linux Docker 中直连 Spring Boot 复测，**500 个请求同时发起、200 张券，四轮全部通过，连接错误为 0**。每轮均落库 200 单，MySQL 与 Redis 库存一致。4 个消费者相较于 1 个，将订单成功可见 P95 从 17.43～18.74 秒降至 11.36～12.24 秒，两组对照均缩短约 **35%**。此前 Windows 宿主机的连接失败已定位到突发建连期间的本机 TCP 监听队列。 [平台对照与复现命令](docs/performance/2026-11-24-seckill-retest.md)

2026-11-05 历史复测（均为 500 请求、200 库存）：100 并发通过，HTTP P95 约 2.04 秒，SUCCESS 可见 P95 约 7.42 秒；500 并发两轮分别出现 97/37 次请求错误，整体失败，分类复测的 37 次均为 `ConnectException`。三轮的 200 个受理订单最终均完成且库存一致。当时的测量观察到了单消费者排队和高并发建连问题。完整对比与原始报告见 [压测记录](docs/SECKILL_LOAD_TEST.md)。

以下为 2026-08-08 的历史阶梯压测记录（旧测量与错误统计口径，不能当作本轮验收结论）：

| 并发 | 请求 / 库存 | 结果 | 吞吐量 | P95 延迟 | HTTP 成功率 |
| ---: | ---: | --- | ---: | ---: | ---: |
| 100 | 500 / 200 | PASS | 133.29 req/s | 3.23 s | 100.00% |
| 200 | 1000 / 400 | PASS | 164.64 req/s | 5.10 s | 100.00% |
| 500 | 2000 / 400 | PASS | 225.09 req/s | 7.30 s | 100.00% |
| 500 | 2000 / 800 | 30 秒内未排空 | — | — | — |

通过档均未超卖，受理数、数据库订单数、成功状态数与 Redis/MySQL 库存一致。500 并发、800 库存档在请求结束后 30 秒内未完成异步落库，说明单实例的消费积压和尾延迟已成为容量边界，但不能据此判断为数据丢失。

压测报告会写入 `target/seckill-load-test-report.md`，并按时间和负载参数归档到 `target/load-reports/`。报告区分 HTTP 响应、数据库可见、最终 SUCCESS 可见和请求结束后的排空时间；异步排空超时也会先写出 FAIL 报告再断言失败。详细口径、复现方法和边界说明见 [`docs/SECKILL_LOAD_TEST.md`](docs/SECKILL_LOAD_TEST.md)。本机数据仅用于回归基线，不代表生产容量承诺。

## 可观测性

管理员可以访问 `/actuator/prometheus`。项目采集的核心指标包括：

- 秒杀请求、受理、拒绝、落库、重试、补偿和 DLQ 数量；
- Redis Stream 长度、Pending 数量、最老 Pending 年龄与消费者 lag；
- Feed Outbox 投递成功、重试和最终失败数量；
- 自定义健康检查对 Stream 积压阈值进行告警。

## 项目结构

```text
CitySeek/
├─ src/main/java/com/cityseek/
│  ├─ controller/        # HTTP 接口
│  ├─ service/impl/      # 核心业务
│  ├─ feed/              # Outbox Worker 与 Feed 投递
│  ├─ metrics/           # 业务指标与健康检查
│  ├─ security/          # RBAC
│  └─ utils/             # 缓存、锁、ID、拦截器
├─ src/main/resources/
│  ├─ db/                # 初始化 SQL 与迁移脚本
│  ├─ mapper/            # MyBatis XML
│  └─ *.lua              # 登录、秒杀、补偿、解锁脚本
├─ src/test/             # 单元、集成与压测代码
├─ web/                  # Vue 2 静态前端与 Nginx 配置
├─ docs/                 # 压测与改进记录
├─ compose.yaml
├─ Dockerfile
└─ pom.xml
```

## 已知边界

- 验证码目前仅写入开发日志，未接入真实短信服务；
- 前端为 Vue 2 静态页面，适合演示与联调，尚未迁移到现代构建工具链；
- 图片默认存储在本地目录，生产环境建议替换为对象存储；
- 主订单 Stream 不自动裁剪，生产部署需要根据 Pending/ACK 水位制定安全清理策略；
- 压测数据来自单机开发环境，正式容量规划需要结合目标硬件重新测试。

进一步的实现记录和演进建议见 [`docs/PROJECT_IMPROVEMENT_PLAN.md`](docs/PROJECT_IMPROVEMENT_PLAN.md)。

## License

本项目采用 [MIT License](LICENSE)。
