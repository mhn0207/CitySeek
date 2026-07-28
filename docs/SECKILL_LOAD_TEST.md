# 秒杀可靠性与压测记录

## 测试结论

2026-07-28 在本机 Docker Desktop 环境执行，结果为 **PASS**。

- 应用：Spring Boot 3.4.4，随机 HTTP 端口
- 依赖：Testcontainers 1.21.4、MySQL 8.0.36、Redis 7.2
- JDK：17.0.19
- 请求：500
- 并发：100
- 初始库存：200

| 指标 | 实测值 |
| --- | ---: |
| HTTP 请求窗口 | 3.87 s |
| 吞吐量 | 129.11 requests/s |
| 平均延迟 | 734.17 ms |
| P50 延迟 | 82.58 ms |
| P95 延迟 | 3351.36 ms |
| P99 延迟 | 3351.96 ms |
| 业务成功率 | 40.00% |
| HTTP 传输成功率 | 100.00% |
| 秒杀受理成功 | 200 |
| 库存不足拒绝 | 300 |
| 传输错误 | 0 |
| MySQL 最终订单 | 200 |
| MySQL 剩余库存 | 0 |
| Redis 剩余库存 | 0 |
| `SUCCESS` 状态记录 | 200 |

吞吐量和延迟只统计 500 个 HTTP 请求的处理窗口，不包含 Testcontainers 启动时间；HTTP 请求完成后，测试会继续等待异步消费者排空，再校验 MySQL 订单、Redis 状态以及两侧库存。因此这组数据用于学习项目的回归基线，不应直接当作生产容量结论。

## 可靠性覆盖

`VoucherOrderReliabilityIntegrationTest` 使用真实 MySQL 与 Redis 验证：

1. 死亡消费者留下的 Pending 消息可以被另一消费者通过 `XAUTOCLAIM` 接管；
2. 40 个并发请求争抢 20 份库存时不超卖，受理数、订单数、状态数和库存一致；
3. 数据库连续处理失败达到上限后，订单标记为 `FAILED`，Redis 库存和购买标记由 Lua 原子补偿，原消息 ACK，并写入有界 DLQ。

## 复现命令

```bash
mvn -Dtest=VoucherOrderReliabilityIntegrationTest test
mvn -Dtest=VoucherSeckillLoadIT -Dload.requests=500 -Dload.stock=200 -Dload.concurrency=100 test
```

压测每次都会重新创建容器和测试数据，并在 `target/seckill-load-test-report.md` 生成本次报告。数值会受到 CPU、Docker 资源、JIT 预热和本机负载影响；判断通过的硬条件是无传输错误，且受理请求数、MySQL 订单数、成功状态数与 Redis/MySQL 库存完全一致。
