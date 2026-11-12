package com.campus.homework.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 缓存序列化配置（Batch 3）。
 * <p>提供"缓存专用 ObjectMapper"：开启 DefaultTyping（把具体类型写入 JSON
 * 属性），使 List&lt;NoticeVO&gt;、ScoreAnalysisVO 等缓存值读回时能还原为
 * 具体类型而非 LinkedHashMap；注册 JavaTimeModule 并禁用时间戳序列化，
 * 保证 LocalDateTime 字段可安全往返。</p>
 * <p>与 Web 层 ObjectMapper 刻意分离：HTTP 响应 JSON 绝不能携带类型标记
 * （前端 jQuery 按固定字段契约解析），两个 Mapper 各司其职、互不污染。
 * 注意：本 Bean 的存在会使 Boot 的 JacksonAutoConfiguration 因
 * ConditionalOnMissingBean(ObjectMapper.class) 退出，Web 层 Mapper 由
 * JacksonWebConfig 显式补位（@Primary），二者互不干扰。
 * StringRedisTemplate 由 Spring Boot 自动装配直接提供（key/value 均为
 * String 序列化），本类不再重复定义，缓存工具类以 String + 手动 JSON
 * 读写，空值占位符 __NULL__ 因此保持为可直读的字面量。</p>
 *
 * @author campus-homework
 */
// 声明为配置类，提供缓存专用序列化器
@Configuration
public class RedisConfig {

    /** 缓存专用 ObjectMapper 的 Bean 名称（RedisCacheUtils 按此名注入） */
    public static final String BEAN_CACHE_OBJECT_MAPPER = "cacheObjectMapper";

    /**
     * 缓存专用 ObjectMapper（仅供 RedisCacheUtils 使用，不参与 HTTP 序列化）。
     *
     * @return 开启类型标记并支持 JSR-310 时间类型的序列化器
     */
    @Bean(BEAN_CACHE_OBJECT_MAPPER)
    public ObjectMapper cacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // 按字段可见性序列化（VO 均为 Lombok @Data 私有字段 + getter，
        // 显式声明 ANY 可见性避免 getter 命名歧义导致的字段遗漏）
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 注册 JavaTimeModule：LocalDateTime 按 ISO 字符串往返，禁用时间戳数组
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 开启默认类型标记（As.PROPERTY）：读回时还原 List<NoticeVO> 等具体类型；
        // LaissezFaire 校验器放行任意子类型——缓存内容全部来自本系统自有 VO，
        // 不存在外部不可信输入，反序列化安全可控
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}
