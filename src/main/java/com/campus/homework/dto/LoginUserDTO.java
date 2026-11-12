package com.campus.homework.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 当前登录用户上下文 DTO：Service 层 IDOR 越权校验的操作者身份载体。
 * <p>Batch 3 接入 Spring Security 后，Controller 从 SecurityContextHolder
 * 组装本对象传入 Service；本批次先以显式参数传递，保证 Service 层的
 * 越权校验逻辑（水平/垂直越权）已完整落地，Batch 3 仅替换取值来源，
 * 不改任何方法签名。</p>
 *
 * @author campus-homework
 */
@Data
public class LoginUserDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 当前登录用户ID（sys_user.id），所有私有数据归属校验的锚点 */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 角色：ROLE_STUDENT / ROLE_TEACHER / ROLE_ADMIN */
    private String role;

    /** 学生所属班级ID（教师/管理员为 null） */
    private Long clazzId;
}
