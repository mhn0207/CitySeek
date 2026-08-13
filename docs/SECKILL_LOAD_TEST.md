# 秒杀可靠性与压测记录

## 测试环境

2026-08-08 在本机 Docker Desktop 环境执行阶梯压测。

- 应用：Spring Boot 3.4.4，随机 HTTP 端口，单应用实例
- 依赖：Testcontainers 1.21.4、MySQL 8.0.36、Redis 7.2
- JDK：17.0.19
- Docker Engine：29.6.1，分配内存约 7.8 GB
- 请求客户端：JDK `HttpClient`，单请求超时 10 秒

## 阶梯压测结论

| 并发 | 请求 | 初始库存 | 业务成功率 | 请求窗口 | 吞吐量 | 平均延迟 | P50 | P95 | P99 | 结果 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 100 | 500 | 200 | 40.00% | 3.75 s | 133.29 requests/s | 704.10 ms | 76.41 ms | 3.23 s | 3.23 s | PASS |
| 200 | 1000 | 400 | 40.00% | 6.07 s | 164.64 requests/s | 1140.97 ms | 158.20 ms | 5.10 s | 5.10 s | PASS |
| 500 | 2000 | 400 | 20.00% | 8.89 s | 225.09 requests/s | 2070.79 ms | 401.56 ms | 7.30 s | 7.34 s | PASS |
| 500 | 2000 | 800 | - | - | - | - | - | - | - | 30 秒内未排空 |

三个通过档的 HTTP 传输成功率均为 100%，且秒杀受理数、MySQL 最终订单数、Redis `SUCCESS` 状态数完全一致，
MySQL 与 Redis 剩余库存均为 0，没有发生超卖。

结果表明：

1. 入口链路可以承受本机 500 并发、2000 个请求，Redis Lua 库存校验在通过档保持一致；
2. 并发从 100 增加到 200 时，吞吐量提高约 23.5%，但 P95 从 3.23 秒上升到 5.10 秒，排队已明显加重；
3. 500 并发、400 库存档虽然通过，但 P95 已达到 7.30 秒，不适合作为低延迟运行点；
4. 500 并发、800 库存档在请求结束后 30 秒内没有满足“最终订单数等于 HTTP 受理数”的等待条件，测试在生成指标报告前失败。
   这说明异步落库排空或请求确认已超过当前测试阈值，但不能单凭该断言判定数据丢失、超卖或最终不一致；
5. 500 并发通过档只有 20% 的请求被受理，其余请求走较轻的库存不足拒绝路径，因此其吞吐量不能与 40% 业务成功率的
   100/200 并发档直接等价比较。

如果以 P95 小于 5 秒作为当前环境的临时目标，建议把单实例并发控制在约 100；更高峰值应配合入口限流、积压监控、
异步消费者扩展或应用水平扩容。该建议只适用于本机回归环境，不是生产容量承诺。

## 指标口径

吞吐量和延迟只统计 HTTP 请求处理窗口，不包含 Testcontainers 拉起、数据库初始化、Spring Boot 启动以及请求后的异步排空时间。
HTTP 请求完成后，测试最多等待 30 秒让订单消费者落库，再等待最多 15 秒确认 Redis 订单状态，最后校验：

- HTTP 传输错误数为 0；
- 秒杀受理数等于初始库存；
- MySQL 最终订单数和 Redis `SUCCESS` 状态数等于初始库存；
- MySQL 与 Redis 剩余库存均为 0。

所有条件同时成立才记为 `PASS`。测试关闭容器时可能出现 Lettuce/Hikari 的断连或重连警告；若这些日志出现在报告输出之后，
且 Surefire 最终为 `BUILD SUCCESS`，它们属于测试依赖回收阶段的日志，不是压测失败。

## 可靠性覆盖

`VoucherOrderReliabilityIntegrationTest` 使用真实 MySQL 与 Redis 验证：

1. 死亡消费者留下的 Pending 消息可以被另一消费者通过 `XAUTOCLAIM` 接管；
2. 40 个并发请求争抢 20 份库存时不超卖，受理数、订单数、状态数和库存一致；
3. 数据库连续处理失败达到上限后，订单标记为 `FAILED`，Redis 库存和购买标记由 Lua 原子补偿，原消息 ACK，并写入有界 DLQ。

## 复现命令

```bash
mvn -Dtest=VoucherOrderReliabilityIntegrationTest test
mvn -Dtest=VoucherSeckillLoadIT -Dload.requests=500 -Dload.stock=200 -Dload.concurrency=100 test
mvn -Dtest=VoucherSeckillLoadIT -Dload.requests=1000 -Dload.stock=400 -Dload.concurrency=200 test
mvn -Dtest=VoucherSeckillLoadIT -Dload.requests=2000 -Dload.stock=400 -Dload.concurrency=500 test
```

当前容量边界复现命令如下；该档预期可能因为 30 秒内未排空而失败：

```bash
mvn -Dtest=VoucherSeckillLoadIT -Dload.requests=2000 -Dload.stock=800 -Dload.concurrency=500 test
```

压测每次都会重新创建容器和测试数据，并覆盖 `target/seckill-load-test-report.md`。数值会受到 CPU、Docker 资源、JIT 预热、
请求成功/拒绝比例和本机负载影响，比较结果时应保持业务成功率和环境配置一致。
