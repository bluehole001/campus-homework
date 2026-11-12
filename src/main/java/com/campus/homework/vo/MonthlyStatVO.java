package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 月度提交统计出参 VO（教师看板趋势折线图数据源）。
 * <p>字段与 monthly_submit_stat 表一一对应，avgScore/passRate 在该月
 * 无已发布成绩时为 null，前端折线图自动跳过这些点。</p>
 *
 * @author campus-homework
 */
@Data
public class MonthlyStatVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 统计记录ID */
    private Long id;

    /** 班级ID */
    private Long clazzId;

    /** 班级名称（派生字段，看板多班切换时展示） */
    private String clazzName;

    /** 统计月份（yyyy-MM） */
    private String statMonth;

    /** 当月作业总数 */
    private Integer totalHomeworkNum;

    /** 平均提交率（百分比） */
    private BigDecimal avgSubmitRate;

    /** 平均得分（无已发布成绩为 null） */
    private BigDecimal avgScore;

    /** 及格率（百分比，无已发布成绩为 null） */
    private BigDecimal passRate;

    /** 生成时间 */
    private LocalDateTime createTime;
}
