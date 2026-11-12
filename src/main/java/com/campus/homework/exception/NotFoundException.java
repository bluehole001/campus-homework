package com.campus.homework.exception;

/**
 * 资源不存在异常（404）：按 ID 查询不到记录、下载文件已丢失时抛出。
 * <p>与通用 BusinessException 区分的意义：全局处理器可针对性返回 404 状态，
 * 前端据此跳转错误页而非仅弹 toast。</p>
 *
 * @author campus-homework
 */
public class NotFoundException extends BusinessException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造 404 异常，默认提示"资源不存在"。
     *
     * @param msg 具体中文提示（如"作业不存在或已被删除"）
     */
    public NotFoundException(String msg) {
        super(404, msg);
    }
}
