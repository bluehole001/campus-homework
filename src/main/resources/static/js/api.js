/**
 * 前端统一 API 封装（Batch 5 视图层基础设施）。
 * 所有后端接口一律返回 Result{code,msg,data}：code=200 为成功，
 * 401 由本封装统一跳转登录页（会话失效），403/400/404/500 统一 toast 提示。
 * 页面脚本只需处理成功分支的 data，错误处理集中于此，避免各页面重复造轮子。
 * jQuery AJAX 默认携带 X-Requested-With: XMLHttpRequest 头，
 * SecurityConfig 据此把未认证/越权的页面 AJAX 一律回 JSON（而非重定向 HTML）。
 */
window.HW = (function ($) {
    'use strict';

    /** 登录页地址（401 统一跳转目标） */
    var LOGIN_URL = '/login';

    /**
     * 统一请求入口：method/url/data 由调用方给出，成功时 resolve(data)，
     * 失败时 reject({code,msg}) 并自动 toast（除非调用方指定 silent）。
     * @param opts {url, method, data, json, contentType, silent, fail}
     * @returns {Promise} jQuery Deferred（resolve 参数为 Result.data）
     */
    function request(opts) {
        var settings = {
            url: opts.url,
            type: opts.method || 'GET',
            // jQuery 默认 traditional 序列化会把数组写成 a[]=1&a[]=2；接口均为
            // 简单标量参数，这里用普通对象即可；POST JSON 走 json 分支
            data: opts.data,
            dataType: 'json'
        };
        if (opts.json) {
            // 请求体为 JSON：手动序列化并切换 Content-Type（@RequestBody 接收）
            settings.contentType = 'application/json;charset=UTF-8';
            settings.data = JSON.stringify(opts.json);
        } else if (opts.data && typeof opts.data === 'object') {
            settings.traditional = true;
        }
        if (opts.contentType !== undefined) {
            // 文件上传必须关闭 contentType 让浏览器自动生成 multipart 边界
            settings.contentType = opts.contentType;
        }
        if (opts.processData !== undefined) {
            settings.processData = opts.processData;
        }
        return $.ajax(settings).then(function (res) {
            if (res && res.code === 200) {
                return res.data;
            }
            // HTTP 200 但业务 code 非 200（Service 层业务异常经全局处理器包装）
            handleBizError(res, opts);
            return $.Deferred().reject({ code: res ? res.code : 500, msg: res ? res.msg : '响应格式异常' }).promise();
        }, function (xhr) {
            // HTTP 层错误：401 跳登录，其余解析 Result JSON 后 toast
            var code = xhr.status;
            var msg = '网络异常，请稍后重试';
            try {
                var body = JSON.parse(xhr.responseText);
                if (body && body.msg) { msg = body.msg; }
                if (body && body.code) { code = body.code; }
            } catch (e) { /* 非 JSON（如网关错误页），保留默认文案 */ }
            if (code === 401) {
                // 登录页自身的 HTTP 401（理论上不会出现，登录接口以 200+code 表达凭证错误）
                // 不做重定向，避免刷新页面丢失错误提示
                if (window.location.pathname !== LOGIN_URL) {
                    window.location.href = LOGIN_URL;
                }
            } else if (!opts.silent) {
                toast(msg, 'danger');
            }
            if (opts.fail) { opts.fail({ code: code, msg: msg }); }
            return $.Deferred().reject({ code: code, msg: msg }).promise();
        });
    }

    /** 业务 code 非 200 的统一处理：401 跳登录（已在登录页时不重定向，避免刷新丢提示），其余 toast（silent 时跳过） */
    function handleBizError(res, opts) {
        if (!res) { return; }
        if (res.code === 401) {
            // 登录页自身的凭证错误也走 401：此时重定向只会刷新页面并丢掉错误提示
            if (window.location.pathname !== LOGIN_URL) {
                window.location.href = LOGIN_URL;
            }
            return;
        }
        if (!opts.silent) {
            toast(res.msg || '操作失败', 'danger');
        }
    }

    /**
     * 浮动提示（Bootstrap 5 toast 组件，右上角堆叠，自动 4 秒消失）。
     * @param msg  提示文本
     * @param type success/danger/warning/info
     */
    function toast(msg, type) {
        var container = $('#hw-toast-container');
        if (container.length === 0) {
            container = $('<div id="hw-toast-container" class="toast-container position-fixed top-0 end-0 p-3" style="z-index:1080"></div>').appendTo('body');
        }
        var cls = { success: 'text-bg-success', danger: 'text-bg-danger', warning: 'text-bg-warning', info: 'text-bg-info' }[type || 'info'];
        var el = $('<div class="toast align-items-center border-0 ' + cls + '" role="alert" aria-live="assertive" aria-atomic="true">' +
            '<div class="d-flex"><div class="toast-body">' + esc(msg) + '</div>' +
            '<button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast" aria-label="关闭"></button></div></div>');
        container.append(el);
        var t = new bootstrap.Toast(el[0], { delay: 4000 });
        t.show();
        el.on('hidden.bs.toast', function () { el.remove(); });
    }

    /** HTML 转义：所有由接口数据拼接进 DOM 的文本一律经过本函数，防 XSS */
    function esc(text) {
        if (text === null || text === undefined) { return ''; }
        return String(text)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    /** 空值显示占位（null/空串统一显示为灰色"—"，避免表格出现空白单元格） */
    function dash(text) {
        if (text === null || text === undefined || String(text).trim() === '') { return '<span class="text-muted">—</span>'; }
        return esc(text);
    }

    /** 日期时间格式化：后端统一输出 yyyy-MM-dd HH:mm:ss 字符串，这里仅做空值兜底与截断 */
    function fmtDateTime(text) {
        if (!text) { return '<span class="text-muted">—</span>'; }
        return esc(String(text));
    }

    /** 提交状态徽章（0未交/1按时交/2逾期未交/3已批改，与 Constants 口径一致） */
    function statusBadge(status) {
        var map = {
            0: ['未交', 'bg-secondary'],
            1: ['按时交', 'bg-success'],
            2: ['逾期未交', 'bg-warning text-dark'],
            3: ['已批改', 'bg-primary']
        };
        var item = map[status] || ['未知', 'bg-secondary'];
        return '<span class="badge ' + item[1] + '">' + item[0] + '</span>';
    }

    /** 查重预警单元格：高重复红色高亮 + 相似度百分比 + 最相似同学（复核入口由页面绑定） */
    function repeatCell(row) {
        if (row.isHighRepeat === 1) {
            var sim = row.similarityRate != null ? (Number(row.similarityRate) * 100).toFixed(1) + '%' : '';
            return '<span class="badge bg-danger">高重复 ' + esc(sim) + '</span> ' +
                '<span class="text-muted small">与 ' + dash(row.similarStudentName) + '</span>' +
                ' <a href="javascript:void(0)" class="small repeat-review" data-stu="' + esc(row.studentId) +
                '" data-simstu="' + esc(row.similarStudentId == null ? '' : row.similarStudentId) + '">复核</a>';
        }
        if (row.similarityRate != null) {
            return '<span class="text-muted small">' + (Number(row.similarityRate) * 100).toFixed(1) + '%</span>';
        }
        return '<span class="text-muted small">未检出</span>';
    }

    /** 得分率进度条（score/totalScore，及格线 60% 以下红色、以上绿色） */
    function scoreBar(score, totalScore) {
        if (score === null || score === undefined || !totalScore) { return '<span class="text-muted">—</span>'; }
        var rate = (Number(score) / Number(totalScore) * 100);
        var cls = rate >= 60 ? 'bg-success' : 'bg-danger';
        return '<div class="d-flex align-items-center gap-2"><div class="progress flex-grow-1" style="height:8px;min-width:80px">' +
            '<div class="progress-bar ' + cls + '" style="width:' + Math.min(100, rate).toFixed(1) + '%"></div></div>' +
            '<span class="small">' + esc(score) + ' / ' + esc(totalScore) + '</span></div>';
    }

    /** 分页条渲染（PageVO{pageNum,pages,total} -> Bootstrap 分页，点击回调 onLoad(page)） */
    function renderPager($container, pageVO, onLoad) {
        if (!pageVO || !pageVO.pages || pageVO.pages <= 1) { $container.empty(); return; }
        var cur = pageVO.pageNum, pages = pageVO.pages;
        var html = '<nav><ul class="pagination pagination-sm mb-0">';
        html += pageItem(cur <= 1 ? null : cur - 1, '‹', cur <= 1);
        var start = Math.max(1, cur - 2), end = Math.min(pages, cur + 2);
        if (start > 1) { html += pageItem(1, '1', false); if (start > 2) { html += '<li class="page-item disabled"><span class="page-link">…</span></li>'; } }
        for (var i = start; i <= end; i++) { html += pageItem(i, String(i), i === cur); }
        if (end < pages) { if (end < pages - 1) { html += '<li class="page-item disabled"><span class="page-link">…</span></li>'; } html += pageItem(pages, String(pages), false); }
        html += pageItem(cur >= pages ? null : cur + 1, '›', cur >= pages);
        html += '</ul></nav>';
        $container.html(html).find('a.page-link').on('click', function (e) {
            e.preventDefault();
            var p = parseInt($(this).data('p'), 10);
            if (p && p !== cur) { onLoad(p); }
        });
    }

    /** 单个分页项 HTML（active/disabled 态与 data-p 页码） */
    function pageItem(p, label, disabled) {
        if (p === null) { return '<li class="page-item disabled"><span class="page-link">' + label + '</span></li>'; }
        return '<li class="page-item' + (disabled ? ' active' : '') + '"><a class="page-link" href="javascript:void(0)" data-p="' + p + '">' + label + '</a></li>';
    }

    /** 当前登录用户信息（页面加载时调用 GET /api/auth/me，失败时由 request 统一跳登录） */
    function whoAmI() {
        return request({ url: '/api/auth/me', silent: true });
    }

    /** 登出：POST /api/auth/logout（框架 LogoutFilter 仅接受 POST），成功后回登录页 */
    function logout() {
        $.ajax({ url: '/api/auth/logout', type: 'POST', dataType: 'json' })
            .always(function () { window.location.href = LOGIN_URL; });
    }

    /**
     * ECharts 浅色科技风（Light Tech）公共配置（与 app.css 品牌令牌一致：
     * 透明背景透出玻璃卡与网格、slate 灰文字、蓝青点缀）。所有图表页通过
     * $.extend(HW.chartBase(), {...}) 复用本配置，保证全站图表风格统一。
     * 背景透明（backgroundColor:'transparent'）让玻璃拟态卡片与 30px 科技网格
     * 成为图表底，画面清透；坐标轴线条颜色由 app.css 同款"淡蓝发丝"口径给出，
     * 各页面在覆盖 xAxis/yAxis 时沿用（rgba(37,99,235,.15) 轴线 / .08 网格线）。
     * 调色板顺序：亮蓝 #3B82F6（主系列/折线）→ 科技青 #22D3EE → 成功绿 →
     * 危险红 → 琥珀金 → 靛蓝（与界面语义色一致）。
     */
    function chartBase() {
        return {
            backgroundColor: 'transparent',
            color: ['#3b82f6', '#22d3ee', '#16a34a', '#dc2626', '#f59e0b', '#6366f1'],
            textStyle: { color: '#475569', fontFamily: '-apple-system, "Segoe UI", "Microsoft YaHei", sans-serif' },
            // 双侧 Y 轴（左「百分比(%)」右「平均分」）的轴名与刻度都占外侧空间，
            // 原 right:24 会把右轴文字裁掉，故左右各留出轴名宽度，上下留出轴名行高。
            grid: { left: 56, right: 64, top: 48, bottom: 40 },
            tooltip: {
                trigger: 'axis',
                backgroundColor: 'rgba(255,255,255,.92)',
                borderColor: 'rgba(37,99,235,.18)',
                borderWidth: 1,
                padding: [8, 12],
                textStyle: { color: '#1e293b', fontSize: 12 },
                extraCssText: 'backdrop-filter:blur(8px);box-shadow:0 8px 24px rgba(37,99,235,.15);border-radius:8px;'
            }
        };
    }

    /**
     * 从当前 URL 路径中提取资源ID：取路径中最后一段纯数字段。
     * 页面路由形如 /student/homework/12、/teacher/homework/5/roster，
     * 数字段即作业ID；页面脚本据此调用对应 API，无需后端注入模型。
     * @returns {string|null} 资源ID字符串；路径中无数字段时返回 null
     */
    function pathId() {
        var parts = String(window.location.pathname).split('/');
        for (var i = parts.length - 1; i >= 0; i--) {
            if (/^\d+$/.test(parts[i])) { return parts[i]; }
        }
        return null;
    }

    /**
     * datetime-local 输入值（yyyy-MM-ddTHH:mm）-> 后端 @JsonFormat 契约的
     * yyyy-MM-dd HH:mm:ss（补 :00 秒位，与 HomeworkSaveDTO.deadline 一致）。
     * @param localValue datetime-local 控件的值
     * @returns {string|null} 转换后的 API 格式；空值返回 null
     */
    function toApiDateTime(localValue) {
        if (!localValue) { return null; }
        var v = String(localValue).replace('T', ' ');
        if (v.length === 16) { v += ':00'; }
        return v;
    }

    /**
     * 后端日期字符串（yyyy-MM-dd HH:mm:ss）-> datetime-local 控件值
     * （yyyy-MM-ddTHH:mm，截去秒位），用于编辑表单回填。
     * @param apiValue 后端返回的日期时间字符串
     * @returns {string} 控件值；空值返回空串
     */
    function toLocalInput(apiValue) {
        if (!apiValue) { return ''; }
        var s = String(apiValue).replace(' ', 'T');
        return s.length >= 16 ? s.substring(0, 16) : '';
    }

    /**
     * 读取 URL 查询参数（如 /teacher/homework/new?edit=5 中的 edit）。
     * @param name 参数名
     * @returns {string|null} 参数值；不存在时返回 null
     */
    function queryParam(name) {
        var m = new RegExp('[?&]' + name + '=([^&#]*)').exec(window.location.search);
        if (!m) { return null; }
        try { return decodeURIComponent(m[1]); } catch (e) { return m[1]; }
    }

    /**
     * 附件下载链接（GET /api/file/download，浏览器直接导航，会话鉴权）。
     * @param path 入库相对路径（VO 字段）
     * @param name 原始文件名（仅用于另存为展示，可空）
     * @returns {string} 完整下载 URL（已做 URL 编码）
     */
    function downloadUrl(path, name) {
        if (!path) { return ''; }
        var url = '/api/file/download?path=' + encodeURIComponent(path);
        if (name) { url += '&name=' + encodeURIComponent(String(name)); }
        return url;
    }

    /**
     * 附件下载按钮 HTML（空路径时显示"—"占位，非空时渲染下载链接）。
     * @param path 入库相对路径
     * @param name 原始文件名
     * @returns {string} HTML 片段
     */
    function fileLink(path, name) {
        if (!path) { return '<span class="text-muted">—</span>'; }
        var label = name ? esc(name) : '下载附件';
        return '<a class="btn btn-sm btn-outline-secondary" href="' + downloadUrl(path, name) +
            '" title="' + esc(path) + '">' + label + '</a>';
    }

    return {
        request: request,
        get: function (url, params, opts) { return request($.extend({ url: url, data: params }, opts || {})); },
        postJson: function (url, body, opts) { return request($.extend({ url: url, method: 'POST', json: body }, opts || {})); },
        putJson: function (url, body, opts) { return request($.extend({ url: url, method: 'PUT', json: body }, opts || {})); },
        del: function (url, opts) { return request($.extend({ url: url, method: 'DELETE' }, opts || {})); },
        upload: function (url, formData, opts) {
            return request($.extend({ url: url, method: 'POST', data: formData, contentType: false, processData: false }, opts || {}));
        },
        toast: toast,
        esc: esc,
        dash: dash,
        fmtDateTime: fmtDateTime,
        statusBadge: statusBadge,
        repeatCell: repeatCell,
        scoreBar: scoreBar,
        renderPager: renderPager,
        whoAmI: whoAmI,
        logout: logout,
        chartBase: chartBase,
        pathId: pathId,
        queryParam: queryParam,
        toApiDateTime: toApiDateTime,
        toLocalInput: toLocalInput,
        downloadUrl: downloadUrl,
        fileLink: fileLink
    };
})(jQuery);
