package com.campus.homework.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.PageQueryDTO;
import com.campus.homework.dto.UserSaveDTO;
import com.campus.homework.entity.SysUser;
import com.campus.homework.vo.ImportResultVO;
import com.campus.homework.vo.PageVO;
import com.campus.homework.vo.UserVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 系统用户服务接口（管理端师生账号管理 + 认证数据源）。
 * <p>密码红线：任何出参 VO 不含 password；新增默认初始密码 123456，
 * 由实现类注入 PasswordEncoder 做 BCrypt 加密后入库。</p>
 *
 * @author campus-homework
 */
public interface SysUserService extends IService<SysUser> {

    /**
     * 按登录用户名查询用户（Batch 3 的 UserDetailsService 数据源，含密码密文）。
     *
     * @param username 登录用户名
     * @return 用户实体（含密文密码）；不存在时返回 null
     */
    SysUser getByUsername(String username);

    /**
     * 分页查询账号列表（仅 ROLE_ADMIN；keyword 模糊匹配学号/用户名/姓名）。
     *
     * @param dto      分页参数
     * @param operator 当前登录用户
     * @return 账号分页 VO（不含密码）
     */
    PageVO<UserVO> pageUsers(PageQueryDTO dto, LoginUserDTO operator);

    /**
     * 账号详情（仅 ROLE_ADMIN）。
     *
     * @param id       用户ID
     * @param operator 当前登录用户
     * @return 账号 VO（不含密码）
     */
    UserVO detail(Long id, LoginUserDTO operator);

    /**
     * 新增或修改账号（仅 ROLE_ADMIN）。
     * <p>业务规则：user_no/username 唯一；学生必须挂到存在的班级；
     * 新增时密码留空则初始化为 123456 的 BCrypt 密文；修改时密码留空表示不变更；
     * 学生增删或改班级后同步目标班级 student_count。</p>
     *
     * @param dto      账号表单
     * @param operator 当前登录用户
     */
    void saveUser(UserSaveDTO dto, LoginUserDTO operator);

    /**
     * 删除账号（仅 ROLE_ADMIN；禁止删除自己；教师仍有已发布作业/公告时禁止删除；
     * 删除学生时级联清理其全部提交记录并同步班级人数）。
     *
     * @param id       用户ID
     * @param operator 当前登录用户
     */
    void removeUser(Long id, LoginUserDTO operator);

    /**
     * 重置密码为初始密码 123456（仅 ROLE_ADMIN，BCrypt 加密入库）。
     *
     * @param id       用户ID
     * @param operator 当前登录用户
     */
    void resetPassword(Long id, LoginUserDTO operator);

    /**
     * 启用/禁用账号（仅 ROLE_ADMIN；status 在 1/0 间切换，禁用账号登录被拦截）。
     *
     * @param id       用户ID
     * @param operator 当前登录用户
     */
    void toggleStatus(Long id, LoginUserDTO operator);

    /**
     * CSV 批量导入学生账号（仅 ROLE_ADMIN，Batch 5 管理端）。
     * <p>CSV 契约（UTF-8，首行为表头，列序固定）：
     * user_no,username,real_name,email,clazz_id —— 角色一律为 ROLE_STUDENT，
     * 密码统一初始化为 123456（BCrypt 入库），status 一律正常(1)。</p>
     * <p>整批事务语义：逐行校验（列数/必填/班级存在/学号与用户名在库内
     * 及文件内唯一），任一行失败则整批一条也不入库，errors 返回逐行原因
     * （最多 20 条）供管理员修正后重新上传；全部通过时逐条插入并按
     * 涉及班级同步 student_count。</p>
     *
     * @param file     CSV 文件（非空，行数上限 1000）
     * @param operator 当前登录管理员
     * @return 导入结果 VO（total/successCount/failCount/errors）
     */
    ImportResultVO importStudents(MultipartFile file, LoginUserDTO operator);
}
