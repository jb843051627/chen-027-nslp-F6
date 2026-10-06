package com.fc.v2.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.common.conf.NslpProperties;
import com.fc.v2.mapper.auto.TNslpTaskItemMapper;
import com.fc.v2.model.auto.TNslpTaskItem;
import com.fc.v2.model.custom.TaskRunResult;
import com.fc.v2.service.ITNslpTaskItemService;
import com.fc.v2.service.support.TaskItemRules;

/**
 * 复测与巡查条目 Service业务层处理（scheduling-job 形状：周期执行）。
 *
 * 复测通知与现场巡查同在一张安排单上，夜里走一趟。本类只落库与装配，口径全在
 * {@link TaskItemRules}：哪一截开不开、哪些行算届期、提前几日怎么数、一趟 500
 * 行从届期最早那一头装满、没摊到定额的行什么也不标。
 *
 * 逐行各自落账：本类不挂整趟事务，每行的两处记号（条目情形改已发出＋记要补上
 * 发出那一刻与发给谁）同一句 UPDATE 落库即定，一行报错翻不动已经发出的行。
 * 回写带「仍搁着」条件（WHERE status=0）：同一趟并着跑两回，先到的落记号，
 * 后到的那一下影响 0 行，不重复发、不重复记——幂等只认这一道。
 *
 * 早先进库的老条目跟今年新建立的条目走同一条路径、同一套挑法，不另算一套。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TNslpTaskItemServiceImpl implements ITNslpTaskItemService {

    @javax.annotation.Resource
    private TNslpTaskItemMapper nslpTaskItemMapper;

    @javax.annotation.Resource
    private NslpProperties nslpProperties;

    /** 本趟墙钟（窗口开合只看它）；单测可顶替，运行期就是系统钟 */
    private Supplier<Date> clock = new Supplier<Date>() {
        @Override
        public Date get() {
            return new Date();
        }
    };

    /** 单测顶替墙钟用（运行期不调用） */
    void setClock(Supplier<Date> clock) {
        this.clock = clock;
    }

    @Override
    public TNslpTaskItem selectTNslpTaskItemById(Long id) {
        return this.nslpTaskItemMapper.selectById(id);
    }

    @Override
    public List<TNslpTaskItem> selectTNslpTaskItemList(QueryWrapper<TNslpTaskItem> queryWrapper) {
        return this.nslpTaskItemMapper.selectList(queryWrapper);
    }

    /**
     * 该时刻可挑的条目：库里先捞出还搁着、没删、有届期的行（按届期先后排，
     * 同刻按主键断先后，定额从届期最早那一头装满），再逐行过
     * {@link TaskItemRules#sendable} 那一道——挑单与回写用的就是这同一种取法。
     */
    @Override
    public List<TNslpTaskItem> listDue(Date at) {
        QueryWrapper<TNslpTaskItem> qw = new QueryWrapper<TNslpTaskItem>();
        qw.eq("status", TaskItemRules.STATUS_WAIT)
                .eq("del_flag", 0)
                .isNotNull("due_at")
                .orderByAsc("due_at")
                .orderByAsc("id");
        List<TNslpTaskItem> waiting = this.nslpTaskItemMapper.selectList(qw);
        Set<LocalDate> closedDays = closedDays();
        List<TNslpTaskItem> due = new ArrayList<TNslpTaskItem>();
        for (TNslpTaskItem r : waiting) {
            if (TaskItemRules.sendable(r, at, closedDays)) {
                due.add(r);
            }
        }
        return due;
    }

    /**
     * 夜里走一趟（唯一触发把手，夜跑定时与页面执行键都走这里）。
     *
     * 窗口之外只答话、一行不动；窗口之内逐行办：站不住的标一句失败原由，
     * 其余各行照常办完，不中断整趟、不回滚已发出的行；定额 500 装满即止，
     * 没摊到的行仍搁着等下一趟；过了 05:00 立刻收手。空单是合法输入，
     * 一行挑不出来就安全回三个 0。
     */
    @Override
    public synchronized TaskRunResult runOnce(Date at) {
        if (!TaskItemRules.windowOpen(this.clock.get())) {
            // 窗外只答话，不动单上任何一行；连按几回，答话一字不差
            return TaskRunResult.of(0, 0, 0);
        }
        int picked = 0;
        int sent = 0;
        int failed = 0;
        for (TNslpTaskItem row : listDue(at)) {
            if (picked >= TaskItemRules.MAX_PER_RUN) {
                break; // 定额装满即止，剩下的下一趟接着挑
            }
            if (!TaskItemRules.windowOpen(this.clock.get())) {
                break; // 过了 05:00 立刻收手，没办到的行仍搁着
            }
            picked++;
            String defect = TaskItemRules.rowDefect(row);
            if (defect != null) {
                if (markFailed(row, defect)) {
                    failed++;
                }
                continue;
            }
            try {
                if (markSent(row, at)) {
                    sent++;
                }
                // 影响 0 行：并着的另一趟已先落记号，本趟不重复发、不重复计数
            } catch (Exception e) {
                // 这一行站不住：标一句失败原由，其余各行照常办完
                try {
                    if (markFailed(row, "发出那一下出了错：" + e.getMessage())) {
                        failed++;
                    }
                } catch (Exception ignore) {
                    // 库够不着时本行仍搁着，下一趟再挑；整趟不许中断
                }
            }
        }
        // 三个数与逐行落下的记号同一次算出；答话只从这三个数放出来
        return TaskRunResult.of(picked, sent, failed);
    }

    /**
     * 办成一行的两处记号同一句落库：条目情形从搁着改成已发出，记要补上
     * 这一回的发出那一刻与发给谁。带「仍搁着」条件，并着的另一趟落不上。
     */
    private boolean markSent(TNslpTaskItem row, Date at) {
        TNslpTaskItem upd = new TNslpTaskItem();
        upd.setId(row.getId());
        upd.setStatus(Integer.valueOf(TaskItemRules.STATUS_DONE));
        upd.setContent(TaskItemRules.dispatchNote(row.getContent(), at));
        return this.nslpTaskItemMapper.update(upd, stillWaiting(row.getId())) == 1;
    }

    /** 站不住那行的交代：条目情形改成发不成，备注标一句失败原由，同样带「仍搁着」条件 */
    private boolean markFailed(TNslpTaskItem row, String reason) {
        TNslpTaskItem upd = new TNslpTaskItem();
        upd.setId(row.getId());
        upd.setStatus(Integer.valueOf(TaskItemRules.STATUS_FAIL));
        upd.setRemark(TaskItemRules.failNote(reason));
        return this.nslpTaskItemMapper.update(upd, stillWaiting(row.getId())) == 1;
    }

    /** 回写只认「仍搁着」：同一趟并着跑两回，先到的落记号，后到的影响 0 行 */
    private UpdateWrapper<TNslpTaskItem> stillWaiting(Long id) {
        UpdateWrapper<TNslpTaskItem> uw = new UpdateWrapper<TNslpTaskItem>();
        uw.eq("id", id).eq("status", TaskItemRules.STATUS_WAIT);
        return uw;
    }

    /** 法定节假日与停办日：只从配置那一处来，算法只认 {@link TaskItemRules} */
    private Set<LocalDate> closedDays() {
        return TaskItemRules.parseClosedDays(this.nslpProperties.getClosedDays());
    }
}
