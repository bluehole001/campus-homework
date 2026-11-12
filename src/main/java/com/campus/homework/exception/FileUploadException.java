package com.campus.homework.exception;

/**
 * 文件上传/下载异常（400）：格式不在白名单、超过大小上限、路径穿越攻击、
 * 落盘 IO 失败等场景由 FileUploadUtils 抛出。
 *
 * @author campus-homework
 */
public class FileUploadException extends BusinessException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造 400 的文件操作异常。
     *
     * @param msg 具体中文提示（如"不支持的文件格式，仅允许 doc/docx/pdf/zip 等")
     */
    public FileUploadException(String msg) {
        super(400, msg);
    }
}
