package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 通用分页结果 VO（Service 层把 MyBatis-Plus Page 转换为本 VO 后返回，
 * 避免持久层分页对象直接泄漏到 Controller/前端）。
 *
 * @param <T> 列表元素类型（具体 VO）
 * @author campus-homework
 */
@Data
public class PageVO<T> implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 当前页码（从1开始） */
    private Long pageNum;

    /** 每页条数 */
    private Long pageSize;

    /** 总记录数（前端计算总页数用） */
    private Long total;

    /** 总页数 */
    private Long pages;

    /** 当前页数据（已脱敏的业务 VO 列表） */
    private List<T> records;
}
