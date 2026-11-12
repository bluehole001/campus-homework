package com.campus.homework.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 班级公告实体（对应表 clazz_notice），由任课教师发布、学生查看。
 * <p>is_top = 1 的公告在列表排序时置顶；缓存键 homework:notice:clazz:{clazzId}
 * 将在 Batch 3 接入 Redis 后使用。</p>
 *
 * @author campus-homework
 */
@Data
// 表名锁定为 clazz_notice
@TableName("clazz_notice")
public class ClazzNotice implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 公告主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 目标班级ID，学生仅可查看本班公告 */
    @TableField("clazz_id")
    private Long clazzId;

    /** 发布教师ID，编辑/删除时校验归属防越权 */
    @TableField("teacher_id")
    private Long teacherId;

    /** 公告标题 */
    @TableField("title")
    private String title;

    /** 公告正文（TEXT） */
    @TableField("content")
    private String content;

    /** 是否置顶：0 否 / 1 是，列表查询按 is_top DESC, create_time DESC 排序 */
    @TableField("is_top")
    private Integer isTop;

    /** 发布时间，由 Service 层显式赋值 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
