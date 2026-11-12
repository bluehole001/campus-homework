package com.campus.homework.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类：注册分页插件（Service 层分页查询依赖它生效）。
 * <p>说明：Controller 严禁拼装 QueryWrapper，分页能力由 ServiceImpl 使用
 * Page&lt;T&gt; + LambdaQueryWrapper 完成，本类仅提供底层插件支持。</p>
 *
 * @author campus-homework
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 拦截器：MySQL 分页插件（limit/offset 由插件自动生成，
     * 防止手写分页 SQL 注入风险）。
     *
     * @return 配置完成的插件链
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 指定 MySQL 方言；单页最大条数 100 与 Constants.MAX_PAGE_SIZE 呼应，防全表拖库
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(100L);
        // 溢出处理：页码超出总页数时返回空页而非回到首页，语义更明确
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
