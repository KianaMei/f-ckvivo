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
4. 热路径零 get_setting()、零字符串拼接、字段全预缓存，适配 ExteraLess 250ms/s 看门狗；
   同时修复多聊天切换返回时壁纸与列表引用滞留、位图 isRecycled 防护。
"""

from base_plugin import BasePlugin, MethodHook
from android_utils import copy_to_clipboard
from java import jclass
from ui.settings import Header, Switch, Text

__id__ = "liquid_glass_global"
__name__ = "液态玻璃全局（双端版）"
__description__ = "顶栏真模糊 + 输入框真模糊 + 药丸变透（tint 压到 100）+ 气泡磨砂（壁纸预糊贴块），兼容 ExteraLess 13.1.x 与 NagramXF 13.2.x。"
__author__ = "安安（ExteraLess 双端适配）"
__version__ = "2.5.0"
__min_version__ = "13.1.0"
__app_version__ = ">=13.1.0"
__sdk_version__ = ">=1.4.5.0"
__permissions__ = ["hooks"]

DEFAULTS = {
    "enabled": True,
    "bubble_frost": True,
}


def _len_java(arr):
    try:
        return int(arr.length)
    except Exception:
        try:
            return len(arr)
        except Exception:
            return 0


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
    def __init__(self, fn):
        super().__init__()
        self._fn = fn

    def before_hooked_method(self, param):
        self._fn(param)


class _AfterHook(MethodHook):
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


class LiquidGlassPlugin(BasePlugin):
    def __init__(self):
        super().__init__()
        self._diag = ["[DIAG] 实例化完成"]
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
        self._f_chat_list = None
        self._f_bg_view = None
        self._f_tx = None
        self._f_ty = None
        self._f_bty = None
        self._f_bg_cache_params = None
        self._f_last_rect = None
        self._f_rect_l = None
        self._f_rect_t = None
        self._f_rect_r = None
        self._f_rect_b = None

        self._tint_err_logged = False
        self._bar_err_logged = False

        # 气泡磨砂贴块状态
        self._tile_bitmap = None
        self._tile_bw = 0
        self._tile_bh = 0
        self._tile_base_tx = 0.0
        self._tile_base_ty = 0.0
        self._tile_base_bty = 0
        self._tile_hits = 0
        self._tile_built_logged = False
        self._tile_logged = set()
        self._content_view = None
        self._list_view = None
        self._bg_view = None
        self._list_x = None
        self._list_y = None
        self._tile_src = None
        self._tile_dst = None
        self._tile_paint = None
        self._tile_sat_paint = None
        self._tile_baked = False
        self._jcls_cache = {}

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

    def _hook(self, cls, method_name, before=None, after=None, stat_key=None):
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
        for i in range(total):
            try:
                member = methods[i]
                if str(member.getName()) != method_name:
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

    def _resolve_field(self, cls_name, field_name):
        try:
            cls = _class_object(cls_name)
            if cls is None:
                return None
            f = cls.getDeclaredField(field_name)
            f.setAccessible(True)
            return f
        except Exception as exc:
            self._tile_diag_once("fld:" + cls_name + "#" + field_name,
                                 "[tile!!] 读字段失败 %s.%s: %s" % (cls_name, field_name, exc))
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
        self._f_bg_view = self._resolve_field("org.telegram.ui.Components.SizeNotifierFrameLayout", "backgroundView")
        self._f_tx = self._resolve_field("org.telegram.ui.Components.SizeNotifierFrameLayout", "translationX")
        self._f_ty = self._resolve_field("org.telegram.ui.Components.SizeNotifierFrameLayout", "translationY")
        self._f_bty = self._resolve_field("org.telegram.ui.Components.SizeNotifierFrameLayout", "backgroundTranslationY")
        self._f_bg_cache_params = self._resolve_field("org.telegram.ui.Cells.ChatMessageCell", "backgroundCacheParams")
        self._f_last_rect = self._resolve_field("org.telegram.ui.ActionBar.MessageDrawable$PathDrawParams", "lastRect")
        self._f_rect_l = self._resolve_field("android.graphics.Rect", "left")
        self._f_rect_t = self._resolve_field("android.graphics.Rect", "top")
        self._f_rect_r = self._resolve_field("android.graphics.Rect", "right")
        self._f_rect_b = self._resolve_field("android.graphics.Rect", "bottom")

    # ---------------- 开关缓存 ----------------

    def _sync_settings_cache(self):
        try:
            v = self.get_setting("enabled", True)
            self._enabled_cached = True if v is None else bool(v)
        except Exception:
            self._enabled_cached = True
        try:
            v2 = self.get_setting("bubble_frost", True)
            self._bubble_frost_cached = True if v2 is None else bool(v2)
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
        # 13.1.1 BlurredBackgroundDrawableRenderNode.draw 执行前压 tint 并标 renderNodeInvalidated=True，
        # 原方法紧接着就会直接重录 RenderNode，首帧即透、零多余重绘。
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
        # 13.2.1 BlurredBackgroundDrawable.draw 兜底路径
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

    def _bind_chat_refs(self, fragment, content_view):
        if content_view is None:
            return
        self._content_view = content_view
        self._list_view = None
        self._bg_view = None
        self._list_x = None
        self._list_y = None
        if self._f_chat_list is not None and fragment is not None:
            try:
                self._list_view = self._f_chat_list.get(fragment)
            except Exception:
                pass
        if self._f_bg_view is not None:
            try:
                self._bg_view = self._f_bg_view.get(content_view)
            except Exception:
                pass

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
            self._bind_chat_refs(fragment, content_view)
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

    # ---------------- 气泡变透 & 气泡磨砂贴块 ----------------

    def _after_message_drawable_alpha(self, param):
        if not self._enabled_cached or self._in_bubble_alpha:
            return
        try:
            d = param.thisObject
            if d is None:
                return
            cur = int(d.getAlpha())
            if cur <= 160:
                return
            self._in_bubble_alpha = True
            try:
                d.setAlpha(160)
            finally:
                self._in_bubble_alpha = False
            self._bubble_hits += 1
            if self._bubble_hits == 1:
                self._diag.append("[bubble] 气泡透明度已压到 160")
        except Exception:
            pass

    def _tile_diag_once(self, key, text):
        try:
            if key not in self._tile_logged:
                self._tile_logged.add(key)
                self._diag.append(text)
        except Exception:
            pass

    def _read_offsets(self):
        tx = 0.0
        ty = 0.0
        bty = 0
        cv = self._content_view
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

    def _read_bg_view(self):
        cv = self._content_view
        if cv is None or self._f_bg_view is None:
            return None
        try:
            bg = self._f_bg_view.get(cv)
            if bg is not None:
                self._bg_view = bg
            return bg
        except Exception:
            return None

    def _build_tile_bitmap(self):
        try:
            if not self._enabled_cached or not self._bubble_frost_cached:
                return False
            bg = self._bg_view
            if bg is None:
                bg = self._read_bg_view()
                if bg is None:
                    self._tile_diag_once("nobg", "[tile!!] 拿不到壁纸 View")
                    return False
            w = int(bg.getWidth())
            h = int(bg.getHeight())
            if w <= 0 or h <= 0:
                return False
            AU = self._get_jclass("org.telegram.messenger.AndroidUtilities")
            if AU is None:
                return False
            bmp = AU.makeBlurBitmap(bg, 7.0, 7)
            if bmp is None:
                self._tile_diag_once("nullbmp", "[tile!!] 糊位图是空的")
                return False
            self._tile_baked = False
            try:
                Bitmap = self._get_jclass("android.graphics.Bitmap")
                Canvas = self._get_jclass("android.graphics.Canvas")
                sat = self._tile_saturation_paint()
                if Bitmap is not None and Canvas is not None and sat is not None:
                    cfg = Bitmap.Config.ARGB_8888
                    baked = Bitmap.createBitmap(int(bmp.getWidth()), int(bmp.getHeight()), cfg)
                    if baked is not None:
                        cv = Canvas(baked)
                        cv.drawBitmap(bmp, 0.0, 0.0, sat)
                        try:
                            bmp.recycle()
                        except Exception:
                            pass
                        bmp = baked
                        self._tile_baked = True
            except Exception as exc:
                self._tile_diag_once("bake", "[tile!!] 烤饱和度失败: %s" % exc)
                self._tile_baked = False
            self._tile_paint = None
            old = self._tile_bitmap
            self._tile_bitmap = bmp
            self._tile_bw = w
            self._tile_bh = h
            tx, ty, bty = self._read_offsets()
            self._tile_base_tx = tx
            self._tile_base_ty = ty
            self._tile_base_bty = bty
            try:
                if old is not None and old is not bmp and not old.isRecycled():
                    old.recycle()
            except Exception:
                pass
            if not self._tile_built_logged:
                self._tile_built_logged = True
                self._diag.append("[tile] 糊位图已建好 %dx%d (baked=%s)" % (w, h, self._tile_baked))
            return True
        except Exception as exc:
            self._tile_diag_once("build", "[tile!!] 建糊位图失败: %s" % exc)
            return False

    def _tile_saturation_paint(self):
        p = self._tile_sat_paint
        if p is not None:
            return p
        try:
            Paint = self._get_jclass("android.graphics.Paint")
            if Paint is None:
                return None
            p = Paint()
            p.setFilterBitmap(True)
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

    def _tile_paint_obj(self):
        p = self._tile_paint
        if p is not None:
            return p
        try:
            Paint = self._get_jclass("android.graphics.Paint")
            if Paint is None:
                return None
            p = Paint()
            p.setFilterBitmap(True)
            if not self._tile_baked:
                try:
                    CM = self._get_jclass("android.graphics.ColorMatrix")
                    CMCF = self._get_jclass("android.graphics.ColorMatrixColorFilter")
                    if CM is not None and CMCF is not None:
                        cm = CM()
                        cm.setSaturation(2.0)
                        p.setColorFilter(CMCF(cm))
                except Exception:
                    pass
            self._tile_paint = p
            return p
        except Exception as exc:
            self._tile_diag_once("paint", "[tile!!] 建 Paint 失败: %s" % exc)
            return None

    def _before_cell_draw_background(self, param):
        if not self._enabled_cached or not self._bubble_frost_cached:
            return
        try:
            bmp = self._tile_bitmap
            if bmp is None or bmp.isRecycled():
                if not self._build_tile_bitmap():
                    return
                bmp = self._tile_bitmap
                if bmp is None or bmp.isRecycled():
                    return
            args = param.args
            canvas = args[0] if args else None
            cell = param.thisObject
            if canvas is None or cell is None:
                return
            left = int(cell.getBackgroundDrawableLeft())
            right = int(cell.getBackgroundDrawableRight())
            top = int(cell.getBackgroundDrawableTop())
            bottom = int(cell.getBackgroundDrawableBottom())
            if right <= left or bottom <= top:
                return

            if self._list_x is None or self._list_y is None:
                lv = self._list_view
                if lv is None:
                    try:
                        lv = cell.getParent()
                    except Exception:
                        lv = None
                    if lv is not None:
                        self._list_view = lv
                if lv is not None:
                    try:
                        self._list_x = float(lv.getX())
                        self._list_y = float(lv.getY())
                    except Exception:
                        pass
            if self._list_x is None or self._list_y is None:
                self._tile_diag_once("nolistpos", "[tile!!] 拿不到列表位置，跳过贴块")
                return

            fx = self._list_x + float(cell.getX()) + float(left)
            fy = self._list_y + float(cell.getY()) + float(top)
            tx, ty, bty = self._read_offsets()
            ox = tx - self._tile_base_tx
            oy = (ty + float(bty)) - (self._tile_base_ty + float(self._tile_base_bty))

            s = 1.0 / 7.0
            sx0 = int((fx - ox) * s)
            sy0 = int((fy - oy) * s)
            sx1 = int((fx + float(right - left) - ox) * s)
            sy1 = int((fy + float(bottom - top) - oy) * s)
            if sx1 <= sx0 or sy1 <= sy0:
                return

            f_params = self._f_bg_cache_params
            if f_params is None:
                return
            params = f_params.get(cell)
            if params is None:
                return
            path = params.getPath()
            if path is None or path.isEmpty():
                return

            if self._f_last_rect is not None and self._f_rect_l is not None:
                lr = self._f_last_rect.get(params)
                if lr is not None:
                    if (int(self._f_rect_l.getInt(lr)) != left
                            or int(self._f_rect_t.getInt(lr)) != top
                            or int(self._f_rect_r.getInt(lr)) != right
                            or int(self._f_rect_b.getInt(lr)) != bottom):
                        return

            paint = self._tile_paint_obj()
            if paint is None:
                return
            src = self._tile_src
            if src is None:
                Rect = self._get_jclass("android.graphics.Rect")
                if Rect is None:
                    return
                src = Rect()
                self._tile_src = src
            src.set(sx0, sy0, sx1, sy1)

            dst = self._tile_dst
            if dst is None:
                RectF = self._get_jclass("android.graphics.RectF")
                if RectF is None:
                    return
                dst = RectF()
                self._tile_dst = dst
            dst.set(float(left), float(top), float(right), float(bottom))

            canvas.save()
            try:
                canvas.clipPath(path)
                canvas.drawBitmap(bmp, src, dst, paint)
            finally:
                try:
                    canvas.restore()
                except Exception:
                    pass
            self._tile_hits += 1
            if self._tile_hits == 1:
                self._diag.append("[tile] 气泡路径裁剪已接上，贴块跑起来了")
        except Exception as exc:
            self._tile_diag_once("paste", "[tile!!] 贴块异常: %s" % exc)

    def _after_chat_resume(self, param):
        if not self._enabled_cached:
            return
        try:
            fragment = param.thisObject
            if fragment is not None:
                fv = None
                try:
                    fv = fragment.getFragmentView()
                except Exception:
                    fv = None
                if fv is not None and fv is not self._content_view:
                    self._bind_chat_refs(fragment, fv)
            self._build_tile_bitmap()
        except Exception:
            pass

    def _before_chat_destroy(self, param):
        try:
            fragment = param.thisObject
            if fragment is None:
                return
            fv = None
            try:
                fv = fragment.getFragmentView()
            except Exception:
                fv = None
            if fv is not None and fv is self._content_view:
                self._content_view = None
                self._list_view = None
                self._bg_view = None
                self._list_x = None
                self._list_y = None
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
            self._hook(BBD, "draw", after=self._after_bbd_draw, stat_key="tint_draw_bbd")
            self._hook(BBD, "updateColors", after=self._after_update_colors, stat_key="tint_update_bbd")

        if BBD_RN is not None:
            self._hook(BBD_RN, "draw", before=self._before_rn_draw, stat_key="tint_draw_rn")
            self._hook(BBD_RN, "updateColors", after=self._after_update_colors, stat_key="tint_update_rn")

        if BBD_SRC is not None:
            self._hook(BBD_SRC, "draw", before=self._before_src_draw, stat_key="tint_draw_src")

        if Factory is not None:
            self._hook(Factory, "setLiquidGlassEffectAllowed", after=self._after_factory_allow_glass, stat_key="factory_effect_allowed")

        Impl = _class_object("org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable$Impl")
        if Impl is not None:
            self._hook(Impl, "setLiquidGlassEnabled", before=self._before_force_true, stat_key="impl_glass_enabled")

        ImplRN = _class_object("org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable$ImplRenderNode")
        if ImplRN is not None:
            self._hook(ImplRN, "setLiquidGlassEnabled", before=self._before_force_true, stat_key="implrn_glass_enabled")

        # 2) LiteMode 强制允许 FLAG_LIQUID_GLASS
        LM = _class_object("org.telegram.messenger.LiteMode")
        if LM is not None:
            try:
                self._lite_flag = int(LM.getField("FLAG_LIQUID_GLASS").get(None))
            except Exception as exc:
                self._diag.append("[lite] 读 FLAG_LIQUID_GLASS 失败: %s" % exc)
                self._lite_flag = None
            self._hook(LM, "isEnabledSetting", before=self._before_lite_flag, stat_key="lite_flag")
            self._hook(LM, "isEnabled", before=self._before_lite_flag, stat_key="lite_enabled")

        # 3) 药丸变透：BlurredBackgroundProviderImpl.checkBlurEnabled -> False
        Provider = _class_object("org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl")
        if Provider is not None:
            self._hook(Provider, "checkBlurEnabled", after=self._after_provider_blur_off, stat_key="provider_blur_off")

        # 4) 顶栏真模糊 + 气泡壁纸预糊
        CA = _class_object("org.telegram.ui.ChatActivity")
        if CA is not None:
            self._hook(CA, "createView", after=self._after_chat_create_view, stat_key="chat_create_view")
            self._hook(CA, "onResume", after=self._after_chat_resume, stat_key="chat_resume")
            self._hook(CA, "onFragmentDestroy", before=self._before_chat_destroy, stat_key="chat_destroy")

        # 5) 输入框真模糊
        SC = _class_object("org.telegram.messenger.SharedConfig")
        if SC is not None:
            self._hook(SC, "chatBlurEnabled", after=self._after_chat_blur_enabled, stat_key="chat_blur")

        # 6) 顶栏清底
        if AB is not None:
            self._hook(AB, "setBackgroundColor", after=self._after_action_bar_color, stat_key="action_bar_color")

        # 7) 气泡变透 + 气泡磨砂贴块
        MD = _class_object("org.telegram.ui.ActionBar.MessageDrawable")
        if MD is not None:
            self._hook(MD, "setAlpha", after=self._after_message_drawable_alpha, stat_key="bubble_alpha")

        CMC = _class_object("org.telegram.ui.Cells.ChatMessageCell")
        if CMC is not None:
            self._hook(CMC, "drawBackgroundInternal", before=self._before_cell_draw_background, stat_key="tile_paste")

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
        bmp = self._tile_bitmap
        self._tile_bitmap = None
        if bmp is not None:
            try:
                if not bmp.isRecycled():
                    bmp.recycle()
            except Exception:
                pass
        self._content_view = None
        self._list_view = None
        self._bg_view = None

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
            tile = "已开（命中 %d 次）" % self._tile_hits
        elif self._tile_bitmap is not None:
            tile = "壁纸糊图已建好"
        elif self._hook_on("tile_paste"):
            tile = "已开"
        else:
            tile = "没开"

        return [
            Header(text="液态玻璃全局 v2.5.0（双端版）"),
            Switch(key="enabled", text="开启液态玻璃", default=True, on_change=self._on_enabled_change),
            Switch(key="bubble_frost", text="气泡磨砂贴块（关闭则仅清玻璃气泡）", default=True, on_change=self._on_frost_change),
            Header(text="已开的功能"),
            Text(text="内置液态玻璃引擎：" + builtin),
            Text(text="顶栏真模糊：" + topbar),
            Text(text="顶栏清底：" + bar_clear),
            Text(text="输入框真模糊：" + chat_blur),
            Text(text="药丸变透（提供方模糊关）：" + provider),
            Text(text="玻璃 tint 压到 100：" + tint),
            Text(text="气泡变透：" + bubble),
            Text(text="气泡磨砂贴块：" + tile),
            Header(text="诊断"),
            Text(text="点击复制诊断信息", on_click=self._copy_diag),
        ]

    def _on_enabled_change(self, val):
        try:
            self._enabled_cached = bool(val)
            self._diag.append("[UI] enabled=%s" % self._enabled_cached)
        except Exception:
            pass

    def _on_frost_change(self, val):
        try:
            self._bubble_frost_cached = bool(val)
            self._diag.append("[UI] bubble_frost=%s" % self._bubble_frost_cached)
        except Exception:
            pass

    def _copy_diag(self, *args):
        self._diag.append("[UI] 点击复制诊断")
        try:
            copy_to_clipboard("\n".join(self._diag))
        except Exception as e:
            self._diag.append("[!!] 复制失败: %s" % e)
