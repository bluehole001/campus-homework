package com.campus.homework.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.homework.entity.HomeworkTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 作业任务 Mapper：继承 BaseMapper 获得单表 CRUD，补充班级作业列表查询
 * 与提交人数原子自增两个自定义方法。
 * <p>submit_count 用 UPDATE ... SET submit_count = submit_count + #{delta}
 * 原子自增，避免"读-改-写"并发丢失；全部 SQL 使用 #{} 预编译。</p>
 *
 * @author campus-homework
 */
@Mapper
public interface HomeworkTaskMapper extends BaseMapper<HomeworkTask> {

    /**
     * 查询指定班级的全部作业（按截止时间倒序，学生端与教师端列表共用；
     * Batch 3 后该结果整体缓存至 Redis 键 homework:list:clazz:{clazzId}）。
     *
     * @param clazzId 班级ID
     * @return 作业列表，最近截止的在前
     */
    @Select("SELECT * FROM homework_task WHERE clazz_id = #{clazzId} ORDER BY deadline DESC, id DESC")
    List<HomeworkTask> selectByClazzId(@Param("clazzId") Long clazzId);

    /**
     * 原子增减作业提交人数（新提交 +1；删除提交记录 -1；delta 可为负）。
     *
     * @param id    作业ID
     * @param delta 增减量（+1/-1）
     * @return 受影响行数
     */
    @Update("UPDATE homework_task SET submit_count = submit_count + #{delta} WHERE id = #{id}")
    int addSubmitCount(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 查询"距截止不足 24 小时且成绩未发布"的作业（每日 8:30 催交定时任务候选）。
     * <p>窗口 (NOW, NOW+24h]：已截止的作业不再催交（逾期标记任务接管）；
     * 已发布成绩说明批改已完成、无需催交。INTERVAL 24 HOUR 为固定字面量，
     * 无 ${} 拼接。</p>
     *
     * @return 需要催交的作业列表（按截止时间升序，先截止的先催）
     */
    @Select("SELECT * FROM homework_task WHERE deadline > NOW() "
            + "AND deadline <= DATE_ADD(NOW(), INTERVAL 24 HOUR) AND is_score_published = 0 "
            + "ORDER BY deadline ASC")
    List<HomeworkTask> selectUrgingTasks();

    /**
     * 查询某班级指定月份（按发布时间归属）的全部作业（月度统计定时任务聚合数据源）。
     * <p>归属口径：以 create_time 所在月归属（"当月发布的作业"），与计划书
     * "每月 1 号生成上月统计"的报表口径一致；DATE_FORMAT 参数为 #{} 预编译。</p>
     *
     * @param clazzId   班级ID
     * @param statMonth 统计月份（yyyy-MM）
     * @return 该班当月发布的作业列表
     */
    @Select("SELECT * FROM homework_task WHERE clazz_id = #{clazzId} "
            + "AND DATE_FORMAT(create_time, '%Y-%m') = #{statMonth} ORDER BY id ASC")
    List<HomeworkTask> selectTasksInMonth(@Param("clazzId") Long clazzId, @Param("statMonth") String statMonth);
}
