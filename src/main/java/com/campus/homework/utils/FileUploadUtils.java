package com.campus.homework.utils;

import com.campus.homework.exception.FileUploadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 安全文件上传下载工具（Spring 组件，供 Service 层注入使用）。
 * <p>安全设计要点：</p>
 * <ol>
 *   <li>扩展名白名单校验：仅允许作业/参考附件常见格式，杜绝 .exe/.js 等可执行载荷；</li>
 *   <li>大小双重校验：应用层按配置上限（默认 50MB）拦截，容器层 multipart 上限兜底；</li>
 *   <li>UUID 重命名落盘：彻底丢弃用户原始文件名参与路径拼接，杜绝路径穿越（../）与文件名注入；</li>
 *   <li>下载时二次校验：相对路径必须位于上传根目录内且文件真实存在，防止越权读任意文件。</li>
 * </ol>
 *
 * @author campus-homework
 */
@Slf4j
@Component
public class FileUploadUtils {

    /** 允许上传的扩展名白名单（小写，作业附件 + 教师参考附件所需格式） */
    private static final List<String> ALLOWED_EXT = Arrays.asList(
            "doc", "docx", "pdf", "zip", "rar", "txt", "md", "png", "jpg", "jpeg");

    /** 单文件大小上限：50MB（与 application.yml 的 multipart 配置保持一致，应用层提前拦截给出友好提示） */
    private static final long MAX_SIZE = 50L * 1024 * 1024;

    /** 附件存储根目录（dev 为 ./upload，prod 为 /data/homework/upload，由配置注入） */
    private final Path uploadRoot;

    /**
     * 构造注入上传根目录，启动时即规范化为绝对路径并创建目录。
     *
     * @param uploadDir 配置项 homework.upload.dir 的值
     */
    public FileUploadUtils(@Value("${homework.upload.dir:./upload}") String uploadDir) {
        // 规范化为绝对路径，后续所有落盘与下载都以此为根，杜绝越界读写
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            // 启动即创建根目录，避免首次上传时才发现目录缺失
            Files.createDirectories(this.uploadRoot);
        } catch (IOException e) {
            throw new IllegalStateException("附件存储根目录初始化失败: " + this.uploadRoot, e);
        }
    }

    /**
     * 安全保存上传文件：校验 -> UUID 重命名 -> 按业务子目录落盘。
     *
     * @param file      前端上传的文件（不可为空文件）
     * @param subDir    业务相对子目录（如 "hw/1/5" 或 "ref/2"），仅允许字母数字与斜杠
     * @return 相对存储路径（如 hw/1/5/3f2a....docx），入库字段；原始名由调用方单独存 *_name 列
     * @throws FileUploadException 空文件/格式非法/超限/路径非法/IO 失败
     */
    public String save(MultipartFile file, String subDir) {
        // 1. 空文件拦截（0 字节通常意味着前端未真正选择文件）
        if (file == null || file.isEmpty()) {
            throw new FileUploadException("上传文件为空，请重新选择");
        }
        // 2. 大小校验：应用层按 50MB 提前拦截，给出比容器异常更友好的中文提示
        if (file.getSize() > MAX_SIZE) {
            throw new FileUploadException("文件大小超过50MB上限（当前 " + (file.getSize() / 1024 / 1024) + "MB）");
        }
        // 3. 扩展名白名单校验：取原始文件名最后一个点后的后缀，统一小写比对
        String original = file.getOriginalFilename();
        String ext = extractExt(original);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new FileUploadException("不支持的文件格式 ." + ext + "，仅允许 " + String.join("/", ALLOWED_EXT));
        }
        // 4. 业务子目录合法性校验：只允许 字母/数字/斜杠/下划线/连字符，防注入与穿越
        if (subDir == null || !subDir.matches("[A-Za-z0-9/_-]+")) {
            throw new FileUploadException("非法的存储目录: " + subDir);
        }
        // 5. UUID 重命名：原始文件名完全不参与落盘路径，杜绝路径穿越与同名覆盖
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = uploadRoot.resolve(subDir).resolve(storedName).normalize();
        // 二次防御：拼接结果必须仍在根目录内（resolve + normalize 后 startsWith 校验）
        if (!target.startsWith(uploadRoot)) {
            throw new FileUploadException("非法的存储路径");
        }
        try (InputStream in = file.getInputStream()) {
            // 自动创建业务子目录后流式复制落盘，REPLACE_EXISTING 保证重传幂等
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("文件落盘失败: {}", target, e);
            throw new FileUploadException("文件保存失败，请稍后重试");
        }
        // 返回入库的相对路径（正斜杠），下载时以此为锚点
        return uploadRoot.relativize(target).toString().replace('\\', '/');
    }

    /**
     * 安全读取已落盘文件：校验路径归属与存在性后返回可写出的资源。
     *
     * @param relativePath 入库的相对存储路径（save() 的返回值）
     * @return 指向真实文件的 FileSystemResource
     * @throws FileUploadException 路径为空/越界/文件不存在
     */
    public FileSystemResource load(String relativePath) {
        // 空路径直接拒绝（记录缺附件时不应尝试下载）
        if (relativePath == null || relativePath.isEmpty()) {
            throw new FileUploadException("文件路径为空");
        }
        // resolve + normalize 后必须仍在上传根目录内：拦截 ../ 与绝对路径注入
        Path target = uploadRoot.resolve(relativePath).normalize();
        if (!target.startsWith(uploadRoot)) {
            throw new FileUploadException("非法的文件路径");
        }
        // 文件必须真实存在（可能被人工清理），否则按 400 提示刷新列表
        if (!Files.exists(target) || !Files.isReadable(target)) {
            throw new FileUploadException("文件不存在或已被清理");
        }
        return new FileSystemResource(target);
    }

    /**
     * 读取文本附件内容（供查重流程在 MQ 消费端提取正文，UTF-8 解码）。
     *
     * @param relativePath 入库的相对存储路径
     * @return 文件文本内容；读取失败时返回空串（调用方按"无文本"处理）
     */
    public String readText(String relativePath) {
        try {
            FileSystemResource resource = load(relativePath);
            // 仅对文本类扩展尝试读取；二进制（docx/zip）由调用方跳过
            String content = new String(Files.readAllBytes(resource.getFile().toPath()), StandardCharsets.UTF_8);
            return content.length() > 200_000 ? content.substring(0, 200_000) : content;
        } catch (FileUploadException | IOException e) {
            // 读取失败不阻断主流程：查重降级为"仅比对正文"，记录 WARN 即可
            log.warn("文本附件读取失败: {} - {}", relativePath, e.getMessage());
            return "";
        }
    }

    /**
     * 删除已落盘文件（作业删除时清理其参考附件；失败仅告警，不阻断业务删除主流程）。
     *
     * @param relativePath 入库的相对存储路径
     */
    public void delete(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return;
        }
        try {
            Path target = uploadRoot.resolve(relativePath).normalize();
            // 越界防御与存在性检查后执行删除
            if (target.startsWith(uploadRoot) && Files.exists(target)) {
                Files.delete(target);
            }
        } catch (IOException e) {
            // 磁盘清理失败不影响数据库记录删除，留待运维处理
            log.warn("附件删除失败: {} - {}", relativePath, e.getMessage());
        }
    }

    /**
     * 从原始文件名提取小写扩展名（无扩展名时返回空串，白名单比对必然失败）。
     *
     * @param originalName 用户上传的原始文件名（可能为 null）
     * @return 小写扩展名（不含点），无扩展名时为空串
     */
    private String extractExt(String originalName) {
        if (originalName == null) {
            return "";
        }
        // 取最后一个点之后的内容作为扩展名，统一转小写后与白名单比对
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return "";
        }
        return originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
