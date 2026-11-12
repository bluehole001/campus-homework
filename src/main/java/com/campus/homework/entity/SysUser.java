package com.campus.homework.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统用户实体（对应表 sys_user），承载学生/教师/管理员三级角色账号。
 * <p>password 字段存储 BCryptPasswordEncoder 生成的密文，严禁明文入库；
 * role 取值限定为 ROLE_STUDENT / ROLE_TEACHER / ROLE_ADMIN，与 Spring Security 角色约定一致。</p>
 *
 * @author campus-homework
 */
@Data
// 表名锁定为 sys_user
@TableName("sys_user")
public class SysUser implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 用户主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 学号/工号，唯一约束 uk_user_no，CSV 批量导入学生时作为去重依据 */
    @TableField("user_no")
    private String userNo;

    /** 登录用户名，唯一约束 uk_username，Spring Security 认证主体 */
    @TableField("username")
    private String username;

    /** BCrypt 密文密码（明文统一 123456），出参时通过 VO 剔除，严禁回传前端 */
    @TableField("password")
    private String password;

    /** 真实姓名，用于名单与排名展示 */
    @TableField("real_name")
    private String realName;

    /** 所属班级ID：学生必填；教师与管理员为 null（教师跨班授课） */
    @TableField("clazz_id")
    private Long clazzId;

    /** 邮箱（必填），用于催交邮件与成绩发布通知 */
    @TableField("email")
    private String email;

    /** 角色字符串：ROLE_STUDENT / ROLE_TEACHER / ROLE_ADMIN */
    @TableField("role")
    private String role;

    /** 账号状态：1 正常 / 0 禁用（禁用账号登录时被拦截） */
    @TableField("status")
    private Integer status;

    /** 创建时间，由 Service 层显式赋值 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
