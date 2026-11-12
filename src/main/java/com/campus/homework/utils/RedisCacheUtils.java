package com.campus.homework.utils;

import com.campus.homework.config.RedisConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Redis 缓存读写工具（Batch 3 统一缓存入口，配合 Constants 中的契约键使用）。
 * <p>三个关键防护设计：</p>
 * <ol>
 *   <li><b>缓存穿透防护</b>：空结果（null 或空列表）也短暂缓存 __NULL__
 *       占位符（TTL 60 秒），同一无效查询在窗口内不再反复打数据库；</li>
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
     * 读缓存列表：命中返回；未命中回源 loader，空结果写 __NULL__ 防穿透。
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
            try {
                List<T> cached = cacheObjectMapper.readValue(raw, typeRef);
                log.debug("缓存命中: key={}", key);
                return cached;
            } catch (Exception e) {
                // 脏数据/类型不匹配（如历史格式）：删除坏键后回源重建
                log.warn("缓存反序列化失败，删除坏键回源: key={} - {}", key, e.getMessage());
                safeDelete(key);
            }
        }
        // 2. 回源加载
        List<T> loaded = loader.get();
        // 3. 写缓存：空结果写 __NULL__（短 TTL），非空写 JSON（正常 TTL）
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
    }

    /**
     * 读缓存对象：命中返回；未命中回源 loader；null 结果写 __NULL__ 防穿透。
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
            try {
                T cached = cacheObjectMapper.readValue(raw, typeRef);
                log.debug("缓存命中: key={}", key);
                return cached;
            } catch (Exception e) {
                log.warn("缓存反序列化失败，删除坏键回源: key={} - {}", key, e.getMessage());
                safeDelete(key);
            }
        }
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
