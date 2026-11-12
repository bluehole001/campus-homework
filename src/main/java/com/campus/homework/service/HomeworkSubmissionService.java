package com.campus.homework.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.homework.dto.GradeSubmitDTO;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.SubmissionSubmitDTO;
import com.campus.homework.entity.HomeworkSubmission;
import com.campus.homework.vo.KnowledgeRadarVO;
import com.campus.homework.vo.MyHomeworkDetailVO;
import com.campus.homework.vo.ScoreAnalysisVO;
import com.campus.homework.vo.SubmissionRosterVO;

import java.util.List;

/**
 * 作业提交服务接口（学生提交/重交、教师批改与名单、成绩发布与个人成绩查看）。
 * <p>IDOR 红线集中于此：学生只能读写自己的提交；未发布成绩时学生看不到分数；
 * 教师只能批改自己发布作业的提交。</p>
 *
 * @author campus-homework
 */
public interface HomeworkSubmissionService extends IService<HomeworkSubmission> {

    /**
     * 学生提交或覆盖重交作业（仅 ROLE_STUDENT）。
     * <p>业务规则：作业存在且属于该学生班级；正文与附件至少其一非空；
     * 截止前重复提交覆盖旧记录（update），截止后补交标记为逾期未交(2)；
     * 新提交时原子自增作业 submit_count；正文落库后由 Batch 4 的 MQ 异步查重。</p>
     *
     * @param dto      提交表单
     * @param operator 当前登录学生
     * @return 提交记录ID
     */
    Long submit(SubmissionSubmitDTO dto, LoginUserDTO operator);

    /**
     * 学生查看"我的作业详情"（仅 ROLE_STUDENT，仅限本班作业）。
     * <p>成绩未发布时 score/teacherComment/similarityRate 保持 null。</p>
     *
     * @param homeworkId 作业ID
     * @param operator   当前登录学生
     * @return 个人详情 VO
     */
    MyHomeworkDetailVO myDetail(Long homeworkId, LoginUserDTO operator);

    /**
     * 教师查看某作业的提交名单（仅 ROLE_TEACHER，仅限本人作业）。
     * <p>对班内无提交记录的学生合成"未交占位行"，前端标记未交。</p>
     *
     * @param homeworkId 作业ID
     * @param operator   当前登录教师
     * @return 名单行 VO 列表
     */
    List<SubmissionRosterVO> roster(Long homeworkId, LoginUserDTO operator);

    /**
     * 教师批改打分写评语（仅 ROLE_TEACHER，仅限本人作业的提交）。
     * <p>业务规则：得分不得超过作业满分；批改后状态置为已批改(3)，记录 grade_time。</p>
     *
     * @param dto      批改表单
     * @param operator 当前登录教师
     */
    void grade(GradeSubmitDTO dto, LoginUserDTO operator);

    /**
     * 一键发布成绩（仅 ROLE_TEACHER，仅限本人作业）。
     * <p>幂等：已发布再次调用直接返回；发布后学生端可见分数，
     * Batch 4 起触发 MQ 成绩统计与 Redis 预热。</p>
     *
     * @param homeworkId 作业ID
     * @param operator   当前登录教师
     */
    void publishScores(Long homeworkId, LoginUserDTO operator);

    /**
     * 教师查看某作业成绩分析（仅 ROLE_TEACHER，仅限本人作业；含排名与分布）。
     *
     * @param homeworkId 作业ID
     * @param operator   当前登录教师
     * @return 成绩分析 VO
     */
    ScoreAnalysisVO analysis(Long homeworkId, LoginUserDTO operator);

    /**
     * 教师查看班级知识点得分雷达（仅 ROLE_TEACHER，仅限本人授课班级；Batch 5 看板）。
     * <p>按作业 knowledge_tag 聚合本班已批改提交的平均得分率（百分制），
     * 供 ECharts radar 绘制各知识点掌握度轴；无已批改样本时返回空列表。
     * IDOR：教师只能查看自己授课（已发布过作业）的班级，与月度趋势接口
     * 同一校验口径。</p>
     *
     * @param clazzId  班级ID
     * @param operator 当前登录教师
     * @return 雷达轴 VO 列表（按平均得分率降序）
     */
    List<KnowledgeRadarVO> knowledgeRadar(Long clazzId, LoginUserDTO operator);

    /**
     * 执行异步查重并回写结论（MQ 消费端专用入口，Batch 4）。
     * <p>与用户请求路径不同，本方法由系统内部（MQ 消费者）调用，无登录态，
     * 不适用 IDOR 操作者校验：消息由 submit() 在事务提交后产生，
     * homeworkId/studentId 已在提交路径完成归属校验，消费端只需按
     * uk_hw_stu 定位记录并保证数据归属正确（记录已删除时静默放弃）。
     * 流程：读本人正文 -> 取同班其他已提交正文为候选 -> N-Gram 余弦
     * 相似度比对 -> 显式 UPDATE 回写三个查重字段（无命中时置空归零）。</p>
     *
     * @param homeworkId 作业ID
     * @param studentId  提交学生ID
     */
    void plagiarismCheck(Long homeworkId, Long studentId);

    /**
     * 成绩发布后的缓存预热（MQ 消费端专用入口，Batch 4）。
     * <p>系统内部调用，无登录态（理由同上）。把"查分高峰"的读压力提前
     * 转移到 MQ 异步消费：重算成绩分析写入
     * homework:score:analysis:{homeworkId}，再逐生预热
     * homework:score:stu:{homeworkId}:{studentId} 个人成绩缓存，
     * 成绩发布瞬间学生查分全部命中 Redis，不打垮 MySQL。</p>
     *
     * @param homeworkId 作业ID（成绩刚发布的作业）
     */
    void warmScoreCache(Long homeworkId);

    /**
     * 标记逾期未交（Spring Task 每 10 分钟入口，Batch 4）。
     * <p>两步幂等动作：先为"已截止且成绩未发布"的作业补插从未提交学生的
     * 未交占位记录（NOT EXISTS 防重），再把其中"从未真实提交"（status=0
     * 且 submit_time IS NULL）的记录标记为逾期未交(2)。已发布成绩的作业
     * 不再改写（成绩口径已冻结）；截止后真实补交的记录不受影响。</p>
     * <p>无需失效任何缓存：班级列表与成绩分析缓存的"已交人数/提交率"
     * 分子一律走修正后的 countSubmitted 口径（status=2 且 submit_time
     * 为 NULL 的定时标记不计入），本动作不改变任何已缓存的统计值。</p>
     *
     * @return 被标记为逾期未交的记录数（0 表示无新增逾期）
     */
    int markOverdueUnsubmitted();

    /**
     * 投递截止前催交邮件消息（Spring Task 每日 8:30 入口，Batch 4）。
     * <p>候选：距截止不足 24 小时且成绩未发布的作业；逐作业向
     * homework.mail.batch 投递 TYPE_URGE 消息，收件人（未交学生）由
     * 邮件消费者实时查库判定——本方法只负责"哪些作业需要催交"的圈定，
     * 单作业投递失败仅 WARN 跳过，不影响其余作业。</p>
     *
     * @return 成功投递的催交消息数
     */
    int dispatchUrgingMails();
}
