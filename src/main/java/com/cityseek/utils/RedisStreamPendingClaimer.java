package com.cityseek.utils;

import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedisStreamPendingClaimer {

    private static final StringRedisSerializer SERIALIZER = StringRedisSerializer.UTF_8;

    private final RedisConnectionFactory connectionFactory;

    public RedisStreamPendingClaimer(StringRedisTemplate stringRedisTemplate) {
        this.connectionFactory = stringRedisTemplate.getConnectionFactory();
    }

    public ClaimBatch autoClaim(
            String stream, String group, String consumer, Duration minIdle,
            String startId, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("XAUTOCLAIM count must be positive");
        }
        ClaimBatch result;
        try (RedisConnection redisConnection = connectionFactory.getConnection()) {
            if (!(redisConnection instanceof LettuceConnection connection)) {
                throw new IllegalStateException(
                        "XAUTOCLAIM requires Lettuce, got " + redisConnection.getClass().getName());
            }
            Object raw = connection.execute(
                    "XAUTOCLAIM",
                    new NestedMultiOutput<>(ByteArrayCodec.INSTANCE),
                    serialize(stream),
                    serialize(group),
                    serialize(consumer),
                    serialize(Long.toString(Math.max(1L, minIdle.toMillis()))),
                    serialize(startId),
                    serialize("COUNT"),
                    serialize(Integer.toString(count))
            );
            result = parse(stream, raw);
        }
        return result == null ? ClaimBatch.empty() : result;
    }

    private ClaimBatch parse(String stream, Object raw) {
        List<?> response = asList(raw);
        if (response.size() < 2) {
            return ClaimBatch.empty();
        }
        String nextStartId = decode(response.get(0));
        List<MapRecord<String, Object, Object>> records = new ArrayList<>();
        for (Object rawEntry : asList(response.get(1))) {
            List<?> entry = asList(rawEntry);
            if (entry.size() < 2) {
                continue;
            }
            String recordId = decode(entry.get(0));
            Map<Object, Object> fields = decodeFields(entry.get(1));
            if (recordId != null && !fields.isEmpty()) {
                records.add(MapRecord.create(stream, fields).withId(RecordId.of(recordId)));
            }
        }
        return new ClaimBatch(nextStartId == null ? "0-0" : nextStartId, records);
    }

    private Map<Object, Object> decodeFields(Object rawFields) {
        Map<Object, Object> fields = new LinkedHashMap<>();
        if (rawFields instanceof Map<?, ?> map) {
            map.forEach((key, value) -> fields.put(decode(key), decode(value)));
            return fields;
        }
        List<?> values = asList(rawFields);
        for (int index = 0; index + 1 < values.size(); index += 2) {
            fields.put(decode(values.get(index)), decode(values.get(index + 1)));
        }
        return fields;
    }

    private List<?> asList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        if (value instanceof Object[] array) {
            return Arrays.asList(array);
        }
        return Collections.emptyList();
    }

    private byte[] serialize(String value) {
        return SERIALIZER.serialize(value);
    }

    private String decode(Object value) {
        if (value instanceof byte[] bytes) {
            return SERIALIZER.deserialize(bytes);
        }
        if (value instanceof ByteBuffer buffer) {
            ByteBuffer copy = buffer.asReadOnlyBuffer();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return SERIALIZER.deserialize(bytes);
        }
        return value == null ? null : value.toString();
    }

    public record ClaimBatch(String nextStartId, List<MapRecord<String, Object, Object>> records) {

        public ClaimBatch {
            records = List.copyOf(records);
        }

        public static ClaimBatch empty() {
            return new ClaimBatch("0-0", Collections.emptyList());
        }
    }
}
