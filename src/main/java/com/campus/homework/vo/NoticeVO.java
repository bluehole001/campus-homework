package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 班级公告出参 VO（学生端公告列表、教师端公告管理共用）。
 * <p>publisherName 为联查派生字段（教师真实姓名），列表页直接展示发布人。</p>
 *
 * @author campus-homework
 */
@Data
public class NoticeVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 公告ID */
    private Long id;

    /** 目标班级ID */
    private Long clazzId;

    /** 班级名称（派生字段） */
    private String clazzName;

    /** 发布教师ID */
    private Long teacherId;

    /** 发布教师姓名（派生字段） */
    private String publisherName;

    /** 公告标题 */
    private String title;

    /** 公告正文 */
    private String content;

    /** 是否置顶：0否/1是 */
    private Integer isTop;

    /** 发布时间 */
    private LocalDateTime createTime;
}
