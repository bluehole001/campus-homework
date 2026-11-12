package com.campus.homework.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.io.Serializable;

/**
 * 通用分页查询入参 DTO（作业列表、提交名单、账号列表共用）。
 * <p>页码从 1 开始；pageSize 上限 100 与 MybatisPlusConfig 的 maxLimit 一致，
 * 双保险防止前端传入超大页容量拖垮数据库。</p>
 *
 * @author campus-homework
 */
@Data
public class PageQueryDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 页码（从1开始，缺省1） */
    @Min(value = 1, message = "页码最小为1")
    private Long pageNum = 1L;

    /** 每页条数（1-100，缺省10） */
    @Min(value = 1, message = "每页至少1条")
    @Max(value = 100, message = "每页最多100条")
    private Long pageSize = 10L;

    /** 关键字（作业标题/用户名等模糊检索，可空；由 Service 层拼装 like，100% #{} 传参） */
    private String keyword;
}
