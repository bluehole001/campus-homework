package com.campus.homework.exception;

/**
 * 自定义业务异常基类：承载业务规则校验失败（重复提交、截止已过、参数非法等）。
 * <p>code 沿用 HTTP 语义（400/403/404/500），由 GlobalExceptionHandler 统一
 * 捕获并转换为 Result&lt;T&gt; JSON，Controller 层无需自行 try-catch。</p>
 *
 * @author campus-homework
 */
public class BusinessException extends RuntimeException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 业务状态码（HTTP 语义：400 参数/规则错误、403 越权、404 不存在、500 内部错误） */
    private final Integer code;

    /**
     * 构造默认 500 的业务异常。
     *
     * @param msg 中文错误提示（直接回传前端 toast）
     */
    public BusinessException(String msg) {
        this(500, msg);
    }

    /**
     * 构造指定状态码的业务异常。
     *
     * @param code 业务状态码（HTTP 语义）
     * @param msg  中文错误提示
     */
    public BusinessException(Integer code, String msg) {
        super(msg);
        this.code = code;
    }

    /**
     * 获取业务状态码，供全局异常处理器组装 Result。
     *
     * @return 业务状态码
     */
    public Integer getCode() {
        return code;
    }
}
