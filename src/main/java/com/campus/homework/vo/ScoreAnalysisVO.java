package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 作业成绩分析 VO（教师看板与"成绩分析"页共用，Batch 4 起整体缓存至
 * Redis 键 homework:score:analysis:{homeworkId}）。
 * <p>knowledgeTag 直接取自作业本身，作为雷达图的一个统计维度；
 * 分布直方图按 10 分一档共 10 档，前端 ECharts 直接消费。</p>
 *
 * @author campus-homework
 */
@Data
public class ScoreAnalysisVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 作业ID */
    private Long homeworkId;

    /** 作业标题 */
    private String title;

    /** 知识点标签 */
    private String knowledgeTag;

    /** 作业满分 */
    private Integer totalScore;

    /** 平均得分（已批改样本的均值，无样本为 null） */
    private BigDecimal avgScore;

    /** 最高分 */
    private BigDecimal maxScore;

    /** 最低分 */
    private BigDecimal minScore;

    /** 及格率（百分比，>=60% 得分率视为及格；无样本为 null） */
    private BigDecimal passRate;

    /** 提交率（百分比，按时+逾期已交 / 班级在册人数） */
    private BigDecimal submitRate;

    /** 高重复预警人数 */
    private Integer highRepeatCount;

    /** 分数分布：10 分一档的段内人数（下标0=0-10分 … 下标9=90-100分） */
    private List<Integer> scoreBuckets;

    /** 班级排名明细（按得分降序，仅成绩发布后生成） */
    private List<RankItemVO> ranking;

    /**
     * 班级排名单行项。
     */
    @Data
    public static class RankItemVO implements Serializable {

        /** 序列化版本号 */
        private static final long serialVersionUID = 1L;

        /** 名次（从1开始，同分并列由 Service 层按提交时间先后定序） */
        private Integer rank;

        /** 学生ID */
        private Long studentId;

        /** 学号 */
        private String userNo;

        /** 真实姓名 */
        private String realName;

        /** 得分 */
        private BigDecimal score;

        /** 得分率（score/totalScore，百分比，保留1位小数） */
        private BigDecimal scoreRate;
    }
}
