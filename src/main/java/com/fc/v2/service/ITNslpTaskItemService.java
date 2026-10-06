package com.fc.v2.service;

import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TNslpTaskItem;
import com.fc.v2.model.custom.TaskRunResult;

/**
 * 复测与巡查条目 Service接口（scheduling-job 形状：周期执行，无增删改查入口）
 *
 * 夜里走一趟的唯一把手是 {@link #runOnce(Date)}：夜跑定时与页面执行键都走它，
 * 不留第二个。挑行的取法、窗口的开合、定额的去向，口径全在
 * {@link com.fc.v2.service.support.TaskItemRules}，本接口只是转述。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITNslpTaskItemService {

    /** 按主键回查条目 */
    TNslpTaskItem selectTNslpTaskItemById(Long id);

    /** 台账列表（页面那一屏只是转述库面） */
    List<TNslpTaskItem> selectTNslpTaskItemList(QueryWrapper<TNslpTaskItem> queryWrapper);

    /** 该时刻按同一取法可挑的条目（还搁着、没删、届期含本行许的提前几日；按届期先后排） */
    List<TNslpTaskItem> listDue(Date at);

    /**
     * 执行一趟，返回这一趟的回话（挑中几行、发出几行、几行没成，连同那句答话）。
     * 窗口之外只答话、一行不动；单条失败标原由继续，不中断整趟、不回滚已发出的行。
     */
    TaskRunResult runOnce(Date at);
}
