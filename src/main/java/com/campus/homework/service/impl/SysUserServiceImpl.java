package com.campus.homework.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.PageQueryDTO;
import com.campus.homework.dto.UserSaveDTO;
import com.campus.homework.entity.ClazzInfo;
import com.campus.homework.entity.ClazzNotice;
import com.campus.homework.entity.HomeworkSubmission;
import com.campus.homework.entity.HomeworkTask;
import com.campus.homework.entity.SysUser;
import com.campus.homework.exception.BusinessException;
import com.campus.homework.exception.ForbiddenException;
import com.campus.homework.exception.NotFoundException;
import com.campus.homework.mapper.ClazzInfoMapper;
import com.campus.homework.mapper.ClazzNoticeMapper;
import com.campus.homework.mapper.HomeworkSubmissionMapper;
import com.campus.homework.mapper.HomeworkTaskMapper;
import com.campus.homework.mapper.SysUserMapper;
import com.campus.homework.service.SysUserService;
import com.campus.homework.utils.Constants;
import com.campus.homework.vo.ImportResultVO;
import com.campus.homework.vo.PageVO;
import com.campus.homework.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 系统用户业务实现：管理端师生账号管理（CRUD、重置密码、启停用）。
 * <p>密码红线：password 仅以 BCrypt 密文入库，出参 VO 一律剔除；
 * 新增账号密码留空时初始化为 123456。学生增删/转班后同步涉及班级的
 * student_count，保证班级人数与名单口径一致。</p>
 *
 * @author campus-homework
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    /** 初始密码明文（仅用于加密前，库中只存密文） */
    private static final String DEFAULT_RAW_PASSWORD = "123456";

    /** CSV 批量导入数据行数上限（防止超大文件拖垮请求与事务） */
    private static final int MAX_IMPORT_ROWS = 1000;

    /** 逐行错误明细最多保留条数（失败过多时只展示前若干条，全量计数仍准确） */
    private static final int MAX_REPORT_ERRORS = 20;

    /** 邮箱基础格式（与 UserSaveDTO 的 @Email 校验口径一致，供 CSV 导入逐行校验复用） */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");

    /** 班级表 Mapper：学生挂班前校验班级存在、同步班级人数 */
    private final ClazzInfoMapper clazzInfoMapper;

    /** 作业表 Mapper：删除教师前校验其是否仍有作业 */
    private final HomeworkTaskMapper homeworkTaskMapper;

    /** 公告表 Mapper：删除教师前校验其是否仍有公告 */
    private final ClazzNoticeMapper clazzNoticeMapper;

    /** 提交表 Mapper：删除学生时级联清理其全部提交记录 */
    private final HomeworkSubmissionMapper homeworkSubmissionMapper;

    /** BCrypt 密码编码器（全局唯一 Bean，与 init.sql 密文互验） */
    private final PasswordEncoder passwordEncoder;

    /**
     * 按用户名查询用户（含密文密码，供 Batch 3 认证使用）。
     *
     * @param username 登录用户名
     * @return 用户实体或 null
     */
    @Override
    public SysUser getByUsername(String username) {
        // 唯一索引直接命中，Mapper 内 100% #{} 预编译
        return getBaseMapper().selectByUsername(username);
    }

    /**
     * 分页查询账号列表（仅管理员；keyword 模糊匹配学号/用户名/姓名）。
     *
     * @param dto      分页参数
     * @param operator 当前登录用户
     * @return 账号分页 VO（不含密码）
     */
    @Override
    public PageVO<UserVO> pageUsers(PageQueryDTO dto, LoginUserDTO operator) {
        requireAdmin(operator);
        // 页码与页容量归一化，页容量封顶 100
        long pageNum = dto.getPageNum() == null || dto.getPageNum() < 1 ? Constants.DEFAULT_PAGE_NUM : dto.getPageNum();
        long pageSize = dto.getPageSize() == null || dto.getPageSize() < 1 ? Constants.DEFAULT_PAGE_SIZE
                : Math.min(dto.getPageSize(), Constants.MAX_PAGE_SIZE);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (dto.getKeyword() != null && !dto.getKeyword().trim().isEmpty()) {
            String kw = dto.getKeyword().trim();
            // 三字段任一命中即返回（like 参数 #{} 传参，无 ${} 注入风险）
            wrapper.and(w -> w.like(SysUser::getUserNo, kw).or().like(SysUser::getUsername, kw).or().like(SysUser::getRealName, kw));
        }
        wrapper.orderByDesc(SysUser::getId);
        Page<SysUser> page = page(new Page<>(pageNum, pageSize), wrapper);
        // Entity -> VO：逐条剔除密码，并联查班级名称供列表展示
        List<UserVO> records = page.getRecords().stream().map(this::toVO).collect(Collectors.toList());
        return buildPage(page, records);
    }

    /**
     * 账号详情（仅管理员）。
     *
     * @param id       用户ID
     * @param operator 当前登录用户
     * @return 账号 VO（不含密码）
     */
    @Override
    public UserVO detail(Long id, LoginUserDTO operator) {
        requireAdmin(operator);
        SysUser user = requireUser(id);
        return toVO(user);
    }

    /**
     * 新增或修改账号（仅管理员，事务内完成唯一性校验、挂班校验与班级人数同步）。
     *
     * @param dto      账号表单
     * @param operator 当前登录用户
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveUser(UserSaveDTO dto, LoginUserDTO operator) {
        requireAdmin(operator);
        // 学号唯一性校验（排除自身）
        Long selfId = dto.getId() == null ? -1L : dto.getId();
        SysUser byNo = getBaseMapper().selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUserNo, dto.getUserNo()).last("LIMIT 1"));
        if (byNo != null && !byNo.getId().equals(selfId)) {
            throw new BusinessException(400, "学号/工号已存在: " + dto.getUserNo());
        }
        // 用户名唯一性校验（排除自身）
        SysUser byName = getBaseMapper().selectByUsername(dto.getUsername());
        if (byName != null && !byName.getId().equals(selfId)) {
            throw new BusinessException(400, "登录用户名已存在: " + dto.getUsername());
        }
        // 学生必须挂到存在的班级；教师/管理员的 clazz_id 一律置空（教师跨班授课）
        Long clazzId = null;
        if (Constants.ROLE_STUDENT.equals(dto.getRole())) {
            if (dto.getClazzId() == null) {
                throw new BusinessException(400, "学生账号必须指定所属班级");
            }
            if (clazzInfoMapper.selectById(dto.getClazzId()) == null) {
                throw new NotFoundException("目标班级不存在: " + dto.getClazzId());
            }
            clazzId = dto.getClazzId();
        }
        SysUser entity = new SysUser();
        BeanUtils.copyProperties(dto, entity);
        entity.setClazzId(clazzId);
        // 状态归一化：表单缺省视为正常(1)
        entity.setStatus(dto.getStatus() == null ? Constants.USER_STATUS_NORMAL : dto.getStatus());
        // 旧班级ID（修改场景下需同步旧班级人数；新增场景为 null）
        Long oldClazzId = null;
        if (dto.getId() == null) {
            // 新增：密码留空则初始化为 123456 的 BCrypt 密文
            String raw = dto.getPassword() == null || dto.getPassword().isEmpty() ? DEFAULT_RAW_PASSWORD : dto.getPassword();
            entity.setPassword(passwordEncoder.encode(raw));
            entity.setCreateTime(LocalDateTime.now());
            save(entity);
            log.info("管理员新增账号: id={}, userNo={}, role={}", entity.getId(), entity.getUserNo(), entity.getRole());
        } else {
            // 修改：目标账号必须存在；先留存旧班级与旧密码，再整体覆盖
            SysUser old = requireUser(dto.getId());
            oldClazzId = old.getClazzId();
            if (dto.getPassword() == null || dto.getPassword().isEmpty()) {
                entity.setPassword(old.getPassword());
            } else {
                entity.setPassword(passwordEncoder.encode(dto.getPassword()));
            }
            entity.setCreateTime(old.getCreateTime());
            updateById(entity);
            log.info("管理员修改账号: id={}", dto.getId());
        }
        // 班级人数同步：新班级必同步；旧班级与被移除/转班时同步，保证两班口径均准确
        syncStudentCount(clazzId);
        if (oldClazzId != null && !oldClazzId.equals(clazzId)) {
            syncStudentCount(oldClazzId);
        }
    }

    /**
     * 删除账号（仅管理员；禁止删自己；教师有作业/公告时禁止删除；
     * 删除学生级联清理其提交记录并同步班级人数）。
     *
     * @param id       用户ID
     * @param operator 当前登录用户
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeUser(Long id, LoginUserDTO operator) {
        requireAdmin(operator);
        // 防误删自己：管理员不能删除当前登录账号
        if (id.equals(operator.getUserId())) {
            throw new BusinessException(400, "不能删除当前登录的管理员账号");
        }
        SysUser user = requireUser(id);
        if (Constants.ROLE_TEACHER.equals(user.getRole())) {
            // 教师仍有已发布作业时禁止删除（提交与成绩引用 teacher_id，删除会造成悬挂）
            Long taskCount = homeworkTaskMapper.selectCount(new LambdaQueryWrapper<HomeworkTask>()
                    .eq(HomeworkTask::getTeacherId, id));
            if (taskCount != null && taskCount > 0) {
                throw new BusinessException(400, "该教师仍有 " + taskCount + " 份作业记录，请先处理作业再删除账号");
            }
            Long noticeCount = clazzNoticeMapper.selectCount(new LambdaQueryWrapper<ClazzNotice>()
                    .eq(ClazzNotice::getTeacherId, id));
            if (noticeCount != null && noticeCount > 0) {
                throw new BusinessException(400, "该教师仍有 " + noticeCount + " 条公告，请先处理公告再删除账号");
            }
        } else if (Constants.ROLE_STUDENT.equals(user.getRole())) {
            // 学生删除前级联清理其全部提交记录（作业 submit_count 由后续统计任务修正）
            homeworkSubmissionMapper.delete(new LambdaQueryWrapper<HomeworkSubmission>()
                    .eq(HomeworkSubmission::getStudentId, id));
            log.info("删除学生 {} 时级联清理其全部提交记录", id);
        }
        removeById(id);
        // 学生原班级人数同步
        if (user.getClazzId() != null) {
            syncStudentCount(user.getClazzId());
        }
        log.info("管理员删除账号: id={}, role={}", id, user.getRole());
    }

    /**
     * 重置密码为初始密码 123456（仅管理员）。
     *
     * @param id       用户ID
     * @param operator 当前登录用户
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, LoginUserDTO operator) {
        requireAdmin(operator);
        SysUser user = requireUser(id);
        SysUser update = new SysUser();
        update.setId(id);
        // 仅更新密码列，其余字段不动
        update.setPassword(passwordEncoder.encode(DEFAULT_RAW_PASSWORD));
        updateById(update);
        log.info("管理员重置账号 {} 密码为初始密码", user.getUsername());
    }

    /**
     * 启用/禁用账号（仅管理员；在 1/0 之间切换）。
     *
     * @param id       用户ID
     * @param operator 当前登录用户
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(Long id, LoginUserDTO operator) {
        requireAdmin(operator);
        SysUser user = requireUser(id);
        // 不允许禁用当前登录管理员自己，避免把自己锁在系统外
        if (id.equals(operator.getUserId())) {
            throw new BusinessException(400, "不能停用当前登录的管理员账号");
        }
        SysUser update = new SysUser();
        update.setId(id);
        // 状态取反：1 -> 0（禁用），0 -> 1（启用）
        update.setStatus(Constants.USER_STATUS_NORMAL == user.getStatus() ? Constants.USER_STATUS_DISABLED : Constants.USER_STATUS_NORMAL);
        updateById(update);
        log.info("管理员切换账号 {} 状态为 {}", user.getUsername(), update.getStatus());
    }

    /**
     * CSV 批量导入学生账号（仅管理员，Batch 5 管理端）。
     * <p>两阶段实现"整批事务"语义：第一阶段逐行解析并校验（列数、必填项、
     * 班级存在、学号/用户名在库内与文件内双重唯一），任何一行失败即记录
     * 错误但不中断后续行的校验——最终 errors 完整呈现全部问题，管理员一次
     * 修正即可重新上传；只要存在失败行，第二阶段（插入）整体跳过，天然
     * 保证"一条也不入库"。第二阶段全部通过时逐条插入（密码统一 123456
     * BCrypt、角色固定 ROLE_STUDENT、状态正常），最后按涉及的每个班级
     * 同步 student_count（与 saveUser 同一口径）。</p>
     * <p>CSV 契约：UTF-8、首行表头、列序 user_no,username,real_name,email,clazz_id；
     * 简单逗号分隔（字段内不允许出现逗号，模板说明已告知使用者）。
     * 全部数据库校验走 MyBatis-Plus LambdaQueryWrapper（#{} 预编译）。</p>
     *
     * @param file     CSV 文件（非空，数据行数上限 1000）
     * @param operator 当前登录管理员
     * @return 导入结果 VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportResultVO importStudents(MultipartFile file, LoginUserDTO operator) {
        requireAdmin(operator);
        // 文件基础校验：非空 + 扩展名限定 .csv
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择要上传的CSV文件");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".csv")) {
            throw new BusinessException(400, "仅支持 .csv 格式文件");
        }
        ImportResultVO result = new ImportResultVO();
        // 校验通过的实体暂存区（第二阶段才真正落库）与文件内唯一性集合、涉及班级集合
        List<SysUser> validRows = new ArrayList<>();
        Set<String> seenUserNos = new HashSet<>();
        Set<String> seenUsernames = new HashSet<>();
        Set<Long> touchedClazzIds = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo == 1) {
                    // 表头行跳过（契约：首行为固定列名，不参与导入）
                    continue;
                }
                if (line.trim().isEmpty()) {
                    // 空行直接跳过（不计入总数，容忍导出工具产生的尾部空行）
                    continue;
                }
                if (result.getTotal() >= MAX_IMPORT_ROWS) {
                    // 行数超限：本行及之后一律不再处理，计为一行失败以阻止整批入库
                    result.setTotal(result.getTotal() + 1);
                    result.setFailCount(result.getFailCount() + 1);
                    addImportError(result, lineNo, "数据行数超过上限" + MAX_IMPORT_ROWS + "，本行及之后未处理");
                    break;
                }
                result.setTotal(result.getTotal() + 1);
                String[] cols = line.split(",", -1);
                String error = validateImportRow(cols, seenUserNos, seenUsernames, touchedClazzIds);
                if (error != null) {
                    result.setFailCount(result.getFailCount() + 1);
                    addImportError(result, lineNo, error);
                    continue;
                }
                // 校验通过：组装实体（角色固定学生、密码统一初始化为 123456 的 BCrypt、状态正常）
                SysUser entity = new SysUser();
                entity.setUserNo(cols[0].trim());
                entity.setUsername(cols[1].trim());
                entity.setRealName(cols[2].trim());
                // email 已在 validateImportRow 中校验非空与格式，此处直接落库（列 NOT NULL）
                entity.setEmail(cols[3].trim());
                entity.setClazzId(Long.valueOf(cols[4].trim()));
                entity.setRole(Constants.ROLE_STUDENT);
                entity.setStatus(Constants.USER_STATUS_NORMAL);
                entity.setPassword(passwordEncoder.encode(DEFAULT_RAW_PASSWORD));
                entity.setCreateTime(LocalDateTime.now());
                validRows.add(entity);
            }
        } catch (IOException e) {
            throw new BusinessException(400, "读取文件失败: " + e.getMessage());
        }
        if (result.getFailCount() == 0) {
            // 第二阶段：全部行校验通过才逐条插入（任一行失败时本节整体跳过，一条也不入库）
            for (SysUser entity : validRows) {
                save(entity);
            }
            result.setSuccessCount(validRows.size());
            // 按涉及的每个班级同步在册人数（与 saveUser 完全同一口径）
            for (Long clazzId : touchedClazzIds) {
                syncStudentCount(clazzId);
            }
            log.info("管理员 {} CSV 批量导入学生 {} 名（涉及班级 {} 个）",
                    operator.getUsername(), validRows.size(), touchedClazzIds.size());
        } else {
            // 整批回滚路径：validRows 不落库，successCount 保持 0，errors 供管理员逐行修正
            log.warn("管理员 {} CSV 导入未执行入库：共 {} 行，校验失败 {} 行（整批一条未入库）",
                    operator.getUsername(), result.getTotal(), result.getFailCount());
        }
        return result;
    }

    /**
     * 校验 CSV 单行数据：全部通过返回 null，否则返回失败原因（供逐行错误报告）。
     * <p>校验顺序：列数 -> 必填项 -> 邮箱格式 -> clazz_id 数字格式 -> 文件内学号/用户名唯一
     * （同批次重复行也是冲突）-> 库内学号/用户名唯一（LambdaQueryWrapper 与
     * selectByUsername 均 #{} 预编译）-> 班级存在。副作用：通过时把学号/用户名
     * 登记进文件内集合、把班级登记进涉及班级集合（供第二阶段人数同步）。</p>
     *
     * @param cols           该行按逗号切分的列数组
     * @param seenUserNos    本文件内已出现过的学号集合
     * @param seenUsernames  本文件内已出现过的用户名集合
     * @param touchedClazzIds 校验通过的班级ID集合（导入成功后同步人数）
     * @return null 表示通过；否则为失败原因文本
     */
    private String validateImportRow(String[] cols, Set<String> seenUserNos,
                                    Set<String> seenUsernames, Set<Long> touchedClazzIds) {
        if (cols.length != 5) {
            return "列数应为5列(user_no,username,real_name,email,clazz_id)，实际" + cols.length + "列";
        }
        String userNo = cols[0].trim();
        String username = cols[1].trim();
        String realName = cols[2].trim();
        String email = cols[3].trim();
        String clazzIdText = cols[4].trim();
        if (userNo.isEmpty() || username.isEmpty() || realName.isEmpty()
                || email.isEmpty() || clazzIdText.isEmpty()) {
            return "user_no/username/real_name/email/clazz_id 均不能为空";
        }
        // 邮箱非空且格式合法（sys_user.email 为 NOT NULL，且与 UserSaveDTO @Email 同口径）
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return "邮箱格式不正确: " + email;
        }
        long clazzId;
        try {
            clazzId = Long.parseLong(clazzIdText);
        } catch (NumberFormatException e) {
            return "clazz_id 必须为数字ID: " + clazzIdText;
        }
        // 文件内唯一性：同批次内重复的学号/用户名同样视为冲突（add 返回 false 即已存在）
        if (!seenUserNos.add(userNo)) {
            return "学号在文件内重复: " + userNo;
        }
        if (!seenUsernames.add(username)) {
            return "登录用户名在文件内重复: " + username;
        }
        // 库内唯一性：与 saveUser 同一校验口径（排除逻辑不适用——导入全部是新增）
        if (getBaseMapper().selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUserNo, userNo).last("LIMIT 1")) != null) {
            return "学号已存在于系统: " + userNo;
        }
        if (getBaseMapper().selectByUsername(username) != null) {
            return "登录用户名已存在于系统: " + username;
        }
        // 班级存在性：学生必须挂到存在的班级
        if (clazzInfoMapper.selectById(clazzId) == null) {
            return "班级不存在: clazz_id=" + clazzId;
        }
        touchedClazzIds.add(clazzId);
        return null;
    }

    /**
     * 追加逐行错误：failCount 全量计数，但明细最多保留前 MAX_REPORT_ERRORS 条
     * （失败成百上千时防止响应体过大，管理员按明细修正后重新上传即可）。
     *
     * @param result 导入结果累加器
     * @param lineNo 出错行号（CSV 物理行号，含表头偏移，与用户看到的行一致）
     * @param reason 失败原因
     */
    private void addImportError(ImportResultVO result, int lineNo, String reason) {
        if (result.getErrors().size() < MAX_REPORT_ERRORS) {
            result.getErrors().add("第" + lineNo + "行: " + reason);
        }
    }

    /**
     * Entity -> VO：剔除密码字段，联查班级名称供管理端列表展示。
     *
     * @param entity 用户实体
     * @return 账号 VO（无 password）
     */
    private UserVO toVO(SysUser entity) {
        UserVO vo = new UserVO();
        // BeanUtils 按同名字段复制，VO 无 password 字段，密文天然被剔除
        BeanUtils.copyProperties(entity, vo);
        if (entity.getClazzId() != null) {
            ClazzInfo clazz = clazzInfoMapper.selectById(entity.getClazzId());
            vo.setClazzName(clazz == null ? null : clazz.getClazzName());
        }
        return vo;
    }

    /**
     * 重算并回写指定班级的在册学生数（以 sys_user 实时统计为准）。
     *
     * @param clazzId 班级ID（null 直接跳过）
     */
    private void syncStudentCount(Long clazzId) {
        if (clazzId == null) {
            return;
        }
        int count = getBaseMapper().countActiveStudents(clazzId);
        ClazzInfo update = new ClazzInfo();
        update.setId(clazzId);
        update.setStudentCount(count);
        // 仅更新 student_count 一列，不触碰班级其他字段
        clazzInfoMapper.updateById(update);
    }

    /**
     * 用户存在性校验。
     *
     * @param id 用户ID
     * @return 用户实体
     */
    private SysUser requireUser(Long id) {
        SysUser user = getById(id);
        if (user == null) {
            throw new NotFoundException("账号不存在或已被删除");
        }
        return user;
    }

    /**
     * 管理员角色校验（垂直越权防线）。
     *
     * @param operator 当前登录用户
     */
    private void requireAdmin(LoginUserDTO operator) {
        if (!Constants.ROLE_ADMIN.equals(operator.getRole())) {
            throw new ForbiddenException("仅教务管理员可管理师生账号");
        }
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
