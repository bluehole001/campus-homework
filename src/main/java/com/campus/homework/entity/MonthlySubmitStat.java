package com.campus.homework.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月度提交统计实体（对应表 monthly_submit_stat），每月 1 号 00:00 由
 * Spring Task 定时任务生成（uk_stat_clazz_month 保证同班同月仅一条，重跑幂等）。
 * <p>本批次仅提供基础 CRUD 读写能力，统计口径的计算逻辑在 Batch 4 实现。</p>
 *
 * @author campus-homework
 */
@Data
// 表名锁定为 monthly_submit_stat
@TableName("monthly_submit_stat")
public class MonthlySubmitStat implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 统计主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 班级ID，与 stat_month 构成联合唯一 uk_stat_clazz_month */
    @TableField("clazz_id")
    private Long clazzId;

    /** 统计月份，格式 yyyy-MM（如 2026-10） */
    @TableField("stat_month")
    private String statMonth;

    /** 当月作业总数 */
    @TableField("total_homework_num")
    private Integer totalHomeworkNum;

    /** 平均提交率（百分比，保留2位小数） */
    @TableField("avg_submit_rate")
    private BigDecimal avgSubmitRate;

    /** 平均得分（保留1位小数，当月无已发布成绩时为 null） */
    @TableField("avg_score")
    private BigDecimal avgScore;

    /** 及格率（百分比，保留2位小数，当月无已发布成绩时为 null） */
    @TableField("pass_rate")
    private BigDecimal passRate;

    /** 生成时间，由定时任务赋值 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
