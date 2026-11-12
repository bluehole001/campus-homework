package com.campus.homework.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.homework.entity.ClazzNotice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 班级公告 Mapper：继承 BaseMapper 获得单表 CRUD，补充置顶优先的班级公告列表查询。
 * <p>排序规则 is_top DESC, create_time DESC 保证置顶公告永远在前；
 * Batch 3 后该结果整体缓存至 Redis 键 homework:notice:clazz:{clazzId}。</p>
 *
 * @author campus-homework
 */
@Mapper
public interface ClazzNoticeMapper extends BaseMapper<ClazzNotice> {

    /**
     * 查询指定班级的公告列表（置顶在前、新在前，学生端与教师端共用）。
     *
     * @param clazzId 班级ID
     * @return 公告列表
     */
    @Select("SELECT * FROM clazz_notice WHERE clazz_id = #{clazzId} ORDER BY is_top DESC, create_time DESC, id DESC")
    List<ClazzNotice> selectByClazzId(@Param("clazzId") Long clazzId);
}
