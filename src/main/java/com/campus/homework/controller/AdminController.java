package com.campus.homework.controller;

import com.campus.homework.dto.ClazzSaveDTO;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.PageQueryDTO;
import com.campus.homework.dto.UserSaveDTO;
import com.campus.homework.service.ClazzInfoService;
import com.campus.homework.service.SysUserService;
import com.campus.homework.utils.Result;
import com.campus.homework.utils.SecurityUtils;
import com.campus.homework.vo.ClazzVO;
import com.campus.homework.vo.ImportResultVO;
import com.campus.homework.vo.PageVO;
import com.campus.homework.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理端接口（/api/admin/**，仅 ROLE_ADMIN）。
 * <p>教务管理员是班级与账号的唯一维护者：班级 CRUD、师生账号 CRUD、
 * 重置密码、启用/禁用。操作者身份同样从 SecurityContextHolder 组装，
 * Service 层的"仅管理员"校验与 @PreAuthorize 构成双保险；所有出参
 * 均为脱敏 VO（UserVO 不含密码密文）。</p>
 *
 * @author campus-homework
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 管理端前缀：SecurityConfig 已限定仅 ROLE_ADMIN 可访问
@RequestMapping("/api/admin")
// 方法级鉴权第二道防线（与 URL 规则互为纵深防御）
@PreAuthorize("hasRole('ADMIN')")
// 构造器注入所需 Service（仅依赖接口）
@RequiredArgsConstructor
public class AdminController {

    /** 班级信息服务：班级 CRUD（管理端） */
    private final ClazzInfoService clazzInfoService;

    /** 系统用户服务：师生账号 CRUD、重置密码、启用/禁用 */
    private final SysUserService sysUserService;

    /**
     * 分页查询班级列表（keyword 模糊匹配编号/名称）。
     *
     * @param dto 分页参数
     * @return 班级分页 VO（人数以实时统计为准）
     */
    @GetMapping("/clazz/page")
    public Result<PageVO<ClazzVO>> pageClazz(@Validated PageQueryDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(clazzInfoService.pageClazz(dto, operator));
    }

    /**
     * 班级详情（管理员可查全部班级）。
     *
     * @param id 班级ID
     * @return 班级详情 VO
     */
    @GetMapping("/clazz/{id}")
    public Result<ClazzVO> clazzDetail(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(clazzInfoService.detail(id, operator));
    }

    /**
     * 新增或修改班级（id 为空新增；保存后按实际在册学生数同步人数）。
     *
     * @param dto 班级表单（编号/名称/院系）
     * @return 无数据成功响应
     */
    @PostMapping("/clazz")
    public Result<Void> saveClazz(@RequestBody @Validated ClazzSaveDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        clazzInfoService.saveClazz(dto, operator);
        log.info("管理员 {} 保存班级（编号 {}）", operator.getUsername(), dto.getClazzCode());
        return Result.success();
    }

    /**
     * 删除班级（班内仍有学生或作业时 Service 层拒绝）。
     *
     * @param id 班级ID
     * @return 无数据成功响应
     */
    @DeleteMapping("/clazz/{id}")
    public Result<Void> removeClazz(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        clazzInfoService.removeClazz(id, operator);
        log.info("管理员 {} 删除班级 {}", operator.getUsername(), id);
        return Result.success();
    }

    /**
     * 分页查询账号列表（keyword 模糊匹配学号/用户名/姓名）。
     *
     * @param dto 分页参数
     * @return 账号分页 VO（不含密码）
     */
    @GetMapping("/user/page")
    public Result<PageVO<UserVO>> pageUsers(@Validated PageQueryDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(sysUserService.pageUsers(dto, operator));
    }

    /**
     * 账号详情（不含密码密文）。
     *
     * @param id 用户ID
     * @return 账号 VO
     */
    @GetMapping("/user/{id}")
    public Result<UserVO> userDetail(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(sysUserService.detail(id, operator));
    }

    /**
     * 新增或修改账号（id 为空新增；密码留空时新增默认 123456、修改表示不变更）。
     *
     * @param dto 账号表单（学号/用户名/姓名/邮箱/角色/班级）
     * @return 无数据成功响应
     */
    @PostMapping("/user")
    public Result<Void> saveUser(@RequestBody @Validated UserSaveDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        sysUserService.saveUser(dto, operator);
        log.info("管理员 {} 保存账号（用户名 {}）", operator.getUsername(), dto.getUsername());
        return Result.success();
    }

    /**
     * 删除账号（禁止删除自己；教师有授课数据、级联规则由 Service 层把关）。
     *
     * @param id 用户ID
     * @return 无数据成功响应
     */
    @DeleteMapping("/user/{id}")
    public Result<Void> removeUser(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        sysUserService.removeUser(id, operator);
        log.info("管理员 {} 删除账号 {}", operator.getUsername(), id);
        return Result.success();
    }

    /**
     * 重置密码为初始密码 123456（BCrypt 加密入库）。
     *
     * @param id 用户ID
     * @return 无数据成功响应
     */
    @PutMapping("/user/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        sysUserService.resetPassword(id, operator);
        log.info("管理员 {} 重置账号 {} 密码", operator.getUsername(), id);
        return Result.success();
    }

    /**
     * 启用/禁用账号（status 在 1/0 间切换；禁用账号登录被框架拦截）。
     *
     * @param id 用户ID
     * @return 无数据成功响应
     */
    @PutMapping("/user/{id}/status")
    public Result<Void> toggleStatus(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        sysUserService.toggleStatus(id, operator);
        log.info("管理员 {} 切换账号 {} 状态", operator.getUsername(), id);
        return Result.success();
    }

    /**
     * CSV 批量导入学生账号（Batch 5 管理端；multipart 文件上传）。
     * <p>CSV 契约与整批事务语义见 SysUserService.importStudents 的 Javadoc：
     * 任一行校验失败则整批一条也不入库，返回逐行错误原因供管理员修正后
     * 重新上传；全部成功时逐条插入并按涉及班级同步在册人数。</p>
     *
     * @param file CSV 文件（表单字段名 file，UTF-8，首行表头）
     * @return 导入结果 VO（total/successCount/failCount/errors）
     */
    @PostMapping("/user/import")
    public Result<ImportResultVO> importStudents(@RequestParam("file") MultipartFile file) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        ImportResultVO result = sysUserService.importStudents(file, operator);
        log.info("管理员 {} CSV 导入完成：共 {} 行，成功 {} 行，失败 {} 行",
                operator.getUsername(), result.getTotal(), result.getSuccessCount(), result.getFailCount());
        return Result.success(result);
    }
}
