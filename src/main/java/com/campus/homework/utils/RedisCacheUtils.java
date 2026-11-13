package com.campus.homework.utils;

import com.campus.homework.config.RedisConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis 缓存读写工具（Batch 3 统一缓存入口，配合 Constants 中的契约键使用）。
 * <p>四个关键防护设计：</p>
 * <ol>
 *   <li><b>缓存穿透防护</b>：空结果（null 或空列表）也短暂缓存 __NULL__
 *       占位符（TTL 60 秒），同一无效查询在窗口内不再反复打数据库；</li>
 *   <li><b>缓存击穿防护</b>：热键过期瞬间的并发未命中由 SETNX 互斥锁收口——
 *       抢锁成功者是唯一回源者，其余请求自旋等待缓存重建（50ms 一轮、最长
 *       3 秒），锁带 10 秒 TTL 且释放前比对令牌，持有者宕机也不死锁；
 *       Redis 故障时抢锁按"成功"处理直接回源，防护逻辑自身绝不新增故障点；</li>
 *   <li><b>事务感知失效</b>：写路径的缓存失效一律注册到事务 afterCommit
 *       回调——避免"先删缓存、事务随后回滚"导致新缓存缺失或旧数据回填；
 *       无事务上下文时立即执行（读路径本身无事务）；</li>
 *   <li><b>故障降级</b>：所有 Redis 读写均 try-catch，连接异常时仅 WARN
 *       并直接回源数据库——缓存是加速器不是依赖项，Redis 不可用绝不能
 *       阻断业务主流程。</li>
 * </ol>
 * <p>序列化：value 为手动 JSON 字符串（StringRedisTemplate + 缓存专用
 * ObjectMapper），空值占位符保持为可直读的字面量 __NULL__；类型信息随
 * JSON 属性写入，读回还原为具体 VO 类型（见 RedisConfig）。</p>
 *
 * @author campus-homework
 */
@Slf4j
// Spring 组件，供 Service 层构造器注入
@Component
public class RedisCacheUtils {

    /** String 序列化模板（Boot 自动装配，key/value 均为 String） */
    private final StringRedisTemplate redisTemplate;

    /** 缓存专用序列化器（开启类型标记，与 Web 层 ObjectMapper 隔离） */
    private final ObjectMapper cacheObjectMapper;

    /** 虚拟锁令牌前缀：Redis 故障时"抢锁按成功处理"的标记，此类令牌无需释放（Redis 恢复后随 TTL 自然消失） */
    private static final String LOCK_NO_REDIS_PREFIX = "NOREDIS:";

    /**
     * Lua 原子解锁脚本：仅当锁值与自己的令牌一致时才删除——防止"持有者超时
     * 后锁已被他人重新抢得"时误删他人的锁（get==token 则 del，单命令原子执行）。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 构造器注入：显式 @Qualifier 锁定缓存专用 Mapper（RedisConfig 中定义），
     * 避免与 Web 层自动装配的 ObjectMapper 产生注入歧义。
     *
     * @param redisTemplate     String 序列化模板
     * @param cacheObjectMapper 缓存专用 ObjectMapper（Bean 名 cacheObjectMapper）
     */
    public RedisCacheUtils(StringRedisTemplate redisTemplate,
                           @Qualifier(RedisConfig.BEAN_CACHE_OBJECT_MAPPER) ObjectMapper cacheObjectMapper) {
        this.redisTemplate = redisTemplate;
        this.cacheObjectMapper = cacheObjectMapper;
    }

    /**
     * 读缓存列表：命中返回；未命中回源 loader，空结果写 __NULL__ 防穿透；
     * 并发未命中由 SETNX 互斥锁收口防击穿（仅持锁者回源，其余等缓存重建）。
     * <p>调用方契约：loader 返回 null 或空列表时，本方法仍返回空列表
     * （而非 null），保证 Service 层"空列表是合法业务结果"的语义不变。</p>
     *
     * @param key      完整缓存键（由调用方按 Constants 契约前缀拼装）
     * @param ttl      正常结果过期秒数（Constants.CACHE_TTL_*）
     * @param typeRef  列表元素类型引用（如 new TypeReference&lt;List&lt;NoticeVO&gt;&gt;(){}）
     * @param loader   未命中时的回源加载器
     * @param <T>      列表元素类型
     * @return 缓存命中或回源得到的列表；回源为空时为空列表
     */
    public <T> List<T> listOrLoad(String key, long ttl, TypeReference<List<T>> typeRef, Supplier<List<T>> loader) {
        // 1. 尝试读缓存（读失败降级回源，不阻断业务）
        String raw = safeGet(key);
        if (raw != null) {
            if (Constants.CACHE_NULL_VALUE.equals(raw)) {
                // 空值占位命中：直接返回空列表（穿透防护生效，不打库）
                return new ArrayList<>();
            }
            List<T> cached = parseListOrDiscard(key, raw, typeRef);
            if (cached != null) {
                log.debug("缓存命中: key={}", key);
                return cached;
            }
            // 脏数据已被删除坏键：继续走未命中流程重建
        }
        // 2. 未命中：抢互斥锁收口并发回源（击穿防护）
        String lockKey = Constants.CACHE_LOCK_PREFIX + key;
        String token = tryAcquireLock(lockKey);
        if (token == null) {
            // 未抢到锁（确有他人在回源写缓存）：自旋等待缓存重建
            String rebuilt = waitForCacheRebuild(key);
            if (rebuilt != null) {
                if (Constants.CACHE_NULL_VALUE.equals(rebuilt)) {
                    return new ArrayList<>();
                }
                List<T> cached = parseListOrDiscard(key, rebuilt, typeRef);
                if (cached != null) {
                    return cached;
                }
            }
            // 等待超时（或重建数据也是脏数据）：放弃互斥照常回源——
            // 宁可多查一次数据库，绝不让请求在锁上无限排队
        }
        try {
            // 3. 回源加载（持锁或 Redis 故障虚拟持锁时执行）
            List<T> loaded = loader.get();
            // 4. 写缓存：空结果写 __NULL__（短 TTL 防穿透），非空写 JSON（正常 TTL）
            if (loaded == null || loaded.isEmpty()) {
                safeSet(key, Constants.CACHE_NULL_VALUE, Constants.CACHE_TTL_NULL);
                return loaded == null ? new ArrayList<>() : loaded;
            }
            try {
                safeSet(key, cacheObjectMapper.writeValueAsString(loaded), ttl);
            } catch (Exception e) {
                log.warn("缓存序列化失败（本次不缓存）: key={} - {}", key, e.getMessage());
            }
            return loaded;
        } finally {
            // 无论回源成败都尝试释放锁（令牌校验防误删；失败靠 TTL 自动过期兜底）
            releaseLock(lockKey, token);
        }
    }

    /**
     * 读缓存对象：命中返回；未命中回源 loader；null 结果写 __NULL__ 防穿透；
     * 并发未命中由 SETNX 互斥锁收口防击穿（语义与 listOrLoad 一致）。
     *
     * @param key       完整缓存键
     * @param ttl       正常结果过期秒数
     * @param typeRef   目标类型引用
     * @param loader    未命中时的回源加载器
     * @param cacheNull 是否缓存 null 结果（业务上"不存在"需要防穿透时传 true）
     * @param <T>       目标类型
     * @return 缓存命中或回源结果；回源为 null 时返回 null
     */
    public <T> T objectOrLoad(String key, long ttl, TypeReference<T> typeRef, Supplier<T> loader, boolean cacheNull) {
        String raw = safeGet(key);
        if (raw != null) {
            if (Constants.CACHE_NULL_VALUE.equals(raw)) {
                return null;
            }
            T cached = parseObjectOrDiscard(key, raw, typeRef);
            if (cached != null) {
                log.debug("缓存命中: key={}", key);
                return cached;
            }
        }
        String lockKey = Constants.CACHE_LOCK_PREFIX + key;
        String token = tryAcquireLock(lockKey);
        if (token == null) {
            String rebuilt = waitForCacheRebuild(key);
            if (rebuilt != null) {
                if (Constants.CACHE_NULL_VALUE.equals(rebuilt)) {
                    // 他人回源确认"业务上不存在"：命中空值占位，返回 null 不打库
                    return null;
                }
                T cached = parseObjectOrDiscard(key, rebuilt, typeRef);
                if (cached != null) {
                    return cached;
                }
            }
        }
        try {
            T loaded = loader.get();
            if (loaded == null) {
                if (cacheNull) {
                    safeSet(key, Constants.CACHE_NULL_VALUE, Constants.CACHE_TTL_NULL);
                }
                return null;
            }
            try {
                safeSet(key, cacheObjectMapper.writeValueAsString(loaded), ttl);
            } catch (Exception e) {
                log.warn("缓存序列化失败（本次不缓存）: key={} - {}", key, e.getMessage());
            }
            return loaded;
        } finally {
            releaseLock(lockKey, token);
        }
    }

    /**
     * 失效单个缓存键（事务感知：注册到 afterCommit，提交后才真正删除）。
     *
     * @param key 完整缓存键
     */
    public void evict(String key) {
        afterCommitOrNow(() -> safeDelete(key));
    }

    /**
     * 按键模式批量失效（SCAN 游标遍历，避免 KEYS 命令阻塞 Redis；
     * 事务感知：提交后才执行）。典型用法：发布成绩后清理
     * homework:score:stu:{hwId}:* 下全部个人成绩缓存。
     *
     * @param pattern Redis 键模式（如 Constants.CACHE_SCORE_STUDENT + hwId + ":*"）
     */
    public void evictByPattern(String pattern) {
        afterCommitOrNow(() -> safeScanDelete(pattern));
    }

    /**
     * 在事务提交后执行失效；当前无事务同步时立即执行（读路径场景）。
     *
     * @param action 失效动作（内部已做异常防护）
     */
    private void afterCommitOrNow(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 写路径在 @Transactional 内调用：等数据真正落库后才删缓存，
            // 回滚则不删（旧缓存仍然有效，天然正确）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // ==================== 缓存击穿防护（互斥锁回源）辅助方法 ====================

    /**
     * 尝试抢占回源互斥锁：{@code SET lockKey token NX EX 10}。
     * <p>返回值三态语义：非 null 令牌 = 抢锁成功（唯一回源者）；null = 锁被
     * 他人持有（确有并发请求正在回源写缓存，调用方应等待重建）；Redis 读写
     * 异常时返回"虚拟令牌"（NOREDIS: 前缀）按抢锁成功处理直接回源——
     * Redis 故障降级路径下宁可放行打库也绝不让防护逻辑本身阻断业务，
     * 虚拟令牌不参与 Lua 解锁（Redis 恢复后随 TTL 自然消失）。</p>
     *
     * @param lockKey 锁键（homework:lock:{缓存键}）
     * @return 持有令牌（含虚拟令牌）；未抢到返回 null
     */
    private String tryAcquireLock(String lockKey) {
        try {
            // UUID 令牌保证解锁时"持有者比对"可判定，杜绝误删他人的锁
            String token = UUID.randomUUID().toString();
            Boolean won = redisTemplate.opsForValue().setIfAbsent(
                    lockKey, token, java.time.Duration.ofSeconds(Constants.CACHE_LOCK_TTL));
            return Boolean.TRUE.equals(won) ? token : null;
        } catch (Exception e) {
            // 故障降级：无法判断锁状态时按"抢到"放行回源，仅记录 WARN
            log.warn("Redis 抢锁失败（按直接回源处理）: lock={} - {}", lockKey, e.getMessage());
            return LOCK_NO_REDIS_PREFIX + UUID.randomUUID();
        }
    }

    /**
     * 自旋等待持锁者回源后写回的缓存重建：每 50ms 轮询一次，最长 3 秒。
     * <p>轮询读失败按"未重建"继续等待（最终走超时照常回源，不抛异常）；
     * 提前读到缓存（正常 JSON 或 __NULL__ 均算重建完成）即返回原始串，
     * 由调用方负责反序列化。</p>
     *
     * @param key 缓存键（非锁键）
     * @return 重建后的缓存原始串；等待超时仍未重建返回 null
     */
    private String waitForCacheRebuild(String key) {
        long deadline = System.currentTimeMillis() + Constants.CACHE_LOCK_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(Constants.CACHE_LOCK_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                // 被中断：归还中断标志后立即放弃等待走回源，绝不吞中断信号
                Thread.currentThread().interrupt();
                return null;
            }
            String raw = safeGet(key);
            if (raw != null) {
                return raw;
            }
        }
        log.debug("等待缓存重建超时，照常回源: key={}", key);
        return null;
    }

    /**
     * 释放回源互斥锁：执行 Lua"get==token 则 del"原子解锁，防止误删
     * "持有者超时后锁被他人重新抢得"的新锁；虚拟令牌（NOREDIS:）直接跳过
     * （其对应锁并不存在）。释放失败仅 WARN——锁已在 10 秒 TTL 覆盖下，
     * 最迟自动过期，不会形成死锁。
     *
     * @param lockKey 锁键
     * @param token   抢锁时获得的令牌（null 表示从未持锁，直接跳过）
     */
    private void releaseLock(String lockKey, String token) {
        if (token == null || token.startsWith(LOCK_NO_REDIS_PREFIX)) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (Exception e) {
            log.warn("Redis 解锁失败（锁将随 TTL 自动过期）: lock={} - {}", lockKey, e.getMessage());
        }
    }

    /**
     * 反序列化缓存列表：成功返回数据；脏数据/类型不匹配时删除坏键并返回
     * null（调用方据此走回源重建），反序列化自身失败不外抛。
     *
     * @param key     缓存键（日志与坏键删除用）
     * @param raw     缓存原始 JSON
     * @param typeRef 列表类型引用
     * @param <T>     元素类型
     * @return 反序列化结果；失败返回 null
     */
    private <T> List<T> parseListOrDiscard(String key, String raw, TypeReference<List<T>> typeRef) {
        try {
            return cacheObjectMapper.readValue(raw, typeRef);
        } catch (Exception e) {
            log.warn("缓存反序列化失败，删除坏键回源: key={} - {}", key, e.getMessage());
            safeDelete(key);
            return null;
        }
    }

    /**
     * 反序列化缓存对象：语义与 parseListOrDiscard 一致（删坏键返回 null）。
     *
     * @param key     缓存键
     * @param raw     缓存原始 JSON
     * @param typeRef 目标类型引用
     * @param <T>     目标类型
     * @return 反序列化结果；失败返回 null
     */
    private <T> T parseObjectOrDiscard(String key, String raw, TypeReference<T> typeRef) {
        try {
            return cacheObjectMapper.readValue(raw, typeRef);
        } catch (Exception e) {
            log.warn("缓存反序列化失败，删除坏键回源: key={} - {}", key, e.getMessage());
            safeDelete(key);
            return null;
        }
    }

    /**
     * 安全读：Redis 异常时返回 null（视为未命中，降级回源）。
     *
     * @param key 缓存键
     * @return 缓存原始字符串；未命中或读失败返回 null
     */
    private String safeGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis 读失败（降级回源）: key={} - {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 安全写：失败仅 WARN，不阻断业务。
     *
     * @param key   缓存键
     * @param value 缓存值（JSON 或 __NULL__）
     * @param ttl   过期秒数
     */
    private void safeSet(String key, String value, long ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, java.time.Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("Redis 写失败（本次不缓存）: key={} - {}", key, e.getMessage());
        }
    }

    /**
     * 安全删除：失败仅 WARN（TTL 到期后坏键自然过期，影响有限）。
     *
     * @param key 缓存键
     */
    private void safeDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis 删除失败（等待 TTL 自然过期）: key={} - {}", key, e.getMessage());
        }
    }

    /**
     * SCAN 游标批量删除：count 200 分批，逐条删除，游标自动关闭。
     * <p>注意：Spring Data Redis 的 Cursor.close() 覆盖了 Closeable.close()
     * 并去掉了 throws IOException，因此 try-with-resources 主体不会抛出受检
     * 异常，无需（也不能）单独 catch IOException——外层 try-catch(Exception)
     * 已统一兜底所有 Redis 异常。</p>
     *
     * @param pattern 键模式
     */
    private void safeScanDelete(String pattern) {
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                // Cursor 实现 Closeable，try-with-resources 保证游标归还连接
                try (var cursor = connection.scan(ScanOptions.scanOptions().match(pattern).count(200).build())) {
                    while (cursor.hasNext()) {
                        connection.del(cursor.next());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Redis 批量删除失败（等待 TTL 自然过期）: pattern={} - {}", pattern, e.getMessage());
        }
    }
}
