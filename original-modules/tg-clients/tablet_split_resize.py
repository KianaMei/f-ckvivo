"""
平板/折叠屏分栏宽度拖拽（Tablet Split Resize）— 通用 Telegram 插件

为未内置 exteraGram TabletSplitUiHelper 的 Telegram 第三方客户端（ExteraLess 13.1.x、
NagramXF 13.2.x、AlexGram 13.2.x 等所有支持 exteraGram 兼容插件引擎的客户端）补全
与 exteraGram / AyuGram 同款的双栏左右拖拽调宽能力：

1. 自动防冲突：检测到宿主已内置 TabletSplitUiHelper（如 exteraGram / AyuGram）时自动待机。
2. 零硬编码匿名类序号：通过 LaunchActivity.launchLayout 运行时实例动态定位并挂钩容器的
   onMeasure / onLayout，兼容 13.1.x（LaunchActivity$10）与 13.2.x+（LaunchActivity$9 等）。
3. 像素级视觉复刻且避开 250ms/s 看门狗：
   - 纯原生 View 组合（12dp 灰槽 + 4 张 16dp 内凹圆角位图 + 居中胶囊 View + 独立触摸热区）；
   - 零内嵌 DEX、零 onDraw Python 回调、零 onInterceptTouchEvent 常规触摸拦截；
   - 胶囊展开/收起由 Android RenderThread 原生 ViewPropertyAnimator 执行，平时 0ms/s 开销。
4. 完整交互：getRawX 防抖跟手拖动、默认 35% 磁吸与触觉震动、双击把手一键复位、
   松手后持久化比例并刷新右侧聊天页排版、主题切换自动同步配色、热启停即时生效与还原。
5. 最小权限声明：仅需 ["hooks"]，经 ExteraLess 静态能力扫描零额外权限误报。
"""

import time

from base_plugin import BasePlugin, MethodHook
from java import dynamic_proxy, jclass
from ui.settings import Divider, Header, Selector, Switch, Text

try:
    from client_utils import get_last_fragment
except Exception:
    get_last_fragment = None

try:
    from android_utils import copy_to_clipboard
except Exception:
    copy_to_clipboard = None

try:
    from ui.bulletin import BulletinHelper
except Exception:
    BulletinHelper = None

__id__ = "tablet_split_resize"
__name__ = "分栏宽度拖拽"
__description__ = (
    "为折叠屏/平板双栏模式添加与 exteraGram / AyuGram 同款的左右拖拽调宽把手，"
    "支持圆角沟槽、磁吸、双击复位与宽度记忆（兼容 ExteraLess / NagramXF 等客户端）。"
)
__author__ = "ganvivo"
__version__ = "1.0.0"
__min_version__ = "13.0.0"
__app_version__ = ">=13.0.0"
__sdk_version__ = ">=1.4.5.0"
__permissions__ = ["hooks"]

DEFAULT_FRACTION = 0.35
MIN_RIGHT_DP = 360.0
MAX_LEFT_RATIO = 0.60
CORNER_DP = 16.0
SNAP_ENTER_DP = 12.0
SNAP_EXIT_DP = 32.0
DOUBLE_TAP_MS = 320

GUTTER_STYLE_DP = (12.0, 2.0, 0.0)
MIN_LEFT_OPTIONS_DP = (240.0, 280.0, 300.0, 320.0, 360.0)
HIT_WIDTH_OPTIONS_DP = (20.0, 28.0, 36.0)

VIEW_VISIBLE = 0
VIEW_GONE = 8
EXACTLY = 1073741824  # View.MeasureSpec.EXACTLY

GRAVITY_TOP = 48
GRAVITY_BOTTOM = 80
GRAVITY_LEFT = 3
GRAVITY_RIGHT = 5
GRAVITY_CENTER = 17
GRAVITY_CENTER_HORIZONTAL = 1

ACTION_DOWN = 0
ACTION_UP = 1
ACTION_MOVE = 2
ACTION_CANCEL = 3

HANDLE_TAG = "tablet_split_resize_handle_v1"

_CLS_LAUNCH = "org.telegram.ui.LaunchActivity"
_CLS_AU = "org.telegram.messenger.AndroidUtilities"
_CLS_THEME = "org.telegram.ui.ActionBar.Theme"
_CLS_NATIVE_HELPER = "com.exteragram.messenger.utils.ui.TabletSplitUiHelper"


def _jlen(arr):
    if arr is None:
        return 0
    try:
        return int(arr.length)
    except Exception:
        try:
            return len(arr)
        except Exception:
            return 0


def _clamp_int(val, lo, hi):
    if hi < lo:
        return lo
    if val < lo:
        return lo
    if val > hi:
        return hi
    return val


def _for_name(name):
    try:
        return jclass("java.lang.Class").forName(name)
    except Exception:
        return None


def _get_field(cls, name):
    if cls is None:
        return None
    cur = cls
    while cur is not None:
        try:
            f = cur.getDeclaredField(name)
            f.setAccessible(True)
            return f
        except Exception:
            try:
                cur = cur.getSuperclass()
            except Exception:
                return None
    return None


class _Hook(MethodHook):
    def __init__(self, before=None, after=None):
        super().__init__()
        self._before = before
        self._after = after

    def before_hooked_method(self, param):
        if self._before is not None:
            try:
                self._before(param)
            except Exception:
                pass

    def after_hooked_method(self, param):
        if self._after is not None:
            try:
                self._after(param)
            except Exception:
                pass


class _SplitState:
    """绑定到单个 LaunchActivity / launchLayout 实例的拖拽状态与把手视图。"""

    def __init__(self, plugin, activity, container):
        self.plugin = plugin
        self.activity = activity
        self.container = container

        self.handle_root = None
        self.gutter_bar = None
        self.corner_views = []
        self.corner_bitmaps = []
        self.pill_view = None
        self.pill_drawable = None
        self.hit_view = None

        self.last_total_w = 0
        self.last_total_h = 0
        self.last_inset_l = 0
        self.last_inset_r = 0
        self.content_w = 0
        self.left_pane_w = 0

        self.tracking = False
        self.dragging = False
        self.start_raw_x = 0.0
        self.start_raw_y = 0.0
        self.start_width = 0
        self.raw_width = 0
        self.drag_width = 0
        self.default_width = 0
        self.snapped = False
        self.snap_progress = 0.0
        self.snap_animator = None
        self.last_tap_ms = 0
        self.touch_slop = 16

        self.last_gutter_color = None
        self.last_pill_color = None
        self.last_style_sig = None

        self._f_insets = None
        self._f_in_layout = None
        self._f_inset_left = None
        self._f_inset_right = None
        self._resolve_container_fields()

    def _resolve_container_fields(self):
        try:
            cls = self.container.getClass()
            self._f_insets = _get_field(cls, "insets")
            self._f_in_layout = _get_field(cls, "inLayout")
        except Exception:
            pass
        try:
            self.touch_slop = int(
                jclass("android.view.ViewConfiguration")
                .get(self.container.getContext())
                .getScaledTouchSlop()
            )
        except Exception:
            self.touch_slop = 16

    def read_insets_lr(self):
        f = self._f_insets
        if f is None:
            return 0, 0
        try:
            ins = f.get(self.container)
            if ins is None:
                return 0, 0
            if self._f_inset_left is None:
                icls = ins.getClass()
                self._f_inset_left = _get_field(icls, "left")
                self._f_inset_right = _get_field(icls, "right")
            if self._f_inset_left is not None and self._f_inset_right is not None:
                return int(self._f_inset_left.getInt(ins)), int(self._f_inset_right.getInt(ins))
        except Exception:
            pass
        return 0, 0

    def set_in_layout(self, flag):
        f = self._f_in_layout
        if f is None:
            return
        try:
            f.setBoolean(self.container, bool(flag))
        except Exception:
            pass

    def build_and_attach(self):
        if self.handle_root is not None:
            try:
                if self.handle_root.getParent() is self.container:
                    self.apply_style_and_colors(force=True)
                    return
            except Exception:
                pass
        self._remove_stale_handles()

        ctx = self.container.getContext()
        FrameLayout = jclass("android.widget.FrameLayout")
        FLParams = jclass("android.widget.FrameLayout$LayoutParams")
        View = jclass("android.view.View")
        ImageView = jclass("android.widget.ImageView")
        ScaleType = jclass("android.widget.ImageView$ScaleType")
        GradientDrawable = jclass("android.graphics.drawable.GradientDrawable")

        root = FrameLayout(ctx)
        root.setTag(HANDLE_TAG)
        root.setClipChildren(False)
        root.setClipToPadding(False)

        gutter_bar = View(ctx)
        root.addView(gutter_bar, FLParams(1, -1, GRAVITY_CENTER_HORIZONTAL))

        gravities = (
            GRAVITY_LEFT | GRAVITY_TOP,
            GRAVITY_LEFT | GRAVITY_BOTTOM,
            GRAVITY_RIGHT | GRAVITY_TOP,
            GRAVITY_RIGHT | GRAVITY_BOTTOM,
        )
        corner_views = []
        for g in gravities:
            iv = ImageView(ctx)
            iv.setScaleType(ScaleType.FIT_XY)
            root.addView(iv, FLParams(1, 1, g))
            corner_views.append(iv)

        pill = View(ctx)
        pill_bg = GradientDrawable()
        pill_bg.setCornerRadius(float(self.plugin._dp(4.0)))
        pill.setBackground(pill_bg)
        pill.setAlpha(0.5)
        pill.setScaleX(4.0 / 6.0)
        pill.setScaleY(36.0 / 72.0)
        root.addView(
            pill,
            FLParams(self.plugin._dp(6.0), self.plugin._dp(72.0), GRAVITY_CENTER),
        )

        hit = View(ctx)
        hit.setClickable(True)
        hit.setOnTouchListener(self._create_touch_listener())
        root.addView(hit, FLParams(self.plugin._dp(28.0), -1, GRAVITY_CENTER_HORIZONTAL))

        self.handle_root = root
        self.gutter_bar = gutter_bar
        self.corner_views = corner_views
        self.pill_view = pill
        self.pill_drawable = pill_bg
        self.hit_view = hit

        # 插入位置精确在 rightActionBarLayout 之后、shadowTablet 之前，确保弹窗遮罩能盖住把手
        insert_idx = -1
        try:
            shadow = self.plugin._read_activity_field(self.activity, "shadowTablet")
            if shadow is not None:
                idx = int(self.container.indexOfChild(shadow))
                if idx >= 0:
                    insert_idx = idx
        except Exception:
            insert_idx = -1

        if insert_idx >= 0:
            self.container.addView(root, insert_idx)
        else:
            self.container.addView(root)

        self.apply_style_and_colors(force=True)
        self.sync_visibility()

    def _remove_stale_handles(self):
        try:
            cnt = int(self.container.getChildCount())
            for i in range(cnt - 1, -1, -1):
                child = self.container.getChildAt(i)
                if child is not None and str(child.getTag()) == HANDLE_TAG:
                    self.container.removeViewAt(i)
        except Exception:
            pass

    def detach(self):
        self._cancel_snap_anim()
        try:
            if self.handle_root is not None:
                p = self.handle_root.getParent()
                if p is not None:
                    p.removeView(self.handle_root)
        except Exception:
            pass
        self._recycle_bitmaps(self.corner_bitmaps)
        self.corner_bitmaps = []
        self.handle_root = None
        self.gutter_bar = None
        self.corner_views = []
        self.pill_view = None
        self.pill_drawable = None
        self.hit_view = None

    def _recycle_bitmaps(self, bmps):
        for b in bmps:
            try:
                if b is not None and not b.isRecycled():
                    b.recycle()
            except Exception:
                pass

    def _build_corner_bitmaps(self, radius_px, color_int):
        if radius_px <= 1:
            return [None, None, None, None]
        Bitmap = jclass("android.graphics.Bitmap")
        Config = jclass("android.graphics.Bitmap$Config")
        Canvas = jclass("android.graphics.Canvas")
        Paint = jclass("android.graphics.Paint")
        Path = jclass("android.graphics.Path")
        Direction = jclass("android.graphics.Path$Direction")
        Op = jclass("android.graphics.Path$Op")

        r = float(radius_px)
        paint = Paint(1)
        paint.setColor(int(color_int))
        # 4 个内凹角：
        # 0: 左上角（削左栏右上角）圆心 (0, r)
        # 1: 左下角（削左栏右下角）圆心 (0, 0)
        # 2: 右上角（削右栏左上角）圆心 (r, r)
        # 3: 右下角（削右栏左下角）圆心 (r, 0)
        centers = ((0.0, r), (0.0, 0.0), (r, r), (r, 0.0))
        out = []
        for cx, cy in centers:
            bmp = Bitmap.createBitmap(int(radius_px), int(radius_px), Config.ARGB_8888)
            cv = Canvas(bmp)
            rect_path = Path()
            rect_path.addRect(0.0, 0.0, r, r, Direction.CW)
            circ_path = Path()
            circ_path.addCircle(cx, cy, r, Direction.CW)
            rect_path.op(circ_path, Op.DIFFERENCE)
            cv.drawPath(rect_path, paint)
            out.append(bmp)
        return out

    def apply_style_and_colors(self, force=False):
        if self.handle_root is None:
            return
        gutter_px, corner_px, hit_px = self.plugin._layout_metrics()
        gutter_color, pill_color = self.plugin._theme_colors()

        sig = (gutter_px, corner_px, hit_px)
        need_layout_update = force or (sig != self.last_style_sig)
        need_color_update = force or (gutter_color != self.last_gutter_color) or (pill_color != self.last_pill_color)

        if need_layout_update:
            self.last_style_sig = sig
            try:
                if gutter_px > 0:
                    self.gutter_bar.setVisibility(VIEW_VISIBLE)
                    lp = self.gutter_bar.getLayoutParams()
                    lp.width = gutter_px
                    self.gutter_bar.setLayoutParams(lp)
                else:
                    self.gutter_bar.setVisibility(VIEW_GONE)
            except Exception:
                pass

            try:
                for iv in self.corner_views:
                    if corner_px > 0:
                        iv.setVisibility(VIEW_VISIBLE)
                        lp = iv.getLayoutParams()
                        lp.width = corner_px
                        lp.height = corner_px
                        iv.setLayoutParams(lp)
                    else:
                        iv.setVisibility(VIEW_GONE)
            except Exception:
                pass

            try:
                lp = self.hit_view.getLayoutParams()
                lp.width = hit_px
                self.hit_view.setLayoutParams(lp)
            except Exception:
                pass

        if need_color_update or (need_layout_update and corner_px > 0):
            self.last_gutter_color = gutter_color
            self.last_pill_color = pill_color
            try:
                self.gutter_bar.setBackgroundColor(int(gutter_color))
            except Exception:
                pass
            try:
                self.pill_drawable.setColor(int(pill_color))
                self.pill_view.invalidate()
            except Exception:
                pass
            if corner_px > 0:
                try:
                    new_bmps = self._build_corner_bitmaps(corner_px, gutter_color)
                    old_bmps = self.corner_bitmaps
                    for i in range(4):
                        self.corner_views[i].setImageBitmap(new_bmps[i])
                    self.corner_bitmaps = new_bmps
                    self._recycle_bitmaps(old_bmps)
                except Exception:
                    pass

    def sync_visibility(self):
        if self.handle_root is None:
            return
        show = False
        if self.plugin._enabled_cached:
            try:
                full_size = self.plugin._read_activity_bool(self.activity, "tabletFullSize", True)
                right_layout = self.plugin._read_activity_field(self.activity, "rightActionBarLayout")
                show = (not full_size) and (right_layout is not None)
            except Exception:
                show = False
        target = VIEW_VISIBLE if show else VIEW_GONE
        try:
            if int(self.handle_root.getVisibility()) != target:
                self.handle_root.setVisibility(target)
        except Exception:
            pass

    def measure_and_adjust_left(self, total_w, total_h):
        if self.handle_root is None:
            return
        self.set_in_layout(True)
        try:
            if not self.plugin._enabled_cached:
                self.sync_visibility()
                return
            full_size = self.plugin._read_activity_bool(self.activity, "tabletFullSize", True)
            if full_size:
                self.sync_visibility()
                return

            inset_l, inset_r = self.read_insets_lr()
            self.last_total_w = total_w
            self.last_total_h = total_h
            self.last_inset_l = inset_l
            self.last_inset_r = inset_r
            self.content_w = max(0, total_w - inset_l - inset_r)

            gutter_px, corner_px, hit_px = self.plugin._layout_metrics()
            left_pane_w = self.plugin._calc_left_pane_width(self, total_w, inset_l, inset_r)
            self.left_pane_w = left_pane_w
            View = self.plugin._view_cls()

            # 如果有沟槽（gutter_px > 0），把 actionBarLayout 缩回 left_pane_w，把沟槽留给把手
            if gutter_px > 0 and View is not None:
                try:
                    ab = self.plugin._read_activity_field(self.activity, "actionBarLayout")
                    if ab is not None:
                        v = ab.getView()
                        if v is not None and int(v.getMeasuredWidth()) != left_pane_w:
                            v.measure(
                                View.MeasureSpec.makeMeasureSpec(left_pane_w, EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(total_h, EXACTLY),
                            )
                except Exception:
                    pass

            self.sync_visibility()
            handle_total_w = max(gutter_px + corner_px * 2, hit_px)
            if View is not None:
                try:
                    self.handle_root.measure(
                        View.MeasureSpec.makeMeasureSpec(handle_total_w, EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(total_h, EXACTLY),
                    )
                except Exception:
                    pass
        finally:
            self.set_in_layout(False)

    def layout_handle(self):
        if self.handle_root is None or not self.plugin._enabled_cached:
            return
        if self.plugin._read_activity_bool(self.activity, "tabletFullSize", True):
            self.sync_visibility()
            return
        gutter_px, corner_px, _ = self.plugin._layout_metrics()
        try:
            mw = int(self.handle_root.getMeasuredWidth())
            mh = int(self.handle_root.getMeasuredHeight())
            if corner_px > 0 and mw == (gutter_px + corner_px * 2):
                left_x = self.left_pane_w - corner_px
            else:
                center_x = self.left_pane_w + (gutter_px // 2)
                left_x = center_x - (mw // 2)
            self.handle_root.layout(left_x, 0, left_x + mw, mh)
        except Exception:
            pass

    def _can_resize(self):
        if not self.plugin._enabled_cached or self.content_w <= 0:
            return False
        if self.plugin._read_activity_bool(self.activity, "tabletFullSize", True):
            return False
        try:
            layers = self.plugin._read_activity_field(self.activity, "layerFragmentsStack")
            if layers is not None and not bool(layers.isEmpty()):
                return False
        except Exception:
            pass
        try:
            ab = self.plugin._read_activity_field(self.activity, "actionBarLayout")
            if ab is not None and bool(ab.checkTransitionAnimation()):
                return False
            rab = self.plugin._read_activity_field(self.activity, "rightActionBarLayout")
            if rab is not None and bool(rab.checkTransitionAnimation()):
                return False
        except Exception:
            pass
        return True

    def _animate_pill(self, active):
        if self.pill_view is None:
            return
        try:
            anim = self.pill_view.animate()
            anim.cancel()
            anim.scaleX(1.0 if active else (4.0 / 6.0))
            anim.scaleY(1.0 if active else (36.0 / 72.0))
            anim.alpha(1.0 if active else 0.5)
            anim.setDuration(260)
            try:
                interp = jclass("org.telegram.ui.Components.CubicBezierInterpolator").EASE_OUT_QUINT
                anim.setInterpolator(interp)
            except Exception:
                pass
            anim.start()
        except Exception:
            pass

    def _vibrate(self):
        if not self.plugin._haptic_cached or self.handle_root is None:
            return
        try:
            jclass(_CLS_AU).vibrateCursor(self.handle_root)
            return
        except Exception:
            pass
        try:
            self.handle_root.performHapticFeedback(9, 1)
        except Exception:
            pass

    def _cancel_snap_anim(self):
        if self.snap_animator is not None:
            try:
                self.snap_animator.cancel()
            except Exception:
                pass
            self.snap_animator = None

    def _animate_snap(self, snapped):
        self._cancel_snap_anim()
        try:
            ValueAnimator = jclass("android.animation.ValueAnimator")
            Listener = jclass("android.animation.ValueAnimator$AnimatorUpdateListener")
            state_ref = self

            class _Upd(dynamic_proxy(Listener)):
                def onAnimationUpdate(self_inner, va):
                    try:
                        state_ref.snap_progress = float(va.getAnimatedValue())
                        state_ref._apply_drag_width()
                    except Exception:
                        pass

            va = ValueAnimator.ofFloat(float(self.snap_progress), 1.0 if snapped else 0.0)
            va.setDuration(220)
            try:
                interp = jclass("org.telegram.ui.Components.CubicBezierInterpolator").EASE_OUT_QUINT
                va.setInterpolator(interp)
            except Exception:
                pass
            va.addUpdateListener(_Upd())
            self.snap_animator = va
            va.start()
        except Exception:
            self.snap_progress = 1.0 if snapped else 0.0
            self._apply_drag_width()

    def _apply_drag_width(self):
        if not self.dragging:
            return
        lerp_w = int(round(self.raw_width + (self.default_width - self.raw_width) * self.snap_progress))
        if lerp_w != self.drag_width:
            self.drag_width = lerp_w
            try:
                self.container.requestLayout()
            except Exception:
                pass

    def _start_drag(self, raw_x):
        self.dragging = True
        self.start_raw_x = raw_x
        cur_w = max(0, self.left_pane_w - self.last_inset_l)
        self.start_width = cur_w
        self.raw_width = cur_w
        self.drag_width = cur_w
        self.default_width = self.plugin._clamp_content_width(self.content_w, DEFAULT_FRACTION)
        self.snapped = self.plugin._snap_cached and (cur_w == self.default_width)
        self.snap_progress = 1.0 if self.snapped else 0.0
        try:
            parent = self.container.getParent()
            if parent is not None:
                parent.requestDisallowInterceptTouchEvent(True)
        except Exception:
            pass
        self._vibrate()
        self._animate_pill(True)

    def _update_drag(self, raw_x):
        if self.content_w <= 0:
            return
        target_frac = (float(self.start_width) + (raw_x - self.start_raw_x)) / float(self.content_w)
        clamped_w = self.plugin._clamp_content_width(self.content_w, target_frac)
        self.raw_width = clamped_w

        if self.plugin._snap_cached:
            dist = abs(clamped_w - self.default_width)
            thresh = self.plugin._dp(SNAP_EXIT_DP if self.snapped else SNAP_ENTER_DP)
            now_snapped = dist < thresh
            if now_snapped != self.snapped:
                self.snapped = now_snapped
                if now_snapped:
                    self._vibrate()
                self._animate_snap(now_snapped)
        else:
            self.snapped = False
            self.snap_progress = 0.0

        self._apply_drag_width()

    def _finish_drag(self):
        self._cancel_snap_anim()
        if self.snapped:
            self.drag_width = self.default_width
        self.dragging = False
        changed = (self.drag_width != self.start_width) and (self.content_w > 0)
        if changed:
            new_frac = DEFAULT_FRACTION if self.snapped else (float(self.drag_width) / float(self.content_w))
            self.plugin._save_fraction(new_frac)
        self._animate_pill(False)
        try:
            self.container.requestLayout()
        except Exception:
            pass
        if changed:
            self.plugin._notify_right_chats_resized(self.activity)

    def _handle_double_tap_reset(self):
        if not self.plugin._double_tap_cached:
            return
        self._vibrate()
        self.plugin._save_fraction(DEFAULT_FRACTION)
        try:
            self.container.requestLayout()
        except Exception:
            pass
        self.plugin._notify_right_chats_resized(self.activity)

    def _create_touch_listener(self):
        OnTouchListener = jclass("android.view.View$OnTouchListener")
        state_ref = self

        class _Touch(dynamic_proxy(OnTouchListener)):
            def onTouch(self_inner, view, event):
                try:
                    return state_ref._on_touch(view, event)
                except Exception:
                    return False

        return _Touch()

    def _on_touch(self, view, event):
        action = int(event.getActionMasked())
        if action == ACTION_DOWN:
            self.dragging = False
            if not self._can_resize():
                self.tracking = False
                return False
            self.tracking = True
            self.start_raw_x = float(event.getRawX())
            self.start_raw_y = float(event.getRawY())
            return True

        if action == ACTION_MOVE:
            if not self.tracking:
                return False
            rx = float(event.getRawX())
            ry = float(event.getRawY())
            if not self.dragging:
                dx = rx - self.start_raw_x
                dy = ry - self.start_raw_y
                if abs(dy) > self.touch_slop and abs(dy) > abs(dx):
                    self.tracking = False
                    return False
                if abs(dx) <= self.touch_slop:
                    return True
                self._start_drag(rx)
            self._update_drag(rx)
            return True

        if action == ACTION_UP or action == ACTION_CANCEL:
            was_dragging = self.dragging
            was_tracking = self.tracking
            self.tracking = False
            if was_dragging:
                self._finish_drag()
                return True
            if action == ACTION_UP and was_tracking and self.plugin._double_tap_cached:
                now_ms = int(time.monotonic() * 1000)
                if (now_ms - self.last_tap_ms) <= DOUBLE_TAP_MS:
                    self.last_tap_ms = 0
                    self._handle_double_tap_reset()
                else:
                    self.last_tap_ms = now_ms
            return was_tracking

        return False


class TabletSplitResizePlugin(BasePlugin):
    def __init__(self):
        super().__init__()
        self._unhooks = []
        self._hooked_container_classes = set()
        self._states = {}  # id_hash(container) -> _SplitState
        self._active_state = None
        self._native_supported = False
        self._diag = []

        # 缓存设置，避免热路径调用 get_setting()
        self._enabled_cached = True
        self._gutter_style_cached = 0
        self._min_left_idx_cached = 3  # 320dp
        self._hit_width_idx_cached = 1  # 28dp
        self._snap_cached = True
        self._double_tap_cached = True
        self._haptic_cached = True
        self._fraction_cached = DEFAULT_FRACTION

        # 缓存反射字段
        self._la_cls = None
        self._la_fields = {}
        self._chat_fields = {}
        self._f_msg_force_update = None
        self._au_jcls = None
        self._theme_jcls = None
        self._view_jcls = None

    # ---------------- 公共工具 ----------------

    def _view_cls(self):
        if self._view_jcls is None:
            try:
                self._view_jcls = jclass("android.view.View")
            except Exception:
                return None
        return self._view_jcls

    def _log_diag(self, msg):
        self._diag.append(msg)
        if len(self._diag) > 30:
            self._diag = self._diag[-30:]

    def _dp(self, val):
        try:
            if self._au_jcls is None:
                self._au_jcls = jclass(_CLS_AU)
            return int(self._au_jcls.dp(float(val)))
        except Exception:
            return int(round(float(val) * 3.0))

    def _read_activity_field(self, activity, name):
        if activity is None:
            return None
        f = self._la_fields.get(name)
        if f is None:
            if self._la_cls is None:
                self._la_cls = _for_name(_CLS_LAUNCH)
            f = _get_field(self._la_cls, name)
            self._la_fields[name] = f if f is not None else False
        if not f:
            return None
        try:
            return f.get(activity)
        except Exception:
            return None

    def _read_activity_bool(self, activity, name, default=False):
        if activity is None:
            return default
        f = self._la_fields.get(name)
        if f is None:
            if self._la_cls is None:
                self._la_cls = _for_name(_CLS_LAUNCH)
            f = _get_field(self._la_cls, name)
            self._la_fields[name] = f if f is not None else False
        if not f:
            return default
        try:
            return bool(f.getBoolean(activity))
        except Exception:
            return default

    # ---------------- 设置读写与缓存 ----------------

    def _sync_settings_cache(self):
        def _bool(k, d):
            try:
                v = self.get_setting(k, d)
                return d if v is None else bool(v)
            except Exception:
                return d

        def _idx(k, d, size):
            try:
                v = self.get_setting(k, d)
                i = d if v is None else int(v)
                return max(0, min(size - 1, i))
            except Exception:
                return d

        self._enabled_cached = _bool("enabled", True)
        self._gutter_style_cached = _idx("gutter_style", 0, len(GUTTER_STYLE_DP))
        self._min_left_idx_cached = _idx("min_left_dp_idx", 3, len(MIN_LEFT_OPTIONS_DP))
        self._hit_width_idx_cached = _idx("hit_width_idx", 1, len(HIT_WIDTH_OPTIONS_DP))
        self._snap_cached = _bool("snap_default", True)
        self._double_tap_cached = _bool("double_tap_reset", True)
        self._haptic_cached = _bool("haptic_feedback", True)

        try:
            raw = self.get_setting("left_fraction_str", "")
            if raw:
                f = float(str(raw))
                if 0.15 <= f <= 0.75:
                    self._fraction_cached = f
                else:
                    self._fraction_cached = DEFAULT_FRACTION
            else:
                self._fraction_cached = DEFAULT_FRACTION
        except Exception:
            self._fraction_cached = DEFAULT_FRACTION

    def _save_fraction(self, frac):
        frac = max(0.15, min(0.75, float(frac)))
        self._fraction_cached = frac
        try:
            self.set_setting("left_fraction_str", "%.4f" % frac)
        except Exception:
            pass

    def _layout_metrics(self):
        style_idx = self._gutter_style_cached
        gutter_dp = GUTTER_STYLE_DP[style_idx]
        gutter_px = self._dp(gutter_dp) if gutter_dp > 0 else 0
        corner_px = self._dp(CORNER_DP) if style_idx == 0 else 0
        hit_px = max(gutter_px, self._dp(HIT_WIDTH_OPTIONS_DP[self._hit_width_idx_cached]))
        return gutter_px, corner_px, hit_px

    def _theme_colors(self):
        try:
            if self._theme_jcls is None:
                self._theme_jcls = jclass(_CLS_THEME)
            T = self._theme_jcls
            bg_key = T.key_windowBackgroundGray if self._gutter_style_cached == 0 else T.key_divider
            gutter_c = int(T.getColor(bg_key))
            pill_c = int(T.getColor(T.key_windowBackgroundWhiteGrayIcon))
            return gutter_c, pill_c
        except Exception:
            return -1447446, -7829368  # 0xFFE9E9EA / 0xFF888888

    # ---------------- 宽度计算（复刻 TabletSplitUiHelper） ----------------

    def _clamp_content_width(self, content_w, fraction):
        gutter_px, _, _ = self._layout_metrics()
        min_left_dp = MIN_LEFT_OPTIONS_DP[self._min_left_idx_cached]
        min_w = self._dp(min_left_dp)
        max_by_ratio = int(float(content_w) * MAX_LEFT_RATIO)
        max_by_right = content_w - gutter_px - self._dp(MIN_RIGHT_DP)
        max_w = max(min_w, min(max_by_ratio, max_by_right))
        desired = int(round(float(content_w) * float(fraction)))
        return _clamp_int(desired, min_w, max_w)

    def _calc_left_pane_width(self, state, total_w, inset_l, inset_r):
        content_w = max(0, total_w - inset_l - inset_r)
        if state is not None and state.dragging and total_w == state.last_total_w:
            return inset_l + state.drag_width
        return inset_l + self._clamp_content_width(content_w, self._fraction_cached)

    # ---------------- 刷新右侧 ChatActivity 排版 ----------------

    def _notify_right_chats_resized(self, activity):
        try:
            rab = self._read_activity_field(activity, "rightActionBarLayout")
            if rab is None:
                return
            stack = rab.getFragmentStack()
            if stack is None:
                return
            n = int(stack.size())
            for i in range(n):
                frag = stack.get(i)
                if frag is None:
                    continue
                self._refresh_chat_fragment(frag)
        except Exception:
            pass

    def _refresh_chat_fragment(self, frag):
        # 等价复刻 AyuGram/exteraGram 的 ChatActivity.onTabletSplitChanged()
        try:
            cls = frag.getClass()
            if str(cls.getName()) != "org.telegram.ui.ChatActivity":
                return
            f_adapter = self._chat_fields.get("chatAdapter")
            f_msgs = self._chat_fields.get("messages")
            if f_adapter is None:
                f_adapter = _get_field(cls, "chatAdapter")
                f_msgs = _get_field(cls, "messages")
                self._chat_fields["chatAdapter"] = f_adapter if f_adapter is not None else False
                self._chat_fields["messages"] = f_msgs if f_msgs is not None else False
            if not f_adapter or not f_msgs:
                return
            adapter = f_adapter.get(frag)
            msgs = f_msgs.get(frag)
            if adapter is None or msgs is None:
                return
            sz = int(msgs.size())
            f_fu = self._f_msg_force_update
            for i in range(sz):
                m = msgs.get(i)
                if m is None:
                    continue
                try:
                    m.resetLayout()
                except Exception:
                    pass
                if f_fu is None:
                    f_fu = _get_field(m.getClass(), "forceUpdate")
                    self._f_msg_force_update = f_fu if f_fu is not None else False
                if f_fu:
                    try:
                        f_fu.setBoolean(m, True)
                    except Exception:
                        pass
            adapter.notifyDataSetChanged()
        except Exception:
            pass

    # ---------------- 容器状态管理 & 动态类 Hook ----------------

    def _state_key(self, container):
        try:
            return int(jclass("java.lang.System").identityHashCode(container))
        except Exception:
            return id(container)

    def _prune_dead_states(self):
        dead = []
        for k, st in list(self._states.items()):
            try:
                if st.activity is None or bool(st.activity.isDestroyed()):
                    dead.append(k)
            except Exception:
                pass
        for k in dead:
            st = self._states.pop(k, None)
            if st is not None:
                st.detach()
                if self._active_state is st:
                    self._active_state = None

    def _attach_activity(self, activity):
        if activity is None:
            return
        self._prune_dead_states()
        container = self._read_activity_field(activity, "launchLayout")
        if container is None:
            return

        key = self._state_key(container)
        st = self._states.get(key)
        if st is None:
            st = _SplitState(self, activity, container)
            self._states[key] = st
            self._hook_container_class(container.getClass())
        else:
            st.activity = activity

        self._active_state = st
        st.build_and_attach()

    def _hook_container_class(self, container_cls):
        if container_cls is None:
            return
        cname = str(container_cls.getName())
        if cname in self._hooked_container_classes:
            return
        self._hooked_container_classes.add(cname)

        n1 = self._hook_methods(container_cls, "onMeasure", after=self._after_container_measure)
        n2 = self._hook_methods(container_cls, "onLayout", after=self._after_container_layout)
        self._log_diag("容器类 %s: onMeasure=%d, onLayout=%d" % (cname.split(".")[-1], n1, n2))

    # ---------------- Hook 回调 ----------------

    def _after_get_left_size(self, param):
        if not self._enabled_cached:
            return
        args = param.args
        if args is None or len(args) < 3:
            return
        total_w = int(args[0])
        inset_l = int(args[1])
        inset_r = int(args[2])
        if total_w <= 0:
            return
        gutter_px, _, _ = self._layout_metrics()
        left_pane_w = self._calc_left_pane_width(self._active_state, total_w, inset_l, inset_r)
        param.setResult(int(left_pane_w + gutter_px))

    def _after_setup_actionbar_layout(self, param):
        act = param.thisObject
        if act is not None:
            self._attach_activity(act)

    def _after_check_layout(self, param):
        act = param.thisObject
        if act is None:
            return
        container = self._read_activity_field(act, "launchLayout")
        if container is None:
            return
        st = self._states.get(self._state_key(container))
        if st is None:
            self._attach_activity(act)
            st = self._states.get(self._state_key(container))
        if st is not None:
            st.sync_visibility()

    def _after_on_resume(self, param):
        act = param.thisObject
        if act is None:
            return
        container = self._read_activity_field(act, "launchLayout")
        if container is not None:
            st = self._states.get(self._state_key(container))
            if st is None or st.handle_root is None:
                self._attach_activity(act)
            else:
                self._active_state = st
                st.sync_visibility()

    def _after_theme_change(self, param):
        for st in list(self._states.values()):
            st.apply_style_and_colors(force=False)

    def _after_container_measure(self, param):
        container = param.thisObject
        if container is None:
            return
        st = self._states.get(self._state_key(container))
        if st is None:
            return
        self._active_state = st
        args = param.args
        if args is None or len(args) < 2:
            return
        View = self._view_cls()
        if View is None:
            return
        w = int(View.MeasureSpec.getSize(int(args[0])))
        h = int(View.MeasureSpec.getSize(int(args[1])))
        st.measure_and_adjust_left(w, h)

    def _after_container_layout(self, param):
        container = param.thisObject
        if container is None:
            return
        st = self._states.get(self._state_key(container))
        if st is not None:
            st.layout_handle()

    # ---------------- Hook 安装 / 卸载 ----------------

    def _hook_methods(self, cls, method_name, before=None, after=None, arity=None):
        if cls is None:
            return 0
        try:
            methods = cls.getDeclaredMethods()
        except Exception:
            return 0
        count = 0
        for i in range(_jlen(methods)):
            m = methods[i]
            try:
                if str(m.getName()) != method_name:
                    continue
                if arity is not None and _jlen(m.getParameterTypes()) != arity:
                    continue
                m.setAccessible(True)
                u = self.hook_method(m, _Hook(before=before, after=after))
                if u is not None:
                    self._unhooks.append(u)
                    count += 1
            except Exception:
                pass
        return count

    def _has_native_tablet_split(self):
        if _for_name(_CLS_NATIVE_HELPER) is not None:
            return True
        la = _for_name(_CLS_LAUNCH)
        if _get_field(la, "tabletSplit") is not None:
            return True
        return False

    def _find_current_launch_activity(self):
        # 热加载时直接取当前已运行的 LaunchActivity，无需重启 App
        if get_last_fragment is not None:
            try:
                frag = get_last_fragment()
                if frag is not None:
                    act = frag.getParentActivity()
                    if act is not None and str(act.getClass().getName()) == _CLS_LAUNCH:
                        return act
            except Exception:
                pass
        try:
            la = jclass(_CLS_LAUNCH)
            frag = la.getSafeLastFragment()
            if frag is not None:
                act = frag.getParentActivity()
                if act is not None and str(act.getClass().getName()) == _CLS_LAUNCH:
                    return act
        except Exception:
            pass
        return None

    def _run_ui(self, fn):
        try:
            Runnable = jclass("java.lang.Runnable")

            class _R(dynamic_proxy(Runnable)):
                def run(self_inner):
                    try:
                        fn()
                    except Exception:
                        pass

            jclass(_CLS_AU).runOnUIThread(_R())
        except Exception:
            try:
                fn()
            except Exception:
                pass

    def _refresh_all_views(self, force_style=True, notify_chats=False):
        def _do():
            for st in list(self._states.values()):
                try:
                    st.apply_style_and_colors(force=force_style)
                    st.sync_visibility()
                    st.container.requestLayout()
                    if notify_chats:
                        self._notify_right_chats_resized(st.activity)
                except Exception:
                    pass

        self._run_ui(_do)

    def on_plugin_load(self):
        self._diag = []
        self._unhooks = []
        self._hooked_container_classes = set()
        self._states = {}
        self._active_state = None
        self._sync_settings_cache()

        if self._has_native_tablet_split():
            self._native_supported = True
            self._log_diag("检测到宿主已内置原生 TabletSplitUiHelper，插件自动待机不冲突。")
            return

        self._native_supported = False
        au_cls = _for_name(_CLS_AU)
        la_cls = _for_name(_CLS_LAUNCH)
        self._la_cls = la_cls

        n_size = self._hook_methods(au_cls, "getTabletLeftFragmentSize", after=self._after_get_left_size, arity=3)
        n_setup = self._hook_methods(la_cls, "setupActionBarLayout", after=self._after_setup_actionbar_layout, arity=0)
        n_check = self._hook_methods(la_cls, "checkLayout", after=self._after_check_layout, arity=0)
        n_resume = self._hook_methods(la_cls, "onResume", after=self._after_on_resume, arity=0)
        n_theme = self._hook_methods(la_cls, "onThemeProgress", after=self._after_theme_change, arity=1)
        n_sysbar = self._hook_methods(la_cls, "checkSystemBarColors", after=self._after_theme_change)

        self._log_diag(
            "Hook 完成: leftSize=%d setup=%d check=%d resume=%d theme=%d sysbar=%d"
            % (n_size, n_setup, n_check, n_resume, n_theme, n_sysbar)
        )

        # 热加载立即接管当前已打开的 LaunchActivity
        def _hot_attach():
            act = self._find_current_launch_activity()
            if act is not None:
                self._attach_activity(act)
                if self._active_state is not None:
                    self._active_state.container.requestLayout()
                    self._notify_right_chats_resized(act)
                    self._log_diag("已热挂载到当前 LaunchActivity。")
            else:
                self._log_diag("等待 LaunchActivity 进入前台或展开双栏。")

        self._run_ui(_hot_attach)

    def on_plugin_unload(self):
        for u in self._unhooks:
            try:
                self.unhook_method(u)
            except Exception:
                try:
                    u.unhook()
                except Exception:
                    pass
        self._unhooks = []
        self._hooked_container_classes = set()

        states_snapshot = list(self._states.values())
        self._states = {}
        self._active_state = None

        def _cleanup():
            for st in states_snapshot:
                container = st.container
                act = st.activity
                st.detach()
                try:
                    container.requestLayout()
                except Exception:
                    pass
                self._notify_right_chats_resized(act)

        self._run_ui(_cleanup)

    # ---------------- 设置界面（ExteraLess / NagramXF 通用标准组件） ----------------

    def _status_summary(self):
        if self._native_supported:
            return "宿主已内置原生分栏拖拽（自动待机）"
        if not self._enabled_cached:
            return "已关闭（恢复原生固定宽度）"
        pct = int(round(self._fraction_cached * 100.0))
        st = self._active_state
        if st is not None and st.left_pane_w > 0:
            return "运行中 · 当前比例 %d%%（左栏 %dpx）" % (pct, st.left_pane_w)
        return "已就绪 · 记忆比例 %d%%（展开双栏即生效）" % pct

    def _on_enabled_changed(self, val):
        self._enabled_cached = bool(val)
        self._refresh_all_views(force_style=True, notify_chats=True)

    def _on_gutter_style_changed(self, val):
        try:
            self._gutter_style_cached = max(0, min(len(GUTTER_STYLE_DP) - 1, int(val)))
        except Exception:
            pass
        self._refresh_all_views(force_style=True, notify_chats=True)

    def _on_min_left_changed(self, val):
        try:
            self._min_left_idx_cached = max(0, min(len(MIN_LEFT_OPTIONS_DP) - 1, int(val)))
        except Exception:
            pass
        self._refresh_all_views(force_style=False, notify_chats=True)

    def _on_hit_width_changed(self, val):
        try:
            self._hit_width_idx_cached = max(0, min(len(HIT_WIDTH_OPTIONS_DP) - 1, int(val)))
        except Exception:
            pass
        self._refresh_all_views(force_style=True, notify_chats=False)

    def _on_snap_changed(self, val):
        self._snap_cached = bool(val)

    def _on_double_tap_changed(self, val):
        self._double_tap_cached = bool(val)

    def _on_haptic_changed(self, val):
        self._haptic_cached = bool(val)

    def _on_reset_clicked(self, *args):
        self._save_fraction(DEFAULT_FRACTION)
        self._refresh_all_views(force_style=False, notify_chats=True)
        if BulletinHelper is not None:
            try:
                BulletinHelper.show_info("已恢复默认分栏宽度 (35%)")
            except Exception:
                pass

    def _on_copy_diag(self, *args):
        text = "[tablet_split_resize v%s]\n状态: %s\n比例: %.4f\n%s" % (
            __version__,
            self._status_summary(),
            self._fraction_cached,
            "\n".join(self._diag),
        )
        if copy_to_clipboard is not None:
            try:
                copy_to_clipboard(text)
            except Exception:
                pass
        if BulletinHelper is not None:
            try:
                BulletinHelper.show_copied_to_clipboard("诊断日志已复制")
            except Exception:
                pass

    def create_settings(self):
        self._sync_settings_cache()
        return [
            Header(text="状态"),
            Text(
                text=self._status_summary(),
                subtext="点击复制诊断日志" if copy_to_clipboard is not None else None,
                on_click=self._on_copy_diag,
            ),
            Divider(),
            Header(text="外观与手势"),
            Switch(
                key="enabled",
                text="启用分栏宽度拖拽",
                subtext="关闭后立即恢复客户端原生固定双栏布局",
                default=True,
                on_change=self._on_enabled_changed,
            ),
            Selector(
                key="gutter_style",
                text="分割条样式",
                default=0,
                items=[
                    "原版圆角沟槽 (12dp，同 AyuGram)",
                    "紧凑细线 + 胶囊 (2dp)",
                    "极简隐形热区（无沟槽）",
                ],
                on_change=self._on_gutter_style_changed,
            ),
            Selector(
                key="min_left_dp_idx",
                text="左栏最小宽度",
                default=3,
                items=[
                    "240 dp（超窄）",
                    "280 dp（较窄）",
                    "300 dp",
                    "320 dp（原版默认）",
                    "360 dp（较宽）",
                ],
                on_change=self._on_min_left_changed,
            ),
            Selector(
                key="hit_width_idx",
                text="把手触摸热区宽度",
                default=1,
                items=[
                    "20 dp（窄，防误触）",
                    "28 dp（推荐）",
                    "36 dp（宽，易抓握）",
                ],
                on_change=self._on_hit_width_changed,
            ),
            Switch(
                key="snap_default",
                text="默认位置磁吸 (35%)",
                subtext="拖拽到默认 35% 位置附近时自动吸附",
                default=True,
                on_change=self._on_snap_changed,
            ),
            Switch(
                key="double_tap_reset",
                text="双击把手复位",
                subtext="连续双击中间胶囊快速恢复默认 35% 比例",
                default=True,
                on_change=self._on_double_tap_changed,
            ),
            Switch(
                key="haptic_feedback",
                text="触觉震动反馈",
                subtext="开始拖拽、磁吸与双击复位时提供轻微震动",
                default=True,
                on_change=self._on_haptic_changed,
            ),
            Divider(),
            Header(text="快捷操作"),
            Text(
                text="重置为默认宽度 (35%)",
                subtext="立即恢复左右分栏默认比例",
                accent=True,
                on_click=self._on_reset_clicked,
            ),
        ]
