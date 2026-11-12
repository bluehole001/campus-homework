package com.campus.homework.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.homework.entity.HomeworkSubmission;
import com.campus.homework.vo.KnowledgeRadarVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 作业提交 Mapper：继承 BaseMapper 获得单表 CRUD，补充查重候选查询、
 * 提交名单查询与成绩发布状态联查三类自定义方法。
 * <p>注意：与 sys_user / homework_task 的联表查询一律使用 #{} 预编译；
 * 查重候选查询排除学生本人（selfExcludeId），避免自己和自己比对恒为 1。</p>
 *
 * @author campus-homework
 */
@Mapper
public interface HomeworkSubmissionMapper extends BaseMapper<HomeworkSubmission> {

    /**
     * 按作业与学生唯一定位提交记录（uk_hw_stu 唯一索引直接命中，提交/批改/查看共用）。
     *
     * @param homeworkId 作业ID
     * @param studentId  学生ID
     * @return 提交记录；不存在时返回 null
     */
    @Select("SELECT * FROM homework_submission WHERE homework_id = #{homeworkId} AND student_id = #{studentId} LIMIT 1")
    HomeworkSubmission selectByHwAndStu(@Param("homeworkId") Long homeworkId, @Param("studentId") Long studentId);

    /**
     * 查询某作业的全部提交（教师端提交名单：含未交占位记录，按提交时间升序，未交的排最后）。
     *
     * @param homeworkId 作业ID
     * @return 该作业全部提交记录
     */
    @Select("SELECT * FROM homework_submission WHERE homework_id = #{homeworkId} ORDER BY submit_time ASC, id ASC")
    List<HomeworkSubmission> selectByHomeworkId(@Param("homeworkId") Long homeworkId);

    /**
     * 查重候选查询：取某作业中除指定学生外、正文非空的已提交文本（MQ 消费端逐条比对）。
     *
     * @param homeworkId    作业ID
     * @param selfExcludeId 排除的学生ID（提交者本人，防自比）
     * @return 候选提交记录列表（仅含 content_text 非空的记录）
     */
    @Select("SELECT * FROM homework_submission WHERE homework_id = #{homeworkId} AND student_id != #{selfExcludeId} AND content_text IS NOT NULL AND content_text != ''")
    List<HomeworkSubmission> selectPlagiarismCandidates(@Param("homeworkId") Long homeworkId, @Param("selfExcludeId") Long selfExcludeId);

    /**
     * 统计某作业已提交人数（月度统计与看板提交率的分子）。
     * <p>口径（Batch 4 修正）：按时交(1)、已批改(3) 直接计入；逾期(2) 仅当
     * submit_time 非空（截止后真实补交）才计入——定时任务把"从未提交"的
     * 占位记录标记为 2 时 submit_time 为 NULL，不得被误计为已提交，
     * 否则逾期标记会人为抬高提交率。</p>
     *
     * @param homeworkId 作业ID
     * @return 已提交（真实提交过）的提交数
     */
    @Select("SELECT COUNT(*) FROM homework_submission WHERE homework_id = #{homeworkId} "
            + "AND (submit_status IN (1, 3) OR (submit_status = 2 AND submit_time IS NOT NULL))")
    int countSubmitted(@Param("homeworkId") Long homeworkId);

    /**
     * 为已截止且成绩未发布的作业补插"未交占位"记录（定时任务幂等前置步骤）。
     * <p>占位记录 submit_status=0、正文/附件/时间全空，仅用于把"从未提交"
     * 的学生落库成行，供随后的逾期标记 UPDATE 命中；NOT EXISTS + uk_hw_stu
     * 双重幂等，重跑不产生重复行。教师/禁用账号不参与（role/status 固定字面量）。</p>
     *
     * @return 新插入的占位记录数
     */
    @Insert("INSERT INTO homework_submission (homework_id, student_id, submit_status, is_high_repeat) "
            + "SELECT t.id, u.id, 0, 0 FROM homework_task t "
            + "JOIN sys_user u ON u.clazz_id = t.clazz_id AND u.role = 'ROLE_STUDENT' AND u.status = 1 "
            + "WHERE t.deadline < NOW() AND t.is_score_published = 0 "
            + "AND NOT EXISTS (SELECT 1 FROM homework_submission s "
            + "WHERE s.homework_id = t.id AND s.student_id = u.id)")
    int insertNotSubmittedPlaceholders();

    /**
     * 标记逾期未交：已截止作业中"从未真实提交"的占位记录置 2（定时任务核心动作）。
     * <p>判定条件 submit_status=0 且 submit_time IS NULL 精确锁定"从未提交"：
     * 截止后补交的记录（status=2 且 submit_time 非空）与已批改记录均不受影响；
     * 已发布成绩的作业不再改写（成绩口径已冻结）。join 走 idx_task_deadline
     * 与 uk_hw_stu 前缀，全部条件为固定字面量与 NOW()，无 ${} 拼接。</p>
     *
     * @return 被标记为逾期未交的记录数
     */
    @Update("UPDATE homework_submission s JOIN homework_task t ON s.homework_id = t.id "
            + "SET s.submit_status = 2 "
            + "WHERE t.deadline < NOW() AND t.is_score_published = 0 "
            + "AND s.submit_status = 0 AND s.submit_time IS NULL")
    int markOverdueUnsubmitted();

    /**
     * 查重结果回写（MQ 消费端专用）：按提交ID显式写入三个查重字段。
     * <p>必须用显式 UPDATE 而非 MyBatis-Plus updateById：本方法要支持
     * "无命中"场景把 similarity_rate / similar_student_id 置回 NULL、
     * is_high_repeat 归 0——updateById 的 NOT_NULL 字段策略会跳过 null 值，
     * 导致重交后旧的查重结论残留。三个参数全部 #{} 预编译。</p>
     *
     * @param id               提交记录ID
     * @param similarityRate   最高相似度（无命中传 null）
     * @param similarStudentId 最相似同学ID（无命中传 null）
     * @param isHighRepeat     高重复预警：0 正常 / 1 预警
     * @return 受影响行数（0 表示记录已被删除，消费端据此放弃回写）
     */
    @Update("UPDATE homework_submission SET similarity_rate = #{similarityRate}, "
            + "similar_student_id = #{similarStudentId}, is_high_repeat = #{isHighRepeat} "
            + "WHERE id = #{id}")
    int updatePlagiarismResult(@Param("id") Long id,
                               @Param("similarityRate") BigDecimal similarityRate,
                               @Param("similarStudentId") Long similarStudentId,
                               @Param("isHighRepeat") Integer isHighRepeat);

    /**
     * 知识点得分雷达聚合（教师端班级看板数据源）：按作业知识点标签汇总本班
     * 已批改提交（submit_status=3 且 score 非空）的平均得分率。
     * <p>口径说明：得分率 = score / total_score * 100（百分制，ROUND 保留1位）；
     * 未填写知识点、或该知识点下无已批改样本的作业/知识点不参与聚合——
     * 雷达只呈现"有数据"的轴。唯一动态参数 clazzId 走 #{} 预编译，
     * 状态与空值过滤均为固定字面量，无 ${} 拼接。</p>
     *
     * @param clazzId 班级ID（调用方已完成"教师仅可看本人授课班级"的 IDOR 校验）
     * @return 雷达轴列表（按平均得分率降序；无数据时返回空列表）
     */
    @Select("SELECT t.knowledge_tag AS knowledgeTag, "
            + "ROUND(AVG(s.score * 100.0 / t.total_score), 1) AS avgScoreRate, "
            + "COUNT(s.id) AS gradedCount "
            + "FROM homework_submission s "
            + "JOIN homework_task t ON s.homework_id = t.id "
            + "WHERE t.clazz_id = #{clazzId} AND s.submit_status = 3 AND s.score IS NOT NULL "
            + "AND t.knowledge_tag IS NOT NULL AND t.knowledge_tag != '' "
            + "GROUP BY t.knowledge_tag ORDER BY avgScoreRate DESC, knowledgeTag ASC")
    List<KnowledgeRadarVO> selectKnowledgeRadar(@Param("clazzId") Long clazzId);
}
