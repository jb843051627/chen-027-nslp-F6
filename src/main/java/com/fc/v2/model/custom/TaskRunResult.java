package com.fc.v2.model.custom;

import java.io.Serializable;

import com.fc.v2.service.support.TaskItemRules;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * 安排单夜跑一趟的回话：挑中几行、发出几行、几行没成，三个数与逐行落下的
 * 记号同一次算出；那句答话也由这三个数放出来（只此一处来源，页面只是转述）。
 *
 * @author fuce
 */
@ApiModel(value = "TaskRunResult", description = "安排单夜跑一趟的回话（三个数与答话同一次算出）")
public class TaskRunResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 本轮挑中几行（定额装满为止，没摊到的行仍搁着） */
    @ApiModelProperty(value = "本轮挑中几行")
    private final int picked;

    /** 本轮发出几行（两处记号都落成的才算） */
    @ApiModelProperty(value = "本轮发出几行")
    private final int sent;

    /** 本轮几行没成（失败原由已标在本行的才算） */
    @ApiModelProperty(value = "本轮几行没成")
    private final int failed;

    /** 趟末那句答话（只从这三个数放出来） */
    @ApiModelProperty(value = "趟末那句答话")
    private final String message;

    private TaskRunResult(int picked, int sent, int failed) {
        this.picked = picked;
        this.sent = sent;
        this.failed = failed;
        this.message = TaskItemRules.runMessage(picked, sent, failed);
    }

    /** 一趟跑完只在这里成账：三个数与答话同一次算出 */
    public static TaskRunResult of(int picked, int sent, int failed) {
        return new TaskRunResult(picked, sent, failed);
    }

    public int getPicked() {
        return picked;
    }

    public int getSent() {
        return sent;
    }

    public int getFailed() {
        return failed;
    }

    public String getMessage() {
        return message;
    }
}
