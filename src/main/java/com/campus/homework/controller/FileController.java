package com.campus.homework.controller;

import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.utils.FileUploadUtils;
import com.campus.homework.utils.Result;
import com.campus.homework.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 文件上传下载接口（/api/file/**，任意已登录角色可访问）。
 * <p>上传：先落盘再回填路径——教师参考附件与学生作业附件都先经本接口
 * 落盘，拿到相对路径后填入各自的 Save DTO 再提交业务接口；格式/大小
 * 校验全部在 FileUploadUtils 内完成（白名单扩展名 + 50MB 上限 +
 * UUID 重命名），本 Controller 只按角色拼装业务子目录。</p>
 * <p>下载安全（IDOR 在文件维度的等价实现）：三重防线——
 * ① 登录即可（未登录 401）；② FileUploadUtils.load 校验路径必须位于
 * 上传根目录内（拦截 ../ 与绝对路径注入）；③ 落盘文件名是 32 位 UUID，
 * 不可枚举、不可猜测，"知道路径即拥有权限"（capability URL）——而路径
 * 只会通过 Service 层 IDOR 校验后的 VO（本人提交/本人作业/本人名单）
 * 下发给前端，越权用户根本拿不到路径。</p>
 *
 * @author campus-homework
 */
@Slf4j
// REST 接口，下载返回文件流、其余返回 Result JSON
@RestController
// 文件接口前缀：SecurityConfig 中归入 anyRequest().authenticated()
@RequestMapping("/api/file")
// 构造器注入安全文件工具
@RequiredArgsConstructor
public class FileController {

    /** 安全文件上传下载工具（白名单校验 + UUID 落盘 + 路径归属校验） */
    private final FileUploadUtils fileUploadUtils;

    /**
     * 上传附件并落盘，返回入库用的相对路径（前端拿到后回填到业务 DTO）。
     *
     * @param file       上传文件（格式与大小由 FileUploadUtils 校验）
     * @param type       业务类型：hw=学生作业附件，ref=教师参考附件
     * @param homeworkId 作业ID（type=hw 时必传，用于按作业归档）
     * @return 成功时 data 为相对存储路径（如 hw/1/5/3f2a....docx）
     */
    @PostMapping("/upload")
    public Result<String> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam(value = "homeworkId", required = false) Long homeworkId) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        String subDir;
        if ("hw".equals(type)) {
            // 学生作业附件：按 作业/学生 两级归档（教师批改页按此定位下载）
            if (homeworkId == null) {
                return Result.error(400, "上传作业附件必须携带作业ID");
            }
            subDir = "hw/" + homeworkId + "/" + operator.getUserId();
        } else if ("ref".equals(type)) {
            // 教师参考附件：按教师归档（发布作业后回填 attachmentUrl）
            subDir = "ref/" + operator.getUserId();
        } else {
            return Result.error(400, "非法的业务类型: " + type);
        }
        String relativePath = fileUploadUtils.save(file, subDir);
        log.info("用户 {} 上传附件 {} 至 {}", operator.getUsername(), file.getOriginalFilename(), relativePath);
        return Result.success(relativePath);
    }

    /**
     * 下载附件（以入库相对路径为锚点，三重安全防线见类注释）。
     *
     * @param path 入库的相对存储路径（FileUploadUtils.save 的返回值）
     * @param name 可选的原始文件名（仅用于浏览器另存为展示，做注入清洗）
     * @return 文件流响应（Content-Disposition: attachment）
     */
    @GetMapping("/download")
    public ResponseEntity<FileSystemResource> download(
            @RequestParam("path") String path,
            @RequestParam(value = "name", required = false) String name) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        // load 内部完成：非空校验 + 路径归属校验 + 存在性校验
        FileSystemResource resource = fileUploadUtils.load(path);
        // 展示文件名：优先用调用方回传的原始名（清洗防 Header 注入），否则用落盘名
        String displayName = sanitizeName(name);
        if (displayName == null) {
            displayName = resource.getFilename() == null ? "download" : resource.getFilename();
        }
        log.info("用户 {} 下载附件 {}", operator.getUsername(), path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + displayName + "\"; filename*=UTF-8''"
                                + URLEncoder.encode(displayName, StandardCharsets.UTF_8))
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    /**
     * 清洗展示文件名：去除引号/换行等可能破坏 Header 的字符并限长。
     *
     * @param raw 调用方回传的原始文件名（可为 null）
     * @return 安全文件名；空白时返回 null（由调用方回退落盘名）
     */
    private String sanitizeName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        // 去掉双引号、反斜杠与控制字符（CR/LF 是 Header 注入的主要载体）
        String cleaned = raw.replace("\"", "").replace("\\", "").replaceAll("[\\x00-\\x1F]", "");
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }
}
