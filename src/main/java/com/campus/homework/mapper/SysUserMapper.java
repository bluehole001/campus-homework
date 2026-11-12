package com.campus.homework.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.homework.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 系统用户 Mapper：继承 BaseMapper 获得单表 CRUD，补充登录认证、
 * 班级名单与在册人数统计三类高频自定义查询。
 * <p>所有自定义 SQL 100% #{} 预编译；role 条件为固定字面量而非 ${} 拼接。</p>
 *
 * @author campus-homework
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按登录用户名精确查询用户（Batch 3 接入 Spring Security 后作为 UserDetailsService 数据源）。
     *
     * @param username 登录用户名（唯一索引 uk_username 命中）
     * @return 用户记录；不存在时返回 null
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} LIMIT 1")
    SysUser selectByUsername(@Param("username") String username);

    /**
     * 查询班级在册正常状态学生名单（教师提交名单、班级排名与提交率分母）。
     *
     * @param clazzId 班级ID
     * @return 学生列表，按学号升序（保证名单页顺序稳定）
     */
    @Select("SELECT * FROM sys_user WHERE clazz_id = #{clazzId} AND role = 'ROLE_STUDENT' AND status = 1 ORDER BY user_no ASC")
    List<SysUser> selectStudentsByClazzId(@Param("clazzId") Long clazzId);

    /**
     * 统计班级在册正常学生人数（同步 clazz_info.student_count、计算提交率与排名基数）。
     *
     * @param clazzId 班级ID
     * @return 在册正常学生人数
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE clazz_id = #{clazzId} AND role = 'ROLE_STUDENT' AND status = 1")
    int countActiveStudents(@Param("clazzId") Long clazzId);
}
