package com.campus.homework.exception;

import com.campus.homework.utils.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器：把各类异常统一转换为 Result&lt;T&gt; JSON，屏蔽堆栈细节。
 * <p>注意：本 Advice 仅拦截 @RestController（JSON 接口）抛出的异常；
 * Thymeleaf 页面路由的异常由 Spring Boot 默认错误页/自定义错误页兜底（Batch 5）。</p>
 *
 * @author campus-homework
 */
@Slf4j
// 仅处理 REST 接口异常，统一返回 JSON
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理自定义业务异常（含 403/404 子类）：按异常自带状态码回传。
     *
     * @param e 业务异常
     * @return 携带异常码与中文提示的失败 Result
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        // 业务异常属于预期内失败，仅记录 WARN，不打全堆栈
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理 @Valid 校验失败（@RequestBody 场景，如提交作业 DTO 字段非法）。
     *
     * @param e 校验异常，含各字段的违规信息
     * @return 400，msg 为"字段: 提示"拼接的中文说明
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        // 把所有字段错误拼成一条可读提示，前端直接 toast
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", msg);
        return Result.error(400, msg.isEmpty() ? "请求参数校验失败" : msg);
    }

    /**
     * 处理表单绑定校验失败（multipart 表单 + @Valid 模型的场景）。
     *
     * @param e 绑定异常
     * @return 400，msg 为字段错误拼接说明
     */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        // 与 MethodArgumentNotValid 同源逻辑：逐字段拼接中文提示
        String msg = e.getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("表单绑定校验失败: {}", msg);
        return Result.error(400, msg.isEmpty() ? "请求参数校验失败" : msg);
    }

    /**
     * 处理方法参数级校验失败（@RequestParam 上的 @Min/@NotBlank 等注解）。
     *
     * @param e 约束违反异常
     * @return 400，msg 为各约束消息拼接
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        // 参数级约束逐条拼接，保持与表单校验一致的提示风格
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数约束校验失败: {}", msg);
        return Result.error(400, msg.isEmpty() ? "请求参数不合法" : msg);
    }

    /**
     * 处理缺少必填请求参数（如分页接口未传 pageNum）。
     *
     * @param e 缺参异常
     * @return 400，提示缺失的参数名
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数: {}", e.getParameterName());
        return Result.error(400, "缺少必要参数: " + e.getParameterName());
    }

    /**
     * 处理参数类型不匹配（如 /task/abc 中 id 应为数字）。
     *
     * @param e 类型不匹配异常
     * @return 400，提示参数名与期望类型
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: name={}, value={}", e.getName(), e.getValue());
        return Result.error(400, "参数类型错误: " + e.getName());
    }

    /**
     * 处理请求体不可读（JSON 格式错误、Content-Type 不正确）。
     *
     * @param e 报文不可读异常
     * @return 400，提示请求体格式问题
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体不可读: {}", e.getMessage());
        return Result.error(400, "请求体格式错误，请检查JSON格式与Content-Type");
    }

    /**
     * 处理请求方法不支持（如 GET 接口被 POST 调用）。
     *
     * @param e 方法不支持异常
     * @return 405 语义（以 400 段回传，msg 说明允许的方法）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMethod());
        return Result.error(405, "不支持的请求方法: " + e.getMethod());
    }

    /**
     * 处理 Spring Security 方法级鉴权失败（@PreAuthorize 拒绝）。
     *
     * @param e 访问拒绝异常
     * @return 403，提示当前角色无权访问该接口
     */
    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("鉴权拒绝: {}", e.getMessage());
        return Result.error(403, "当前角色无权访问该功能");
    }

    /**
     * 处理上传文件超过 50MB 上限（spring.servlet.multipart.max-file-size）。
     *
     * @param e 超限异常
     * @return 400，提示单文件不得超过 50MB
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过上限: {}", e.getMessage());
        return Result.error(400, "上传文件超过50MB上限，请压缩后重试");
    }

    /**
     * 处理唯一索引冲突（如重复学号导入、同一作业重复提交并发插入撞 uk_hw_stu）。
     *
     * @param e 重复键异常
     * @return 400，提示数据重复
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMessage());
        return Result.error(400, "数据重复，请检查学号/用户名或提交记录是否已存在");
    }

    /**
     * 兜底异常处理：未预见的系统异常统一 500，堆栈仅落日志不外抛。
     *
     * @param e 任意异常
     * @return 500，通用中文提示
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        // 未预期异常打完整堆栈便于排障，对外提示保持通用避免泄露内部细节
        log.error("系统异常", e);
        return Result.error(500, "服务器内部错误，请稍后重试");
    }
}
