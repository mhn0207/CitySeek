# 2026-11-24 秒杀多消费者 Docker 复测

本机 Linux Docker 上，500 个请求同时发起的四轮测试全部通过，连接错误为 0。4 个消费者相较于 1 个，订单成功可见 P95 两组对照均缩短约 35%。Windows 宿主机的 500 并发失败记录保留在下文，后续诊断定位到本机 TCP 建连。

## Windows 宿主机对照

- Windows、JDK 17.0.19、Docker Desktop Engine 29.9.3。应用与 JDK HTTP 压测客户端在宿主机运行，真实 MySQL 8.0.36 和 Redis 7.2 由 Testcontainers 启动。
- 每轮 500 个请求、200 张券、独立容器和 JVM。订单消费批大小均为 16，消息接管阈值 30 秒，扫描间隔 5 秒。两个并发档反转 1 / 4 消费者的先后顺序，测试期间各轮串行执行。
- 双方使用同一份多消费者实现、Lua 和连接池配置，只改变消费者数量。结果来自单机冷启动并发直连，不包含 Nginx，也未测量持续流量下的容量。
- HTTP P95 包含业务拒绝和请求错误。订单成功可见 P95 只统计受理订单，从请求发送到后台观察线程首次查到 SUCCESS，包含轮询误差。排空时间从最后一个 HTTP 请求结束到观察到最后一笔成功订单。

### 八轮结果

| 客户端并发 | 消费者 | 配置内轮次 | HTTP P95（秒） | SUCCESS 可见 P95（秒） | HTTP 后排空（秒） | 连接错误 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 1 | 1 | 4.19 | 8.69 | 8.09 | 0 |
| 100 | 4 | 1 | 4.40 | 7.15 | 4.67 | 0 |
| 100 | 4 | 2 | 4.47 | 7.63 | 4.73 | 0 |
| 100 | 1 | 2 | 4.47 | 8.72 | 8.17 | 0 |
| 500 | 4 | 1 | 7.25 | 10.90 | 4.18 | 33 |
| 500 | 1 | 1 | 7.15 | 12.71 | 5.88 | 25 |
| 500 | 1 | 2 | 6.42 | 13.66 | 7.63 | 70 |
| 500 | 4 | 2 | 6.57 | 10.78 | 4.76 | 12 |

每轮都受理 200 单，MySQL 订单数与 Redis SUCCESS 数均为 200，MySQL 和 Redis 的库存均为 0，重试、补偿、死信和结束时的 Pending / lag 均为 0。100 并发四轮通过。500 并发四轮均因 `ConnectException` 失败。4 个消费者在这四组对照中的订单成功可见 P95 降低 12.5%～21.0%，HTTP P95 均略高。

### 宿主机复现

启动 Docker 后执行以下命令，将消费者数设为 1 和 4 分别运行，再将客户端并发切换为 500 重复对照。

```bash
mvn -B -Dtest=VoucherSeckillLoadIT \
  -Dload.requests=500 -Dload.stock=200 -Dload.concurrency=100 \
  -Dapp.redis.stream-consumer-concurrency=4 \
  -Dapp.redis.pending-min-idle=30s -Dapp.redis.pending-recovery-interval=5s test
```

常规容器回归仍使用 100 毫秒接管设置，以在较短时间内验证接管和补偿。初次复测在隔离源码副本中移除了测试的 100 毫秒覆盖，并显式传入 30 秒和 5 秒。目前可用同一组命令行属性直接覆盖测试配置，生成的报告也会记录这两个参数和消费者数。逐轮报告位于本次运行的 `target/load-reports/`。

## Windows 建连诊断

- 同样以 500 个请求同时发起，Tomcat 的配置队列从 1024 调至 4096 后仍出现 17 次连接错误。关闭 JDK 自动建连重试后，另一轮的 296 次失败均直接报出 `Connection refused: getsockopt`，秒杀服务只收到另外 204 个请求。
- 暂停取出连接并同时发起 500 次纯 TCP 建连时，Windows Winsock 配置为 1024 和 4096 均只有 203 条排入队列，其余返回 `10061`。Java `ServerSocketChannel` 使用相同参数只排入 201 和 202 条，即便将参数设到 `2147483647`，两次也只排入 205 和 203 条。这些结果支持本机监听队列在突发建连时饱和。 [Winsock 行为](https://learn.microsoft.com/en-us/windows/win32/api/winsock2/nf-winsock2-listen) · [Tomcat NIO 监听实现](https://github.com/apache/tomcat/blob/10.1.39/java/org/apache/tomcat/util/net/NioEndpoint.java)
- Windows 上按 5 毫秒间隔发出 500 个秒杀请求的对照通过，连接错误为 0。错开发送验证的是另一种流量形态。

## Linux Docker 500 并发复测

在 Docker Desktop 29.9.3 的 WSL2 Linux 容器内运行同一份 `VoucherSeckillLoadIT`，Spring Boot 和 JDK HTTP 客户端均在容器内。Linux 使用 Temurin 17.0.15，Windows 对照使用 Microsoft JDK 17.0.19。两边都直连 Spring Boot 并使用 Testcontainers 的 MySQL 8.0.36 和 Redis 7.2。四轮均为 500 个请求同时发起、200 张券，使用 16 条读取批大小、30 秒接管阈值和 5 秒扫描间隔。

| 消费者 | 配置内轮次 | HTTP P95（秒） | SUCCESS 可见 P95（秒） | HTTP 后排空（秒） | 连接错误 | 结果 |
| ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 4 | 1 | 6.96 | 11.36 | 4.65 | 0 | 通过 |
| 1 | 1 | 8.11 | 17.43 | 9.86 | 0 | 通过 |
| 1 | 2 | 9.86 | 18.74 | 9.37 | 0 | 通过 |
| 4 | 2 | 7.98 | 12.24 | 4.56 | 0 | 通过 |

每轮受理 200 单、落库 200 单、记录 200 个 `SUCCESS`，MySQL 和 Redis 库存都归零，重试、补偿、死信和结束时的 Pending / lag 均为 0。四轮结果反映的是本机 Linux Docker 冷启动直连表现，生产部署容量仍需按目标环境测量。

### Docker Desktop 复现

在仓库根目录打开 PowerShell，使用 Docker Desktop 的 Linux 容器模式。以下命令复现一轮，按 4、1、1、4 切换消费者数，配合轮次 1、1、2、2 保存四个报告。测试容器通过 Docker 套接字启动 Testcontainers 依赖。

```powershell
docker build --target build --tag cityseek-linux-load .
$reportDir = (New-Item -ItemType Directory -Force target/linux-seckill-load).FullName
$workers = 4
$trial = 1
docker run --rm `
  --mount 'type=bind,src=/var/run/docker.sock,dst=/var/run/docker.sock' `
  --mount "type=bind,src=$reportDir,dst=/results" `
  --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal `
  --env "LOAD_WORKERS=$workers" --env "LOAD_TRIAL=$trial" `
  --entrypoint /bin/bash cityseek-linux-load -lc '
    mvn -B -ntp -Dtest=VoucherSeckillLoadIT \
      -Dload.requests=500 -Dload.stock=200 -Dload.concurrency=500 \
      -Dapp.redis.stream-consumer-concurrency="$LOAD_WORKERS" \
      -Dapp.redis.pending-min-idle=30s -Dapp.redis.pending-recovery-interval=5s test
    result=$?
    cp target/seckill-load-test-report.md "/results/c500-w$LOAD_WORKERS-r$LOAD_TRIAL.md" || exit 1
    exit "$result"
  '
```

## 独立验证

- 常规回归执行 `mvn -B test`，32 项通过，其中 8 项运行于真实 MySQL / Redis 容器，0 失败、0 跳过。
- 用仓库 Dockerfile 和 Compose 构建 Nginx、Spring Boot、MySQL 8.4 和 Redis 7.4，四个容器均达到 `healthy`。经 Nginx 验证了首页、验证码登录、签到、匿名访问返回 401 和普通用户发券返回 403。
- 管理员创建库存为 2 的测试券，普通用户下单并查到 SUCCESS。重复下单被拒，MySQL 有 1 笔订单，MySQL 和 Redis 库存均剩 1 张。验证码由测试脚本从隔离 Redis 读取。
