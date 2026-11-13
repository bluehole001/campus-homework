import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次性数据库初始化工具（非项目源码，仅服务于本机冒烟/压测环境搭建）。
 * 用途：在没有 mysql 命令行的环境下，直接用 MySQL JDBC 驱动执行 homework_init.sql，
 * 完成 DROP/CREATE DATABASE、建表与种子数据写入，随后逐表回读行数做自洽性校验。
 * 参数：args[0]=SQL 文件路径 args[1]=JDBC URL（含 allowMultiQueries） args[2]=user args[3]=password
 */
public class SqlRunner {

    public static void main(String[] args) throws Exception {
        String sqlFile = args[0];
        String url = args[1];
        String user = args[2];
        String password = args[3];

        // 读取脚本：剔除 -- 注释行与行内注释后，按分号切分为独立语句
        List<String> statements = parse(new InputStreamReader(
                Files.newInputStream(Path.of(sqlFile)), StandardCharsets.UTF_8));
        System.out.println("parsed statements = " + statements.size());

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement st = conn.createStatement()) {
            int seq = 0;
            for (String sql : statements) {
                seq++;
                try {
                    st.execute(sql);
                } catch (Exception e) {
                    System.out.println("FAILED at statement #" + seq + ": " + abbrev(sql)
                            + " | " + e.getMessage());
                    throw e;
                }
            }
            System.out.println("ALL STATEMENTS EXECUTED OK");

            // 回读各表行数，供数据自洽性核对（种子数据是否完整写入）
            String[] tables = {"clazz_info", "sys_user", "homework_task", "homework_submission",
                    "clazz_notice", "monthly_submit_stat"};
            for (String t : tables) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + t)) {
                    rs.next();
                    System.out.println("rowcount " + t + " = " + rs.getInt(1));
                }
            }
            // 关键自洽性校验：班级 student_count 与 sys_user 实际学生数是否一致
            try (ResultSet rs = st.executeQuery(
                    "SELECT c.id, c.clazz_code, c.student_count,"
                            + " (SELECT COUNT(*) FROM sys_user u WHERE u.clazz_id = c.id AND u.role='ROLE_STUDENT') actual"
                            + " FROM clazz_info c ORDER BY c.id")) {
                while (rs.next()) {
                    int declared = rs.getInt("student_count");
                    int actual = rs.getInt("actual");
                    System.out.println("class " + rs.getString("clazz_code")
                            + " declared=" + declared + " actual=" + actual
                            + (declared == actual ? " PASS" : " FAIL"));
                }
            }
            // 关键自洽性校验：作业 submit_count 与提交表"已交记录数"（排除 status=0 未交占位）是否一致
            try (ResultSet rs = st.executeQuery(
                    "SELECT h.id, h.title, h.submit_count,"
                            + " (SELECT COUNT(*) FROM homework_submission s WHERE s.homework_id = h.id"
                            + " AND s.submit_status <> 0) actual"
                            + " FROM homework_task h ORDER BY h.id")) {
                while (rs.next()) {
                    int declared = rs.getInt("submit_count");
                    int actual = rs.getInt("actual");
                    System.out.println("hw#" + rs.getInt("id") + " declared=" + declared
                            + " actual=" + actual + (declared == actual ? " PASS" : " FAIL"));
                }
            }
        }
    }

    /** 按行解析 SQL：跳过纯注释行，剥离行内 -- 注释，遇到行尾分号则封装为一条语句。 */
    private static List<String> parse(Reader reader) throws Exception {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                    continue;
                }
                int comment = indexOfLineComment(line);
                if (comment >= 0) {
                    line = line.substring(0, comment);
                }
                current.append(line).append('\n');
                if (line.trim().endsWith(";")) {
                    String stmt = current.toString().trim();
                    stmt = stmt.substring(0, stmt.lastIndexOf(';'));
                    if (!stmt.isBlank()) {
                        result.add(stmt);
                    }
                    current.setLength(0);
                }
            }
        }
        String tail = current.toString().trim();
        if (!tail.isBlank() && !tail.equals(";")) {
            result.add(tail.endsWith(";") ? tail.substring(0, tail.length() - 1) : tail);
        }
        return result;
    }

    /** 定位行内 -- 注释起点（忽略引号内的连字符，本脚本注释均为独立行或行尾说明） */
    private static int indexOfLineComment(String line) {
        boolean inQuote = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuote) {
                if (ch == quote) {
                    inQuote = false;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                inQuote = true;
                quote = ch;
                continue;
            }
            if (ch == '-' && i + 1 < line.length() && line.charAt(i + 1) == '-' && i + 2 < line.length()
                    && (line.charAt(i + 2) == ' ' || line.charAt(i + 2) == '\t')) {
                return i;
            }
        }
        return -1;
    }

    private static String abbrev(String sql) {
        String flat = sql.replaceAll("\\s+", " ");
        return flat.length() > 120 ? flat.substring(0, 120) + "..." : flat;
    }
}
