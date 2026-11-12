package com.campus.homework.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 批量导入学生账号的结果 VO（管理端批量导入专用）。
 * <p>导入采用"整批事务"语义：任一行校验失败则整批回滚（一条也不入库），
 * errors 携带逐行失败原因（"第 N 行：原因"），供管理端逐条修正后重新上传；
 * 全部成功时 failCount=0、errors 为空。</p>
 *
 * @author campus-homework
 */
@Data
public class ImportResultVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 文件内数据行总数（不含表头） */
    private int total;

    /** 成功入库行数（整批成功时等于 total） */
    private int successCount;

    /** 校验失败行数（整批回滚时等于 total） */
    private int failCount;

    /** 逐行失败原因（格式"第 N 行：原因"；整批失败时最多保留前 20 条） */
    private List<String> errors = new ArrayList<>();
}
