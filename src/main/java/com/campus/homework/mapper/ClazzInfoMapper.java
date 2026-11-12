package com.campus.homework.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.homework.entity.ClazzInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 班级信息 Mapper：继承 MyBatis-Plus BaseMapper 获得单表 CRUD，
 * 仅补充按班级编号精确查询（唯一索引 uk_clazz_code 命中，用于导入查重与登录归属校验）。
 * <p>铁律：所有自定义 SQL 100% 使用 #{} 预编译占位符。</p>
 *
 * @author campus-homework
 */
@Mapper
public interface ClazzInfoMapper extends BaseMapper<ClazzInfo> {

    /**
     * 按班级编号精确查询（唯一索引直接命中）。
     *
     * @param clazzCode 班级编号（如 CS2023-01）
     * @return 班级记录；不存在时返回 null
     */
    @Select("SELECT * FROM clazz_info WHERE clazz_code = #{clazzCode} LIMIT 1")
    ClazzInfo selectByCode(@Param("clazzCode") String clazzCode);
}
