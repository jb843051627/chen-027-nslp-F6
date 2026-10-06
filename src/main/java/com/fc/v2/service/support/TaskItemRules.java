package com.fc.v2.service.support;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fc.v2.model.auto.TNslpTaskItem;

/**
 * 复测与巡查安排单夜跑纯口径（不碰库、不沾 Spring）。
 *
 * 复测通知与现场巡查同在一张安排单上，一行一事。夜里走一趟的规矩只认这一处：
 * 那一截（02:00–05:00）开不开、哪些行算届期（恰好等于这一刻的也算）、提前几日
 * 怎么数（只看本行自己那一格，零头不算、法定节假日不算、停办日不算、空着就当
 * 不提前）、一趟至多 500 行从哪一头装满、没摊到定额的行标成什么（什么也不标，
 * 仍搁着等下一趟）、办成一行的两处记号怎么落、趟末那句答话怎么从三个数放出来。
 * 旁处一律不下结论，页面上那一屏只是转述。
 *
 * @author fuce
 */
public final class TaskItemRules {

    /** 条目情形：搁着（本轮只挑这一档） */
    public static final int STATUS_WAIT = 0;
    /** 条目情形：已发出（终局，不再碰） */
    public static final int STATUS_DONE = 1;
    /** 条目情形：发不成（终局，不再碰） */
    public static final int STATUS_FAIL = 2;

    /** 执行窗口起点（含）：02:00:00 */
    public static final int WIN_FROM_HOUR = 2;
    /** 执行窗口讫点（不含）：05:00:00，过了这一刻立刻收手 */
    public static final int WIN_TO_HOUR = 5;

    /** 一趟至多派几行：装满即止，没摊到的行什么也不标，仍搁着等下一趟 */
    public static final int MAX_PER_RUN = 500;

    /** 提前日数填过这个数算离谱，当这格没填（不提前） */
    public static final int EARLY_DAYS_ABSURD = 366;

    /** 整张安排单只对口这一个承办岗：发给谁只此一处写定 */
    public static final String DISPATCH_POST = "声管科承办岗";

    /** 事由与所对点位的记要（content）库面宽度，补记不许越界 */
    public static final int CONTENT_CAP = 255;
    /** 备注（remark）库面宽度，失败原由不许越界 */
    public static final int REMARK_CAP = 500;

    private TaskItemRules() {
    }

    // ------------------------------------------------------------------
    // 那一截开不开：只认 02:00:00（含）到 05:00:00（不含）这一截墙钟
    // ------------------------------------------------------------------

    /** 这一刻动不动手：窗口内才动；05:00:00 一到即算窗外，立刻收手 */
    public static boolean windowOpen(Date at) {
        if (at == null) {
            return false;
        }
        Calendar c = Calendar.getInstance();
        c.setTime(at);
        int sec = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60
                + c.get(Calendar.SECOND);
        return sec >= WIN_FROM_HOUR * 3600 && sec < WIN_TO_HOUR * 3600;
    }

    // ------------------------------------------------------------------
    // 哪些行算届期：只认一种取法，挑单与回写两头都是它
    // ------------------------------------------------------------------

    /**
     * 这一行此刻发不发得：还搁着、没删、届期那一一日（含本行许的提前几日）
     * 走到本次这一刻了就算到——恰好等于这一刻的也算。
     *
     * @param row        安排单上一行（归哪个区册、排在哪一段只认本行写的，不借邻行）
     * @param at         本次这一刻（一趟之内这一刻钉死，不逐行各取各的）
     * @param closedDays 法定节假日与停办日（这些日子不占提前日数）
     */
    public static boolean sendable(TNslpTaskItem row, Date at, Set<LocalDate> closedDays) {
        if (row == null || at == null) {
            return false;
        }
        if (row.getStatus() == null || row.getStatus().intValue() != STATUS_WAIT) {
            return false;
        }
        if (row.getDelFlag() != null && row.getDelFlag().intValue() != 0) {
            return false;
        }
        if (row.getDueAt() == null) {
            return false;
        }
        return !earliestSendMoment(row.getDueAt(), row.getAmount(), closedDays).after(at);
    }

    /**
     * 本行最早可发那一刻：届期那一刻往前挪本行许的整几日，法定节假日与停办日
     * 不占日数（挪到的日子若是不计日，再往前让）；那一格空着、填负、填得离谱，
     * 都当不提前，最早可发那一刻就是届期那一刻本身。
     */
    public static Date earliestSendMoment(Date dueAt, BigDecimal amount, Set<LocalDate> closedDays) {
        int n = earlyDays(amount);
        if (n <= 0 || dueAt == null) {
            return dueAt;
        }
        Set<LocalDate> closed = closedDays == null ? Collections.<LocalDate>emptySet() : closedDays;
        LocalDate day = dueAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        int remaining = n;
        while (remaining > 0) {
            day = day.minusDays(1);
            if (!closed.contains(day)) {
                remaining--;
            }
        }
        LocalTime time = dueAt.toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
        return Date.from(day.atTime(time).atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     * 本行许提前的整几日（只认这一格）：空着、填负、填得离谱都当不提前；
     * 零头不算（2.9 日就是 2 日）。
     */
    public static int earlyDays(BigDecimal amount) {
        if (amount == null) {
            return 0;
        }
        long n = amount.longValue();
        if (n <= 0 || n > EARLY_DAYS_ABSURD) {
            return 0;
        }
        return (int) n;
    }

    /**
     * 法定节假日与停办日那一串（yyyy-MM-dd 逐个写）放出来给算法用；
     * 空着、写歪的那一格不算数，不按住整趟。
     */
    public static Set<LocalDate> parseClosedDays(List<String> days) {
        Set<LocalDate> out = new HashSet<LocalDate>();
        if (days != null) {
            for (String s : days) {
                if (s == null || s.trim().isEmpty()) {
                    continue;
                }
                try {
                    out.add(LocalDate.parse(s.trim()));
                } catch (DateTimeParseException e) {
                    // 写歪的那一格不算数，不按住整趟
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 一行站不住：只认这两种站不住，原由一句一句点出名
    // ------------------------------------------------------------------

    /** 本行站不站得住：站得住回 null；站不住回那句点名的原由 */
    public static String rowDefect(TNslpTaskItem row) {
        StringBuilder sb = new StringBuilder();
        if (blank(row.getItemNo())) {
            sb.append("条目代号空着");
        }
        if (blank(row.getContent())) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("事由与所对点位的记要空着");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------------
    // 记号怎么落：两处同一句式，只此一处出处
    // ------------------------------------------------------------------

    /**
     * 办成一行的第二处记号：在事由与所对点位的记要末尾补上这一回的发出那一刻
     * 与发给谁（第一处是条目情形改成已发出，两处同一次落，缺一处下一趟还会重挑）。
     * 记要库面只有 255，补记越界就截到库面宽，不许把整行顶出错。
     */
    public static String dispatchNote(String content, Date at) {
        String base = content == null ? "" : content.trim();
        String note = base + "｜已发出 " + ts(at) + " 发给" + DISPATCH_POST;
        return note.length() <= CONTENT_CAP ? note : note.substring(0, CONTENT_CAP);
    }

    /** 站不住那行的交代：备注里标一句失败原由，越界截到库面宽 */
    public static String failNote(String reason) {
        String note = "发不成：" + (reason == null ? "" : reason.trim());
        return note.length() <= REMARK_CAP ? note : note.substring(0, REMARK_CAP);
    }

    // ------------------------------------------------------------------
    // 趟末那句答话：只从这三个数放出来，不许拿内存里的临时数回话
    // ------------------------------------------------------------------

    /**
     * 这一趟动没动单，答话只引用趟末那三个数：一行没发成也没标废，
     * 答话钉死「本轮未改动安排单」，连问几回一字不差；动过就把三个数摆全。
     */
    public static String runMessage(int picked, int sent, int failed) {
        if (sent == 0 && failed == 0) {
            return "本轮未改动安排单";
        }
        return "本轮挑中" + picked + "行，发出" + sent + "行，" + failed + "行没成";
    }

    /** 墙钟串（yyyy-MM-dd HH:mm:ss）：与库面 datetime 同一口钟，两头不许各算各的 */
    public static String ts(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
