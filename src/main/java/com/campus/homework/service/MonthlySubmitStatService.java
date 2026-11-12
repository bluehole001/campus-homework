package com.campus.homework.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.entity.MonthlySubmitStat;
import com.campus.homework.vo.MonthlyStatVO;

import java.util.List;

/**
 * 月度提交统计服务接口（教师看板趋势数据读取 + 定时任务写入入口）。
 * <p>本批次仅提供"读取 + 幂等写入"基础能力；统计口径的聚合计算在 Batch 4
 * 的定时任务中实现，届时调用本接口的 upsert 方法落库。</p>
 *
 * @author campus-homework
 */
public interface MonthlySubmitStatService extends IService<MonthlySubmitStat> {

    /**
     * 查询某班级最近 N 个月的统计（教师看板趋势折线图；教师限自己授课班级）。
     *
     * @param clazzId  班级ID
     * @param months   最近月数（1-24，Service 层校验）
     * @param operator 当前登录用户
     * @return 统计 VO 列表（按月份升序）
     */
    List<MonthlyStatVO> recentMonths(Long clazzId, int months, LoginUserDTO operator);

    /**
     * 幂等写入某班级某月统计（定时任务调用：存在则更新，不存在则插入，
     * 保证同班同月仅一条记录，重跑不产生重复数据）。
     *
     * @param stat 统计实体（clazz_id + stat_month 定位）
     */
    void upsert(MonthlySubmitStat stat);

    /**
     * 聚合生成指定月份的全量班级统计（Spring Task 每月 1 号 00:00 入口，Batch 4）。
     * <p>系统内部调用，无登录态、不适用 IDOR 校验。统计口径：</p>
     * <ul>
     *   <li>归属：作业按发布时间（create_time）所在月归属，任务传入"上月"
     *       即生成上月报表（每月 1 号统计刚结束的自然月）；</li>
     *   <li>total_homework_num：该班当月发布的作业数；当月无作业则不生成
     *       全零行（避免看板出现无意义的 0 值月份）；</li>
     *   <li>avg_submit_rate：逐作业 已交人数/在册人数 后对作业取平均
     *       （已交口径与 countSubmitted 修正口径一致，定时逾期标记不算已交）；</li>
     *   <li>avg_score / pass_rate：仅统计"成绩已发布"作业的已批改样本，
     *       当月无已发布成绩时为 null（与表结构 NULL 语义一致）。</li>
     * </ul>
     * <p>落库一律经 upsert 幂等写入，重跑覆盖同值，不产生重复数据。</p>
     *
     * @param statMonth 统计月份（yyyy-MM，由定时任务传入上月）
     */
    void aggregateMonth(String statMonth);
}
