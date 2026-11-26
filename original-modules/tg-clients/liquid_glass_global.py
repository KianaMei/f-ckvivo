"""
Liquid Glass 全局 — ExteraLess (13.1.1+) & NagramXF (13.2.1+) 双端兼容版

基于 @安安 原版 liquid_glass_global v2.4.2 改造适配：
1. 改用标准 BasePlugin.hook_method / MethodHook / ui.settings（Header, Switch, Text），
   彻底解决 ExteraLess 缺少 PyMethodHook / XposedBridge 导致加载即 ImportError 的问题。
2. 补齐 Telegram 13.1.1（ExteraLess）与 13.2.1（NagramXF）双套 blur3 渲染类 Hook：
   - 13.1.1：BlurredBackgroundDrawableViewFactory / BlurredBackgroundDrawableRenderNode / BlurredBackgroundDrawableSource
     （压 tint 时同步置位 renderNodeInvalidated / invalidateDisplayList，首帧即透）
   - 13.2.1：BlurredBackgroundDrawable / BlurredBackgroundDrawable$Impl / $ImplRenderNode
3. 声明最小权限 __permissions__ = ["hooks"]（消除非必要的 messages.read 误报）。
4. v2.6.0 彻底重构气泡磨砂渲染管线，根治滚动跳变、闪烁与错位：
   - 接入原生 ChatMessageCell.drawBackgroundInParent()：让所有普通气泡（含对方消息与纯色己方消息）
     与原生渐变气泡一样由父列表 ChatListRecyclerView.drawChatBackgroundElements 每帧实时绘制，
     彻底解决硬件加速 RenderNode 缓存导致气泡内模糊壁纸随气泡冻结拖拽、invalidate 时骤然跳变的问题。
   - 精确按参数个数挂钩：只挂 2 参 drawBackgroundInternal(Canvas, boolean) 与 2 参 drawCached(Canvas, PathDrawParams)，
     避免 2/3 参双触发与己方渐变气泡在父列表+子 View 双重覆盖的问题；并补齐 9 参 drawBackground(...) 相册分组气泡。
   - 改用 BitmapShader + Matrix 浮点子像素采样 + MessageDrawable.draw(canvas, shaderPaint)：
     消除 1/7 缩放 Rect 整型量化导致的 7px 阶梯跳动与拉伸抖动；首帧即用当前 MessageDrawable 实时生成精确轮廓路径
     （含 NinePatch 对方气泡、尾巴开关、分组圆角、过渡 delta bounds），彻底消除复用首帧闪烁与缺路径跳变。
   - 按 SizeNotifierFrameLayout 隔离壁纸糊图缓存：每帧在 drawChatBackgroundElements 统一刷新一次列表偏移/壁纸位移，
     自动感知折叠屏展开/分栏拖拽/键盘 bottomClip/换壁纸与多聊天切换；MessageDrawable.setAlpha 改直写字段零重入。
"""

from base_plugin import BasePlugin, MethodHook
from android_utils import copy_to_clipboard, run_on_ui_thread
from java import jclass
from ui.settings import Header, Switch, Text

__id__ = "liquid_glass_global"
__name__ = "液态玻璃全局（双端版）"
__description__ = "顶栏真模糊 + 输入框真模糊 + 药丸变透（tint 压到 100）+ 子像素平滑气泡磨砂，兼容 ExteraLess 13.1.x 与 NagramXF 13.2.x。"
__author__ = "安安（ExteraLess 双端适配）"
__version__ = "2.6.0"
__min_version__ = "13.1.0"
__app_version__ = ">=13.1.0"
__sdk_version__ = ">=1.4.5.0"
__permissions__ = ["hooks"]

DEFAULTS = {
    "enabled": True,
    "bubble_frost": True,
}

_BLUR_DOWNSCALE = 7.0
_BLUR_RADIUS = 7
_BUBBLE_MAX_ALPHA = 160
_MAX_TILE_ENTRIES = 4


def _len_java(arr):
    try:
        return int(arr.length)
    except Exception:
        try:
            return len(arr)
        except Exception:
            return 0


def _jbool(val):
    """安全拆箱 Java Boolean / Python bool，防止 bool(Boolean.FALSE) 为 True。"""
    if val is None or isinstance(val, bool):
        return bool(val)
    try:
        return bool(val.booleanValue())
    except Exception:
        if isinstance(val, str):
            return val.strip().lower() not in ("", "0", "false", "no", "off")
        return bool(val)


_CLASS_CACHE = {}
_CLASS_PATH = {}


def _class_object(name):
    """获取真正的 java.lang.Class 反射对象（非 Chaquopy 代理类）。"""
    if not name:
        return None
    if name in _CLASS_CACHE:
        return _CLASS_CACHE[name]
    try:
        jcls = jclass("java.lang.Class")
        cls = jcls.forName(name)
        if cls is not None and hasattr(cls, "getDeclaredMethods"):
            _CLASS_CACHE[name] = cls
            _CLASS_PATH[name] = "Class.forName"
            return cls
    except Exception:
        pass
    try:
        from hook_utils import find_class
        proxy = find_class(name)
    except Exception:
        proxy = None
    if proxy is not None:
        try:
            cls = proxy.getClass()
            if cls is not None and hasattr(cls, "getDeclaredMethods"):
                _CLASS_CACHE[name] = cls
                _CLASS_PATH[name] = "proxy.getClass()"
                return cls
        except Exception:
            pass
        if hasattr(proxy, "getDeclaredMethods"):
            _CLASS_CACHE[name] = proxy
            _CLASS_PATH[name] = "find_class"
            return proxy
    _CLASS_PATH[name] = "未找到"
    _CLASS_CACHE[name] = None
    return None


def _sdk_int():
    try:
        return int(jclass("android.os.Build$VERSION").SDK_INT)
    except Exception:
        return 0


class _BeforeHook(MethodHook):
    after_hooked_method = None

    def __init__(self, fn):
        super().__init__()
        self._fn = fn

    def before_hooked_method(self, param):
        self._fn(param)


class _AfterHook(MethodHook):
    before_hooked_method = None

    def __init__(self, fn):
        super().__init__()
        self._fn = fn

    def after_hooked_method(self, param):
        self._fn(param)


class _BothHook(MethodHook):
    def __init__(self, before_fn, after_fn):
        super().__init__()
        self._before_fn = before_fn
        self._after_fn = after_fn

    def before_hooked_method(self, param):
        self._before_fn(param)

    def after_hooked_method(self, param):
        self._after_fn(param)


class _TileEntry(object):
    __slots__ = (
        "cv", "bg", "bg_drawable", "bw", "bh",
        "bmp", "shader", "matrix", "paint", "last_alpha",
        "scale_x", "scale_y", "base_tx", "base_ty", "base_bty",
        "need_rebuild", "seq",
    )

    def __init__(self, cv, seq):
        self.cv = cv
        self.bg = None
        self.bg_drawable = None
        self.bw = 0
        self.bh = 0
        self.bmp = None
        self.shader = None
        self.matrix = None
        self.paint = None
        self.last_alpha = 255
        self.scale_x = _BLUR_DOWNSCALE
        self.scale_y = _BLUR_DOWNSCALE
        self.base_tx = 0.0
        self.base_ty = 0.0
        self.base_bty = 0
        self.need_rebuild = False
        self.seq = seq

    def recycle(self):
        bmp = self.bmp
        self.bmp = None
        self.shader = None
        if self.paint is not None:
            try:
                self.paint.setShader(None)
            except Exception:
                pass
        if bmp is not None:
            try:
                if not bmp.isRecycled():
                    bmp.recycle()
            except Exception:
                pass


class LiquidGlassPlugin(BasePlugin):
    def __init__(self):
        super().__init__()
        self._diag = ["[DIAG] 实例化完成 (v%s)" % __version__]
        self._unhooks = []
        self._installed = {}
        self._enabled_cached = True
        self._bubble_frost_cached = True

        self._lite_flag = None
        self._topbar_count = 0
        self._provider_hits = 0
        self._tint_hits = 0
        self._bar_clear_count = 0
        self._bubble_hits = 0
        self._in_bubble_alpha = False
        self._factory_hits = 0

        # 预缓存反射类与字段（避免热路径重复查表）
        self._bbd_cls = None
        self._bbd_rn_cls = None
        self._factory_cls = None
        self._action_bar_cls = None
        self._f_bg_color = None
        self._f_rn_invalidated = None
        self._f_factory_lg = None
        self._f_ab_blur = None

        # ChatActivity / SizeNotifierFrameLayout / ChatMessageCell / MessageDrawable 字段
        self._f_chat_list = None
        self._f_pull_off = None
        self._f_draw_lv_yoff = None
        self._f_bg_view = None
        self._f_bg_drawable = None
        self._f_tx = None
        self._f_ty = None
        self._f_bty = None
        self._f_bottom_clip = None
        self._f_theme_anim = None
        self._f_skip_bg = None
        self._f_drawing_blur = None
        self._f_can_bg_parent = None
        self._f_cur_msg = None
        self._f_cur_group = None
        self._f_trans_y_off = None
        self._f_md_alpha = None
        self._f_md_paint = None
        self._f_md_grad = None
        self._f_md_is_selected = None

        self._tint_err_logged = False
        self._bar_err_logged = False

        # 气泡磨砂状态（按 SizeNotifierFrameLayout 隔离，彻底消除跨聊/分屏/折叠串态）
        self._entries = {}          # id_hash -> _TileEntry
        self._entry_seq = 0
        self._lv_to_frag = {}       # id_hash(lv) -> (lv, fragment)
        self._hooked_list_classes = set()
        self._active_frame = None   # (entry, base_x, base_y, lv)
        self._frame_stack = []
        self._cur_ctx = None        # [entry, tx, ty, cell_or_None, first_drawable_or_None]
        self._ctx_stack = []
        self._in_frost_draw = False

        self._tile_hits = 0
        self._tile_rebuilds = 0
        self._tile_built_logged = False
        self._tile_logged = set()
        self._tile_sat_paint = None
        self._tile_baked_ok = True
        self._jcls_cache = {}
        self._java_sys = None
        self._java_mod = None
        self._clamp_mode = None

    # ---------------- 标准双端 Hook 安装 ----------------

    def _try_hook_member(self, member, before=None, after=None):
        if before is not None and after is not None:
            handler = _BothHook(before, after)
        elif before is not None:
            handler = _BeforeHook(before)
        elif after is not None:
            handler = _AfterHook(after)
        else:
            return None
        return self.hook_method(member, handler)

    def _hook(self, cls, method_name, before=None, after=None, stat_key=None, param_count=None, skip_abstract=False):
        key = stat_key or method_name
        if cls is None:
            self._installed[key] = "0（类不存在）"
            return 0
        try:
            methods = cls.getDeclaredMethods()
        except Exception as exc:
            self._installed[key] = "getDeclaredMethods 失败: %s" % exc
            return 0
        total = _len_java(methods)
        matched = installed = 0
        err = None
        mod_cls = self._get_jclass("java.lang.reflect.Modifier") if skip_abstract else None
        for i in range(total):
            try:
                member = methods[i]
                if str(member.getName()) != method_name:
                    continue
                if param_count is not None:
                    if _len_java(member.getParameterTypes()) != param_count:
                        continue
                if skip_abstract and mod_cls is not None:
                    if bool(mod_cls.isAbstract(int(member.getModifiers()))):
                        continue
            except Exception:
                continue
            matched += 1
            try:
                member.setAccessible(True)
            except Exception:
                pass
            try:
                u = self._try_hook_member(member, before=before, after=after)
                if u is not None:
                    self._unhooks.append(u)
                    installed += 1
            except Exception as exc:
                err = str(exc)
        if matched == 0:
            self._installed[key] = "0（类里没有此方法）"
        elif installed == 0:
            self._installed[key] = "0 / %d 失败: %s" % (matched, err or "?")
        elif installed < matched:
            self._installed[key] = "%d / %d" % (installed, matched)
        else:
            self._installed[key] = "%d" % installed
        return installed

    # ---------------- 预缓存工具 ----------------

    def _get_jclass(self, name):
        c = self._jcls_cache.get(name)
        if c is None:
            try:
                c = jclass(name)
            except Exception as exc:
                self._jcls_cache[name] = False
                self._tile_diag_once("jcls:" + name, "[tile!!] 拿不到 Java 类 %s: %s" % (name, exc))
                return None
            self._jcls_cache[name] = c
        return c if c is not False else None

    def _id_hash(self, obj):
        sys_cls = self._java_sys
        if sys_cls is None:
            sys_cls = self._get_jclass("java.lang.System")
            self._java_sys = sys_cls
        if sys_cls is not None:
            try:
                return int(sys_cls.identityHashCode(obj))
            except Exception:
                pass
        return id(obj)

    def _resolve_field(self, cls_name, field_name, optional=False):
        try:
            cls = _class_object(cls_name)
            if cls is None:
                return None
            f = cls.getDeclaredField(field_name)
            f.setAccessible(True)
            return f
        except Exception as exc:
            if not optional:
                self._tile_diag_once(
                    "fld:" + cls_name + "#" + field_name,
                    "[tile!!] 读字段失败 %s.%s: %s" % (cls_name, field_name, exc),
                )
            return None

    def _init_cached_fields(self):
        if self._bbd_cls is not None:
            try:
                f = self._bbd_cls.getDeclaredField("backgroundColor")
                f.setAccessible(True)
                self._f_bg_color = f
            except Exception as exc:
                self._diag.append("[fld] backgroundColor: %s" % exc)
        if self._bbd_rn_cls is not None:
            try:
                f = self._bbd_rn_cls.getDeclaredField("renderNodeInvalidated")
                f.setAccessible(True)
                self._f_rn_invalidated = f
            except Exception:
                self._f_rn_invalidated = None
        if self._factory_cls is not None:
            try:
                f = self._factory_cls.getDeclaredField("isLiquidGlassEffectAllowed")
                f.setAccessible(True)
                self._f_factory_lg = f
            except Exception:
                self._f_factory_lg = None
        if self._action_bar_cls is not None:
            try:
                f = self._action_bar_cls.getDeclaredField("blurredBackground")
                f.setAccessible(True)
                self._f_ab_blur = f
            except Exception as exc:
                self._diag.append("[fld] blurredBackground: %s" % exc)

        self._f_chat_list = self._resolve_field("org.telegram.ui.ChatActivity", "chatListView")
        self._f_pull_off = self._resolve_field("org.telegram.ui.ChatActivity", "pullingDownOffset", optional=True)
        self._f_draw_lv_yoff = self._resolve_field("org.telegram.ui.ChatActivity", "drawingChatListViewYoffset", optional=True)

        snfl = "org.telegram.ui.Components.SizeNotifierFrameLayout"
        self._f_bg_view = self._resolve_field(snfl, "backgroundView")
        self._f_bg_drawable = self._resolve_field(snfl, "backgroundDrawable", optional=True)
        self._f_tx = self._resolve_field(snfl, "translationX")
        self._f_ty = self._resolve_field(snfl, "translationY")
        self._f_bty = self._resolve_field(snfl, "backgroundTranslationY")
        self._f_bottom_clip = self._resolve_field(snfl, "bottomClip", optional=True)
        self._f_theme_anim = self._resolve_field(snfl, "themeAnimationValue", optional=True)
        self._f_skip_bg = self._resolve_field(snfl, "skipBackgroundDrawing", optional=True)
        self._f_drawing_blur = self._resolve_field(snfl, "drawingBlur", optional=True)

        cmc = "org.telegram.ui.Cells.ChatMessageCell"
        self._f_can_bg_parent = self._resolve_field(cmc, "canDrawBackgroundInParent")
        self._f_cur_msg = self._resolve_field(cmc, "currentMessageObject")
        self._f_cur_group = self._resolve_field(cmc, "currentMessagesGroup")
        self._f_trans_y_off = self._resolve_field(cmc, "transitionYOffsetForDrawables", optional=True)

        md = "org.telegram.ui.ActionBar.MessageDrawable"
        self._f_md_alpha = self._resolve_field(md, "alpha", optional=True)
        self._f_md_paint = self._resolve_field(md, "paint", optional=True)
        self._f_md_grad = self._resolve_field(md, "gradientShader", optional=True)
        self._f_md_is_selected = self._resolve_field(md, "isSelected", optional=True)

        try:
            TileMode = self._get_jclass("android.graphics.Shader$TileMode")
            if TileMode is not None:
                self._clamp_mode = TileMode.CLAMP
        except Exception:
            self._clamp_mode = None

    # ---------------- 开关缓存 ----------------

    def _sync_settings_cache(self):
        try:
            v = self.get_setting("enabled", True)
            self._enabled_cached = True if v is None else _jbool(v)
        except Exception:
            self._enabled_cached = True
        try:
            v2 = self.get_setting("bubble_frost", True)
            self._bubble_frost_cached = True if v2 is None else _jbool(v2)
        except Exception:
            self._bubble_frost_cached = True

    # ---------------- 内置液态玻璃 & 药丸变透回调 ----------------

    def _after_liquid_glass_enabled(self, param):
        if self._enabled_cached:
            try:
                param.setResult(True)
            except Exception:
                pass

    def _before_force_true(self, param):
        if self._enabled_cached:
            try:
                if param.args and len(param.args) > 0:
                    param.args[0] = True
            except Exception as exc:
                self._diag.append("[!!] force_true: %s" % exc)

    def _after_factory_allow_glass(self, param):
        if not self._enabled_cached:
            return
        f = self._f_factory_lg
        if f is None:
            return
        try:
            obj = param.thisObject
            if obj is not None:
                f.setBoolean(obj, True)
                self._factory_hits += 1
        except Exception:
            pass

    def _before_lite_flag(self, param):
        if not self._enabled_cached:
            return
        flag = self._lite_flag
        if flag is None:
            return
        try:
            args = param.args
            if args and int(args[0]) == flag:
                param.setResult(True)
        except Exception:
            pass

    def _after_chat_blur_enabled(self, param):
        if self._enabled_cached:
            try:
                param.setResult(True)
            except Exception:
                pass

    def _after_provider_blur_off(self, param):
        if not self._enabled_cached:
            return
        try:
            param.setResult(False)
            self._provider_hits += 1
            if self._provider_hits == 1:
                self._diag.append("[provider] 药丸 tint 已回半透明分支")
        except Exception:
            pass

    def _press_tint(self, drawable, mark_rn_invalid=False):
        f = self._f_bg_color
        if drawable is None or f is None:
            return False
        try:
            c = int(f.getInt(drawable))
            if c == 0:
                return False
            a = (c >> 24) & 0xFF
            if a <= 100:
                return False
            v = (c & 0x00FFFFFF) | 0x64000000
            if v >= 0x80000000:
                v -= 0x100000000
            f.setInt(drawable, v)
            if mark_rn_invalid and self._f_rn_invalidated is not None:
                try:
                    self._f_rn_invalidated.setBoolean(drawable, True)
                except Exception:
                    pass
            return True
        except Exception as exc:
            if not self._tint_err_logged:
                self._tint_err_logged = True
                self._diag.append("[!!] press_tint: %s" % exc)
            return False

    def _before_rn_draw(self, param):
        if not self._enabled_cached:
            return
        try:
            if self._press_tint(param.thisObject, mark_rn_invalid=True):
                self._tint_hits += 1
                if self._tint_hits == 1:
                    self._diag.append("[tint] 玻璃 tint 已压到 100 (RenderNode)")
        except Exception:
            pass

    def _before_src_draw(self, param):
        if not self._enabled_cached:
            return
        try:
            if self._press_tint(param.thisObject, mark_rn_invalid=False):
                self._tint_hits += 1
                if self._tint_hits == 1:
                    self._diag.append("[tint] 玻璃 tint 已压到 100 (Source)")
        except Exception:
            pass

    def _after_bbd_draw(self, param):
        if not self._enabled_cached:
            return
        try:
            d = param.thisObject
            if self._press_tint(d, mark_rn_invalid=True):
                self._tint_hits += 1
                if self._tint_hits == 1:
                    self._diag.append("[tint] 玻璃 tint 已压到 100 (BBD)")
                try:
                    d.invalidateSelf()
                except Exception:
                    pass
        except Exception:
            pass

    def _after_update_colors(self, param):
        if not self._enabled_cached:
            return
        try:
            d = param.thisObject
            if self._press_tint(d, mark_rn_invalid=True):
                self._tint_hits += 1
                if self._tint_hits == 1:
                    self._diag.append("[tint] 玻璃 tint 已压到 100 (updateColors)")
                try:
                    d.invalidateSelf()
                except Exception:
                    pass
        except Exception:
            pass

    # ---------------- 顶栏真模糊 & 清底 ----------------

    def _invalidate_list_and_children(self, lv):
        if lv is None:
            return
        try:
            n = int(lv.getChildCount())
            for i in range(n):
                try:
                    ch = lv.getChildAt(i)
                    if ch is not None:
                        ch.invalidate()
                except Exception:
                    pass
            lv.invalidate()
        except Exception:
            pass

    def _invalidate_all_lists(self):
        try:
            items = list(self._lv_to_frag.values())
        except Exception:
            items = []
        for lv, _ in items:
            self._invalidate_list_and_children(lv)

    def _ensure_list_hooked(self, lv):
        if lv is None:
            return False
        try:
            lv_cls = lv.getClass()
            if lv_cls is None:
                return False
            cls_name = str(lv_cls.getName())
            if cls_name in self._hooked_list_classes:
                return True
            self._hooked_list_classes.add(cls_name)
            # 沿继承链找具体实现的 drawChatBackgroundElements(Canvas, RectF)（不硬编码 $22）
            cur = lv_cls
            installed = 0
            while cur is not None:
                installed = self._hook(
                    cur,
                    "drawChatBackgroundElements",
                    before=self._before_draw_chat_bg_elements,
                    after=self._after_draw_chat_bg_elements,
                    stat_key="list_draw_bg",
                    param_count=2,
                    skip_abstract=True,
                )
                if installed > 0:
                    self._diag.append("[tile] 动态挂钩列表背景绘制 %s (x%d)" % (str(cur.getName()), installed))
                    break
                cur = cur.getSuperclass()
            if installed <= 0:
                # 兜底：挂 1 参版本
                cur = lv_cls
                while cur is not None:
                    installed = self._hook(
                        cur,
                        "drawChatBackgroundElements",
                        before=self._before_draw_chat_bg_elements,
                        after=self._after_draw_chat_bg_elements,
                        stat_key="list_draw_bg",
                        param_count=1,
                        skip_abstract=True,
                    )
                    if installed > 0:
                        self._diag.append("[tile] 动态挂钩列表背景绘制(1参) %s (x%d)" % (str(cur.getName()), installed))
                        break
                    cur = cur.getSuperclass()
            if installed > 0:
                self._invalidate_list_and_children(lv)
                return True
            return False
        except Exception as exc:
            self._tile_diag_once("hooklist", "[tile!!] 动态挂钩列表失败: %s" % exc)
            return False

    def _register_chat_refs(self, fragment, content_view):
        if fragment is None or content_view is None:
            return
        lv = None
        if self._f_chat_list is not None:
            try:
                lv = self._f_chat_list.get(fragment)
            except Exception:
                lv = None
        if lv is not None:
            self._lv_to_frag[self._id_hash(lv)] = (lv, fragment)
            self._ensure_list_hooked(lv)

    def _after_chat_create_view(self, param):
        if not self._enabled_cached:
            return
        try:
            fragment = param.thisObject
            content_view = param.getResult()
            if fragment is None or content_view is None:
                return
            action_bar = fragment.getActionBar()
            if action_bar is not None:
                action_bar.setDrawBlurBackground(content_view)
                self._topbar_count += 1
                if self._topbar_count == 1:
                    self._diag.append("[topbar] 顶栏模糊已接上")
            self._register_chat_refs(fragment, content_view)
        except Exception as exc:
            self._diag.append("[!!] chat_create_view: %s" % exc)

    def _after_action_bar_color(self, param):
        if not self._enabled_cached:
            return
        f = self._f_ab_blur
        if f is None:
            return
        try:
            ab = param.thisObject
            if ab is None or not bool(f.getBoolean(ab)):
                return
            ab.setBackground(None)
            self._bar_clear_count += 1
            if self._bar_clear_count == 1:
                self._diag.append("[bar] 顶栏实色底已清掉")
        except Exception as exc:
            if not self._bar_err_logged:
                self._bar_err_logged = True
                self._diag.append("[!!] action_bar_color: %s" % exc)

    # ---------------- 气泡变透（按比例缩放 + 零重入） ----------------

    def _after_message_drawable_alpha(self, param):
        if not self._enabled_cached or self._in_bubble_alpha:
            return
        try:
            d = param.thisObject
            if d is None:
                return
            args = param.args
            raw = int(args[0]) if args and len(args) > 0 else int(d.getAlpha())
            if raw <= 0:
                return
            target = (raw * _BUBBLE_MAX_ALPHA) // 255
            if target >= raw:
                return
            f_a = self._f_md_alpha
            f_p = self._f_md_paint
            if f_a is not None and f_p is not None:
                f_a.setInt(d, target)
                p = f_p.get(d)
                if p is not None:
                    p.setAlpha(target)
                f_g = self._f_md_grad
                if f_g is None or f_g.get(d) is None:
                    bg_d = d.getBackgroundDrawable()
                    if bg_d is not None:
                        bg_d.setAlpha(target)
            else:
                self._in_bubble_alpha = True
                try:
                    d.setAlpha(target)
                finally:
                    self._in_bubble_alpha = False
            self._bubble_hits += 1
            if self._bubble_hits == 1:
                self._diag.append("[bubble] 气泡透明度已按比例压至上限 160")
        except Exception:
            pass

    # ---------------- 气泡磨砂：子像素 BitmapShader + 父列表逐帧绘制 ----------------

    def _tile_diag_once(self, key, text):
        try:
            if key not in self._tile_logged:
                self._tile_logged.add(key)
                self._diag.append(text)
        except Exception:
            pass

    def _is_drawing_blur(self):
        f = self._f_drawing_blur
        if f is None:
            return False
        try:
            return bool(f.getBoolean(None))
        except Exception:
            return False

    def _read_offsets(self, cv):
        tx = 0.0
        ty = 0.0
        bty = 0
        if cv is None:
            return tx, ty, bty
        if self._f_tx is not None:
            try:
                tx = float(self._f_tx.getFloat(cv))
            except Exception:
                pass
        if self._f_ty is not None:
            try:
                ty = float(self._f_ty.getFloat(cv))
            except Exception:
                pass
        if self._f_bty is not None:
            try:
                bty = int(self._f_bty.getInt(cv))
            except Exception:
                pass
        return tx, ty, bty

    def _read_pull_y(self, lv):
        if self._f_pull_off is None or self._f_draw_lv_yoff is None:
            return 0.0
        rec = self._lv_to_frag.get(self._id_hash(lv))
        if rec is None:
            return 0.0
        frag = rec[1]
        if frag is None:
            return 0.0
        try:
            if float(self._f_pull_off.getFloat(frag)) != 0.0:
                return float(self._f_draw_lv_yoff.getFloat(frag))
        except Exception:
            pass
        return 0.0

    def _tile_saturation_paint(self):
        p = self._tile_sat_paint
        if p is not None:
            return p
        try:
            Paint = self._get_jclass("android.graphics.Paint")
            if Paint is None:
                return None
            p = Paint(2)  # FILTER_BITMAP_FLAG
            try:
                CM = self._get_jclass("android.graphics.ColorMatrix")
                CMCF = self._get_jclass("android.graphics.ColorMatrixColorFilter")
                if CM is not None and CMCF is not None:
                    cm = CM()
                    cm.setSaturation(2.0)
                    p.setColorFilter(CMCF(cm))
            except Exception:
                pass
            self._tile_sat_paint = p
            return p
        except Exception as exc:
            self._tile_diag_once("satpaint", "[tile!!] 建饱和度 Paint 失败: %s" % exc)
            return None

    def _evict_if_needed(self):
        while len(self._entries) > _MAX_TILE_ENTRIES:
            oldest_key = None
            oldest_seq = None
            for k, e in self._entries.items():
                if oldest_seq is None or e.seq < oldest_seq:
                    oldest_seq = e.seq
                    oldest_key = k
            if oldest_key is None:
                break
            old = self._entries.pop(oldest_key, None)
            if old is not None:
                old.recycle()

    def _rebuild_entry_bitmap(self, entry, cv, bg, cur_dr, w, h, theme_anim):
        AU = self._get_jclass("org.telegram.messenger.AndroidUtilities")
        BitmapShader = self._get_jclass("android.graphics.BitmapShader")
        Matrix = self._get_jclass("android.graphics.Matrix")
        Paint = self._get_jclass("android.graphics.Paint")
        clamp = self._clamp_mode
        if AU is None or BitmapShader is None or Matrix is None or Paint is None or clamp is None:
            return False

        # 键盘打开时 bottomClip 非零会裁掉壁纸下半区；截糊图时临时清零再还原，防止键盘收起后下半屏发白
        saved_clip = 0
        f_clip = self._f_bottom_clip
        if f_clip is not None:
            try:
                saved_clip = int(f_clip.getInt(cv))
                if saved_clip != 0:
                    f_clip.setInt(cv, 0)
            except Exception:
                saved_clip = 0
        try:
            bmp = AU.makeBlurBitmap(bg, float(_BLUR_DOWNSCALE), int(_BLUR_RADIUS))
        finally:
            if saved_clip != 0 and f_clip is not None:
                try:
                    f_clip.setInt(cv, saved_clip)
                except Exception:
                    pass

        if bmp is None:
            self._tile_diag_once("nullbmp", "[tile!!] 糊位图是空的")
            return False

        baked_ok = False
        try:
            Bitmap = self._get_jclass("android.graphics.Bitmap")
            Canvas = self._get_jclass("android.graphics.Canvas")
            sat = self._tile_saturation_paint()
            if Bitmap is not None and Canvas is not None and sat is not None:
                cfg = Bitmap.Config.ARGB_8888
                baked = Bitmap.createBitmap(int(bmp.getWidth()), int(bmp.getHeight()), cfg)
                if baked is not None:
                    c = Canvas(baked)
                    c.drawBitmap(bmp, 0.0, 0.0, sat)
                    try:
                        bmp.recycle()
                    except Exception:
                        pass
                    bmp = baked
                    baked_ok = True
        except Exception as exc:
            self._tile_diag_once("bake", "[tile!!] 烤饱和度失败: %s" % exc)
            baked_ok = False

        shader = BitmapShader(bmp, clamp, clamp)
        mat = entry.matrix if entry.matrix is not None else Matrix()
        paint = entry.paint
        if paint is None or baked_ok != self._tile_baked_ok:
            paint = Paint(3)  # ANTI_ALIAS_FLAG | FILTER_BITMAP_FLAG
            if not baked_ok:
                try:
                    CM = self._get_jclass("android.graphics.ColorMatrix")
                    CMCF = self._get_jclass("android.graphics.ColorMatrixColorFilter")
                    if CM is not None and CMCF is not None:
                        cm = CM()
                        cm.setSaturation(2.0)
                        paint.setColorFilter(CMCF(cm))
                except Exception:
                    pass
            self._tile_baked_ok = baked_ok
            entry.last_alpha = 255
        paint.setShader(shader)

        old_bmp = entry.bmp
        entry.bg = bg
        entry.bg_drawable = cur_dr
        entry.bw = w
        entry.bh = h
        entry.bmp = bmp
        entry.shader = shader
        entry.matrix = mat
        entry.paint = paint
        entry.scale_x = _BLUR_DOWNSCALE
        entry.scale_y = _BLUR_DOWNSCALE
        tx, ty, bty = self._read_offsets(cv)
        entry.base_tx = tx
        entry.base_ty = ty
        entry.base_bty = bty
        entry.need_rebuild = (theme_anim < 0.999)

        if old_bmp is not None:
            try:
                if old_bmp != bmp and not old_bmp.isRecycled():
                    old_bmp.recycle()
            except Exception:
                pass

        self._tile_rebuilds += 1
        if not self._tile_built_logged:
            self._tile_built_logged = True
            self._diag.append("[tile] 子像素糊位图已建好 %dx%d (baked=%s)" % (w, h, baked_ok))
        return True

    def _ensure_entry(self, cv):
        if cv is None or self._f_bg_view is None:
            return None
        try:
            if self._f_skip_bg is not None and bool(self._f_skip_bg.getBoolean(cv)):
                return None
            bg = self._f_bg_view.get(cv)
            if bg is None:
                return None
            w = int(bg.getWidth())
            h = int(bg.getHeight())
            if w <= 0 or h <= 0:
                return None
            cur_dr = None
            if self._f_bg_drawable is not None:
                cur_dr = self._f_bg_drawable.get(cv)
                if cur_dr is None:
                    return None
            theme_anim = 1.0
            if self._f_theme_anim is not None:
                try:
                    theme_anim = float(self._f_theme_anim.getFloat(cv))
                except Exception:
                    theme_anim = 1.0

            key = self._id_hash(cv)
            entry = self._entries.get(key)
            if entry is not None:
                try:
                    same_cv = (entry.cv == cv)
                except Exception:
                    same_cv = False
                if not same_cv:
                    entry.recycle()
                    entry = None
            if entry is None:
                self._entry_seq += 1
                entry = _TileEntry(cv, self._entry_seq)
                self._entries[key] = entry
                self._evict_if_needed()

            need_build = False
            if entry.bmp is None:
                need_build = True
            else:
                try:
                    if entry.bmp.isRecycled():
                        need_build = True
                except Exception:
                    need_build = True
            if not need_build:
                if entry.bw != w or entry.bh != h:
                    need_build = True
                elif entry.bg is None or entry.bg != bg:
                    need_build = True
                elif self._f_bg_drawable is not None and cur_dr != entry.bg_drawable:
                    need_build = True
                elif entry.need_rebuild and theme_anim >= 0.999:
                    need_build = True

            if need_build:
                if not self._rebuild_entry_bitmap(entry, cv, bg, cur_dr, w, h, theme_anim):
                    return None
            return entry
        except Exception as exc:
            self._tile_diag_once("ensure", "[tile!!] 准备壁纸糊图失败: %s" % exc)
            return None

    def _should_draw_in_parent(self, cell):
        if cell is None:
            return False
        f_can = self._f_can_bg_parent
        f_msg = self._f_cur_msg
        f_grp = self._f_cur_group
        if f_can is None or f_msg is None or f_grp is None:
            return False
        try:
            return (
                bool(f_can.getBoolean(cell))
                and f_msg.get(cell) is not None
                and f_grp.get(cell) is None
            )
        except Exception:
            return False

    def _before_draw_bg_in_parent(self, param):
        if not self._enabled_cached or not self._bubble_frost_cached:
            return
        cell = param.thisObject
        if self._should_draw_in_parent(cell):
            try:
                param.setResult(True)
            except Exception:
                pass

    def _before_draw_chat_bg_elements(self, param):
        self._frame_stack.append(self._active_frame)
        self._active_frame = None
        if not self._enabled_cached or not self._bubble_frost_cached:
            return
        try:
            if self._is_drawing_blur():
                return
            lv = param.thisObject
            if lv is None:
                return
            cv = lv.getParent()
            if cv is None:
                return
            entry = self._ensure_entry(cv)
            if entry is None:
                return
            bg = entry.bg
            bg_x = float(bg.getX())
            bg_y = float(bg.getY())
            lx = float(lv.getX())
            ly = float(lv.getY())
            pull_y = self._read_pull_y(lv)
            tx, ty, bty = self._read_offsets(cv)
            ox = tx - entry.base_tx
            oy = (ty + float(bty)) - (entry.base_ty + float(entry.base_bty))
            self._active_frame = (entry, bg_x + ox - lx, bg_y + oy - (ly + pull_y), lv)
        except Exception as exc:
            self._tile_diag_once("frame", "[tile!!] 列表帧准备异常: %s" % exc)

    def _after_draw_chat_bg_elements(self, param):
        self._cur_ctx = None
        if self._frame_stack:
            self._active_frame = self._frame_stack.pop()
        else:
            self._active_frame = None

    def _resolve_frame_fallback(self, cell):
        if cell is None:
            return None
        try:
            lv = cell.getParent()
            if lv is None:
                return None
            self._ensure_list_hooked(lv)
            cv = lv.getParent()
            if cv is None:
                return None
            entry = self._ensure_entry(cv)
            if entry is None:
                return None
            bg = entry.bg
            tx_o, ty_o, bty_o = self._read_offsets(cv)
            ox = tx_o - entry.base_tx
            oy = (ty_o + float(bty_o)) - (entry.base_ty + float(entry.base_bty))
            base_x = float(bg.getX()) + ox - float(lv.getX())
            base_y = float(bg.getY()) + oy - float(lv.getY())
            return (entry, base_x, base_y, lv)
        except Exception:
            return None

    def _before_cell_draw_background(self, param):
        self._ctx_stack.append(self._cur_ctx)
        self._cur_ctx = None
        if not self._enabled_cached or not self._bubble_frost_cached:
            return
        try:
            if self._is_drawing_blur():
                return
            args = param.args
            cell = param.thisObject
            if cell is None or not args:
                return
            from_parent = _jbool(args[1]) if len(args) > 1 else False
            # 子 View onDraw 调用的 drawBackgroundInternal(canvas, false) 会在父列表已绘制时被原生直接跳过，
            # 绝不在此处建立上下文，防止在子 RenderNode 中重复贴死图盖住父列表实时层。
            if not from_parent and self._should_draw_in_parent(cell):
                return
            frame = self._active_frame
            if frame is None:
                frame = self._resolve_frame_fallback(cell)
                if frame is None:
                    return
            entry, base_x, base_y, _ = frame
            tx = base_x - float(cell.getX())
            ty = base_y - float(cell.getY()) - float(cell.getPaddingTop())
            self._cur_ctx = [entry, tx, ty, cell, None]
        except Exception as exc:
            self._tile_diag_once("bgint", "[tile!!] drawBackgroundInternal 异常: %s" % exc)

    def _after_cell_draw_background(self, param):
        if self._ctx_stack:
            self._cur_ctx = self._ctx_stack.pop()
        else:
            self._cur_ctx = None

    def _before_cell_draw_group_bg(self, param):
        self._ctx_stack.append(self._cur_ctx)
        self._cur_ctx = None
        if not self._enabled_cached or not self._bubble_frost_cached:
            return
        try:
            if self._is_drawing_blur():
                return
            cell = param.thisObject
            if cell is None:
                return
            frame = self._active_frame
            if frame is None:
                frame = self._resolve_frame_fallback(cell)
                if frame is None:
                    return
            entry, base_x, base_y, _ = frame
            # 相册分组背景直接在 chatListView 坐标系下绘制，不减 cell X/Y
            self._cur_ctx = [entry, base_x, base_y, None, None]
        except Exception as exc:
            self._tile_diag_once("bggrp", "[tile!!] drawBackground 分组异常: %s" % exc)

    def _after_cell_draw_group_bg(self, param):
        if self._ctx_stack:
            self._cur_ctx = self._ctx_stack.pop()
        else:
            self._cur_ctx = None

    def _before_md_draw_cached(self, param):
        if self._in_frost_draw or not self._enabled_cached or not self._bubble_frost_cached:
            return
        ctx = self._cur_ctx
        if ctx is None:
            return
        drawable = param.thisObject
        if drawable is None:
            return
        # 高亮/多选动画会紧接着画第二个 selectedDrawable，跳过重复磨砂
        first_d = ctx[4]
        if first_d is not None:
            try:
                if first_d != drawable:
                    f_sel = self._f_md_is_selected
                    if f_sel is None or bool(f_sel.getBoolean(drawable)):
                        return
            except Exception:
                return
        entry = ctx[0]
        bmp = entry.bmp
        if bmp is None:
            return
        try:
            if bmp.isRecycled():
                return
        except Exception:
            return
        args = param.args
        canvas = args[0] if args and len(args) > 0 else None
        if canvas is None:
            return
        try:
            tx = ctx[1]
            ty = ctx[2]
            cell = ctx[3]
            if cell is not None and self._f_trans_y_off is not None:
                ty_off = float(self._f_trans_y_off.getFloat(cell))
                if ty_off != 0.0:
                    ty -= ty_off
            cur_a = int(drawable.getAlpha())
            if cur_a <= 0:
                return
            frost_a = 255 if cur_a >= _BUBBLE_MAX_ALPHA else (cur_a * 255) // _BUBBLE_MAX_ALPHA
            if frost_a > 255:
                frost_a = 255
            mat = entry.matrix
            mat.setScale(entry.scale_x, entry.scale_y)
            mat.postTranslate(tx, ty)
            entry.shader.setLocalMatrix(mat)
            paint = entry.paint
            if entry.last_alpha != frost_a:
                paint.setAlpha(frost_a)
                entry.last_alpha = frost_a
            ctx[4] = drawable
            self._in_frost_draw = True
            try:
                # 调 2 参 MessageDrawable.draw(canvas, paint)：绕开 NinePatch 分支，
                # 实时按当前 bounds/isOut/分组圆角/尾巴生成精确轮廓并用 GPU BitmapShader 填充
                drawable.draw(canvas, paint)
            finally:
                self._in_frost_draw = False
            self._tile_hits += 1
            if self._tile_hits == 1:
                self._diag.append("[tile] BitmapShader 子像素气泡磨砂已接上")
        except Exception as exc:
            self._tile_diag_once("paste", "[tile!!] 气泡磨砂绘制异常: %s" % exc)

    def _after_chat_resume(self, param):
        if not self._enabled_cached:
            return
        try:
            fragment = param.thisObject
            if fragment is None:
                return
            fv = None
            try:
                fv = fragment.getFragmentView()
            except Exception:
                fv = None
            if fv is not None:
                self._register_chat_refs(fragment, fv)
                if self._bubble_frost_cached:
                    # 回到聊天页时强制标记检查（切主题/换壁纸后自动重建）
                    key = self._id_hash(fv)
                    entry = self._entries.get(key)
                    if entry is not None:
                        entry.need_rebuild = True
                    self._ensure_entry(fv)
        except Exception:
            pass

    def _before_chat_destroy(self, param):
        try:
            fragment = param.thisObject
            if fragment is None:
                return
            if self._f_chat_list is not None:
                try:
                    lv = self._f_chat_list.get(fragment)
                    if lv is not None:
                        self._lv_to_frag.pop(self._id_hash(lv), None)
                except Exception:
                    pass
            fv = None
            try:
                fv = fragment.getFragmentView()
            except Exception:
                fv = None
            if fv is not None:
                old = self._entries.pop(self._id_hash(fv), None)
                if old is not None:
                    old.recycle()
        except Exception:
            pass

    def _hook_on(self, key):
        try:
            v = self._installed.get(key) or ""
            n = int(str(v).split(" ")[0].split("/")[0])
            return n > 0
        except Exception:
            return False

    # ---------------- 生命周期 ----------------

    def on_plugin_load(self):
        self._sync_settings_cache()
        self._diag.append("[DIAG] on_plugin_load 进入，Android SDK=%s" % _sdk_int())

        BBD = _class_object("org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable")
        BBD_RN = _class_object("org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode")
        BBD_SRC = _class_object("org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableSource")
        Factory = _class_object("org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory")
        AB = _class_object("org.telegram.ui.ActionBar.ActionBar")

        self._bbd_cls = BBD
        self._bbd_rn_cls = BBD_RN
        self._factory_cls = Factory
        self._action_bar_cls = AB
        self._init_cached_fields()

        # 1) 13.1.1 & 13.2.1 共用 + 各自专属 blur3 玻璃开关与 tint 压透
        if BBD is not None:
            self._hook(BBD, "isLiquidGlassEffectEnabled", after=self._after_liquid_glass_enabled, stat_key="builtin_effect_enabled")
            self._hook(BBD, "draw", after=self._after_bbd_draw, stat_key="tint_draw_bbd", param_count=1)
            self._hook(BBD, "updateColors", after=self._after_update_colors, stat_key="tint_update_bbd", param_count=0)

        if BBD_RN is not None:
            self._hook(BBD_RN, "draw", before=self._before_rn_draw, stat_key="tint_draw_rn", param_count=1)
            self._hook(BBD_RN, "updateColors", after=self._after_update_colors, stat_key="tint_update_rn", param_count=0)

        if BBD_SRC is not None:
            self._hook(BBD_SRC, "draw", before=self._before_src_draw, stat_key="tint_draw_src", param_count=1)

        if Factory is not None:
            self._hook(Factory, "setLiquidGlassEffectAllowed", after=self._after_factory_allow_glass, stat_key="factory_effect_allowed", param_count=1)

        Impl = _class_object("org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable$Impl")
        if Impl is not None:
            self._hook(Impl, "setLiquidGlassEnabled", before=self._before_force_true, stat_key="impl_glass_enabled", param_count=1)

        ImplRN = _class_object("org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable$ImplRenderNode")
        if ImplRN is not None:
            self._hook(ImplRN, "setLiquidGlassEnabled", before=self._before_force_true, stat_key="implrn_glass_enabled", param_count=1)

        # 2) LiteMode 强制允许 FLAG_LIQUID_GLASS
        LM = _class_object("org.telegram.messenger.LiteMode")
        if LM is not None:
            try:
                self._lite_flag = int(LM.getField("FLAG_LIQUID_GLASS").get(None))
            except Exception as exc:
                self._diag.append("[lite] 读 FLAG_LIQUID_GLASS 失败: %s" % exc)
                self._lite_flag = None
            self._hook(LM, "isEnabledSetting", before=self._before_lite_flag, stat_key="lite_flag", param_count=1)
            self._hook(LM, "isEnabled", before=self._before_lite_flag, stat_key="lite_enabled", param_count=1)

        # 3) 药丸变透：BlurredBackgroundProviderImpl.checkBlurEnabled -> False
        Provider = _class_object("org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl")
        if Provider is not None:
            self._hook(Provider, "checkBlurEnabled", after=self._after_provider_blur_off, stat_key="provider_blur_off")

        # 4) 顶栏真模糊 + 气泡壁纸预糊 + 列表动态挂钩
        CA = _class_object("org.telegram.ui.ChatActivity")
        if CA is not None:
            self._hook(CA, "createView", after=self._after_chat_create_view, stat_key="chat_create_view", param_count=1)
            self._hook(CA, "onResume", after=self._after_chat_resume, stat_key="chat_resume", param_count=0)
            self._hook(CA, "onFragmentDestroy", before=self._before_chat_destroy, stat_key="chat_destroy", param_count=0)

        # 5) 输入框真模糊
        SC = _class_object("org.telegram.messenger.SharedConfig")
        if SC is not None:
            self._hook(SC, "chatBlurEnabled", after=self._after_chat_blur_enabled, stat_key="chat_blur", param_count=0)

        # 6) 顶栏清底
        if AB is not None:
            self._hook(AB, "setBackgroundColor", after=self._after_action_bar_color, stat_key="action_bar_color", param_count=1)

        # 7) 气泡变透 + 父列表逐帧子像素磨砂
        MD = _class_object("org.telegram.ui.ActionBar.MessageDrawable")
        if MD is not None:
            self._hook(MD, "setAlpha", after=self._after_message_drawable_alpha, stat_key="bubble_alpha", param_count=1)
            self._hook(MD, "drawCached", before=self._before_md_draw_cached, stat_key="md_draw_cached", param_count=2)

        CMC = _class_object("org.telegram.ui.Cells.ChatMessageCell")
        if CMC is not None:
            self._hook(CMC, "drawBackgroundInParent", before=self._before_draw_bg_in_parent, stat_key="bg_in_parent", param_count=0)
            self._hook(
                CMC,
                "drawBackgroundInternal",
                before=self._before_cell_draw_background,
                after=self._after_cell_draw_background,
                stat_key="tile_paste",
                param_count=2,
            )
            self._hook(
                CMC,
                "drawBackground",
                before=self._before_cell_draw_group_bg,
                after=self._after_cell_draw_group_bg,
                stat_key="tile_group",
                param_count=9,
            )

        self._diag.append("[DIAG] 安装结果: %s" % self._installed)
        self._diag.append("[DIAG] 类解析: %s" % {k.split(".")[-1]: v for k, v in _CLASS_PATH.items()})

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
        self._hooked_list_classes.clear()
        self._active_frame = None
        self._frame_stack.clear()
        self._cur_ctx = None
        self._ctx_stack.clear()
        lvs = [lv for lv, _ in self._lv_to_frag.values()]
        self._lv_to_frag.clear()
        for e in list(self._entries.values()):
            e.recycle()
        self._entries.clear()
        if lvs:
            try:
                def _refresh_after_unload():
                    for lv in lvs:
                        self._invalidate_list_and_children(lv)
                run_on_ui_thread(_refresh_after_unload)
            except Exception:
                pass

    def create_settings(self):
        for k, v in DEFAULTS.items():
            if self.get_setting(k, None) is None:
                self.set_setting(k, v)
        self._sync_settings_cache()

        has_132 = self._hook_on("builtin_effect_enabled") and self._hook_on("implrn_glass_enabled")
        has_131 = self._hook_on("factory_effect_allowed") and self._hook_on("tint_draw_rn")
        if has_132:
            builtin = "已开（13.2 模式）"
        elif has_131 and self._hook_on("lite_flag"):
            builtin = "已开（13.1 RenderNode 模式）"
        elif self._hook_on("factory_effect_allowed") or self._hook_on("lite_flag"):
            builtin = "部分已开"
        else:
            builtin = "未挂载（请检查 hooks 权限）"

        topbar = ("已接上 %d 次" % self._topbar_count) if self._topbar_count > 0 else ("已就绪" if self._hook_on("chat_create_view") else "没开")
        bar_clear = ("已清 %d 次" % self._bar_clear_count) if self._bar_clear_count > 0 else ("已开" if self._hook_on("action_bar_color") else "没开")
        chat_blur = "已开" if self._hook_on("chat_blur") else "没开"
        provider = ("已开（命中 %d 次）" % self._provider_hits) if self._provider_hits > 0 else ("已开" if self._hook_on("provider_blur_off") else "没开")
        tint_ok = (self._hook_on("tint_draw_rn") or self._hook_on("tint_draw_bbd")
                   or self._hook_on("tint_update_rn") or self._hook_on("tint_update_bbd"))
        tint = ("已开（命中 %d 次）" % self._tint_hits) if self._tint_hits > 0 else ("已开" if tint_ok else "没开")
        bubble = ("已开（命中 %d 次）" % self._bubble_hits) if self._bubble_hits > 0 else ("已开" if self._hook_on("bubble_alpha") else "没开")
        if not self._bubble_frost_cached:
            tile = "已手动关闭（仅清玻璃气泡）"
        elif self._tile_hits > 0:
            tile = "已开（子像素着色器，命中 %d 次 / 建图 %d 次）" % (self._tile_hits, self._tile_rebuilds)
        elif self._tile_rebuilds > 0:
            tile = "壁纸糊图已建好（%d 次）" % self._tile_rebuilds
        elif self._hook_on("md_draw_cached") and self._hook_on("bg_in_parent"):
            tile = "已就绪（父列表子像素模式）"
        else:
            tile = "没开"

        return [
            Header(text="液态玻璃全局 v%s（双端版）" % __version__),
            Switch(key="enabled", text="开启液态玻璃", default=True, on_change=self._on_enabled_change),
            Switch(key="bubble_frost", text="气泡磨砂（关闭则仅清玻璃气泡）", default=True, on_change=self._on_frost_change),
            Header(text="已开的功能"),
            Text(text="内置液态玻璃引擎：" + builtin),
            Text(text="顶栏真模糊：" + topbar),
            Text(text="顶栏清底：" + bar_clear),
            Text(text="输入框真模糊：" + chat_blur),
            Text(text="药丸变透（提供方模糊关）：" + provider),
            Text(text="玻璃 tint 压到 100：" + tint),
            Text(text="气泡变透：" + bubble),
            Text(text="气泡磨砂：" + tile),
            Header(text="诊断"),
            Text(text="点击复制诊断信息", on_click=self._copy_diag),
        ]

    def _on_enabled_change(self, val):
        try:
            self._enabled_cached = _jbool(val)
            self._diag.append("[UI] enabled=%s" % self._enabled_cached)
            self._invalidate_all_lists()
        except Exception:
            pass

    def _on_frost_change(self, val):
        try:
            self._bubble_frost_cached = _jbool(val)
            self._diag.append("[UI] bubble_frost=%s" % self._bubble_frost_cached)
            self._invalidate_all_lists()
        except Exception:
            pass

    def _copy_diag(self, *args):
        self._diag.append("[UI] 点击复制诊断")
        try:
            copy_to_clipboard("\n".join(self._diag))
        except Exception as e:
            self._diag.append("[!!] 复制失败: %s" % e)
