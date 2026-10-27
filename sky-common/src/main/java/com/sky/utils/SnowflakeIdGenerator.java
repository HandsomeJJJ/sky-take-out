package com.sky.utils;

/**
 * 雪花算法 ID 生成器：解决订单号用 System.currentTimeMillis() 在毫秒级并发下会撞号的问题。
 *
 * 结构：1 位符号位(0) + 41 位时间戳 + 5 位机器ID + 12 位序列号。
 * 单机默认 workerId=1；集群部署时不同实例传入不同 workerId（可用环境变量/配置中心下发）。
 */
public class SnowflakeIdGenerator {

    /** 起始时间戳：2024-01-01 00:00:00，避免过早溢出 */
    private static final long START_TIMESTAMP = 1704067200000L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    private static class Holder {
        private static final SnowflakeIdGenerator INSTANCE = new SnowflakeIdGenerator(1L);
    }

    public static SnowflakeIdGenerator getInstance() {
        return Holder.INSTANCE;
    }

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 [0, " + MAX_WORKER_ID + "] 之间");
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("系统时钟回拨，拒绝生成 ID，等待时钟追上");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 同一毫秒序列号用完，自旋到下一毫秒
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
