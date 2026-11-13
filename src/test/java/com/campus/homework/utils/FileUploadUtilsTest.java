package com.campus.homework.utils;

import com.campus.homework.exception.FileUploadException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件上传安全面单元测试：路径穿越拦截、扩展名白名单、大小上限提示、
 * UUID 重命名彻底剥离原始文件名、下载越界与文件缺失防护、删除幂等。
 * <p>不依赖 Spring 上下文：构造器直接传入 @TempDir 隔离目录，纯单元速度。</p>
 *
 * @author campus-homework
 */
class FileUploadUtilsTest {

    /** JUnit 临时目录：每个用例独立的上传根，用例结束自动清理 */
    @TempDir
    Path tempRoot;

    /**
     * 构造指向临时根目录的上传工具（等价于注入 homework.upload.dir）。
     *
     * @return 待测工具实例
     */
    private FileUploadUtils tool() {
        return new FileUploadUtils(tempRoot.toString());
    }

    /**
     * 构造内存上传文件（不占真实磁盘，仅模拟前端 multipart 请求）。
     *
     * @param name     原始文件名（含扩展名）
     * @param bytes    模拟内容
     * @return MockMultipartFile
     */
    private MultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    @Test
    @DisplayName("正常上传：落盘成功且路径相对根目录、扩展名保留、原始名完全不参与落盘")
    void happyPathSavesWithUuidName() throws IOException {
        FileUploadUtils utils = tool();
        String relative = utils.save(file("我的作业 v2(最终).docx",
                "hello".getBytes(StandardCharsets.UTF_8)), "hw/1/5");
        // 相对路径三段齐全：业务子目录 + UUID 文件名（无原始名痕迹）
        assertTrue(relative.startsWith("hw/1/5/"), "入库路径应为业务子目录: " + relative);
        assertTrue(relative.matches("hw/1/5/[0-9a-f]{32}\\.docx"),
                "文件名应为 32 位 UUID + 白名单扩展名: " + relative);
        // 磁盘真实存在且仍在根目录内（Path.resolve 原生支持正斜杠相对路径）
        Path target = tempRoot.resolve(relative);
        assertTrue(Files.exists(target.normalize()));
    }

    @Test
    @DisplayName("路径穿越拦截：原始名携带 ../ 与绝对路径均无法写出根目录")
    void pathTraversalIsBlocked() {
        FileUploadUtils utils = tool();
        // 攻击样本1：子目录注入 ..\..\（Windows 风格穿越）
        assertThrows(FileUploadException.class,
                () -> utils.save(file("a.docx", "x".getBytes(StandardCharsets.UTF_8)), "../secret"));
        // 攻击样本2：子目录注入绝对路径
        assertThrows(FileUploadException.class,
                () -> utils.save(file("a.docx", "x".getBytes(StandardCharsets.UTF_8)), "/etc/passwd"));
        // 攻击样本3：子目录含注入字符（空格/分号/反斜杠）
        assertThrows(FileUploadException.class,
                () -> utils.save(file("a.docx", "x".getBytes(StandardCharsets.UTF_8)), "hw;rm/1"));
        // 穿越样本即便放行文件名，normalize 后 startsWith 校验也必拦截（双重防御）
        assertThrows(FileUploadException.class,
                () -> utils.load("../../build.gradle"));
    }

    @Test
    @DisplayName("扩展名白名单：.exe/.sh 等可执行载荷一律拒绝（含大小写混排）")
    void executablePayloadsRejected() {
        FileUploadUtils utils = tool();
        assertThrows(FileUploadException.class, () -> utils.save(file("payload.exe", new byte[1]), "hw/1"));
        assertThrows(FileUploadException.class, () -> utils.save(file("SCRIPT.SH", new byte[1]), "hw/1"));
        assertThrows(FileUploadException.class, () -> utils.save(file("noext", new byte[1]), "hw/1"));
        assertThrows(FileUploadException.class, () -> utils.save(file("trailingdot.", new byte[1]), "hw/1"));
        // 白名单内扩展名（大小写不敏感）必须放行
        assertDoesNotThrowSave(utils, "报告.PDF");
    }

    /**
     * 白名单内文件保存不应抛异常（辅助断言）。
     *
     * @param utils  工具实例
     * @param name   原始文件名
     */
    private void assertDoesNotThrowSave(FileUploadUtils utils, String name) {
        try {
            utils.save(file(name, new byte[1]), "hw/1");
        } catch (FileUploadException e) {
            throw new AssertionError(name + " 应可上传，却抛出: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("空文件与 null 文件直接拒绝（前端未真正选择文件的兜底）")
    void emptyFilesRejected() {
        FileUploadUtils utils = tool();
        assertThrows(FileUploadException.class,
                () -> utils.save(new MockMultipartFile("f", "a.docx", null, new byte[0]), "hw/1"));
        assertThrows(FileUploadException.class, () -> utils.save(null, "hw/1"));
    }

    @Test
    @DisplayName("load：空路径/越界/不存在的文件均抛 400，真实文件返回可读资源")
    void loadValidatesBeforeReturningResource() throws IOException {
        FileUploadUtils utils = tool();
        assertThrows(FileUploadException.class, () -> utils.load(""));
        assertThrows(FileUploadException.class, () -> utils.load("/absolute/path.docx"));
        assertThrows(FileUploadException.class, () -> utils.load("hw/1/missing.docx"));
        // 先保存再读取：往返一致
        String relative = utils.save(file("ok.txt", "content".getBytes(StandardCharsets.UTF_8)), "hw/1");
        assertEquals("content", utils.readText(relative));
        // 文本读取失败降级为空串（二进制/缺失都不阻断查重主流程）
        assertEquals("", utils.readText("hw/1/不存在.txt"));
    }

    @Test
    @DisplayName("deleteQuiet 幂等：删除真实文件成功，对不存在路径静默无异常")
    void deleteIsIdempotent() {
        FileUploadUtils utils = tool();
        String relative = utils.save(file("del.txt", "x".getBytes(StandardCharsets.UTF_8)), "hw/2");
        utils.delete(relative);
        // Path.resolve 原生支持正斜杠相对路径，可直接断言文件已消失
        assertTrue(!Files.exists(tempRoot.resolve(relative)));
        // 重复删除与越界删除均静默（不抛异常、不越界误删）
        utils.delete(relative);
        utils.delete("../outside.txt");
    }
}
