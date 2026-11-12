package com.campus.homework.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.homework.dto.HomeworkSaveDTO;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.PageQueryDTO;
import com.campus.homework.entity.HomeworkTask;
import com.campus.homework.vo.HomeworkListVO;
import com.campus.homework.vo.PageVO;

/**
 * 作业任务服务接口（教师发布/编辑/删除作业，学生查看本班作业列表）。
 * <p>越权红线：学生仅可查看自己班级的作业；教师仅可维护自己发布的作业；
 * 删除作业时级联清理提交记录与参考附件文件。</p>
 *
 * @author campus-homework
 */
public interface HomeworkTaskService extends IService<HomeworkTask> {

    /**
     * 分页查询学生可见作业（仅 ROLE_STUDENT；限定为登录学生所在班级）。
     *
     * @param dto      分页参数（keyword 模糊匹配标题）
     * @param operator 当前登录学生
     * @return 作业列表 VO（不含个人分数）
     */
    PageVO<HomeworkListVO> pageForStudent(PageQueryDTO dto, LoginUserDTO operator);

    /**
     * 分页查询教师发布的作业（仅 ROLE_TEACHER；限定为登录教师本人发布）。
     *
     * @param dto      分页参数（keyword 模糊匹配标题）
     * @param operator 当前登录教师
     * @return 作业列表 VO（含提交人数与班级人数，教师是数据主人）
     */
    PageVO<HomeworkListVO> pageForTeacher(PageQueryDTO dto, LoginUserDTO operator);

    /**
     * 作业详情（教师仅限本人发布；学生仅限本班作业；教师可看提交统计，学生不含分数）。
     *
     * @param id       作业ID
     * @param operator 当前登录用户
     * @return 作业列表项 VO（详情复用，教师端含 submitCount）
     */
    HomeworkListVO detail(Long id, LoginUserDTO operator);

    /**
     * 发布或编辑作业（仅 ROLE_TEACHER）。
     * <p>业务规则：班级必须存在；新增时截止时间必须晚于当前时间；编辑仅限本人；
     * 已有学生提交后不允许下调满分（避免成绩口径漂移）。</p>
     *
     * @param dto      作业表单
     * @param operator 当前登录教师
     * @return 作业ID（新增时为自增主键，编辑时为原ID）
     */
    Long saveHomework(HomeworkSaveDTO dto, LoginUserDTO operator);

    /**
     * 删除作业（仅 ROLE_TEACHER，仅限本人发布；级联删除全部提交记录与参考附件文件）。
     *
     * @param id       作业ID
     * @param operator 当前登录教师
     */
    void removeHomework(Long id, LoginUserDTO operator);
}
