package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 知识点得分雷达轴 VO（教师端班级看板：按知识点标签聚合本班已批改作业的平均得分率）。
 * <p>avgScoreRate 为百分制平均得分率（0~100），前端 ECharts radar 的 indicator
 * 固定 max=100，本 VO 提供各轴数值；gradedCount 为该知识点下已批改样本数，
 * 供教师判断样本量是否充分（样本过少的轴结论仅供参考）。</p>
 *
 * @author campus-homework
 */
@Data
public class KnowledgeRadarVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 知识点标签（雷达轴名称；未填写知识点的作业不参与聚合） */
    private String knowledgeTag;

    /** 该知识点的平均得分率（百分制，保留1位小数） */
    private BigDecimal avgScoreRate;

    /** 该知识点下已批改样本数（教师评估结论置信度） */
    private Integer gradedCount;
}
