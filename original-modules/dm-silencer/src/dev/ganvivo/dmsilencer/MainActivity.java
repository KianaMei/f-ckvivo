package dev.ganvivo.dmsilencer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 运营商遥测静音控制台（仅在打开页面或手动点击「刷新」时向运行中的目标进程拉取内存快照，绝不后台轮询、绝不唤醒休眠进程）。
 */
public final class MainActivity extends Activity {

    private static final String PKG_DEVICEREG = "com.vivo.devicereg";
    private static final String PKG_SYSHELPER = "com.mobiletools.systemhelper";
    private static final String[] TARGET_PKGS = {PKG_DEVICEREG, PKG_SYSHELPER};

    private static final String PREF_NAME = "dm_silencer_history";
    private static final String KEY_EVENTS_JSON = "events_json";
    private static final int MAX_STORED_EVENTS = 200;

    private static final int FILTER_ALL = 0;
    private static final int FILTER_DEVICEREG = 1;
    private static final int FILTER_SYSHELPER = 2;
    private static final int FILTER_NET_DB = 3;
    private static final int FILTER_LOC_RX = 4;

    private static final int BG_PAGE = Color.parseColor("#0B0F17");
    private static final int BG_CARD = Color.parseColor("#131A26");
    private static final int BG_SUBCARD = Color.parseColor("#182132");
    private static final int BORDER_CARD = Color.parseColor("#243044");
    private static final int TEXT_PRIMARY = Color.parseColor("#F1F5F9");
    private static final int TEXT_SECONDARY = Color.parseColor("#94A3B8");
    private static final int TEXT_MUTED = Color.parseColor("#64748B");
    private static final int ACCENT_GREEN = Color.parseColor("#34D399");
    private static final int ACCENT_CYAN = Color.parseColor("#38BDF8");
    private static final int ACCENT_AMBER = Color.parseColor("#FBBF24");
    private static final int ACCENT_ROSE = Color.parseColor("#FB7185");
    private static final int ACCENT_VIOLET = Color.parseColor("#A78BFA");

    private static final class ProcessSnapshot {
        final String pkg;
        boolean responded;
        int pid;
        int blockerHooks;
        int fenceHooks;
        int totalBlocks;
        long loadedAtMs;
        String version = "";
        final Map<String, Integer> counters = new LinkedHashMap<String, Integer>();

        ProcessSnapshot(String pkg) {
            this.pkg = pkg;
        }
    }

    private static final class BlockEvent {
        final String id;       // pkg#pid#seq
        final String pkg;
        final int pid;
        final long timeMs;
        final int seq;
        final int ruleCount;
        final String key;
        final String detail;

        BlockEvent(String pkg, int pid, long timeMs, int seq, int ruleCount, String key, String detail) {
            this.pkg = pkg;
            this.pid = pid;
            this.timeMs = timeMs;
            this.seq = seq;
            this.ruleCount = ruleCount;
            this.key = key;
            this.detail = detail;
            this.id = pkg + "#" + pid + "#" + seq;
        }
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Map<String, ProcessSnapshot> mSnapshots = new LinkedHashMap<String, ProcessSnapshot>();
    private final List<BlockEvent> mEvents = new ArrayList<BlockEvent>();
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat mShortTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private int mActiveFilter = FILTER_ALL;
    private int mQueryGen = 0;
    private int mPendingReplies = 0;
    private boolean mDefenseExpanded = false;

    private TextView mLastRefreshView;
    private TextView mRefreshBtn;
    private TextView mMetricTotalBlocks;
    private TextView mMetricTotalSub;
    private TextView mMetricActiveTargets;
    private TextView mMetricActiveSub;
    private LinearLayout mTargetsContainer;
    private LinearLayout mFilterBar;
    private TextView mLogHeaderView;
    private LinearLayout mLogsContainer;
    private TextView mDefenseToggle;
    private TextView mDefenseBody;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        for (String pkg : TARGET_PKGS) {
            mSnapshots.put(pkg, new ProcessSnapshot(pkg));
        }
        loadHistory();
        buildUi();
        renderAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNow(false);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG_PAGE);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(44), pad, dp(32));

        // 1. 顶部标题区
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("运营商遥测静音");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(title);

        TextView verPill = makePill("v" + LibXposedEntry.VERSION, ACCENT_GREEN, Color.parseColor("#103329"));
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        verLp.leftMargin = dp(8);
        headerRow.addView(verPill, verLp);
        root.addView(headerRow);

        TextView subtitle = new TextView(this);
        subtitle.setText("进页面才按需拉取 · 绝不唤醒休眠进程 · 目标进程零落盘");
        subtitle.setTextColor(TEXT_SECONDARY);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setPadding(0, dp(4), 0, dp(4));
        root.addView(subtitle);

        mLastRefreshView = new TextView(this);
        mLastRefreshView.setText("正在查询进程内存快照...");
        mLastRefreshView.setTextColor(TEXT_MUTED);
        mLastRefreshView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mLastRefreshView.setPadding(0, 0, 0, dp(12));
        root.addView(mLastRefreshView);

        // 2. 操作按钮栏（刷新 / 复制日志 / 清空记录）
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowLp.bottomMargin = dp(14);

        mRefreshBtn = makeActionBtn("立即刷新", Color.parseColor("#06281E"), ACCENT_GREEN, ACCENT_GREEN);
        mRefreshBtn.setOnClickListener(v -> refreshNow(false));
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f);
        b1Lp.rightMargin = dp(8);
        btnRow.addView(mRefreshBtn, b1Lp);

        TextView copyBtn = makeActionBtn("复制日志", TEXT_PRIMARY, BG_SUBCARD, BORDER_CARD);
        copyBtn.setOnClickListener(v -> copyVisibleLogs());
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b2Lp.rightMargin = dp(8);
        btnRow.addView(copyBtn, b2Lp);

        TextView clearBtn = makeActionBtn("清空记录", ACCENT_ROSE, Color.parseColor("#25131B"), Color.parseColor("#4C1D2D"));
        clearBtn.setOnClickListener(v -> clearAllHistory());
        LinearLayout.LayoutParams b3Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnRow.addView(clearBtn, b3Lp);

        root.addView(btnRow, btnRowLp);

        // 3. 双列核心指标卡
        LinearLayout metricRow = new LinearLayout(this);
        metricRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams metricRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metricRowLp.bottomMargin = dp(12);

        LinearLayout mCard1 = makeCard();
        LinearLayout.LayoutParams mc1Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mc1Lp.rightMargin = dp(6);
        TextView mLabel1 = makeLabel("累计记录拦截");
        mMetricTotalBlocks = makeBigNumber("0", ACCENT_GREEN);
        mMetricTotalSub = makeTiny("当前无活跃计数");
        mCard1.addView(mLabel1);
        mCard1.addView(mMetricTotalBlocks);
        mCard1.addView(mMetricTotalSub);
        metricRow.addView(mCard1, mc1Lp);

        LinearLayout mCard2 = makeCard();
        LinearLayout.LayoutParams mc2Lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mc2Lp.leftMargin = dp(6);
        TextView mLabel2 = makeLabel("目标进程接管态");
        mMetricActiveTargets = makeBigNumber("0 / 2", ACCENT_CYAN);
        mMetricActiveSub = makeTiny("等待刷新");
        mCard2.addView(mLabel2);
        mCard2.addView(mMetricActiveTargets);
        mCard2.addView(mMetricActiveSub);
        metricRow.addView(mCard2, mc2Lp);

        root.addView(metricRow, metricRowLp);

        // 4. 目标进程实时状态卡片容器
        mTargetsContainer = new LinearLayout(this);
        mTargetsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(mTargetsContainer);

        // 5. 拦截日志卡片（带筛选条）
        LinearLayout logCard = makeCard();
        LinearLayout.LayoutParams logCardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        logCardLp.topMargin = dp(4);
        logCardLp.bottomMargin = dp(12);

        mLogHeaderView = new TextView(this);
        mLogHeaderView.setText("拦截时间线");
        mLogHeaderView.setTextColor(TEXT_PRIMARY);
        mLogHeaderView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mLogHeaderView.setTypeface(Typeface.DEFAULT_BOLD);
        mLogHeaderView.setPadding(0, 0, 0, dp(8));
        logCard.addView(mLogHeaderView);

        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        mFilterBar = new LinearLayout(this);
        mFilterBar.setOrientation(LinearLayout.HORIZONTAL);
        mFilterBar.setPadding(0, 0, 0, dp(10));
        filterScroll.addView(mFilterBar);
        logCard.addView(filterScroll);

        mLogsContainer = new LinearLayout(this);
        mLogsContainer.setOrientation(LinearLayout.VERTICAL);
        logCard.addView(mLogsContainer);

        root.addView(logCard, logCardLp);

        // 6. 可折叠「四层防护清单 & 调试命令」卡
        LinearLayout defCard = makeCard();
        mDefenseToggle = new TextView(this);
        mDefenseToggle.setText("▸ 四层防御清单与 ADB 核验命令（点击展开）");
        mDefenseToggle.setTextColor(TEXT_SECONDARY);
        mDefenseToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mDefenseToggle.setTypeface(Typeface.DEFAULT_BOLD);
        mDefenseToggle.setOnClickListener(v -> {
            mDefenseExpanded = !mDefenseExpanded;
            mDefenseToggle.setText(mDefenseExpanded
                    ? "▾ 四层防御清单与 ADB 核验命令（点击收起）"
                    : "▸ 四层防御清单与 ADB 核验命令（点击展开）");
            mDefenseBody.setVisibility(mDefenseExpanded ? View.VISIBLE : View.GONE);
        });
        defCard.addView(mDefenseToggle);

        mDefenseBody = new TextView(this);
        mDefenseBody.setVisibility(View.GONE);
        mDefenseBody.setTextColor(TEXT_SECONDARY);
        mDefenseBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mDefenseBody.setLineSpacing(dp(3), 1.0f);
        mDefenseBody.setPadding(0, dp(10), 0, 0);
        mDefenseBody.setText(
                "1. 总开关与 DM SDK：锁定 DeviceRegActivity.e=false、ctrl.a.p/t=false，短路 DMController 18/18 上报方法与 DbManager 写库；\n"
                + "2. 采集广播与定位：阻断 DeviceReg 全部自注册广播（保留暗码）及 SystemHelper 7/7 广播、15 分钟百度 GPS 定位与 e1.b.a 上报；\n"
                + "3. 进程级系统 API 围栏：仅在两个目标进程内拦截 URL/Socket 出网、SQLite 写库、LocationManager、Wi-Fi 扫描、Alarm、JobScheduler 与静默短信；\n"
                + "4. 零后台耗电：本 App 仅在打开或点「立即刷新」时通过 FLAG_RECEIVER_REGISTERED_ONLY 查询运行中进程的内存环，休眠进程绝不会被唤醒。\n\n"
                + "ADB 核验命令（点击复制）：\nadb shell su -c \"logcat -d -s DmSilencer\""
        );
        mDefenseBody.setOnClickListener(v -> copyText("DmSilencer ADB", "adb shell su -c \"logcat -d -s DmSilencer\""));
        defCard.addView(mDefenseBody);
        root.addView(defCard);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void refreshNow(final boolean alsoClearRemoteRing) {
        final int gen = ++mQueryGen;
        mPendingReplies = TARGET_PKGS.length;
        mRefreshBtn.setText("查询中...");
        mRefreshBtn.setEnabled(false);

        for (final String pkg : TARGET_PKGS) {
            final ProcessSnapshot snap = new ProcessSnapshot(pkg);
            mSnapshots.put(pkg, snap);

            Intent q = new Intent(StatsResponder.ACTION_QUERY_STATS);
            q.setPackage(pkg);
            q.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);
            if (alsoClearRemoteRing) {
                q.putExtra(StatsResponder.EXTRA_CLEAR_RING, true);
            }

            try {
                sendOrderedBroadcast(q, null, new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (gen != mQueryGen) {
                            return;
                        }
                        Bundle res = getResultExtras(false);
                        if (getResultCode() == Activity.RESULT_OK && res != null && res.getInt("pid", 0) > 0) {
                            applySnapshot(pkg, res);
                        }
                        onSingleQueryDone(gen);
                    }
                }, mMain, Activity.RESULT_CANCELED, null, null);
            } catch (Throwable t) {
                onSingleQueryDone(gen);
            }
        }

        // 兜底超时：防止异常情况下按钮卡死
        mMain.postDelayed(() -> {
            if (gen == mQueryGen && mPendingReplies > 0) {
                mPendingReplies = 0;
                finishRefreshUi();
            }
        }, 1600L);
    }

    private void onSingleQueryDone(int gen) {
        if (gen != mQueryGen) {
            return;
        }
        mPendingReplies--;
        if (mPendingReplies <= 0) {
            mPendingReplies = 0;
            finishRefreshUi();
        }
    }

    private void finishRefreshUi() {
        saveHistory();
        mRefreshBtn.setText("立即刷新");
        mRefreshBtn.setEnabled(true);
        mLastRefreshView.setText("上次刷新：" + mShortTimeFmt.format(new Date()) + "（按需内存拉取，未唤醒休眠进程）");
        renderAll();
    }

    private void applySnapshot(String pkg, Bundle b) {
        ProcessSnapshot snap = mSnapshots.get(pkg);
        if (snap == null) {
            snap = new ProcessSnapshot(pkg);
            mSnapshots.put(pkg, snap);
        }
        snap.responded = true;
        snap.pid = b.getInt("pid", 0);
        snap.blockerHooks = b.getInt("blocker_hooks", 0);
        snap.fenceHooks = b.getInt("fence_hooks", 0);
        snap.totalBlocks = b.getInt("total_blocks", 0);
        snap.loadedAtMs = b.getLong("loaded_at", 0L);
        snap.version = b.getString("version", "");
        snap.counters.clear();

        ArrayList<String> keys = b.getStringArrayList("counter_keys");
        int[] vals = b.getIntArray("counter_vals");
        if (keys != null && vals != null) {
            int n = Math.min(keys.size(), vals.length);
            for (int i = 0; i < n; i++) {
                snap.counters.put(keys.get(i), Integer.valueOf(vals[i]));
            }
        }

        ArrayList<String> rawEvents = b.getStringArrayList("events");
        if (rawEvents != null && !rawEvents.isEmpty()) {
            mergeEvents(pkg, snap.pid, rawEvents);
        }
    }

    private void mergeEvents(String pkg, int pid, List<String> rawLines) {
        Map<String, BlockEvent> byId = new HashMap<String, BlockEvent>();
        for (BlockEvent e : mEvents) {
            byId.put(e.id, e);
        }
        for (String line : rawLines) {
            if (line == null) {
                continue;
            }
            String[] parts = line.split("\\|", 5);
            if (parts.length < 5) {
                continue;
            }
            try {
                long timeMs = Long.parseLong(parts[0]);
                int seq = Integer.parseInt(parts[1]);
                int ruleCount = Integer.parseInt(parts[2]);
                String key = parts[3];
                String detail = parts[4];
                BlockEvent ev = new BlockEvent(pkg, pid, timeMs, seq, ruleCount, key, detail);
                byId.put(ev.id, ev);
            } catch (NumberFormatException ignored) {
            }
        }
        mEvents.clear();
        mEvents.addAll(byId.values());
        Collections.sort(mEvents, new Comparator<BlockEvent>() {
            @Override
            public int compare(BlockEvent a, BlockEvent b) {
                int cmp = Long.compare(b.timeMs, a.timeMs);
                return cmp != 0 ? cmp : Integer.compare(b.seq, a.seq);
            }
        });
        while (mEvents.size() > MAX_STORED_EVENTS) {
            mEvents.remove(mEvents.size() - 1);
        }
    }

    private void renderAll() {
        renderSummaryMetrics();
        renderTargetCards();
        renderFilterBar();
        renderEventTimeline();
    }

    private void renderSummaryMetrics() {
        int activeCount = 0;
        int liveBlocks = 0;
        int totalHooks = 0;
        for (ProcessSnapshot s : mSnapshots.values()) {
            if (s.responded) {
                activeCount++;
                liveBlocks += s.totalBlocks;
                totalHooks += s.blockerHooks + s.fenceHooks;
            }
        }
        int displayBlocks = Math.max(liveBlocks, mEvents.size());
        mMetricTotalBlocks.setText(String.valueOf(displayBlocks));
        mMetricTotalSub.setText("当前活跃进程拦截 " + liveBlocks + " 次 · 本地历史 " + mEvents.size() + " 条");

        mMetricActiveTargets.setText(activeCount + " / " + TARGET_PKGS.length);
        mMetricActiveTargets.setTextColor(activeCount > 0 ? ACCENT_GREEN : ACCENT_CYAN);
        mMetricActiveSub.setText(activeCount > 0
                ? ("已挂载 " + totalHooks + " 个 Hook，其余休眠")
                : "两个进程均未驻留（彻底休眠中）");
    }

    private void renderTargetCards() {
        mTargetsContainer.removeAllViews();
        for (String pkg : TARGET_PKGS) {
            ProcessSnapshot snap = mSnapshots.get(pkg);
            boolean isReg = PKG_DEVICEREG.equals(pkg);
            String title = isReg ? "设备自注册 / 彩讯 DM" : "中国移动信号助手";
            String installedVer = getInstalledVersion(pkg);

            LinearLayout card = makeCard();
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp(10);

            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout nameCol = new LinearLayout(this);
            nameCol.setOrientation(LinearLayout.VERTICAL);
            TextView tvTitle = new TextView(this);
            tvTitle.setText(title);
            tvTitle.setTextColor(TEXT_PRIMARY);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            nameCol.addView(tvTitle);

            TextView tvPkg = new TextView(this);
            tvPkg.setText(pkg + (installedVer.isEmpty() ? "" : (" · " + installedVer)));
            tvPkg.setTextColor(TEXT_MUTED);
            tvPkg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            nameCol.addView(tvPkg);

            topRow.addView(nameCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView statusPill;
            if (snap != null && snap.responded) {
                statusPill = makePill("已接管 · PID " + snap.pid, ACCENT_GREEN, Color.parseColor("#103329"));
            } else {
                statusPill = makePill("休眠未驻留", ACCENT_CYAN, Color.parseColor("#102A3A"));
            }
            topRow.addView(statusPill);
            card.addView(topRow);

            TextView detail = new TextView(this);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            detail.setLineSpacing(dp(2), 1.0f);
            detail.setPadding(0, dp(8), 0, 0);

            if (snap != null && snap.responded) {
                detail.setTextColor(TEXT_SECONDARY);
                String loadedAt = snap.loadedAtMs > 0 ? mShortTimeFmt.format(new Date(snap.loadedAtMs)) : "-";
                StringBuilder sb = new StringBuilder();
                sb.append("Hook：定向 ").append(snap.blockerHooks)
                        .append(" + 围栏 ").append(snap.fenceHooks)
                        .append(" = ").append(snap.blockerHooks + snap.fenceHooks)
                        .append("  |  本轮拦截：").append(snap.totalBlocks).append(" 次")
                        .append("  |  挂载：").append(loadedAt);
                if (!snap.counters.isEmpty()) {
                    sb.append("\n命中规则：");
                    List<Map.Entry<String, Integer>> sorted = new ArrayList<Map.Entry<String, Integer>>(snap.counters.entrySet());
                    Collections.sort(sorted, (a, b) -> Integer.compare(b.getValue().intValue(), a.getValue().intValue()));
                    int limit = Math.min(6, sorted.size());
                    for (int i = 0; i < limit; i++) {
                        if (i > 0) sb.append(" · ");
                        sb.append(shortRuleName(sorted.get(i).getKey()))
                                .append("×").append(sorted.get(i).getValue());
                    }
                }
                detail.setText(sb.toString());
            } else {
                detail.setTextColor(TEXT_MUTED);
                detail.setText("当前进程未在内存驻留（本刷新不会唤醒它，零偷跑耗电）。待系统下次拉起后会自动挂满防御并开始统计；若刚覆盖更新本模块，请先重启旧进程一次。");
            }
            card.addView(detail);
            mTargetsContainer.addView(card, cardLp);
        }
    }

    private void renderFilterBar() {
        mFilterBar.removeAllViews();
        String[] labels = {"全部", "DeviceReg", "SystemHelper", "出网/写库", "定位/广播"};
        for (int i = 0; i < labels.length; i++) {
            final int filterId = i;
            boolean active = (mActiveFilter == filterId);
            int fg = active ? Color.parseColor("#06281E") : TEXT_SECONDARY;
            int bg = active ? ACCENT_GREEN : BG_SUBCARD;
            TextView chip = makePill(labels[i], fg, bg);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            chip.setOnClickListener(v -> {
                mActiveFilter = filterId;
                renderFilterBar();
                renderEventTimeline();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            mFilterBar.addView(chip, lp);
        }
    }

    private void renderEventTimeline() {
        mLogsContainer.removeAllViews();
        List<BlockEvent> filtered = getFilteredEvents();
        mLogHeaderView.setText("拦截时间线（" + filtered.size() + " / " + mEvents.size() + "）");

        if (filtered.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setTextColor(TEXT_MUTED);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            empty.setLineSpacing(dp(3), 1.0f);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            empty.setText(mEvents.isEmpty()
                    ? "暂无拦截记录。目标进程可能正处于彻底休眠，或刚刚开启尚未触发采集；等它们下次企图偷跑时，点击「立即刷新」就会看到每条被拦截的详情。"
                    : "当前筛选条件下没有匹配记录，切换上方分类标签可查看全部。");
            mLogsContainer.addView(empty);
            return;
        }

        int showCount = Math.min(filtered.size(), 120);
        for (int i = 0; i < showCount; i++) {
            BlockEvent ev = filtered.get(i);
            mLogsContainer.addView(makeEventRow(ev));
        }
    }

    private View makeEventRow(BlockEvent ev) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_SUBCARD);
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);

        int[] badgeStyle = badgeStyleFor(ev.key);
        TextView badge = makePill(categoryLabelFor(ev.key), badgeStyle[0], badgeStyle[1]);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        meta.addView(badge);

        TextView metaText = new TextView(this);
        String appShort = PKG_DEVICEREG.equals(ev.pkg) ? "DeviceReg" : "SystemHelper";
        metaText.setText("  " + mTimeFmt.format(new Date(ev.timeMs))
                + " · " + appShort + " (PID " + ev.pid + ") · #" + ev.ruleCount);
        metaText.setTextColor(TEXT_MUTED);
        metaText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        meta.addView(metaText);
        row.addView(meta);

        TextView detail = new TextView(this);
        detail.setText(ev.detail.isEmpty() ? ev.key : ev.detail);
        detail.setTextColor(TEXT_PRIMARY);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        detail.setPadding(0, dp(4), 0, 0);
        row.addView(detail);
        return row;
    }

    private List<BlockEvent> getFilteredEvents() {
        List<BlockEvent> out = new ArrayList<BlockEvent>();
        for (BlockEvent e : mEvents) {
            if (matchesFilter(e)) {
                out.add(e);
            }
        }
        return out;
    }

    private boolean matchesFilter(BlockEvent e) {
        if (mActiveFilter == FILTER_ALL) {
            return true;
        }
        if (mActiveFilter == FILTER_DEVICEREG) {
            return PKG_DEVICEREG.equals(e.pkg);
        }
        if (mActiveFilter == FILTER_SYSHELPER) {
            return PKG_SYSHELPER.equals(e.pkg);
        }
        String k = e.key == null ? "" : e.key.toLowerCase(Locale.ROOT);
        if (mActiveFilter == FILTER_NET_DB) {
            return k.contains(".net.") || k.contains(".db.") || k.contains(".dm.") || k.contains(".report");
        }
        if (mActiveFilter == FILTER_LOC_RX) {
            return k.contains(".loc.") || k.contains(".gps") || k.contains(".wifi.")
                    || k.contains(".rx.") || k.contains(".job") || k.contains(".alarm")
                    || k.contains(".svc.") || k.contains(".sms.");
        }
        return true;
    }

    private void copyVisibleLogs() {
        List<BlockEvent> list = getFilteredEvents();
        if (list.isEmpty()) {
            Toast.makeText(this, "当前没有可复制的拦截记录", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# DmSilencer ").append(LibXposedEntry.VERSION)
                .append(" 拦截日志（共 ").append(list.size()).append(" 条）\n");
        for (BlockEvent e : list) {
            sb.append(mTimeFmt.format(new Date(e.timeMs)))
                    .append(" [").append(e.pkg).append(" pid=").append(e.pid).append("] ")
                    .append("[").append(e.key).append(" #").append(e.ruleCount).append("] ")
                    .append(e.detail).append('\n');
        }
        copyText("DmSilencer Logs", sb.toString());
    }

    private void copyText(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearAllHistory() {
        mEvents.clear();
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().remove(KEY_EVENTS_JSON).apply();
        refreshNow(true);
        Toast.makeText(this, "已清空历史记录与活跃内存缓冲", Toast.LENGTH_SHORT).show();
    }

    private void loadHistory() {
        mEvents.clear();
        String raw = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(KEY_EVENTS_JSON, "");
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                mEvents.add(new BlockEvent(
                        o.optString("pkg", ""),
                        o.optInt("pid", 0),
                        o.optLong("t", 0L),
                        o.optInt("seq", i),
                        o.optInt("n", 1),
                        o.optString("k", ""),
                        o.optString("d", "")));
            }
        } catch (Throwable ignored) {
        }
    }

    private void saveHistory() {
        try {
            JSONArray arr = new JSONArray();
            int limit = Math.min(mEvents.size(), MAX_STORED_EVENTS);
            for (int i = 0; i < limit; i++) {
                BlockEvent e = mEvents.get(i);
                JSONObject o = new JSONObject();
                o.put("pkg", e.pkg);
                o.put("pid", e.pid);
                o.put("t", e.timeMs);
                o.put("seq", e.seq);
                o.put("n", e.ruleCount);
                o.put("k", e.key);
                o.put("d", e.detail);
                arr.put(o);
            }
            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_EVENTS_JSON, arr.toString())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private String getInstalledVersion(String pkg) {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            return pi != null && pi.versionName != null ? pi.versionName : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String shortRuleName(String key) {
        if (key == null) return "?";
        int idx = key.lastIndexOf('.');
        return idx >= 0 && idx + 1 < key.length() ? key.substring(idx + 1) : key;
    }

    private static String categoryLabelFor(String key) {
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (k.contains(".net.")) return "出网";
        if (k.contains(".db.")) return "写库";
        if (k.contains(".loc.") || k.contains(".gps")) return "定位";
        if (k.contains(".wifi.")) return "Wi-Fi";
        if (k.contains(".sms.")) return "短信";
        if (k.contains(".dm.")) return "DM SDK";
        if (k.contains(".rx.") || k.contains(".job") || k.contains(".alarm") || k.contains(".svc.")) return "广播/调度";
        if (k.contains(".master") || k.contains(".cmcc") || k.contains(".ctcc") || k.contains(".perm") || k.contains(".init")) return "总开关";
        return "拦截";
    }

    private static int[] badgeStyleFor(String key) {
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (k.contains(".net.") || k.contains(".sms.")) {
            return new int[]{ACCENT_ROSE, Color.parseColor("#331420")};
        }
        if (k.contains(".db.") || k.contains(".loc.") || k.contains(".gps")) {
            return new int[]{ACCENT_AMBER, Color.parseColor("#33260F")};
        }
        if (k.contains(".wifi.")) {
            return new int[]{ACCENT_VIOLET, Color.parseColor("#261B3D")};
        }
        if (k.contains(".rx.") || k.contains(".job") || k.contains(".alarm") || k.contains(".svc.") || k.contains(".dm.")) {
            return new int[]{ACCENT_CYAN, Color.parseColor("#112938")};
        }
        return new int[]{ACCENT_GREEN, Color.parseColor("#103329")};
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), BORDER_CARD);
        card.setBackground(bg);
        int p = dp(14);
        card.setPadding(p, p, p, p);
        return card;
    }

    private TextView makePill(String text, int fg, int bgColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(999));
        tv.setBackground(bg);
        return tv;
    }

    private TextView makeActionBtn(String text, int fg, int bgColor, int strokeColor) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(fg);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), strokeColor);
        btn.setBackground(bg);
        return btn;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT_SECONDARY);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return tv;
    }

    private TextView makeBigNumber(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(4), 0, dp(2));
        return tv;
    }

    private TextView makeTiny(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT_MUTED);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        return tv;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
