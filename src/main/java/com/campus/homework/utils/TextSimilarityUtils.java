package com.campus.homework.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 作业正文查重工具：N-Gram 分词 + 余弦相似度（Cosine Similarity），纯 JDK 实现。
 * <p>算法契约（YAGNI 裁剪边界，零外部依赖）：</p>
 * <ol>
 *   <li>预处理：统一小写、剔除全部空白与中英文标点，仅保留汉字、字母、数字，
 *       使"加空格/换标点/大小写"等表面改写不影响比对结果；</li>
 *   <li>N-Gram 分词：对清洗后的字符序列取连续 n 个字符（默认 n=3）滑动切分，
 *       统计每个 n-gram 出现次数形成词频向量（中文按字符、混排英文按清洗后字符流，
 *       对中文作业最稳健，无需分词库）；</li>
 *   <li>余弦相似度：把两份词频向量视为高维空间向量，cos = A·B / (|A|*|B|)，
 *       只比较方向（用词分布）不比较长度，天然抗"复制后加一段废话"的干扰；</li>
 *   <li>结果：相似度 ∈ [0,1]，保留 4 位小数；调用方按配置阈值（默认 0.85）
 *       判定是否触发高重复预警。</li>
 * </ol>
 * <p>本类为无状态静态工具，全部方法线程安全，MQ 消费端（Batch 4）直接静态调用。</p>
 *
 * @author campus-homework
 */
public final class TextSimilarityUtils {

    /** 默认 N-Gram 长度：3-gram 对中文短文本兼顾灵敏度与抗噪性 */
    public static final int DEFAULT_NGRAM_SIZE = 3;

    /** 单篇参与比对的最大字符数：超长文本截断，防止 MQ 消费端内存放大 */
    private static final int MAX_TEXT_LENGTH = 20_000;

    /** 工具类禁止实例化 */
    private TextSimilarityUtils() {
    }

    /**
     * 计算两段文本的余弦相似度（使用默认 3-gram）。
     *
     * @param textA 文本A（如某学生的作业正文）
     * @param textB 文本B（如另一学生的作业正文）
     * @return 相似度 [0,1]，保留4位小数；任一文本清洗后为空时返回 0
     */
    public static double cosine(String textA, String textB) {
        return cosine(textA, textB, DEFAULT_NGRAM_SIZE);
    }

    /**
     * 计算两段文本的余弦相似度（指定 n-gram 长度）。
     *
     * @param textA    文本A
     * @param textB    文本B
     * @param ngramLen n-gram 长度（必须 ≥ 1）
     * @return 相似度 [0,1]，保留4位小数
     */
    public static double cosine(String textA, String textB, int ngramLen) {
        // 参数防御：非法 n-gram 长度直接归正为默认值，保证调用方永不抛异常
        int n = ngramLen < 1 ? DEFAULT_NGRAM_SIZE : ngramLen;
        // 第一步：分别构建两份词频向量（内部已完成清洗与截断）
        Map<String, Integer> vectorA = buildNGramVector(textA, n);
        Map<String, Integer> vectorB = buildNGramVector(textB, n);
        // 任一文本清洗后为空（如纯标点/纯数字编号），视为无法比对，返回 0
        if (vectorA.isEmpty() || vectorB.isEmpty()) {
            return 0.0;
        }
        // 第二步：计算点积与两向量的模长，完成 cos = A·B / (|A|*|B|)
        // 点积只需遍历较小向量与交集键，避免遍历全部键的并集
        Map<String, Integer> small = vectorA.size() <= vectorB.size() ? vectorA : vectorB;
        Map<String, Integer> large = small == vectorA ? vectorB : vectorA;
        double dot = 0.0;
        for (Map.Entry<String, Integer> entry : small.entrySet()) {
            Integer otherCount = large.get(entry.getKey());
            // 键不存在于另一向量时贡献为 0，跳过
            if (otherCount != null) {
                dot += (double) entry.getValue() * otherCount;
            }
        }
        // 模长：sqrt(Σ count^2)，两份向量分别计算
        double normA = magnitude(vectorA);
        double normB = magnitude(vectorB);
        // 模长为 0 已在前面空向量分支拦截，此处分母必不为 0
        double similarity = dot / (normA * normB);
        // 浮点误差可能使结果略越界（如 1.0000000002），钳制到 [0,1]
        if (similarity < 0.0) {
            similarity = 0.0;
        } else if (similarity > 1.0) {
            similarity = 1.0;
        }
        // 保留 4 位小数后返回（与数据库 similarity_rate DECIMAL(5,4) 精度一致）
        return Math.round(similarity * 10000.0) / 10000.0;
    }

    /**
     * 在候选集合中查找与目标文本最相似的一篇（教师端"高重复预警"复核入口）。
     *
     * @param target     目标文本（当前学生的作业正文）
     * @param candidates 候选映射：业务ID（如学生ID） -> 该候选的文本
     * @param threshold  高重复预警阈值（如 0.85）
     * @return 比对结果列表（按相似度降序，仅包含 ≥ threshold 的候选）；target 为空时返回空列表
     */
    public static List<MatchResult> findMatches(String target, Map<Long, String> candidates, double threshold) {
        // 目标文本为空则无法比对
        if (target == null || target.trim().isEmpty() || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        // 目标向量只构建一次，循环内复用，避免重复清洗与切分
        Map<String, Integer> targetVector = buildNGramVector(target, DEFAULT_NGRAM_SIZE);
        if (targetVector.isEmpty()) {
            return Collections.emptyList();
        }
        List<MatchResult> results = new ArrayList<>();
        for (Map.Entry<Long, String> entry : candidates.entrySet()) {
            // 逐个候选计算相似度：复用向量构建 + 点积/模长逻辑
            Map<String, Integer> candidateVector = buildNGramVector(entry.getValue(), DEFAULT_NGRAM_SIZE);
            if (candidateVector.isEmpty()) {
                continue;
            }
            double dot = 0.0;
            // 点积遍历较小向量，降低高维稀疏向量的计算量
            Map<String, Integer> small = targetVector.size() <= candidateVector.size() ? targetVector : candidateVector;
            Map<String, Integer> large = small == targetVector ? candidateVector : targetVector;
            for (Map.Entry<String, Integer> e : small.entrySet()) {
                Integer other = large.get(e.getKey());
                if (other != null) {
                    dot += (double) e.getValue() * other;
                }
            }
            double normA = magnitude(targetVector);
            double normB = magnitude(candidateVector);
            double similarity = normA == 0 || normB == 0 ? 0.0 : dot / (normA * normB);
            // 仅保留达到预警阈值的命中，供调用方标记 is_high_repeat
            if (similarity >= threshold) {
                double clamped = Math.min(1.0, Math.max(0.0, similarity));
                results.add(new MatchResult(entry.getKey(), Math.round(clamped * 10000.0) / 10000.0));
            }
        }
        // 按相似度从高到低排序，首位即"最相似同学"
        results.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
        return results;
    }

    /**
     * 第一步：文本清洗。统一小写、截断超长文本、剔除空白与标点，仅保留汉字/字母/数字。
     * <p>清洗保证：同一段内容无论怎样调整排版、标点、大小写，清洗后完全一致，
     * 查重只比对"用词分布"而非"表面格式"。</p>
     *
     * @param text 原始文本（可为 null）
     * @return 清洗后的字符序列（仅含 汉字、a-z、0-9），null 或空输入返回空串
     */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        // 超长截断：控制单篇内存占用，防止异常长文拖垮 MQ 消费端
        String limited = text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
        StringBuilder sb = new StringBuilder(limited.length());
        for (int i = 0; i < limited.length(); i++) {
            char c = limited.charAt(i);
            // 统一小写（Character.toLowerCase 同时处理全角字母）
            char lower = Character.toLowerCase(c);
            // 保留三类字符：汉字（CJK 统一表意文字区）、ASCII 字母、数字；
            // 其余（空格、换行、中英文标点、符号、其他文字）全部剔除
            if (isCjk(lower) || isAsciiLetter(lower) || Character.isDigit(lower)) {
                sb.append(lower);
            }
        }
        return sb.toString();
    }

    /**
     * 第二步：N-Gram 分词并构建词频向量（滑动窗口取连续 n 个字符统计出现次数）。
     *
     * @param text     原始文本（内部先清洗）
     * @param ngramLen n-gram 长度（n ≥ 1）
     * @return 不可变的词频向量：n-gram 字符串 -> 出现次数；清洗后长度不足 n 时退化为整串单条
     */
    public static Map<String, Integer> buildNGramVector(String text, int ngramLen) {
        // 先清洗，保证格式差异不影响向量
        String cleaned = clean(text);
        if (cleaned.isEmpty()) {
            return Collections.emptyMap();
        }
        int n = ngramLen < 1 ? DEFAULT_NGRAM_SIZE : ngramLen;
        Map<String, Integer> frequency = new HashMap<>();
        // 退化保护：文本比 n 还短时无法滑动，把整串作为唯一 n-gram（短答案也能参与比对）
        if (cleaned.length() <= n) {
            frequency.put(cleaned, 1);
            return Collections.unmodifiableMap(frequency);
        }
        // 标准滑动窗口：窗口起点从 0 到 length-n，共 length-n+1 个 n-gram
        for (int i = 0; i + n <= cleaned.length(); i++) {
            // 取子串 [i, i+n)，出现次数 +1（merge 表达"存在则加1，否则置1"）
            String gram = cleaned.substring(i, i + n);
            frequency.merge(gram, 1, Integer::sum);
        }
        return Collections.unmodifiableMap(frequency);
    }

    /**
     * 计算向量的欧几里得模长 |v| = sqrt(Σ count^2)，余弦公式的分母部分。
     *
     * @param vector 词频向量
     * @return 模长；空向量返回 0
     */
    private static double magnitude(Map<String, Integer> vector) {
        double sumOfSquares = 0.0;
        for (Integer count : vector.values()) {
            // 每个维度的贡献是出现次数的平方
            sumOfSquares += (double) count * count;
        }
        return Math.sqrt(sumOfSquares);
    }

    /**
     * 判断字符是否为 CJK 统一表意文字（基本区 + 扩展A区），覆盖常用汉字。
     *
     * @param c 待判断字符
     * @return true 表示属于汉字字符集
     */
    private static boolean isCjk(char c) {
        // \u4E00-\u9FFF 基本区，\u3400-\u4DBF 扩展A区；其余符号标点均不保留
        return (c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF');
    }

    /**
     * 判断字符是否为 ASCII 字母（a-z，已小写化后判断）。
     *
     * @param c 待判断字符
     * @return true 表示 a-z 字母
     */
    private static boolean isAsciiLetter(char c) {
        return c >= 'a' && c <= 'z';
    }

    /**
     * 查重命中结果：记录命中的业务ID与相似度，供 Service 层回写
     * similarity_rate / similar_student_id / is_high_repeat 三个字段。
     */
    public static final class MatchResult {

        /** 命中的业务ID（如最相似同学的学生ID） */
        private final Long matchedId;

        /** 与目标文本的余弦相似度 [0,1]，保留4位小数 */
        private final double similarity;

        /**
         * 构造命中结果。
         *
         * @param matchedId  命中的业务ID
         * @param similarity 相似度
         */
        public MatchResult(Long matchedId, double similarity) {
            this.matchedId = matchedId;
            this.similarity = similarity;
        }

        /**
         * 获取命中的业务ID。
         *
         * @return 业务ID
         */
        public Long getMatchedId() {
            return matchedId;
        }

        /**
         * 获取相似度。
         *
         * @return 相似度 [0,1]
         */
        public double getSimilarity() {
            return similarity;
        }
    }
}
