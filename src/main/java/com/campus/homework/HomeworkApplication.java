package com.campus.homework;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 班级学业管理与作业提交系统（campus-homework）启动类。
 * <p>
 * 技术栈：Spring Boot 2.7.18 + MySQL 8.0 + MyBatis-Plus 3.5.3.1 + Thymeleaf，
 * 后续批次将接入 Spring Security、Redis、RabbitMQ、Spring Task。
 * </p>
 *
 * @author campus-homework
 */
@SpringBootApplication
// 统一扫描 Mapper 接口，免去每个 Mapper 单独标注 @Mapper
@MapperScan("com.campus.homework.mapper")
// 开启 Spring Task 定时调度：逾期标记 / 催交邮件 / 月度聚合三个 @Scheduled 任务生效
@EnableScheduling
public class HomeworkApplication {

    /**
     * 应用入口：启动内嵌 Tomcat 与 Spring 容器。
     *
     * @param args 命令行参数（可传 --spring.profiles.active=prod 切换环境）
     */
    public static void main(String[] args) {
        SpringApplication.run(HomeworkApplication.class, args);
    }
}
