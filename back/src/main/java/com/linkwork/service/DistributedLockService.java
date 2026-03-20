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

/**
 * Redis 分布式锁服务
 * 
 * 用于多实例部署时，保证操作原子性：
 * - 扩缩容操作：锁键 scale:lock:{serviceId}
 * - OSS 目录操作：锁键 oss:lock:{serviceId}:{userId}
 * 
 * 支持降级：Redis 不可用时自动降级为本地锁（用于测试环境）
 */
@Component
@Slf4j
public class DistributedLockService {
    
    private final StringRedisTemplate redisTemplate;
    
    /**
     * 锁 Key 前缀
     */
    private static final String LOCK_PREFIX = "scale:lock:";
    
    /**
     * 锁超时时间（秒）- 防止死锁
     */
    private static final int LOCK_TIMEOUT_SECONDS = 30;
    
    /**
     * 获取锁等待时间（秒）
     */
    private static final int LOCK_WAIT_SECONDS = 35;
    
    /**
     * Lua 脚本：原子释放锁（只释放自己持有的锁）
     */
    private static final String RELEASE_LOCK_SCRIPT = 
        "if redis.call('get', KEYS[1]) == ARGV[1] then " +
        "   return redis.call('del', KEYS[1]) " +
        "else " +
        "   return 0 " +
        "end";
    
    /**
     * 本地锁（用于降级，仅在 Redis 不可用时使用）
     */
    private final ConcurrentHashMap<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();
    
    /**
     * 本地锁标识前缀
     */
    private static final String LOCAL_LOCK_PREFIX = "LOCAL:";
    
    /**
     * Redis 是否可用
     */
    private volatile boolean redisAvailable = true;
    
    public DistributedLockService(@Nullable StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        if (redisTemplate == null) {
            this.redisAvailable = false;
            log.warn("Redis not configured, using local locks only (not suitable for multi-instance deployment)");
        }
    }
    
    /**
     * 尝试获取扩缩容分布式锁（按 serviceId）
     * 
     * @param serviceId 服务 ID
     * @return lockValue 如果获取成功返回锁标识（用于释放），失败返回 null
     */
    public String tryAcquireLock(String serviceId) {
        return tryAcquireLockByKey(LOCK_PREFIX + serviceId);
    }
    
    /**
     * 尝试获取分布式锁（通用方法，支持自定义锁键）
     * 
     * @param fullKey 完整的锁键（如 "oss:lock:svc1:user1"）
     * @return lockValue 如果获取成功返回锁标识（用于释放），失败返回 null
     */
    public String tryAcquireLockByKey(String fullKey) {
        return tryAcquireLockByKey(fullKey, LOCK_TIMEOUT_SECONDS, LOCK_WAIT_SECONDS);
    }

    /**
     * 尝试获取分布式锁（支持自定义超时时间）
     */
    public String tryAcquireLockByKey(String fullKey, int lockTimeoutSeconds, int lockWaitSeconds) {
        // 如果 Redis 不可用，降级为本地锁
        if (!redisAvailable || redisTemplate == null) {
            return tryAcquireLocalLock(fullKey, lockWaitSeconds);
        }
        
        String lockValue = UUID.randomUUID().toString();
        
        long startTime = System.currentTimeMillis();
        long waitMillis = lockWaitSeconds * 1000L;
        
        while (System.currentTimeMillis() - startTime < waitMillis) {
            try {
                // SET NX EX：不存在则设置，带过期时间
                Boolean success = redisTemplate.opsForValue()
                    .setIfAbsent(fullKey, lockValue, lockTimeoutSeconds, TimeUnit.SECONDS);
                
                if (Boolean.TRUE.equals(success)) {
                    log.debug("Acquired distributed lock key={}, lockValue={}", fullKey, lockValue);
                    return lockValue;
                }
                
                // 等待 100ms 后重试
                Thread.sleep(100);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for lock key={}", fullKey);
                return null;
            } catch (Exception e) {
                // Redis 连接异常，降级为本地锁
                log.warn("Redis error, falling back to local lock key={}: {}", fullKey, e.getMessage());
                redisAvailable = false;
                return tryAcquireLocalLock(fullKey, lockWaitSeconds);
            }
        }

        log.warn("Failed to acquire lock key={} within {} seconds", fullKey, lockWaitSeconds);
        return null;
    }
    
    /**
     * 获取本地锁（降级方案）
     */
    private String tryAcquireLocalLock(String key, int lockWaitSeconds) {
        ReentrantLock lock = localLocks.computeIfAbsent(key, k -> new ReentrantLock(true));
        try {
            boolean acquired = lock.tryLock(lockWaitSeconds, TimeUnit.SECONDS);
            if (acquired) {
                String lockValue = LOCAL_LOCK_PREFIX + UUID.randomUUID().toString();
                log.debug("Acquired local lock key={}, lockValue={}", key, lockValue);
                return lockValue;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for local lock key={}", key);
        }
        return null;
    }
    
    /**
     * 释放扩缩容分布式锁（按 serviceId）
     * 
     * @param serviceId 服务 ID
     * @param lockValue 锁标识（tryAcquireLock 返回的值）
     */
    public void releaseLock(String serviceId, String lockValue) {
        releaseLockByKey(LOCK_PREFIX + serviceId, lockValue);
    }
    
    /**
     * 释放分布式锁（通用方法，支持自定义锁键）
     * 
     * 使用 Lua 脚本保证原子性：只有锁的持有者才能释放
     * 
     * @param fullKey 完整的锁键
     * @param lockValue 锁标识（tryAcquireLockByKey 返回的值）
     */
    public void releaseLockByKey(String fullKey, String lockValue) {
        if (lockValue == null) {
            return;
        }
        
        // 如果是本地锁，释放本地锁
        if (lockValue.startsWith(LOCAL_LOCK_PREFIX)) {
            releaseLocalLock(fullKey);
            return;
        }
        
        // 释放 Redis 分布式锁
        if (redisTemplate == null) {
            return;
        }
        
        try {
            Long result = redisTemplate.execute(
                new DefaultRedisScript<>(RELEASE_LOCK_SCRIPT, Long.class),
                Collections.singletonList(fullKey),
                lockValue
            );
            
            if (result != null && result == 1) {
                log.debug("Released distributed lock key={}", fullKey);
            } else {
                log.warn("Lock key={} was not held by this instance or already expired", fullKey);
            }
        } catch (Exception e) {
            log.error("Error releasing lock key={}: {}", fullKey, e.getMessage());
        }
    }
    
    /**
     * 释放本地锁
     */
    private void releaseLocalLock(String key) {
        ReentrantLock lock = localLocks.get(key);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.debug("Released local lock key={}", key);
        }
    }
    
    /**
     * 检查扩缩容锁是否存在（用于测试/诊断）
     * 
     * @param serviceId 服务 ID
     * @return true 如果锁存在
     */
    public boolean isLocked(String serviceId) {
        return isLockedByKey(LOCK_PREFIX + serviceId);
    }
    
    /**
     * 检查指定锁键是否存在（用于测试/诊断）
     * 
     * @param fullKey 完整的锁键
     * @return true 如果锁存在
     */
    public boolean isLockedByKey(String fullKey) {
        if (redisTemplate == null) {
            ReentrantLock lock = localLocks.get(fullKey);
            return lock != null && lock.isLocked();
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(fullKey));
    }
}
