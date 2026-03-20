package com.linkwork.common;

import org.springframework.stereotype.Component;

import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 雪花算法 ID 生成器
 * 
 * 生成分布式全局唯一 64 位 ID：
 * - 1 bit: 符号位（固定 0）
 * - 41 bits: 时间戳（毫秒级，可用约 69 年）
 * - 10 bits: 机器 ID（支持 1024 个节点）
 * - 12 bits: 序列号（每毫秒最多 4096 个 ID）
 */
@Component
public class SnowflakeIdGenerator {

    // 起始时间戳 (2024-01-01 00:00:00 UTC)
    private static final long EPOCH = 1704067200000L;

    // 机器 ID 位数
    private static final long WORKER_ID_BITS = 10L;
    // 序列号位数
    private static final long SEQUENCE_BITS = 12L;

    // 最大机器 ID
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    // 最大序列号
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    // 时间戳左移位数
    private static final long TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    // 机器 ID 左移位数
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator() {
        this.workerId = generateWorkerId();
    }

    /**
     * 生成下一个分布式唯一 ID
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        // 时钟回拨检测
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("时钟回拨，拒绝生成 ID: " + (lastTimestamp - timestamp) + " ms");
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒内，序列号递增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 序列号溢出，等待下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // 新的毫秒，序列号重置
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 生成字符串格式的 ID（用于 taskNo）
     */
    public String nextIdStr() {
        return String.valueOf(nextId());
    }

    /**
     * 生成带前缀的任务编号
     */
    public String nextTaskNo() {
        return "TSK-" + nextId();
    }

    /**
     * 等待下一毫秒
     */
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    /**
     * 基于机器 MAC 地址生成 workerId
     */
    private long generateWorkerId() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                byte[] mac = network.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    // 取 MAC 地址后两个字节计算 workerId
                    long id = ((0x000000FF & (long) mac[mac.length - 2])
                            | (0x0000FF00 & (((long) mac[mac.length - 1]) << 8))) & MAX_WORKER_ID;
                    return id;
                }
            }
        } catch (Exception e) {
            // 获取失败时使用随机值
        }
        return (long) (Math.random() * MAX_WORKER_ID);
    }
}
