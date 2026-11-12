package com.campus.homework.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Web 层 ObjectMapper 显式装配（登录 400 问题修复）。
 * <p>背景：RedisConfig 中的缓存专用 ObjectMapper（Bean 名 cacheObjectMapper，
 * 开启 DefaultTyping 类型标记）导致 Boot 的 JacksonAutoConfiguration 因
 * {@code @ConditionalOnMissingBean(ObjectMapper.class)} 整体退出，MVC 的
 * MappingJackson2HttpMessageConverter 被迫注入该缓存 Mapper——它要求输入
 * JSON 携带 "@class" 类型标记，浏览器提交的普通 JSON（如登录表单）因此
 * 解析失败，表现为"请求体格式错误，请检查JSON格式与Content-Type"。</p>
 * <p>本类显式提供标注 @Primary 的 Web Mapper：由 Boot 自动装配的
 * Jackson2ObjectMapperBuilder 构建（spring.jackson.* 配置如日期格式、时区
 * 照常生效），行为与自动配置原本生成的完全一致，且不开启类型标记。
 * 未限定注入点（SecurityConfig、MVC 消息转换器）一律解析到 @Primary 的
 * 本 Bean；RedisCacheUtils 仍按 @Qualifier(cacheObjectMapper) 使用缓存
 * Mapper，两个序列化器恢复"各司其职、互不污染"的设计契约。</p>
 *
 * @author campus-homework
 */
// 声明为配置类，补充 Web 层序列化器 Bean
@Configuration
public class JacksonWebConfig {

    /**
     * Web 层专用 ObjectMapper（HTTP 请求/响应 JSON 序列化，无类型标记）。
     * <p>刻意标注 @Primary：容器内存在两个 ObjectMapper Bean（本类与
     * RedisConfig.cacheObjectMapper）时，未加 @Qualifier 的注入点必须
     * 唯一地解析到本 Bean，保证 @RequestBody 反序列化按标准 JSON 契约
     * 工作（不要求 "@class" 属性）。</p>
     *
     * @param builder Boot 自动装配的构建器（已应用 spring.jackson.* 全局配置）
     * @return 与 Boot 自动配置行为一致的 Web Mapper
     */
    @Bean
    // 主 Bean：MVC 消息转换器与 SecurityConfig 的未限定注入一律指向本 Mapper
    @Primary
    public ObjectMapper webObjectMapper(Jackson2ObjectMapperBuilder builder) {
        // createXmlMapper(false)：仅 JSON；builder 已按 application.yml 的
        // spring.jackson.date-format/time-zone 完成定制，这里直接构建成品
        return builder.createXmlMapper(false).build();
    }
}
