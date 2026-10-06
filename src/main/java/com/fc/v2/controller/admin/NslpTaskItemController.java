package com.fc.v2.controller.admin;

import java.util.Date;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TNslpTaskItem;
import com.fc.v2.model.custom.TaskRunResult;
import com.fc.v2.service.ITNslpTaskItemService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * 复测与巡查安排单 Controller（scheduling-job 形状：台账转述＋执行键）。
 *
 * 页面那一屏只是转述：台账照库面列，执行键的回话只引用这一趟给出的
 * 三个数与那句答话，本处不另起一轮逐行数来对冲。窗口之外按执行键，
 * 程序只答话、不动单上任何一行，连按两回答话一字不差。
 *
 * @author fuce
 * @date 2026-10-06
 */
@Api(value = "复测与巡查安排单")
@Controller
@RequestMapping("/nslpTaskItem")
public class NslpTaskItemController extends BaseController {

    @Autowired
    private ITNslpTaskItemService nslpTaskItemService;

    @Log(title = "复测与巡查安排单台账", action = "list")
    @ApiOperation(value = "安排单台账", notes = "照库面列，已发出、发不成的行照常露脸")
    @GetMapping("/list")
    @RequiresPermissions("nslpTaskItem:list")
    @ResponseBody
    public ResultTable list(TNslpTaskItem record) {
        QueryWrapper<TNslpTaskItem> queryWrapper = new QueryWrapper<TNslpTaskItem>();
        startPage();
        com.github.pagehelper.PageInfo<TNslpTaskItem> page =
                new com.github.pagehelper.PageInfo<TNslpTaskItem>(
                        nslpTaskItemService.selectTNslpTaskItemList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "安排单执行一趟", action = "runOnce")
    @ApiOperation(value = "执行键", notes = "与夜跑同走一个把手；窗口之外只答话不动单，答话只引用这一趟的三个数")
    @PostMapping("/runOnce")
    @RequiresPermissions("nslpTaskItem:runOnce")
    @ResponseBody
    public AjaxResult runOnce() {
        TaskRunResult r = nslpTaskItemService.runOnce(new Date());
        return AjaxResult.success(r.getMessage()).put("data", r);
    }
}
