package com.campus.homework.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 统一响应契约 Result&lt;T&gt; 单元测试：锁定"HTTP 语义 code + 中文 msg +
 * data 仅在成功时携带"的前后端契约，防止后续改动悄悄破坏 api.js 的
 * code === 200 判定逻辑。
 *
 * @author campus-homework
 */
class ResultTest {

    @Test
    @DisplayName("无数据成功响应：code=200、msg=ok、data=null（写操作契约）")
    void successWithoutData() {
        Result<Void> result = Result.success();
        assertEquals(200, result.getCode());
        assertEquals("ok", result.getMsg());
        assertNull(result.getData());
    }

    @Test
    @DisplayName("携带数据成功响应：data 原样透传（读操作契约）")
    void successCarriesData() {
        String payload = "hello";
        Result<String> result = Result.success(payload);
        assertEquals(200, result.getCode());
        assertSame(payload, result.getData());
    }

    @Test
    @DisplayName("自定义提示成功响应：msg 与 data 同时携带")
    void successWithCustomMessage() {
        Result<Integer> result = Result.success("导入完成", 42);
        assertEquals(200, result.getCode());
        assertEquals("导入完成", result.getMsg());
        assertEquals(42, result.getData());
    }

    @Test
    @DisplayName("指定状态码失败响应：403/404/500 语义正确且 data 必为 null")
    void errorWithCode() {
        assertEquals(403, Result.error(403, "无权访问该数据").getCode());
        assertEquals(404, Result.error(404, "资源不存在").getCode());
        assertEquals(500, Result.error(500, "服务器内部错误").getCode());
        assertNull(Result.error(403, "x").getData());
    }

    @Test
    @DisplayName("默认 400 失败快捷方法：参数错误场景 msg 原样透传")
    void errorShortcutIsBadRequest() {
        Result<Void> result = Result.error("截止时间已过，无法提交");
        assertEquals(400, result.getCode());
        assertEquals("截止时间已过，无法提交", result.getMsg());
        assertNull(result.getData());
    }
}
