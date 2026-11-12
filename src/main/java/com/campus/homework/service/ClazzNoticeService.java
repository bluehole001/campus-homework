package com.campus.homework.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.campus.homework.dto.LoginUserDTO;
import com.campus.homework.dto.NoticeSaveDTO;
import com.campus.homework.entity.ClazzNotice;
import com.campus.homework.vo.NoticeVO;

import java.util.List;

/**
 * 班级公告服务接口（教师发布/编辑/删除，学生查看本班公告）。
 * <p>越权红线：学生仅可查看自己班级公告；教师仅可维护自己发布的公告。</p>
 *
 * @author campus-homework
 */
public interface ClazzNoticeService extends IService<ClazzNotice> {

    /**
     * 查询某班级公告列表（学生限本班；教师限自己已授课班级；置顶在前）。
     *
     * @param clazzId  班级ID
     * @param operator 当前登录用户
     * @return 公告 VO 列表（含发布人姓名）
     */
    List<NoticeVO> listByClazz(Long clazzId, LoginUserDTO operator);

    /**
     * 发布或编辑公告（仅 ROLE_TEACHER；编辑仅限本人）。
     *
     * @param dto      公告表单
     * @param operator 当前登录教师
     * @return 公告ID
     */
    Long saveNotice(NoticeSaveDTO dto, LoginUserDTO operator);

    /**
     * 删除公告（仅 ROLE_TEACHER，仅限本人发布）。
     *
     * @param id       公告ID
     * @param operator 当前登录教师
     */
    void removeNotice(Long id, LoginUserDTO operator);
}
