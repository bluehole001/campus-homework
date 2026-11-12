package com.campus.homework.exception;

/**
 * 越权/权限不足异常（403）：IDOR 校验失败时由 Service 层抛出。
 * <p>典型场景：学生查看他人提交、学生查看未发布成绩、教师编辑非本人发布的
 * 作业、非管理员执行班级维护。水平越权与垂直越权统一用本异常表达。</p>
 *
 * @author campus-homework
 */
public class ForbiddenException extends BusinessException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造 403 异常。
     *
     * @param msg 具体中文提示（如"只能查看自己的作业提交"）
     */
    public ForbiddenException(String msg) {
        super(403, msg);
    }
}
