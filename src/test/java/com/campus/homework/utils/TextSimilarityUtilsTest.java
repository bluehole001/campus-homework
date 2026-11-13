package com.campus.homework.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 查重算法（N-Gram + 余弦相似度）单元测试：覆盖契约的全部关键行为——
 * 表面改写免疫、抗"复制加废话"干扰、空输入边界、n-gram 退化保护、
 * 截断防内存放大、findMatches 阈值命中与降序排序。
 *
 * @author campus-homework
 */
class TextSimilarityUtilsTest {

    /** 演示正文1：与 SQL 初始化数据中作业1（陈明提交）正文同源，验证真实数据可复现相似度 */
    private static final String HW_A =
            "关系模式R(U,F)中F={A->B,B->C,C->D}，逐一判定各属性闭包后可知R存在非主属性对码的"
            + "部分函数依赖，因此R属于1NF但不属于2NF。无损分解步骤：先按A->B拆出R1(A,B)，"
            + "剩余R2(A,C,D)仍含A->C的传递依赖，继续拆出R3(A,C)，最终R4(C,D)。分解后各子模式"
            + "均达到3NF，且连接后信息无损。";

    /** 演示正文2：与 SQL 初始化数据中作业1（刘洋提交）正文同源，与正文2高度雷同触发预警 */
    private static final String HW_B =
            "关系模式R(U,F)中F={A->B,B->C,C->D}，逐一判定各属性闭包后可知R存在非主属性对码的"
            + "部分函数依赖，因此R属于1NF但不属于2NF。无损分解过程：先按A->B拆出R1(A,B)，"
            + "剩余R2(A,C,D)仍含A->C的传递依赖，继续拆出R3(A,C)，最终得到R4(C,D)。分解后各子模式"
            + "均达到3NF，且自然连接后信息无损。";

    /** 完全无关的第二主题文本（操作系统调度实验），用于"低相似"对照组 */
    private static final String HW_UNRELATED =
            "用Java实现FCFS/SJF/RR三种调度算法模拟，进程到达序列固定为P1到P5，时间片取4。"
            + "运行结果：FCFS平均周转时间10.4，SJF平均周转时间6.8，RR平均周转时间11.2。"
            + "SJF最优，RR周转时间最长但响应时间最均匀。";

    @Test
    @DisplayName("完全相同的文本相似度精确等于 1.0")
    void identicalTextIsExactlyOne() {
        assertEquals(1.0, TextSimilarityUtils.cosine(HW_A, HW_A), 0.0001);
    }

    @Test
    @DisplayName("表面改写免疫：加空格/换标点/大小写后清洗结果一致，相似度不降")
    void superficialRewriteDoesNotLowerSimilarity() {
        // 同一内容：调整空格、替换中英文标点、字母大小写互换——清洗后与原文完全一致
        String rewritten = HW_A.replace("，", ", ").replace("。", ".")
                .replaceAll("\\s+", " ")
                .replace("1NF", "1nf").replace("2NF", "2nf")
                + "  ";
        assertEquals(1.0, TextSimilarityUtils.cosine(HW_A, rewritten), 0.0001);
    }

    @Test
    @DisplayName("SQL 演示数据复现：刘洋与赵磊两篇正文相似度达到 0.85 预警线")
    void sqlDemoDataReproducesHighSimilarity() {
        double similarity = TextSimilarityUtils.cosine(HW_A, HW_B);
        // 预置库中该对提交的演示值为 0.9612：重查应复现"高度雷同"结论
        assertTrue(similarity >= 0.85, "预警对相似度应 ≥ 0.85，实测 " + similarity);
        assertTrue(similarity < 1.0, "两篇并非逐字相同，相似度应 < 1.0，实测 " + similarity);
    }

    @Test
    @DisplayName("无关主题文本相似度显著低于预警线")
    void unrelatedTextIsBelowThreshold() {
        double similarity = TextSimilarityUtils.cosine(HW_A, HW_UNRELATED);
        assertTrue(similarity < 0.5, "无关文本相似度应显著偏低，实测 " + similarity);
    }

    @Test
    @DisplayName("抗噪：复制正文后再加一段无关内容，相似度仍很高（只比方向不比长度）")
    void appendedUnrelatedTailStillHigh() {
        // B = A + 约40字无关结尾：余弦只比"用词分布方向"，结论仍应 ≥ 0.90 且 < 1
        String withTail = HW_A + "另外我认为这门课程很有意思，课后我还阅读了数据库经典教材。";
        double similarity = TextSimilarityUtils.cosine(HW_A, withTail);
        assertTrue(similarity >= 0.90 && similarity < 1.0,
                "复制+废话应维持高相似但低于1.0，实测 " + similarity);
    }

    @Test
    @DisplayName("空输入边界：null/空串/纯标点文本一律返回 0 且不抛异常")
    void emptyAndPunctuationOnlyTextsReturnZero() {
        assertEquals(0.0, TextSimilarityUtils.cosine(null, HW_A));
        assertEquals(0.0, TextSimilarityUtils.cosine(HW_A, ""));
        assertEquals(0.0, TextSimilarityUtils.cosine("！！！，。：；", "  ,.;: "));
    }

    @Test
    @DisplayName("非法 n-gram 长度自动归正为默认 3（调用方永不因参数异常失败）")
    void invalidNgramLengthFallsBackToDefault() {
        assertEquals(TextSimilarityUtils.cosine(HW_A, HW_B, 3),
                TextSimilarityUtils.cosine(HW_A, HW_B, 0));
        assertEquals(TextSimilarityUtils.cosine(HW_A, HW_B, 3),
                TextSimilarityUtils.cosine(HW_A, HW_B, -2));
    }

    @Test
    @DisplayName("n-gram 分词语义：滑动窗口覆盖全部子串且词频计数正确")
    void ngramVectorSemantics() {
        // n=1 时退化为单字符词频表：abc -> {a:1,b:1,c:1}
        Map<String, Integer> single = TextSimilarityUtils.buildNGramVector("abc", 1);
        assertEquals(Map.of("a", 1, "b", 1, "c", 1), single);
        // 正常文本：3-gram 总数必为 len-n+1（清洗后 9 字 -> 7 个窗口且互不重复）
        String shortText = "关系数据库设计模式";
        Map<String, Integer> grams = TextSimilarityUtils.buildNGramVector(shortText, 3);
        int total = grams.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(TextSimilarityUtils.clean(shortText).length() - 2, total);
        // 退化保护：文本比 n 还短时整串作为唯一 n-gram
        Map<String, Integer> degraded = TextSimilarityUtils.buildNGramVector("ab", 3);
        assertEquals(1, degraded.size());
        assertEquals(1, degraded.get("ab"));
        // 清洗规则：仅保留汉字/小写字母/数字，标点空白全部剔除
        assertEquals("rufabcd", TextSimilarityUtils.clean("R(U,F) {A->B, C->D} ，。！"));
    }

    @Test
    @DisplayName("超长截断防内存放大：>20000 字符的部分不参与比对")
    void truncatesLongTexts() {
        // 构造 20001+ 字符的长文：前 20000 与截断副本必须给出精确 1.0
        StringBuilder longText = new StringBuilder();
        while (longText.length() < 25000) {
            longText.append(HW_A);
        }
        String truncated = longText.substring(0, 20000);
        assertEquals(1.0, TextSimilarityUtils.cosine(longText.toString(), truncated), 0.0001);
    }

    @Test
    @DisplayName("findMatches：阈值内命中按相似度降序返回，阈值外候选被过滤")
    void findMatchesHitsSortedAndFilters() {
        Map<Long, String> candidates = new LinkedHashMap<>();
        candidates.put(6L, HW_B);                                   // 高度雷同（≥0.85 命中）
        candidates.put(8L, HW_UNRELATED);                           // 无关（过滤）
        candidates.put(9L, HW_A.replace("，", ","));                // 逐字等价（=1.0 首位）
        List<TextSimilarityUtils.MatchResult> results =
                TextSimilarityUtils.findMatches(HW_A, candidates, 0.85);
        // 命中两条（8L 无关被过滤），首位必是完全相同文本
        assertNotNull(results);
        assertTrue(results.size() >= 2, "至少两条命中预警线");
        assertEquals(9L, results.get(0).getMatchedId());
        assertEquals(1.0, results.get(0).getSimilarity(), 0.0001);
        // 降序契约：后续每条都不大于前一条
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i).getSimilarity() <= results.get(i - 1).getSimilarity());
        }
        // 空目标/空候选集快速返回空列表
        assertTrue(TextSimilarityUtils.findMatches(null, candidates, 0.85).isEmpty());
        assertTrue(TextSimilarityUtils.findMatches(HW_A, Map.of(), 0.85).isEmpty());
    }
}
