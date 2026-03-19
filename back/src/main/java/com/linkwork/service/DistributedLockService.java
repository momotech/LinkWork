package com.linkwork.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Slf4j
public class DistributedLockService {

    private final StringRedisTemplate redisTemplate;

    private static final String LOCK_PREFIX = "lock:";
    private static final int DEFAULT_LOCK_TIMEOUT = 30;
    private static final int DEFAULT_LOCK_WAIT = 35;

    private static final String RELEASE_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "   return redis.call('del', KEYS[1]) " +
        "else " +
        "   return 0 " +
        "end";

    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    private static final String LOCAL_PREFIX = "LOCAL:";
    private volatile boolean redisAvailable = true;

    public DistributedLockService(@Nullable StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        if (redisTemplate == null) {
            this.redisAvailable = false;
            log.warn("Redis not configured, using local locks only");
        }
    }

    public String tryAcquireLock(String key) {
        return tryAcquireLockByKey(LOCK_PREFIX + key);
    }

    public String tryAcquireLockByKey(String fullKey) {
        return tryAcquireLockByKey(fullKey, DEFAULT_LOCK_TIMEOUT, DEFAULT_LOCK_WAIT);
    }

    public String tryAcquireLockByKey(String fullKey, int lockTimeoutSec, int lockWaitSec) {
        if (!redisAvailable || redisTemplate == null) {
            return tryAcquireLocalLock(fullKey, lockWaitSec);
        }

        String lockValue = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();
        long waitMillis = lockWaitSec * 1000L;

        while (System.currentTimeMillis() - startTime < waitMillis) {
            try {
                Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(fullKey, lockValue, lockTimeoutSec, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(success)) {
                    return lockValue;
                }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.warn("Redis error, falling back to local lock: {}", e.getMessage());
                redisAvailable = false;
                return tryAcquireLocalLock(fullKey, lockWaitSec);
            }
        }
        log.warn("Failed to acquire lock key={} within {}s", fullKey, lockWaitSec);
        return null;
    }

    private String tryAcquireLocalLock(String key, int lockWaitSec) {
        ReentrantLock lock = localLocks.computeIfAbsent(key, k -> new ReentrantLock(true));
        try {
            if (lock.tryLock(lockWaitSec, TimeUnit.SECONDS)) {
                return LOCAL_PREFIX + UUID.randomUUID();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    public void releaseLock(String key, String lockValue) {
        releaseLockByKey(LOCK_PREFIX + key, lockValue);
    }

    public void releaseLockByKey(String fullKey, String lockValue) {
        if (lockValue == null) return;
        if (lockValue.startsWith(LOCAL_PREFIX)) {
            releaseLocalLock(fullKey);
            return;
        }
        if (redisTemplate == null) return;
        try {
            redisTemplate.execute(
                new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class),
                Collections.singletonList(fullKey),
                lockValue
            );
        } catch (Exception e) {
            log.error("Error releasing lock key={}: {}", fullKey, e.getMessage());
        }
    }

    private void releaseLocalLock(String key) {
        ReentrantLock lock = localLocks.get(key);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    public boolean isLockedByKey(String fullKey) {
        if (redisTemplate == null) {
            ReentrantLock lock = localLocks.get(fullKey);
            return lock != null && lock.isLocked();
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(fullKey));
    }
}
