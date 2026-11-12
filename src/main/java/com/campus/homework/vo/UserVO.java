package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户账号出参 VO（管理端账号列表/详情共用）。
 * <p>安全红线：绝不包含 password 字段——BCrypt 密文严禁回传前端，
 * 这是 Entity 与 VO 分离的最典型场景。</p>
 *
 * @author campus-homework
 */
@Data
public class UserVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long id;

    /** 学号/工号 */
    private String userNo;

    /** 登录用户名 */
    private String username;

    /** 真实姓名 */
    private String realName;

    /** 所属班级ID（学生有值，师生管理员为 null） */
    private Long clazzId;

    /** 所属班级名称（联查派生字段，管理端列表直接展示） */
    private String clazzName;

    /** 邮箱 */
    private String email;

    /** 角色字符串 */
    private String role;

    /** 状态：1正常/0禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;
}
