package com.campus.homework.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 班级信息实体（对应表 clazz_info）。
 * <p>由教务管理员（ROLE_ADMIN）维护，学生账号通过 clazz_id 归属班级；
 * student_count 在学生批量导入或账号增删时由 Service 层同步维护。</p>
 *
 * @author campus-homework
 */
@Data
// 表名锁定为 clazz_info（严禁使用 Reggie 风格命名）
@TableName("clazz_info")
public class ClazzInfo implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 班级主键，数据库自增（IdType.AUTO 与 init.sql 的 AUTO_INCREMENT 对应） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 班级编号，唯一约束 uk_clazz_code，如 CS2023-01 */
    @TableField("clazz_code")
    private String clazzCode;

    /** 班级名称，展示用 */
    @TableField("clazz_name")
    private String clazzName;

    /** 所属院系，如 计算机学院 */
    @TableField("department")
    private String department;

    /** 班级在册学生数，由 Service 层在学生账号增删/CSV 导入时同步 */
    @TableField("student_count")
    private Integer studentCount;

    /** 创建时间，由 Service 层显式赋值（不依赖 MetaObjectHandler） */
    @TableField("create_time")
    private LocalDateTime createTime;
}
