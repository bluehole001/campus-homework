package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 班级信息出参 VO（管理端列表/详情共用）。
 * <p>与 Entity 分离的意义：出参可追加派生字段（如教师数），且不暴露表结构细节。</p>
 *
 * @author campus-homework
 */
@Data
public class ClazzVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 班级ID */
    private Long id;

    /** 班级编号 */
    private String clazzCode;

    /** 班级名称 */
    private String clazzName;

    /** 所属院系 */
    private String department;

    /** 在册学生数（实时统计，可能与表内冗余字段一致或修正） */
    private Integer studentCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
