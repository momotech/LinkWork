package com.linkwork.common;

import org.springframework.stereotype.Component;

import java.net.NetworkInterface;
import java.util.Enumeration;

@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L;
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);
    private static final long TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator() {
        this.workerId = generateWorkerId();
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards: " + (lastTimestamp - timestamp) + " ms");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    public String nextIdStr() {
        return String.valueOf(nextId());
    }

    public String nextTaskNo() {
        return "TSK-" + nextId();
    }

    public String nextApprovalNo() {
        return "APR-" + nextId();
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    private long generateWorkerId() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                byte[] mac = network.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    long id = ((0x000000FF & (long) mac[mac.length - 2])
                            | (0x0000FF00 & (((long) mac[mac.length - 1]) << 8))) & MAX_WORKER_ID;
                    return id;
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return (long) (Math.random() * MAX_WORKER_ID);
    }
}
