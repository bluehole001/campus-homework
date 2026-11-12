package com.campus.homework.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.homework.dto.ClazzSaveDTO;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.PageQueryDTO;
import com.campus.homework.entity.ClazzInfo;
import com.campus.homework.vo.ClazzVO;
import com.campus.homework.vo.PageVO;

import java.util.List;

/**
 * 班级信息服务接口（管理端班级维护 + 教师/学生端班级下拉数据）。
 * <p>分层铁律：本接口仅声明业务契约，实现与事务在 service.impl 完成；
 * Controller 不得绕过本接口直接操作 Mapper。</p>
 *
 * @author campus-homework
 */
public interface ClazzInfoService extends IService<ClazzInfo> {

    /**
     * 分页查询班级列表（仅 ROLE_ADMIN）。
     *
     * @param dto      分页与关键字参数（keyword 模糊匹配班级编号/名称）
     * @param operator 当前登录用户（非管理员抛 403）
     * @return 班级分页 VO
     */
    PageVO<ClazzVO> pageClazz(PageQueryDTO dto, LoginUserDTO operator);

    /**
     * 班级详情（管理员可查全部；教师/学生仅可查自己所在班级）。
     *
     * @param id       班级ID
     * @param operator 当前登录用户
     * @return 班级详情 VO
     */
    ClazzVO detail(Long id, LoginUserDTO operator);

    /**
     * 新增或修改班级（仅 ROLE_ADMIN；id 为空新增，否则修改）。
     * <p>业务规则：班级编号唯一；保存后按实际在册学生数同步 student_count。</p>
     *
     * @param dto      班级表单
     * @param operator 当前登录用户
     */
    void saveClazz(ClazzSaveDTO dto, LoginUserDTO operator);

    /**
     * 删除班级（仅 ROLE_ADMIN；班内仍有学生或已发布作业时禁止删除）。
     *
     * @param id       班级ID
     * @param operator 当前登录用户
     */
    void removeClazz(Long id, LoginUserDTO operator);

    /**
     * 查询全部班级简要列表（教师发布作业/管理员表单的下拉数据源）。
     *
     * @param operator 当前登录用户（教师仅返回自己已发布作业覆盖的班级，管理员返回全部）
     * @return 班级 VO 列表
     */
    List<ClazzVO> listSelectable(LoginUserDTO operator);
}
