package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.common.conf.NslpProperties;
import com.fc.v2.mapper.auto.TNslpTaskItemMapper;
import com.fc.v2.model.auto.TNslpTaskItem;
import com.fc.v2.model.custom.TaskRunResult;
import com.fc.v2.service.support.TaskItemRules;

/**
 * 安排单夜跑测试：空单不崩、窗外只答话、两处记号同落、失败标原由续办、
 * 500 定额、同一趟幂等、老条目同路、过 05:00 收手。
 *
 * mapper 用内存账顶替，条件按 Wrapper 片段里的列名逐个过滤；回写带
 * 「仍搁着」条件，影响行数就是落成的记号数。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TNslpTaskItemServiceImplTest {

    @Mock
    private TNslpTaskItemMapper mapper;
    @Mock
    private NslpProperties props;
    @InjectMocks
    private TNslpTaskItemServiceImpl service;

    private static final SimpleDateFormat F = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private List<TNslpTaskItem> store;
    /** 发出那一下要出错的行（模拟库面报错） */
    private final Set<Long> throwForSendIds = new HashSet<Long>();
    /** 并着的另一趟先落记号的行（本趟回写影响 0 行） */
    private final Set<Long> raceLoseIds = new HashSet<Long>();
    /** 回写时用到的 Wrapper，留着断「仍搁着」条件在不在 */
    private final List<UpdateWrapper<?>> usedWrappers = new ArrayList<UpdateWrapper<?>>();

    private static Date d(String s) {
        try {
            return F.parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final Pattern CLAUSE = Pattern.compile(
            "(\\w+)\\s*(<=|>=|=|<|>)\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)}");

    private static boolean eq(Object field, Object val) {
        if (field == null || val == null) {
            return field == val;
        }
        return field.equals(val) || String.valueOf(field).equals(String.valueOf(val));
    }

    private static List<String[]> clauses(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> w) {
        Map<String, Object> params = w.getParamNameValuePairs();
        List<String[]> out = new ArrayList<String[]>();
        String seg = w.getSqlSegment() == null ? "" : w.getSqlSegment();
        Matcher m = CLAUSE.matcher(seg);
        while (m.find()) {
            out.add(new String[]{m.group(1), m.group(2), String.valueOf(params.get(m.group(3)))});
        }
        return out;
    }

    private boolean matches(TNslpTaskItem r, List<String[]> cs, String seg) {
        for (String[] c : cs) {
            Object v;
            if ("status".equals(c[0])) {
                v = r.getStatus();
            } else if ("del_flag".equals(c[0])) {
                v = r.getDelFlag();
            } else if ("id".equals(c[0])) {
                v = r.getId();
            } else {
                continue;
            }
            if ("=".equals(c[1]) && !eq(v, c[2])) {
                return false;
            }
        }
        if (seg.contains("due_at IS NOT NULL") && r.getDueAt() == null) {
            return false;
        }
        return true;
    }

    @BeforeEach
    public void setup() {
        store = new ArrayList<TNslpTaskItem>();
        throwForSendIds.clear();
        raceLoseIds.clear();
        usedWrappers.clear();

        when(mapper.selectList(any(QueryWrapper.class))).thenAnswer(inv -> {
            QueryWrapper<?> qw = inv.getArgument(0);
            List<String[]> cs = clauses(qw);
            String seg = qw.getSqlSegment() == null ? "" : qw.getSqlSegment();
            List<TNslpTaskItem> out = new ArrayList<TNslpTaskItem>();
            for (TNslpTaskItem r : store) {
                if (matches(r, cs, seg)) {
                    out.add(r);
                }
            }
            out.sort(Comparator.comparing(TNslpTaskItem::getDueAt).thenComparing(TNslpTaskItem::getId));
            return out;
        });

        when(mapper.update(any(TNslpTaskItem.class), any(UpdateWrapper.class))).thenAnswer(inv -> {
            TNslpTaskItem upd = inv.getArgument(0);
            UpdateWrapper<?> uw = inv.getArgument(1);
            usedWrappers.add(uw);
            if (throwForSendIds.contains(upd.getId())
                    && upd.getStatus() != null && upd.getStatus().intValue() == TaskItemRules.STATUS_DONE) {
                throw new RuntimeException("库面打了个嗝");
            }
            List<String[]> cs = clauses(uw);
            int affected = 0;
            for (TNslpTaskItem r : store) {
                if (!matches(r, cs, "")) {
                    continue;
                }
                if (raceLoseIds.contains(r.getId())) {
                    continue; // 并着的另一趟先落了记号，本趟影响 0 行
                }
                if (upd.getStatus() != null) {
                    r.setStatus(upd.getStatus());
                }
                if (upd.getContent() != null) {
                    r.setContent(upd.getContent());
                }
                if (upd.getRemark() != null) {
                    r.setRemark(upd.getRemark());
                }
                affected++;
            }
            return affected;
        });

        // 默认墙钟：窗口内 03:00
        service.setClock(() -> d("2026-10-06 03:00:00"));
    }

    private TNslpTaskItem row(long id, String dueAt, String amount) {
        TNslpTaskItem r = new TNslpTaskItem();
        r.setId(id);
        r.setItemNo("IT" + id);
        r.setDueAt(d(dueAt));
        r.setAmount(amount == null ? null : new BigDecimal(amount));
        r.setContent("事由与所对点位" + id);
        r.setStatus(TaskItemRules.STATUS_WAIT);
        r.setDelFlag(0);
        r.setCreateTime(d("2026-10-01 00:00:00"));
        return r;
    }

    // ------------------------------------------------------------------
    // 空单是合法输入：安全回 0，不许去拿头一条
    // ------------------------------------------------------------------

    @Test
    public void emptySheetReturnsZeroSafely() {
        TaskRunResult r = service.runOnce(d("2026-10-06 03:00:00"));
        assertEquals(0, r.getPicked());
        assertEquals(0, r.getSent());
        assertEquals(0, r.getFailed());
        assertEquals("本轮未改动安排单", r.getMessage());
    }

    // ------------------------------------------------------------------
    // 窗口之外：只答话，一行不动；连按两回一字不差
    // ------------------------------------------------------------------

    @Test
    public void outsideWindowTalksOnly() {
        store.add(row(1L, "2026-10-05 00:00:00", null));
        service.setClock(() -> d("2026-10-06 12:00:00"));

        TaskRunResult r1 = service.runOnce(d("2026-10-06 12:00:00"));
        TaskRunResult r2 = service.runOnce(d("2026-10-06 12:00:00"));

        assertEquals("本轮未改动安排单", r1.getMessage());
        assertEquals(r1.getMessage(), r2.getMessage());
        assertEquals(0, r1.getPicked());
        assertEquals(TaskItemRules.STATUS_WAIT, store.get(0).getStatus().intValue());
        assertEquals("事由与所对点位1", store.get(0).getContent());
    }

    // ------------------------------------------------------------------
    // 办成一行：两处记号同一次落
    // ------------------------------------------------------------------

    @Test
    public void sentRowGetsBothMarks() {
        store.add(row(1L, "2026-10-05 08:00:00", null));
        Date at = d("2026-10-06 03:00:00");

        TaskRunResult r = service.runOnce(at);

        assertEquals(1, r.getPicked());
        assertEquals(1, r.getSent());
        assertEquals(0, r.getFailed());
        assertEquals("本轮挑中1行，发出1行，0行没成", r.getMessage());
        TNslpTaskItem row = store.get(0);
        assertEquals(TaskItemRules.STATUS_DONE, row.getStatus().intValue());
        assertTrue(row.getContent().startsWith("事由与所对点位1｜已发出 2026-10-06 03:00:00 发给"));
        assertTrue(row.getContent().contains(TaskItemRules.DISPATCH_POST));
    }

    @Test
    public void exactDueMomentCounts() {
        store.add(row(1L, "2026-10-06 03:00:00", null));
        TaskRunResult r = service.runOnce(d("2026-10-06 03:00:00"));
        assertEquals(1, r.getSent());
    }

    @Test
    public void futureDueWithoutAmountStaysWaiting() {
        store.add(row(1L, "2026-10-07 03:00:00", null));
        TaskRunResult r = service.runOnce(d("2026-10-06 03:00:00"));
        assertEquals(0, r.getPicked());
        assertEquals("本轮未改动安排单", r.getMessage());
        assertEquals(TaskItemRules.STATUS_WAIT, store.get(0).getStatus().intValue());
    }

    // ------------------------------------------------------------------
    // 提前几日只认本行那一格，别去邻行借
    // ------------------------------------------------------------------

    @Test
    public void earlyDaysReadFromOwnCellOnly() {
        store.add(row(1L, "2026-10-08 03:00:00", "2"));   // 本行许提前 2 日：今天发得
        store.add(row(2L, "2026-10-08 03:00:00", null));  // 邻行那一格空着：不提前
        TaskRunResult r = service.runOnce(d("2026-10-06 03:00:00"));
        assertEquals(1, r.getSent());
        assertEquals(TaskItemRules.STATUS_DONE, store.get(0).getStatus().intValue());
        assertEquals(TaskItemRules.STATUS_WAIT, store.get(1).getStatus().intValue());
    }

    @Test
    public void closedDaysFromConfigShiftEarlier() {
        // 10-06 是停办日不占日数：届期 10-07 许提前 1 日，提前那一日落到 10-05
        when(props.getClosedDays()).thenReturn(Arrays.asList("2026-10-06"));
        store.add(row(1L, "2026-10-07 03:00:00", "1"));
        TaskRunResult r = service.runOnce(d("2026-10-05 03:00:00"));
        assertEquals(1, r.getSent());
    }

    // ------------------------------------------------------------------
    // 已发出、发不成、已删除的行：都不要再碰
    // ------------------------------------------------------------------

    @Test
    public void terminalAndDeletedRowsUntouched() {
        TNslpTaskItem done = row(1L, "2026-10-05 00:00:00", null);
        done.setStatus(TaskItemRules.STATUS_DONE);
        TNslpTaskItem fail = row(2L, "2026-10-05 00:00:00", null);
        fail.setStatus(TaskItemRules.STATUS_FAIL);
        TNslpTaskItem del = row(3L, "2026-10-05 00:00:00", null);
        del.setDelFlag(1);
        store.add(done);
        store.add(fail);
        store.add(del);

        TaskRunResult r = service.runOnce(d("2026-10-06 03:00:00"));
        assertEquals(0, r.getPicked());
        assertEquals("本轮未改动安排单", r.getMessage());
        assertEquals("事由与所对点位1", store.get(0).getContent());
        assertEquals("事由与所对点位2", store.get(1).getContent());
        assertEquals("事由与所对点位3", store.get(2).getContent());
    }

    // ------------------------------------------------------------------
    // 站不住：本行标一句失败原由，其余各行照常办完
    // ------------------------------------------------------------------

    @Test
    public void defectRowMarkedFailedOthersContinue() {
        TNslpTaskItem bad = row(1L, "2026-10-05 00:00:00", null);
        bad.setItemNo(" ");
        store.add(bad);
        store.add(row(2L, "2026-10-05 00:00:00", null));

        TaskRunResult r = service.runOnce(d("2026-10-06 03:00:00"));

        assertEquals(2, r.getPicked());
        assertEquals(1, r.getSent());
        assertEquals(1, r.getFailed());
        assertEquals("本轮挑中2行，发出1行，1行没成", r.getMessage());
        assertEquals(TaskItemRules.STATUS_FAIL, store.get(0).getStatus().intValue());
        assertTrue(store.get(0).getRemark().startsWith("发不成："));
        assertTrue(store.get(0).getRemark().contains("条目代号"));
        assertEquals(TaskItemRules.STATUS_DONE, store.get(1).getStatus().intValue());
    }

    @Test
    public void sendErrorMarksReasonAndNeverRollsBack() {
        store.add(row(1L, "2026-10-05 00:00:00", null));
        store.add(row(2L, "2026-10-05 01:00:00", null));
        throwForSendIds.add(1L);

        TaskRunResult r = service.runOnce(d("2026-10-06 03:00:00"));

        assertEquals(2, r.getPicked());
        assertEquals(1, r.getSent());
        assertEquals(1, r.getFailed());
        // 报错那行标了原由，另一行照常发出，谁也没被翻回原样
        assertEquals(TaskItemRules.STATUS_FAIL, store.get(0).getStatus().intValue());
        assertTrue(store.get(0).getRemark().contains("发出那一下出了错"));
        assertEquals("事由与所对点位1", store.get(0).getContent());
        assertEquals(TaskItemRules.STATUS_DONE, store.get(1).getStatus().intValue());
        assertTrue(store.get(1).getContent().contains("已发出"));
    }

    // ------------------------------------------------------------------
    // 定额 500：装满即止，剩下的下一趟接着挑
    // ------------------------------------------------------------------

    @Test
    public void quotaCapsAtFiveHundred() {
        for (long i = 1; i <= 501; i++) {
            store.add(row(i, "2026-10-05 00:00:00", null));
        }
        TaskRunResult r = service.runOnce(d("2026-10-06 03:00:00"));
        assertEquals(500, r.getPicked());
        assertEquals(500, r.getSent());
        assertEquals(0, r.getFailed());
        // 没摊到定额的那一行什么也不标，仍搁着
        assertEquals(TaskItemRules.STATUS_WAIT, store.get(500).getStatus().intValue());
        assertEquals("事由与所对点位501", store.get(500).getContent());

        // 下一趟接着挑：剩下那一行这一回办成
        TaskRunResult r2 = service.runOnce(d("2026-10-06 03:30:00"));
        assertEquals(1, r2.getPicked());
        assertEquals(1, r2.getSent());
    }

    // ------------------------------------------------------------------
    // 同一趟并着跑两回要幂等
    // ------------------------------------------------------------------

    @Test
    public void secondRunPicksNothing() {
        store.add(row(1L, "2026-10-05 00:00:00", null));
        TaskRunResult r1 = service.runOnce(d("2026-10-06 03:00:00"));
        assertEquals(1, r1.getSent());

        TaskRunResult r2 = service.runOnce(d("2026-10-06 03:10:00"));
        assertEquals(0, r2.getPicked());
        assertEquals("本轮未改动安排单", r2.getMessage());
        // 记要里只有一回发出记号
        String content = store.get(0).getContent();
        assertEquals(content.indexOf("已发出"), content.lastIndexOf("已发出"));
    }

    @Test
    public void lostRaceRowNotCountedTwice() {
        store.add(row(1L, "2026-10-05 00:00:00", null));
        raceLoseIds.add(1L);

        TaskRunResult r = service.runOnce(d("2026-10-06 03:00:00"));
        assertEquals(1, r.getPicked());
        assertEquals(0, r.getSent());
        assertEquals(0, r.getFailed());
        assertEquals("本轮未改动安排单", r.getMessage());
        assertEquals(TaskItemRules.STATUS_WAIT, store.get(0).getStatus().intValue());
    }

    @Test
    public void writeBackCarriesStillWaitingGuard() {
        store.add(row(1L, "2026-10-05 00:00:00", null));
        service.runOnce(d("2026-10-06 03:00:00"));
        assertEquals(1, usedWrappers.size());
        List<String[]> cs = clauses(usedWrappers.get(0));
        boolean hasStatusGuard = false;
        boolean hasId = false;
        for (String[] c : cs) {
            if ("status".equals(c[0]) && "=".equals(c[1]) && "0".equals(c[2])) {
                hasStatusGuard = true;
            }
            if ("id".equals(c[0])) {
                hasId = true;
            }
        }
        assertTrue(hasStatusGuard, "回写必须带「仍搁着」条件");
        assertTrue(hasId, "回写必须点名本行");
    }

    // ------------------------------------------------------------------
    // 老条目跟今年新建立的条目走同一条路径
    // ------------------------------------------------------------------

    @Test
    public void oldRowsGoTheSamePath() {
        TNslpTaskItem old = row(1L, "2026-10-05 00:00:00", null);
        old.setCreateTime(d("2019-01-01 00:00:00"));
        store.add(old);
        store.add(row(2L, "2026-10-05 00:00:00", null));

        TaskRunResult r = service.runOnce(d("2026-10-06 03:00:00"));
        assertEquals(2, r.getSent());
        assertEquals(TaskItemRules.STATUS_DONE, store.get(0).getStatus().intValue());
        assertEquals(TaskItemRules.STATUS_DONE, store.get(1).getStatus().intValue());
    }

    // ------------------------------------------------------------------
    // 过了 05:00 立刻收手
    // ------------------------------------------------------------------

    @Test
    public void stopsRightAtFive() {
        store.add(row(1L, "2026-10-05 00:00:00", null));
        store.add(row(2L, "2026-10-05 01:00:00", null));
        store.add(row(3L, "2026-10-05 02:00:00", null));
        // 墙钟一格一格走：开趟 04:55，办完第一行 04:59，到第二行前 05:00 收手
        Iterator<Date> ticks = Arrays.asList(
                d("2026-10-06 04:55:00"), d("2026-10-06 04:59:00"), d("2026-10-06 05:00:00"),
                d("2026-10-06 05:00:01")).iterator();
        service.setClock(() -> ticks.hasNext() ? ticks.next() : d("2026-10-06 05:00:01"));

        TaskRunResult r = service.runOnce(d("2026-10-06 04:55:00"));

        assertEquals(1, r.getPicked());
        assertEquals(1, r.getSent());
        assertEquals(TaskItemRules.STATUS_DONE, store.get(0).getStatus().intValue());
        assertEquals(TaskItemRules.STATUS_WAIT, store.get(1).getStatus().intValue());
        assertEquals(TaskItemRules.STATUS_WAIT, store.get(2).getStatus().intValue());
    }

    @Test
    public void listDueOrdersByDueThenId() {
        store.add(row(3L, "2026-10-05 08:00:00", null));
        store.add(row(1L, "2026-10-04 08:00:00", null));
        store.add(row(2L, "2026-10-04 08:00:00", null));
        List<TNslpTaskItem> due = service.listDue(d("2026-10-06 03:00:00"));
        assertEquals(3, due.size());
        assertEquals(1L, due.get(0).getId().longValue());
        assertEquals(2L, due.get(1).getId().longValue());
        assertEquals(3L, due.get(2).getId().longValue());
        assertNotNull(due.get(0).getDueAt());
    }
}
