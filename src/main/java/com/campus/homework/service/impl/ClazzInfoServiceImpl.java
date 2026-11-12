package com.campus.homework.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.homework.dto.ClazzSaveDTO;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.PageQueryDTO;
import com.campus.homework.entity.ClazzInfo;
import com.campus.homework.entity.HomeworkTask;
import com.campus.homework.entity.SysUser;
import com.campus.homework.exception.BusinessException;
import com.campus.homework.exception.ForbiddenException;
import com.campus.homework.exception.NotFoundException;
import com.campus.homework.mapper.ClazzInfoMapper;
import com.campus.homework.mapper.HomeworkTaskMapper;
import com.campus.homework.mapper.SysUserMapper;
import com.campus.homework.service.ClazzInfoService;
import com.campus.homework.utils.Constants;
import com.campus.homework.vo.ClazzVO;
import com.campus.homework.vo.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 班级信息业务实现：管理端班级 CRUD + 教师/学生端班级下拉数据。
 * <p>分层铁律：QueryWrapper 只在本实现类拼装；写操作全部 @Transactional；
 * 越权校验（仅管理员可维护、教师/学生仅可看自己班级）在方法入口完成。</p>
 *
 * @author campus-homework
 */
@Slf4j
@Service
// 构造器注入各 Mapper，便于单元测试 mock
@RequiredArgsConstructor
public class ClazzInfoServiceImpl extends ServiceImpl<ClazzInfoMapper, ClazzInfo> implements ClazzInfoService {

    /** 用户表 Mapper：统计班级在册人数、删除前校验班内学生 */
    private final SysUserMapper sysUserMapper;

    /** 作业表 Mapper：删除班级前校验是否仍有作业 */
    private final HomeworkTaskMapper homeworkTaskMapper;

    /**
     * 分页查询班级列表（仅管理员）。
     *
     * @param dto      分页与关键字参数
     * @param operator 当前登录用户
     * @return 班级分页 VO
     */
    @Override
    public PageVO<ClazzVO> pageClazz(PageQueryDTO dto, LoginUserDTO operator) {
        // 垂直越权校验：非管理员不得进入管理端班级列表
        requireAdmin(operator);
        // 页码与页容量归一化：非法值回退默认，页容量封顶 100
        long pageNum = dto.getPageNum() == null || dto.getPageNum() < 1 ? Constants.DEFAULT_PAGE_NUM : dto.getPageNum();
        long pageSize = dto.getPageSize() == null || dto.getPageSize() < 1 ? Constants.DEFAULT_PAGE_SIZE
                : Math.min(dto.getPageSize(), Constants.MAX_PAGE_SIZE);
        // 关键字模糊检索：编号或名称任一命中（like 参数经 #{} 预编译，无注入风险）
        LambdaQueryWrapper<ClazzInfo> wrapper = new LambdaQueryWrapper<>();
        if (dto.getKeyword() != null && !dto.getKeyword().trim().isEmpty()) {
            String kw = dto.getKeyword().trim();
            wrapper.and(w -> w.like(ClazzInfo::getClazzCode, kw).or().like(ClazzInfo::getClazzName, kw));
        }
        wrapper.orderByDesc(ClazzInfo::getId);
        Page<ClazzInfo> page = page(new Page<>(pageNum, pageSize), wrapper);
        // Entity -> VO 转换（studentCount 以实时统计为准，修正表内冗余字段）
        List<ClazzVO> records = page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
        return buildPage(page, records);
    }

    /**
     * 班级详情：管理员可查全部，教师/学生仅可查自己所在班级。
     *
     * @param id       班级ID
     * @param operator 当前登录用户
     * @return 班级详情 VO
     */
    @Override
    public ClazzVO detail(Long id, LoginUserDTO operator) {
        // 先取实体，不存在直接 404
        ClazzInfo clazz = requireClazz(id);
        // 水平越权校验：非管理员只能查看自己归属的班级
        if (!Constants.ROLE_ADMIN.equals(operator.getRole()) && !id.equals(operator.getClazzId())) {
            throw new ForbiddenException("只能查看自己所在班级");
        }
        return toVO(clazz);
    }

    /**
     * 新增或修改班级（仅管理员，事务内完成唯一性校验与人数同步）。
     *
     * @param dto      班级表单
     * @param operator 当前登录用户
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveClazz(ClazzSaveDTO dto, LoginUserDTO operator) {
        requireAdmin(operator);
        // 编号唯一性校验：排除自身ID后查重（修改时允许编号不变）
        ClazzInfo byCode = getBaseMapper().selectByCode(dto.getClazzCode());
        if (byCode != null && !byCode.getId().equals(dto.getId())) {
            throw new BusinessException(400, "班级编号已存在: " + dto.getClazzCode());
        }
        ClazzInfo entity = new ClazzInfo();
        BeanUtils.copyProperties(dto, entity);
        // 人数以实时统计为准，不信任表单值
        entity.setStudentCount(sysUserMapper.countActiveStudents(resolveId(dto)));
        if (dto.getId() == null) {
            // 新增：显式赋值创建时间（不依赖 MetaObjectHandler）
            entity.setCreateTime(LocalDateTime.now());
            save(entity);
            log.info("管理员新增班级: id={}, code={}", entity.getId(), entity.getClazzCode());
        } else {
            // 修改：目标班级必须存在
            requireClazz(dto.getId());
            updateById(entity);
            log.info("管理员修改班级: id={}", dto.getId());
        }
    }

    /**
     * 删除班级（仅管理员；班内有学生或有作业时禁止删除，防悬挂引用）。
     *
     * @param id       班级ID
     * @param operator 当前登录用户
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeClazz(Long id, LoginUserDTO operator) {
        requireAdmin(operator);
        requireClazz(id);
        // 前置约束1：班内仍有在册学生时禁止删除（应先迁移或删除学生账号）
        int students = sysUserMapper.countActiveStudents(id);
        if (students > 0) {
            throw new BusinessException(400, "该班级仍有 " + students + " 名在册学生，请先处理学生账号再删除班级");
        }
        // 前置约束2：班级仍有作业时禁止删除（教师跨班作业引用 clazz_id）
        Long taskCount = homeworkTaskMapper.selectCount(new LambdaQueryWrapper<HomeworkTask>()
                .eq(HomeworkTask::getClazzId, id));
        if (taskCount != null && taskCount > 0) {
            throw new BusinessException(400, "该班级仍有 " + taskCount + " 份作业记录，请先删除作业再删除班级");
        }
        removeById(id);
        log.info("管理员删除班级: id={}", id);
    }

    /**
     * 班级下拉数据：管理员返回全部；教师返回自己已发布作业覆盖的班级；
     * 学生返回自己所在班级（单条）。
     *
     * @param operator 当前登录用户
     * @return 班级 VO 列表
     */
    @Override
    public List<ClazzVO> listSelectable(LoginUserDTO operator) {
        String role = operator.getRole();
        // 管理员：全部班级，按编号升序
        if (Constants.ROLE_ADMIN.equals(role)) {
            List<ClazzInfo> all = list(new LambdaQueryWrapper<ClazzInfo>().orderByAsc(ClazzInfo::getClazzCode));
            return all.stream().map(this::toVO).collect(Collectors.toList());
        }
        // 教师：仅返回自己发布过作业的班级（去重），避免教师误挂无关班级
        if (Constants.ROLE_TEACHER.equals(role)) {
            List<HomeworkTask> tasks = homeworkTaskMapper.selectList(new LambdaQueryWrapper<HomeworkTask>()
                    .eq(HomeworkTask::getTeacherId, operator.getUserId())
                    .select(HomeworkTask::getClazzId)
                    .groupBy(HomeworkTask::getClazzId));
            List<Long> clazzIds = tasks.stream().map(HomeworkTask::getClazzId).distinct().collect(Collectors.toList());
            if (clazzIds.isEmpty()) {
                return new ArrayList<>();
            }
            List<ClazzInfo> clazzes = listByIds(clazzIds);
            return clazzes.stream().map(this::toVO).collect(Collectors.toList());
        }
        // 学生：仅自己所在班级一条（页面下拉只读展示）
        if (operator.getClazzId() != null) {
            ClazzInfo clazz = getById(operator.getClazzId());
            if (clazz != null) {
                List<ClazzVO> single = new ArrayList<>();
                single.add(toVO(clazz));
                return single;
            }
        }
        return new ArrayList<>();
    }

    /**
     * Entity -> VO：studentCount 以实时统计覆盖表内冗余值，保证列表口径准确。
     *
     * @param entity 班级实体
     * @return 班级 VO
     */
    private ClazzVO toVO(ClazzInfo entity) {
        ClazzVO vo = new ClazzVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setStudentCount(sysUserMapper.countActiveStudents(entity.getId()));
        return vo;
    }

    /**
     * 班级存在性校验。
     *
     * @param id 班级ID
     * @return 班级实体
     */
    private ClazzInfo requireClazz(Long id) {
        ClazzInfo clazz = getById(id);
        if (clazz == null) {
            throw new NotFoundException("班级不存在或已被删除");
        }
        return clazz;
    }

    /**
     * 管理员角色校验（垂直越权防线）。
     *
     * @param operator 当前登录用户
     */
    private void requireAdmin(LoginUserDTO operator) {
        if (!Constants.ROLE_ADMIN.equals(operator.getRole())) {
            throw new ForbiddenException("仅教务管理员可维护班级信息");
        }
    }

    /**
     * 取表单主键（新增时为 null，返回 -1 供唯一性比较避免 NPE）。
     *
     * @param dto 班级表单
     * @return 主键或 -1
     */
    private Long resolveId(ClazzSaveDTO dto) {
        return dto.getId() == null ? -1L : dto.getId();
    }

    /**
     * MyBatis-Plus 分页对象 -> 通用 PageVO。
     *
     * @param page    持久层分页结果
     * @param records 转换后的 VO 列表
     * @param <T>     VO 类型
     * @return 对外分页 VO
     */
    private <T> PageVO<T> buildPage(Page<?> page, List<T> records) {
        PageVO<T> vo = new PageVO<>();
        vo.setPageNum(page.getCurrent());
        vo.setPageSize(page.getSize());
        vo.setTotal(page.getTotal());
        vo.setPages(page.getPages());
        vo.setRecords(records);
        return vo;
    }
}
