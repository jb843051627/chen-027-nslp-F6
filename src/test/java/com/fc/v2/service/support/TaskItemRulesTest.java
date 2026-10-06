package com.fc.v2.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fc.v2.model.auto.TNslpTaskItem;

/**
 * 安排单夜跑纯口径测试：那一截开不开、届期一种取法（等于也算）、提前几日
 * 只认本行那一格（零头不算、不计日跳过、空着不提前）、两处记号句式、
 * 趟末答话只从三个数放出来。
 */
public class TaskItemRulesTest {

    private static final SimpleDateFormat F = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private static Date d(String s) {
        try {
            return F.parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TNslpTaskItem row(Date dueAt, String amount) {
        TNslpTaskItem r = new TNslpTaskItem();
        r.setId(1L);
        r.setItemNo("IT0001");
        r.setDueAt(dueAt);
        r.setAmount(amount == null ? null : new BigDecimal(amount));
        r.setContent("事由与所对点位");
        r.setStatus(TaskItemRules.STATUS_WAIT);
        r.setDelFlag(0);
        return r;
    }

    // ------------------------------------------------------------------
    // 那一截：02:00:00（含）到 05:00:00（不含）
    // ------------------------------------------------------------------

    @Test
    public void windowBoundaries() {
        assertFalse(TaskItemRules.windowOpen(d("2026-10-06 01:59:59")));
        assertTrue(TaskItemRules.windowOpen(d("2026-10-06 02:00:00")));
        assertTrue(TaskItemRules.windowOpen(d("2026-10-06 04:59:59")));
        assertFalse(TaskItemRules.windowOpen(d("2026-10-06 05:00:00")));
        assertFalse(TaskItemRules.windowOpen(d("2026-10-06 12:00:00")));
        assertFalse(TaskItemRules.windowOpen(null));
    }

    // ------------------------------------------------------------------
    // 届期一种取法：走到这一刻就算到，恰好等于这一刻的也算
    // ------------------------------------------------------------------

    @Test
    public void dueMomentExactlyEqualCounts() {
        Date at = d("2026-10-06 02:00:00");
        assertTrue(TaskItemRules.sendable(row(d("2026-10-06 02:00:00"), null), at, null));
        assertTrue(TaskItemRules.sendable(row(d("2026-10-06 01:59:59"), null), at, null));
        assertFalse(TaskItemRules.sendable(row(d("2026-10-06 02:00:01"), null), at, null));
    }

    @Test
    public void onlyWaitingRowsPicked() {
        Date at = d("2026-10-06 02:00:00");
        TNslpTaskItem r = row(d("2026-10-05 00:00:00"), null);
        r.setStatus(TaskItemRules.STATUS_DONE);
        assertFalse(TaskItemRules.sendable(r, at, null));
        r.setStatus(TaskItemRules.STATUS_FAIL);
        assertFalse(TaskItemRules.sendable(r, at, null));
        r.setStatus(null);
        assertFalse(TaskItemRules.sendable(r, at, null));
        r.setStatus(TaskItemRules.STATUS_WAIT);
        r.setDelFlag(1);
        assertFalse(TaskItemRules.sendable(r, at, null));
        r.setDelFlag(0);
        r.setDueAt(null);
        assertFalse(TaskItemRules.sendable(r, at, null));
    }

    // ------------------------------------------------------------------
    // 提前几日：只认本行那一格，零头不算、不计日跳过、空着不提前
    // ------------------------------------------------------------------

    @Test
    public void earlyDaysOnlyWholeDays() {
        assertEquals(0, TaskItemRules.earlyDays(null));
        assertEquals(2, TaskItemRules.earlyDays(new BigDecimal("2")));
        assertEquals(2, TaskItemRules.earlyDays(new BigDecimal("2.9")));
        assertEquals(0, TaskItemRules.earlyDays(new BigDecimal("-3")));
        assertEquals(0, TaskItemRules.earlyDays(new BigDecimal("999")));
        assertEquals(30, TaskItemRules.earlyDays(new BigDecimal("30")));
    }

    @Test
    public void earlyDaysShiftFromOwnCell() {
        // 届期 10-10 03:00，本行许提前 2 日：10-08 03:00 那一刻起发得
        Date due = d("2026-10-10 03:00:00");
        assertEquals(d("2026-10-08 03:00:00"),
                TaskItemRules.earliestSendMoment(due, new BigDecimal("2"), null));
        assertTrue(TaskItemRules.sendable(row(due, "2"), d("2026-10-08 03:00:00"), null));
        assertFalse(TaskItemRules.sendable(row(due, "2"), d("2026-10-08 02:59:59"), null));
        // 那一格空着就当不提前：同一届期，空着的行 10-08 还发不得
        assertFalse(TaskItemRules.sendable(row(due, null), d("2026-10-08 03:00:00"), null));
    }

    @Test
    public void closedDaysDoNotCount() {
        // 届期 10-10 03:00，许提前 2 日；10-09 是停办日不占日数 → 10-07 03:00 起发得
        Date due = d("2026-10-10 03:00:00");
        Set<LocalDate> closed = new HashSet<LocalDate>(Collections.singletonList(LocalDate.of(2026, 10, 9)));
        assertEquals(d("2026-10-07 03:00:00"),
                TaskItemRules.earliestSendMoment(due, new BigDecimal("2"), closed));
        assertTrue(TaskItemRules.sendable(row(due, "2"), d("2026-10-07 03:00:00"), closed));
        assertFalse(TaskItemRules.sendable(row(due, "2"), d("2026-10-07 02:59:59"), closed));
    }

    @Test
    public void parseClosedDaysSkipsBlankAndBad() {
        assertTrue(TaskItemRules.parseClosedDays(null).isEmpty());
        Set<LocalDate> out = TaskItemRules.parseClosedDays(
                Arrays.asList("2026-10-01", " ", null, "十月二日", "2026-10-02"));
        assertEquals(2, out.size());
        assertTrue(out.contains(LocalDate.of(2026, 10, 1)));
        assertTrue(out.contains(LocalDate.of(2026, 10, 2)));
    }

    // ------------------------------------------------------------------
    // 站不住与两处记号
    // ------------------------------------------------------------------

    @Test
    public void rowDefectNamesEachOne() {
        TNslpTaskItem r = row(d("2026-10-05 00:00:00"), null);
        assertNull(TaskItemRules.rowDefect(r));
        r.setItemNo(" ");
        assertTrue(TaskItemRules.rowDefect(r).contains("条目代号"));
        r.setItemNo("IT0001");
        r.setContent("");
        assertTrue(TaskItemRules.rowDefect(r).contains("记要"));
        r.setItemNo(" ");
        String both = TaskItemRules.rowDefect(r);
        assertTrue(both.contains("条目代号") && both.contains("记要"));
    }

    @Test
    public void dispatchNoteCarriesMomentAndPost() {
        String note = TaskItemRules.dispatchNote("事由与所对点位", d("2026-10-06 02:00:00"));
        assertEquals("事由与所对点位｜已发出 2026-10-06 02:00:00 发给" + TaskItemRules.DISPATCH_POST, note);
    }

    @Test
    public void dispatchNoteNeverOverflowsContentCap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            sb.append("事");
        }
        String note = TaskItemRules.dispatchNote(sb.toString(), d("2026-10-06 02:00:00"));
        assertEquals(TaskItemRules.CONTENT_CAP, note.length());
    }

    @Test
    public void failNoteCarriesReason() {
        assertEquals("发不成：条目代号空着", TaskItemRules.failNote("条目代号空着"));
    }

    // ------------------------------------------------------------------
    // 趟末答话：只从三个数放出来，没动单一字不差
    // ------------------------------------------------------------------

    @Test
    public void runMessageOnlyFromThreeNumbers() {
        assertEquals("本轮未改动安排单", TaskItemRules.runMessage(0, 0, 0));
        // 挑中了却一行没落成（并着的另一趟先办了）：本趟没动单，答话一样
        assertEquals("本轮未改动安排单", TaskItemRules.runMessage(3, 0, 0));
        assertEquals("本轮挑中3行，发出2行，1行没成", TaskItemRules.runMessage(3, 2, 1));
        assertEquals("本轮挑中3行，发出0行，1行没成", TaskItemRules.runMessage(3, 0, 1));
    }

    @Test
    public void quotaIsFiveHundred() {
        assertEquals(500, TaskItemRules.MAX_PER_RUN);
    }
}
