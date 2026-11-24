package com.cityseek.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.cityseek.entity.SeckillVoucher;
import com.cityseek.entity.VoucherOrder;
import com.cityseek.mapper.VoucherOrderMapper;
import com.cityseek.metrics.VoucherOrderMetrics;
import com.cityseek.service.ISeckillVoucherService;
import com.cityseek.utils.RedisIdWorker;
import com.cityseek.utils.RedisStreamPendingClaimer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class VoucherOrderServiceImplTest {

    private VoucherOrderServiceImpl service;
    private ISeckillVoucherService seckillVoucherService;
    private UpdateChainWrapper<SeckillVoucher> updateChain;
    private StringRedisTemplate stringRedisTemplate;
    private StreamOperations<String, Object, Object> streamOperations;
    private ValueOperations<String, String> valueOperations;
    private RedisStreamPendingClaimer pendingClaimer;
    private SimpleMeterRegistry meterRegistry;
    private VoucherOrderMapper voucherOrderMapper;

    @BeforeEach
    void setUp() {
        service = spy(new VoucherOrderServiceImpl());
        seckillVoucherService = mock(ISeckillVoucherService.class);
        updateChain = mock(UpdateChainWrapper.class);
        RedisIdWorker redisIdWorker = mock(RedisIdWorker.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        streamOperations = mock(StreamOperations.class);
        valueOperations = mock(ValueOperations.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        pendingClaimer = mock(RedisStreamPendingClaimer.class);
        meterRegistry = new SimpleMeterRegistry();
        VoucherOrderMetrics metrics = new VoucherOrderMetrics(meterRegistry);
        voucherOrderMapper = mock(VoucherOrderMapper.class);

        when(seckillVoucherService.update()).thenReturn(updateChain);
        when(updateChain.setSql(anyString())).thenReturn(updateChain);
        when(updateChain.eq(any(), any())).thenReturn(updateChain);
        when(updateChain.gt(any(), any())).thenReturn(updateChain);
        when(updateChain.update()).thenReturn(true);

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                any(), any(), any(), any(), any())).thenReturn(1L);

        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(service, "pendingClaimer", pendingClaimer);
        ReflectionTestUtils.setField(service, "metrics", metrics);
        ReflectionTestUtils.setField(service, "baseMapper", voucherOrderMapper);
        ReflectionTestUtils.setField(service, "consumerName", "cityseek-consumer");
        ReflectionTestUtils.setField(service, "pendingMinIdle", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(service, "pendingRecoveryInterval", Duration.ofSeconds(5));
        ReflectionTestUtils.setField(service, "pendingBatchSize", 20);
        ReflectionTestUtils.setField(service, "dlqMaxLength", 10000L);
    }

    @Test
    void assignsDistinctConsumerNamesWhenConcurrencyExceedsOne() {
        assertEquals("cityseek-consumer", service.workerConsumerName(0, 1));
        assertEquals("cityseek-consumer-1", service.workerConsumerName(0, 4));
        assertEquals("cityseek-consumer-4", service.workerConsumerName(3, 4));
    }

    @Test
    void isolateBatchFailuresSoValidOrdersStillComplete() {
        MapRecord<String, Object, Object> malformed = MapRecord.<String, Object, Object>create(
                        "stream.orders", Map.<Object, Object>of("userId", "1"))
                .withId(RecordId.of("1-0"));
        MapRecord<String, Object, Object> valid = MapRecord.<String, Object, Object>create(
                        "stream.orders", Map.<Object, Object>of(
                                "id", "2001",
                                "userId", "10",
                                "voucherId", "5"
                        ))
                .withId(RecordId.of("2-0"));

        when(valueOperations.increment("stream.orders:retry:1-0")).thenReturn(1L);
        when(voucherOrderMapper.insert(any(VoucherOrder.class))).thenReturn(1);

        service.consumeBatch(List.of(malformed, valid), "cityseek-consumer-1");

        verify(streamOperations, never()).acknowledge("stream.orders", "g1", RecordId.of("1-0"));
        verify(streamOperations).acknowledge("stream.orders", "g1", RecordId.of("2-0"));
        verify(stringRedisTemplate, never()).delete("stream.orders:retry:2-0");
        assertEquals(1.0, meterRegistry.counter("cityseek.order.persisted").count());
        assertEquals(1.0, meterRegistry.counter("cityseek.order.retry").count());
    }

    @Test
    void fallsBackToPersistedOrderOnDuplicateKeyWithoutDoubleCounting() {
        VoucherOrder existing = new VoucherOrder().setId(2002L).setUserId(11L).setVoucherId(5L);
        MapRecord<String, Object, Object> record = MapRecord.<String, Object, Object>create(
                        "stream.orders", Map.<Object, Object>of(
                                "id", "2002",
                                "userId", "11",
                                "voucherId", "5"
                        ))
                .withId(RecordId.of("3-0"));

        doThrow(new DuplicateKeyException("duplicate")).when(service).save(any(VoucherOrder.class));
        doReturn(existing).when(service).getById(2002L);

        service.processRecord(record, false);

        verify(service, times(1)).getById(2002L);
        verify(streamOperations).acknowledge("stream.orders", "g1", RecordId.of("3-0"));
        assertEquals(0.0, meterRegistry.counter("cityseek.order.persisted").count());
    }

    @Test
    void redeliveryChecksExistingOrderFirstAndClearsRetryKeyOnSuccess() {
        VoucherOrder existing = new VoucherOrder().setId(2003L).setUserId(12L).setVoucherId(5L);
        MapRecord<String, Object, Object> record = MapRecord.<String, Object, Object>create(
                        "stream.orders", Map.<Object, Object>of(
                                "id", "2003",
                                "userId", "12",
                                "voucherId", "5"
                        ))
                .withId(RecordId.of("4-0"));

        doReturn(existing).when(service).getById(2003L);

        service.processRecord(record, true);

        verify(updateChain, never()).update();
        verify(stringRedisTemplate).delete("stream.orders:retry:4-0");
        verify(streamOperations).acknowledge("stream.orders", "g1", RecordId.of("4-0"));
        assertEquals(0.0, meterRegistry.counter("cityseek.order.persisted").count());
    }

    @Test
    void onlyOneWorkerRunsPendingRecoveryPerInterval() throws Exception {
        when(pendingClaimer.autoClaim(anyString(), anyString(), anyString(), any(), anyString(), eq(20)))
                .thenReturn(RedisStreamPendingClaimer.ClaimBatch.empty());

        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return service.recoverPendingIfDue("cityseek-consumer-" + (i + 1));
                    }))
                    .toList();
            start.countDown();
            long winners = 0;
            for (Future<Boolean> future : futures) {
                if (Boolean.TRUE.equals(future.get(5, TimeUnit.SECONDS))) {
                    winners++;
                }
            }
            assertEquals(1L, winners);
            verify(pendingClaimer, times(1))
                    .autoClaim(eq("stream.orders"), eq("g1"), anyString(), any(), eq("0-0"), eq(20));
            assertTrue(winners == 1L);
        } finally {
            pool.shutdownNow();
        }
    }
}
