package com.campus.homework.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campus.homework.entity.MonthlySubmitStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 月度提交统计 Mapper：继承 BaseMapper 获得单表 CRUD，补充按班级查询近 N 个月
 * 统计（教师看板趋势折线图数据源）。
 * <p>全部 SQL 使用 #{} 预编译；stat_month 为字符串比较，字典序即时间序。</p>
 *
 * @author campus-homework
 */
@Mapper
public interface MonthlySubmitStatMapper extends BaseMapper<MonthlySubmitStat> {

    /**
     * 按班级与月份唯一定位统计记录（uk_stat_clazz_month 命中，定时任务重跑幂等依据）。
     *
     * @param clazzId   班级ID
     * @param statMonth 统计月份（yyyy-MM）
     * @return 统计记录；不存在时返回 null
     */
    @Select("SELECT * FROM monthly_submit_stat WHERE clazz_id = #{clazzId} AND stat_month = #{statMonth} LIMIT 1")
    MonthlySubmitStat selectByClazzAndMonth(@Param("clazzId") Long clazzId, @Param("statMonth") String statMonth);

    /**
     * 查询某班级最近 N 个月的统计（看板趋势图数据源，按月份升序便于前端直接绘制）。
     *
     * @param clazzId 班级ID
     * @param months  最近月数（由 Service 层校验后传入，防止非法值）
     * @return 统计列表（最多 months 条，按 stat_month ASC）
     */
    @Select("SELECT * FROM monthly_submit_stat WHERE clazz_id = #{clazzId} ORDER BY stat_month DESC LIMIT #{months}")
    List<MonthlySubmitStat> selectRecentMonths(@Param("clazzId") Long clazzId, @Param("months") int months);
}
