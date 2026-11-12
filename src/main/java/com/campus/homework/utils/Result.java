package com.campus.homework.utils;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一 JSON 响应包装（替代 Reggie 风格的 R.java，遵循反抄袭红线）。
 * <p>code 沿用 HTTP 状态码语义：200 成功、400 参数错误、401 未认证、
 * 403 越权、404 资源不存在、500 服务器内部错误；前端 jQuery 统一按
 * code === 200 判定成功，非 200 时 toast 提示 msg。</p>
 *
 * @param <T> 业务数据类型
 * @author campus-homework
 */
@Data
public class Result<T> implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 业务状态码（HTTP 语义：200/400/401/403/404/500） */
    private Integer code;

    /** 提示信息（成功为 ok，失败为可读的中文错误说明） */
    private String msg;

    /** 业务数据（失败时为 null） */
    private T data;

    /**
     * 无数据成功响应（用于写操作：新增/修改/删除）。
     *
     * @param <T> 占位类型
     * @return code=200、msg=ok 的成功结果
     */
    public static <T> Result<T> success() {
        return build(200, "ok", null);
    }

    /**
     * 携带数据的成功响应（用于读操作：查询/看板数据）。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return code=200、msg=ok 的成功结果
     */
    public static <T> Result<T> success(T data) {
        return build(200, "ok", data);
    }

    /**
     * 自定义提示的成功响应（少数需要回传提示文案的场景）。
     *
     * @param msg  提示文案
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return code=200 的成功结果
     */
    public static <T> Result<T> success(String msg, T data) {
        return build(200, msg, data);
    }

    /**
     * 通用失败响应（默认 500，由调用方给出具体状态码与中文提示）。
     *
     * @param code 业务状态码（HTTP 语义）
     * @param msg  中文错误提示
     * @param <T>  占位类型
     * @return data=null 的失败结果
     */
    public static <T> Result<T> error(Integer code, String msg) {
        return build(code, msg, null);
    }

    /**
     * 参数错误快捷方法（400，JSR-303 校验失败与业务参数非法共用）。
     *
     * @param msg 中文错误提示
     * @param <T> 占位类型
     * @return code=400 的失败结果
     */
    public static <T> Result<T> error(String msg) {
        return build(400, msg, null);
    }

    /**
     * 私有构造，统一通过静态工厂方法创建，避免状态码与提示不一致。
     *
     * @param code 业务状态码
     * @param msg  提示信息
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return 组装完成的响应对象
     */
    private static <T> Result<T> build(Integer code, String msg, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        return result;
    }
}
