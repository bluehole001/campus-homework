package com.campus.homework.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.NoticeSaveDTO;
import com.campus.homework.entity.ClazzInfo;
import com.campus.homework.entity.ClazzNotice;
import com.campus.homework.entity.HomeworkTask;
import com.campus.homework.entity.SysUser;
import com.campus.homework.exception.ForbiddenException;
import com.campus.homework.exception.NotFoundException;
import com.campus.homework.mapper.ClazzInfoMapper;
import com.campus.homework.mapper.ClazzNoticeMapper;
import com.campus.homework.mapper.HomeworkTaskMapper;
import com.campus.homework.mapper.SysUserMapper;
import com.campus.homework.service.ClazzNoticeService;
import com.campus.homework.utils.Constants;
import com.campus.homework.utils.RedisCacheUtils;
import com.campus.homework.vo.NoticeVO;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 班级公告业务实现：教师发布/编辑/删除，学生查看本班公告（置顶在前）。
 * <p>越权红线：学生仅可查看自己班级（clazz_id 匹配）的公告；
 * 教师仅可维护自己发布（teacher_id 匹配）的公告。</p>
 * <p>缓存策略（Batch 3）：公告列表按契约键 homework:notice:clazz:{clazzId}
 * 整体缓存（TTL 5 分钟，空列表写 __NULL__ 防穿透）；发布/编辑/删除在
 * 事务提交后精确失效该班级键——越权校验一律先于缓存读取执行，
 * 缓存命中也不会泄露非本班数据。</p>
 *
 * @author campus-homework
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClazzNoticeServiceImpl extends ServiceImpl<ClazzNoticeMapper, ClazzNotice> implements ClazzNoticeService {

    /** 班级表 Mapper：发布公告前校验班级存在、VO 回填班级名称 */
    private final ClazzInfoMapper clazzInfoMapper;

    /** 用户表 Mapper：VO 回填发布教师姓名 */
    private final SysUserMapper sysUserMapper;

    /** 作业表 Mapper：教师查看非自己班级公告时校验其在该班确有授课（已发布作业） */
    private final HomeworkTaskMapper homeworkTaskMapper;

    /** Redis 缓存工具：公告列表缓存读写与写路径失效（故障时自动降级回源） */
    private final RedisCacheUtils redisCacheUtils;

    /**
     * 查询某班级公告列表（学生限本班；教师限自己授课班级；管理员可巡检全部班级）。
     *
     * @param clazzId  班级ID
     * @param operator 当前登录用户
     * @return 公告 VO 列表（含发布人姓名，置顶在前）
     */
    @Override
    public List<NoticeVO> listByClazz(Long clazzId, LoginUserDTO operator) {
        // 班级必须存在（不存在直接 404，避免拼出"空公告列表"误导前端）
        ClazzInfo clazz = clazzInfoMapper.selectById(clazzId);
        if (clazz == null) {
            throw new NotFoundException("班级不存在或已被删除");
        }
        String role = operator.getRole();
        if (Constants.ROLE_STUDENT.equals(role)) {
            // 水平越权：学生只能查看自己班级的公告
            if (operator.getClazzId() == null || !operator.getClazzId().equals(clazzId)) {
                throw new ForbiddenException("只能查看自己班级的公告");
            }
        } else if (Constants.ROLE_TEACHER.equals(role)) {
            // 教师可查看自己发布过作业的班级公告（授课范围界定），或自己发布的公告所在班级
            Long ownTasks = homeworkTaskMapper.selectCount(new LambdaQueryWrapper<HomeworkTask>()
                    .eq(HomeworkTask::getTeacherId, operator.getUserId())
                    .eq(HomeworkTask::getClazzId, clazzId));
            if (ownTasks == null || ownTasks == 0) {
                throw new ForbiddenException("只能查看自己授课班级的公告");
            }
        } else if (!Constants.ROLE_ADMIN.equals(role)) {
            throw new ForbiddenException("当前角色无权查看公告");
        }
        // 越权校验已通过，此处才读缓存：缓存命中同样不会泄露非本班数据
        return redisCacheUtils.listOrLoad(
                Constants.CACHE_NOTICE_LIST + clazzId,
                Constants.CACHE_TTL_LIST,
                new TypeReference<List<NoticeVO>>() {
                },
                () -> loadNoticeList(clazzId, clazz));
    }

    /**
     * 回源加载公告列表（Mapper 内排序：is_top DESC, create_time DESC，置顶永远在前）。
     *
     * @param clazzId 班级ID
     * @param clazz   已加载的班级实体
     * @return 公告 VO 列表
     */
    private List<NoticeVO> loadNoticeList(Long clazzId, ClazzInfo clazz) {
        List<ClazzNotice> notices = getBaseMapper().selectByClazzId(clazzId);
        return notices.stream().map(n -> toVO(n, clazz)).collect(Collectors.toList());
    }

    /**
     * 发布或编辑公告（仅教师；编辑仅限本人发布的公告）。
     *
     * @param dto      公告表单
     * @param operator 当前登录教师
     * @return 公告ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveNotice(NoticeSaveDTO dto, LoginUserDTO operator) {
        // 垂直越权：仅教师可发布公告
        if (!Constants.ROLE_TEACHER.equals(operator.getRole())) {
            throw new ForbiddenException("仅任课教师可发布公告");
        }
        // 目标班级必须存在
        if (clazzInfoMapper.selectById(dto.getClazzId()) == null) {
            throw new NotFoundException("目标班级不存在: " + dto.getClazzId());
        }
        // 置顶开关归一化：非 1 一律视为不置顶
        int isTop = dto.getIsTop() != null && dto.getIsTop() == Constants.NOTICE_TOP ? Constants.NOTICE_TOP : Constants.NOTICE_NOT_TOP;
        if (dto.getId() == null) {
            // 新发布：教师归属以登录态为准，表单不可伪造
            ClazzNotice notice = new ClazzNotice();
            notice.setClazzId(dto.getClazzId());
            notice.setTeacherId(operator.getUserId());
            notice.setTitle(dto.getTitle());
            notice.setContent(dto.getContent());
            notice.setIsTop(isTop);
            notice.setCreateTime(LocalDateTime.now());
            save(notice);
            // 事务提交后失效该班级公告列表缓存（回滚则不删，旧缓存仍正确）
            redisCacheUtils.evict(Constants.CACHE_NOTICE_LIST + dto.getClazzId());
            log.info("教师 {} 发布公告 {}（班级 {}，置顶 {}）", operator.getUsername(), notice.getId(),
                    dto.getClazzId(), isTop);
            return notice.getId();
        }
        // 编辑：先取原记录做归属校验（水平越权防线）
        ClazzNotice old = getById(dto.getId());
        if (old == null) {
            throw new NotFoundException("公告不存在或已被删除");
        }
        if (!operator.getUserId().equals(old.getTeacherId())) {
            throw new ForbiddenException("只能编辑自己发布的公告");
        }
        ClazzNotice notice = new ClazzNotice();
        notice.setId(dto.getId());
        notice.setClazzId(dto.getClazzId());
        notice.setTitle(dto.getTitle());
        notice.setContent(dto.getContent());
        notice.setIsTop(isTop);
        // 归属与发布时间以库内旧值为准
        notice.setTeacherId(old.getTeacherId());
        notice.setCreateTime(old.getCreateTime());
        updateById(notice);
        // 班级可能变更：新旧班级列表均需失效
        redisCacheUtils.evict(Constants.CACHE_NOTICE_LIST + dto.getClazzId());
        if (old.getClazzId() != null && !old.getClazzId().equals(dto.getClazzId())) {
            redisCacheUtils.evict(Constants.CACHE_NOTICE_LIST + old.getClazzId());
        }
        log.info("教师 {} 编辑公告 {}", operator.getUsername(), dto.getId());
        return dto.getId();
    }

    /**
     * 删除公告（仅教师，仅限本人发布）。
     *
     * @param id       公告ID
     * @param operator 当前登录教师
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeNotice(Long id, LoginUserDTO operator) {
        if (!Constants.ROLE_TEACHER.equals(operator.getRole())) {
            throw new ForbiddenException("仅任课教师可删除公告");
        }
        ClazzNotice notice = getById(id);
        if (notice == null) {
            throw new NotFoundException("公告不存在或已被删除");
        }
        if (!operator.getUserId().equals(notice.getTeacherId())) {
            throw new ForbiddenException("只能删除自己发布的公告");
        }
        removeById(id);
        // 事务提交后失效该班级公告列表缓存
        redisCacheUtils.evict(Constants.CACHE_NOTICE_LIST + notice.getClazzId());
        log.info("教师 {} 删除公告 {}", operator.getUsername(), id);
    }

    /**
     * Entity -> VO：回填班级名称与发布教师姓名，列表页一次渲染完成。
     *
     * @param notice 公告实体
     * @param clazz  已加载的班级实体（调用方已校验存在）
     * @return 公告 VO
     */
    private NoticeVO toVO(ClazzNotice notice, ClazzInfo clazz) {
        NoticeVO vo = new NoticeVO();
        vo.setId(notice.getId());
        vo.setClazzId(notice.getClazzId());
        vo.setClazzName(clazz.getClazzName());
        vo.setTeacherId(notice.getTeacherId());
        // 发布人姓名：教师账号必在库内（删除教师前已校验公告清理）
        SysUser publisher = sysUserMapper.selectById(notice.getTeacherId());
        vo.setPublisherName(publisher == null ? null : publisher.getRealName());
        vo.setTitle(notice.getTitle());
        vo.setContent(notice.getContent());
        vo.setIsTop(notice.getIsTop());
        vo.setCreateTime(notice.getCreateTime());
        return vo;
    }
}
