package com.fc.v2.common.quartz.task;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fc.v2.model.custom.TaskRunResult;
import com.fc.v2.service.ITNslpTaskItemService;

/**
 * 复测与巡查安排单夜跑把手（scheduling-job 形状）。
 *
 * 夜里走一趟只此一个触发把手：在调度台账（sys_quartz_job）里登记
 * {@code nslpTaskItemTask.runOnce()}，cron 落在 02:00–05:00 那一截之内
 * （建议 0 0 2 * * ?）。窗口开不开始终由服务端口径复核——登记错钟点
 * 也只是白按一回，动不了单上一行。页面执行键走的也是同一个服务方法，
 * 不在本处另起第二套。
 *
 * @author fuce
 * @date 2026-10-06
 */
@Component("nslpTaskItemTask")
public class NslpTaskItemTask {

    private static final Logger log = LoggerFactory.getLogger(NslpTaskItemTask.class);

    @Autowired
    private ITNslpTaskItemService nslpTaskItemService;

    /** 夜里走一趟：回话（挑中／发出／没成与那句答话）只从这一趟来 */
    public void runOnce() {
        TaskRunResult r = nslpTaskItemService.runOnce(new Date());
        log.info("安排单夜跑一趟：{}（挑中{}行・发出{}行・{}行没成）",
                r.getMessage(), r.getPicked(), r.getSent(), r.getFailed());
    }
}
