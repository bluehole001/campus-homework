package com.campus.homework.service.impl;

import com.campus.homework.entity.ClazzInfo;
import com.campus.homework.entity.HomeworkSubmission;
import com.campus.homework.entity.HomeworkTask;
import com.campus.homework.entity.SysUser;
import com.campus.homework.exception.BusinessException;
import com.campus.homework.mapper.ClazzInfoMapper;
import com.campus.homework.mapper.HomeworkSubmissionMapper;
import com.campus.homework.mapper.HomeworkTaskMapper;
import com.campus.homework.mapper.SysUserMapper;
import com.campus.homework.service.MailService;
import com.campus.homework.utils.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 批量邮件业务实现（Batch 4）：成绩发布通知与截止前催交两类邮件。
 * <p>收件人一律在发送时实时查库获取（班级在册学生 / 未交学生），与消息体
 * 只带作业ID的设计一致——名单准确、且催交不会误发给"消息发出后又补交"的学生。</p>
 * <p>环境降级开关：{@code homework.mail.enabled=false}（dev 默认）时不真正
 * 投递，仅以 INFO 日志记录邮件内容（本机通常无 SMTP，避免消费端报错进死信）；
 * prod 环境 enabled=true 走真实 JavaMailSender 逐封投递。单封失败仅 WARN 跳过，
 * 全部失败才抛 BusinessException 触发死信人工重投——部分失败不值得整条消息重放。</p>
 * <p>本方法均为 MQ 消费端 / 定时任务专用入口，无登录态、无 IDOR 校验对象。</p>
 *
 * @author campus-homework
 */
@Slf4j
// 邮件业务实现（业务逻辑集中在此类，消费者只做协议适配）
@Service
// 构造器注入所需 Mapper 与邮件发送器
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {

    /** 邮件正文中的截止时间展示格式 */
    private static final DateTimeFormatter DEADLINE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 作业表 Mapper：定位作业与班级、取标题与截止时间 */
    private final HomeworkTaskMapper homeworkTaskMapper;

    /** 班级表 Mapper：邮件正文中回填班级名称 */
    private final ClazzInfoMapper clazzInfoMapper;

    /** 用户表 Mapper：班级在册学生名单（收件人来源） */
    private final SysUserMapper sysUserMapper;

    /** 提交表 Mapper：催交时判定"哪些学生尚未提交" */
    private final HomeworkSubmissionMapper homeworkSubmissionMapper;

    /** Spring Boot 自动装配的邮件发送器（spring.mail.* 配置存在时可用） */
    private final JavaMailSender mailSender;

    /** 真实发送开关：false（dev 默认）时仅日志模拟，不投递 */
    @Value("${homework.mail.enabled:false}")
    private boolean mailEnabled;

    /** 发件人地址：复用 spring.mail.username（与 SMTP 认证账号一致） */
    @Value("${spring.mail.username:}")
    private String senderAddress;

    /** 邮件正文署名：来自业务配置 homework.mail.from-name */
    @Value("${homework.mail.from-name:班级学业管理与作业提交系统}")
    private String fromName;

    /**
     * 发送成绩发布通知邮件（该作业所在班级全部在册且有邮箱的学生）。
     *
     * @param homeworkId 成绩刚发布的作业ID
     * @return 实际发送（或日志模拟发送）的邮件数
     * @throws BusinessException 开启真实发送且全部投递失败
     */
    @Override
    public int sendScorePublishMail(Long homeworkId) {
        HomeworkTask task = loadTaskOrThrow(homeworkId);
        ClazzInfo clazz = clazzInfoMapper.selectById(task.getClazzId());
        // 收件人：班级在册正常状态学生（与名单页同一口径），过滤无邮箱者
        List<SysUser> students = sysUserMapper.selectStudentsByClazzId(task.getClazzId());
        String subject = "成绩发布通知：" + task.getTitle();
        String body = "同学你好！\n\n"
                + "课程班级：" + (clazz == null ? "-" : clazz.getClazzName()) + "\n"
                + "作业名称：" + task.getTitle() + "\n"
                + "作业满分：" + task.getTotalScore() + " 分\n\n"
                + "该作业成绩已发布，请登录系统查看你的得分、评语与班级排名。\n\n"
                + fromName;
        return dispatch(students, subject, body, "成绩发布通知");
    }

    /**
     * 发送截止前催交邮件（只发给该作业尚未提交的学生，消费时重新判定）。
     *
     * @param homeworkId 临近截止的作业ID
     * @return 实际发送（或日志模拟发送）的邮件数
     * @throws BusinessException 开启真实发送且全部投递失败
     */
    @Override
    public int sendUrgeMail(Long homeworkId) {
        HomeworkTask task = loadTaskOrThrow(homeworkId);
        ClazzInfo clazz = clazzInfoMapper.selectById(task.getClazzId());
        // 已提交集合：状态为按时交(1)/逾期(2)/已批改(3)均视为已交；占位(0)仍算未交
        Set<Long> submittedIds = new HashSet<>();
        for (HomeworkSubmission sub : homeworkSubmissionMapper.selectByHomeworkId(homeworkId)) {
            if (sub.getSubmitStatus() != null && sub.getSubmitStatus() != Constants.SUBMIT_NOT) {
                submittedIds.add(sub.getStudentId());
            }
        }
        // 收件人：在册学生中剔除已提交者（消费时重新判定，避免误发给刚补交的学生）
        List<SysUser> targets = new ArrayList<>();
        for (SysUser student : sysUserMapper.selectStudentsByClazzId(task.getClazzId())) {
            if (!submittedIds.contains(student.getId())) {
                targets.add(student);
            }
        }
        String subject = "作业催交提醒：" + task.getTitle();
        String body = "同学你好！\n\n"
                + "课程班级：" + (clazz == null ? "-" : clazz.getClazzName()) + "\n"
                + "作业名称：" + task.getTitle() + "\n"
                + "截止时间：" + task.getDeadline().format(DEADLINE_FMT) + "\n\n"
                + "系统显示你尚未提交该作业，请尽快登录系统提交作业正文与附件。\n\n"
                + fromName;
        return dispatch(targets, subject, body, "催交提醒");
    }

    /**
     * 统一投递：按开关决定真实发送或日志模拟；逐封投递、单封失败跳过、全部失败抛异常。
     *
     * @param recipients 收件人列表（含邮箱过滤）
     * @param subject    邮件主题
     * @param body       邮件正文
     * @param scene      场景名（日志区分）
     * @return 实际发送（或日志模拟发送）的邮件数
     * @throws BusinessException 开启真实发送且全部投递失败
     */
    private int dispatch(List<SysUser> recipients, String subject, String body, String scene) {
        // 邮箱过滤：email 为空的收件人跳过（数据库允许为空的历史账号）
        int total = 0;
        int sent = 0;
        int failed = 0;
        for (SysUser user : recipients) {
            String email = user.getEmail();
            if (email == null || email.trim().isEmpty()) {
                continue;
            }
            total++;
            if (!mailEnabled) {
                // dev 降级：不真正投递，仅记录"本应发送"，消费端据此正常 ACK
                log.info("[邮件模拟-{}] 收件人={}({}) 主题={}", scene, user.getRealName(), email, subject);
                sent++;
                continue;
            }
            try {
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(senderAddress);
                mail.setTo(email);
                mail.setSubject(subject);
                mail.setText(body);
                mailSender.send(mail);
                sent++;
            } catch (Exception e) {
                // 单封失败仅 WARN 跳过：部分失败不值得整条消息重放
                failed++;
                log.warn("[邮件-{}] 投递失败 收件人={}({}): {}", scene, user.getRealName(), email, e.getMessage());
            }
        }
        if (total == 0) {
            log.info("[邮件-{}] 无有效收件人，跳过发送", scene);
            return 0;
        }
        // 全部失败才抛异常触发死信：MQ 重投后仍失败则进 DLQ 人工排查
        if (mailEnabled && failed == total) {
            throw new BusinessException(500, "邮件全部投递失败（" + failed + "/" + total + "），转死信人工排查");
        }
        log.info("[邮件-{}] 发送完成：成功 {} 封 / 共 {} 封（失败 {}）", scene, sent, total, failed);
        return sent;
    }

    /**
     * 作业定位（消费端专用：作业可能已被教师删除，此时消息无意义，快速失败进死信）。
     *
     * @param homeworkId 作业ID
     * @return 作业实体
     * @throws BusinessException 作业不存在
     */
    private HomeworkTask loadTaskOrThrow(Long homeworkId) {
        HomeworkTask task = homeworkTaskMapper.selectById(homeworkId);
        if (task == null) {
            throw new BusinessException(404, "作业不存在或已被删除，邮件消息放弃处理: hw=" + homeworkId);
        }
        return task;
    }
}
