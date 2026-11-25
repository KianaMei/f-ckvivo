import math
import time
import traceback

from base_plugin import BasePlugin, MethodHook
from java import dynamic_proxy, jclass, jarray, jint
from hook_utils import find_class
try:
    from hook_utils import get_private_field
except Exception:
    get_private_field = None
from ui.settings import Header, Switch, Selector, Input, Text, Custom, SimpleSettingFactory
try:
    from ui.settings import Slider as NativeSlider
except Exception:
    NativeSlider = None

try:
    from client_utils import get_last_fragment
except Exception:
    get_last_fragment = None

try:
    from android_utils import log, run_on_ui_thread
except Exception:
    def log(*a):
        pass

__id__ = "side_panel"
__name__ = "Chats Menu（聊天快捷菜单）"
__description__ = "通过侧边栏 / 悬浮按钮快速访问和切换聊天（完整简体中文汉化 + ExteraLess 滑块/置顶修复版）。"
__author__ = "@d1zzy412 • @d1zplug"
__icon__ = "madokamoustache_by_fStikBot/0"
__version__ = "3.2.3"
__min_version__ = "12.10.6"
__app_version__ = ">=12.10.6"
__category__ = ["chats", "appearance"]
__permissions__ = ["hooks", "messages.read"]

TOP_FALLBACK = 88
TOP_OFFSET = 2
BOTTOM_MARGIN = 14
HANDLE_SHIFT = 40
DEFAULT_COLOR = "1B1B1B"
TAG = "chat_menu_container"
TAG_FLOAT = "chat_menu_float"


BASE_SIZE = {"width": 66, "avatar": 42, "item_height": 78, "text_size": 11.5, "badge": 16, "handle_width": 25, "handle_height": 53}

KIND_KEYS = {"user": "show_users", "bot": "show_bots", "group": "show_groups", "channel": "show_channels"}
KIND_LABELS = {"user": "личный", "bot": "бот", "group": "группа", "channel": "канал"}

TRANSLATIONS = {
    "чаты": "Chats", "режим чатов": "Chat mode", "выбранные": "Selected", "из категории": "By category",
    "выбранные чаты": "Selected chats", "юзернеймы чатов": "Chat usernames",
    "указывайте юзернеймы (с @ или без) и id. разделяйте их запятыми или пробелами, можно смешивать оба способа. например: @exteraGram, @exteraless, 123456.": "Enter usernames (with or without @) and IDs. Separate them with commas or spaces. You can mix both formats. Example: @exteraGram, @exteraless, 123456.",
    "фильтры": "Filters", "избранное": "Saved Messages", "закреплённые": "Pinned", "категории": "Categories",
    "лс": "Private chats", "боты": "Bots", "группы": "Groups", "каналы": "Channels", "личный": "Private", "бот": "Bot", "группа": "Group", "канал": "Channel", "исключения": "Exceptions",
    "закреплённые чаты": "Pinned chats", "количество чатов": "Number of chats", "меню": "Menu", "смещение (боковая)": "Offset (side panel)",
    "вид меню": "Menu type", "боковая панель": "Side panel", "плавающая кнопка": "Floating button",
    "размер": "Size", "смещение (боковая)": "Offset (side panel)", "закрывать по тапу вне меню": "Close when tapping outside", "скрывать при наборе (боковая)": "Hide while typing (side panel)",
    "скрывать при наборе (боковая)": "Hide while typing (side panel)", "анимация открытия": "Opening animation",
    "выезд": "Slide", "появление": "Fade", "пружина": "Spring", "без анимаций": "No animations",
    "внешний вид": "Appearance", "показывать названия чатов": "Show chat names", "цвет меню": "Menu color",
    "прозрачность": "Opacity", "закругление": "Corner radius", "дополнительно": "Advanced",
    "скрытые чаты": "Hidden chats", "нашли баг/есть идея?": "Found a bug / have an idea?",
    "язык": "Language", "русский": "Russian", "английский": "English",
    "цвет панели": "Panel color", "готово": "Done", "ок": "OK", "скрытых чатов нет": "There are no hidden chats",
    "всегда показывать": "Always show", "все типы чатов включены. отключите нужный тип, и его чаты появятся здесь.": "All chat types are enabled. Disable a chat type to see its chats here.",
    "открыть": "Open", "предпросмотр": "Preview", "открыть профиль": "Open profile",
    "отметить прочитанным": "Mark as read", "включить звук": "Unmute", "выключить звук": "Mute", "скрыть из панели": "Hide from panel", "пусто": "Empty",
}

MATCH, WRAP = -1, -2
VISIBLE, GONE = 0, 8
OVAL = 1
OVERSCROLL_NEVER = 2
GRAV_CENTER = 17
GRAV_CENTER_H = 1
GRAV_CENTER_V = 16
GRAV_TOP_CENTER_H = 49
GRAV_TOP_LEFT = 51
GRAV_BOTTOM_RIGHT = 85
GRAV_BOTTOM_LEFT = 83
GRAV_END = 8388613
GRAV_START_CENTER_V = 8388627

Long = jclass("java.lang.Long")
UserObject = jclass("org.telegram.messenger.UserObject")
DialogObject = jclass("org.telegram.messenger.DialogObject")
AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
Theme = jclass("org.telegram.ui.ActionBar.Theme")
GradientDrawable = jclass("android.graphics.drawable.GradientDrawable")
GradientOrientation = jclass("android.graphics.drawable.GradientDrawable$Orientation")
InsetDrawable = jclass("android.graphics.drawable.InsetDrawable")
RippleDrawable = jclass("android.graphics.drawable.RippleDrawable")
ColorStateList = jclass("android.content.res.ColorStateList")


UI_NAMES = ["классик", "MD3", "iOS", "Glass", "AMOLED"]

STYLE_ORDER = [0, 2, 1, 1, 0]
ANIM_ORDER = [0, 2, 3, 4, 5, 6, 7, 8]
SKIN_ORDER = ["", "", "", "glass", "amoled"]
SKIN_STATE = {"v": ""}
RADIUS_BONUS = [0, 8, 2]
ALPHA_MUL = [1.0, 1.0, 1.0]
PANEL_ELEVATION = [6, 10, 3]
FAB_ELEVATION = [4, 8, 6]

TRANSLATIONS.update({
    "стиль": "Style", "функции": "Features", "кнопка поиска": "Search button",
    "тап по чату пересылает выбранные сообщения": "Tapping a chat forwards selected messages",
    "упоминания и счётчик на язычке": "Mentions and unread count on the handle",
    "открывать свайпом от края (может мешать жесту «назад»)": "Open with an edge swipe (may conflict with the back gesture)",
    "автосворачивание": "Auto-collapse", "выкл": "Off", "приглушать": "Dim", "закрывать": "Close",
    "через сколько секунд": "After seconds", "масштаб": "Zoom", "падение": "Drop", "волна": "Wave", "поворот": "Flip",
    "поиск чатов": "Search chats", "переслать": "Forward", "открыть чат": "Open chat", "переслано": "Forwarded",
    "не удалось переслать": "Could not forward", "ничего не найдено": "Nothing found",
    "классик": "Classic", "MD3": "MD3", "затухание": "Fade", "закрывать и затухание": "Close and fade",
    "прозрачность при затухании": "Fade opacity",
    "кнопка поиска (удержание — вверх/вниз)": "Search button (hold to move up/down)",
    "вернуть кнопку поиска наверх": "Move search button to top",
    "поверх всего приложения": "Over the whole app",
    "акцент": "Accent", "контур": "Outline",
    "растяжение": "Stretch", "каскад": "Cascade", "землетрясение 🫨": "Earthquake 🫨", "вертолёт 🚁": "Helicopter 🚁",
    "жесты": "Gestures", "свайп влево": "Swipe left", "свайп вправо": "Swipe right",
    "прочитать": "Mark as read", "заглушить": "Mute / unmute", "скрыть": "Hide from panel",
})

TRANSLATIONS_ZH = {
    "чаты": "聊天",
    "режим чатов": "聊天来源模式",
    "выбранные": "手动指定",
    "из категории": "按分类（最近对话）",
    "выбранные чаты": "已指定的聊天",
    "юзернеймы чатов": "聊天用户名 / ID",
    "указывайте юзернеймы (с @ или без) и id. разделяйте их запятыми или пробелами, можно смешивать оба способа. например: @exteraGram, @exteraless, 123456.": "输入用户名（带或不带 @ 均可）或数字 ID，用逗号或空格分隔，可混用。例如：@exteraGram, @exteraless, 123456。",
    "фильтры": "筛选",
    "избранное": "收藏夹",
    "закреплённые": "显示置顶聊天",
    "закреплённые чаты": "优先显示置顶聊天",
    "категории": "分类",
    "лс": "私聊",
    "боты": "机器人",
    "группы": "群组",
    "каналы": "频道",
    "личный": "私聊",
    "бот": "机器人",
    "группа": "群组",
    "канал": "频道",
    "исключения": "白名单例外（始终显示）",
    "количество чатов": "显示聊天数量",
    "меню": "菜单布局",
    "вид меню": "菜单形态",
    "боковая панель": "侧边栏",
    "плавающая кнопка": "悬浮按钮",
    "размер": "面板宽度",
    "смещение (боковая)": "垂直偏移（侧边栏）",
    "закрывать по тапу вне меню": "点击菜单外空白处关闭",
    "скрывать при наборе (боковая)": "打字弹键盘时隐藏（侧边栏）",
    "анимация открытия": "展开动画",
    "выезд": "滑出",
    "появление": "淡入",
    "пружина": "弹簧",
    "без анимаций": "无动画",
    "масштаб": "缩放",
    "падение": "弹落",
    "волна": "波浪",
    "поворот": "翻转",
    "растяжение": "拉伸",
    "каскад": "级联",
    "землетрясение 🫨": "震动 🫨",
    "вертолёт 🚁": "旋转 🚁",
    "внешний вид": "外观",
    "стиль": "视觉风格",
    "классик": "经典",
    "MD3": "MD3",
    "iOS": "iOS",
    "Glass": "毛玻璃",
    "AMOLED": "AMOLED 纯黑",
    "показывать названия чатов": "显示聊天名称",
    "цвет меню": "菜单颜色",
    "прозрачность": "不透明度",
    "закругление": "圆角",
    "дополнительно": "更多",
    "скрытые чаты": "已隐藏的聊天",
    "нашли баг/есть идея?": "反馈 Bug / 联系作者",
    "язык": "界面语言",
    "简体中文": "简体中文",
    "русский": "Русский（俄语）",
    "английский": "English（英语）",
    "цвет панели": "面板颜色",
    "готово": "完成",
    "ок": "确定",
    "скрытых чатов нет": "当前没有已隐藏的聊天",
    "всегда показывать": "白名单（始终显示）",
    "все типы чатов включены. отключите нужный тип, и его чаты появятся здесь.": "当前已启用全部聊天类型。关闭某个分类后，该分类下的聊天会出现在这里供单独加入白名单。",
    "открыть": "打开聊天",
    "предпросмотр": "预览聊天",
    "открыть профиль": "打开资料页",
    "отметить прочитанным": "标记为已读",
    "включить звук": "取消静音",
    "выключить звук": "静音",
    "скрыть из панели": "从面板隐藏",
    "пусто": "暂无聊天",
    "функции": "增强功能",
    "кнопка поиска": "搜索按钮",
    "кнопка поиска (удержание — вверх/вниз)": "搜索按钮（长按可拖至顶部/底部）",
    "вернуть кнопку поиска наверх": "将搜索按钮重置回顶部",
    "тап по чату пересылает выбранные сообщения": "多选消息时点击聊天直接转发",
    "упоминания и счётчик на язычке": "把手上显示未读数与提及/回应标记",
    "открывать свайпом от края (может мешать жесту «назад»)": "从屏幕右缘侧滑展开（可能与返回手势冲突）",
    "автосворачивание": "静置自动处理",
    "выкл": "关闭",
    "приглушать": "变暗",
    "затухание": "淡出半透明",
    "закрывать": "自动收起",
    "закрывать и затухание": "收起并淡出",
    "через сколько секунд": "静置触发时间",
    "прозрачность при затухании": "淡出后不透明度",
    "поиск чатов": "搜索聊天...",
    "переслать": "转发",
    "открыть чат": "打开聊天",
    "переслано": "已转发",
    "не удалось переслать": "转发失败",
    "ничего не найдено": "未找到匹配的聊天",
    "поверх всего приложения": "全局悬浮（覆盖所有页面）",
    "акцент": "强调色",
    "контур": "描边",
    "жесты": "滑动手势",
    "свайп влево": "列表项左滑",
    "свайп вправо": "列表项右滑",
    "прочитать": "标为已读",
    "заглушить": "静音 / 取消静音",
    "скрыть": "从面板隐藏",
    " с": " 秒",
    "закрепить в панели": "置顶到侧栏",
    "открепить из панели": "取消侧栏置顶",
    "закреплено в панели": "已置顶到侧栏",
    "откреплено из панели": "已取消侧栏置顶",
    "закреплённые в панели": "侧栏已置顶的聊天",
    "закреплённых чатов нет": "当前账号暂无侧栏置顶聊天，长按聊天头像即可置顶。",
    "долгое нажатие сразу закрепляет": "长按头像直接置顶（跳过菜单）",
    "закрепить": "置顶 / 取消置顶",
}
TRANSLATIONS["简体中文"] = "简体中文"
TRANSLATIONS[" с"] = " s"
TRANSLATIONS.update({
    "закрепить в панели": "Pin to panel",
    "открепить из панели": "Unpin from panel",
    "закреплено в панели": "Pinned to panel",
    "откреплено из панели": "Unpinned from panel",
    "закреплённые в панели": "Pinned in panel",
    "закреплённых чатов нет": "No pinned chats in panel yet. Long-press a chat avatar to pin it.",
    "долгое нажатие сразу закрепляет": "Long-press avatar to pin directly",
    "закрепить": "Pin / unpin",
})



def argb(c):
    c &= 0xFFFFFFFF
    return c - (1 << 32) if c >= (1 << 31) else c


def safe(fn, *args, default=None):
    try:
        return fn(*args)
    except Exception:
        log(traceback.format_exc())
        return default


def theme_color(name, fallback):
    try:
        return Theme.getColor(getattr(Theme, name)) & 0xFFFFFFFF
    except Exception:
        return fallback


def mix_rgb(a, b, t):
    ar, ag, ab = (a >> 16) & 255, (a >> 8) & 255, a & 255
    br, bg, bb = (b >> 16) & 255, (b >> 8) & 255, b & 255
    r = int(ar + (br - ar) * t)
    g = int(ag + (bg - ag) * t)
    bl = int(ab + (bb - ab) * t)
    return (r << 16) | (g << 8) | bl


def is_dark_bg(text):
    return (text & 0xFFFFFF) > 0x808080


def md3_palette(rgb, text):

    dark = is_dark_bg(text)
    seed = theme_accent() & 0xFFFFFF
    base = rgb & 0xFFFFFF
    foreground = text & 0xFFFFFF


    primary = mix_rgb(seed, 0xFFFFFF, 0.22) if dark else mix_rgb(seed, 0x000000, 0.08)
    on_primary = on_color(primary) & 0xFFFFFF
    surface = mix_rgb(base, seed, 0.035)
    surface_low = mix_rgb(base, seed, 0.055)
    surface_container = mix_rgb(base, seed, 0.085)
    surface_high = mix_rgb(base, seed, 0.125)
    surface_variant = mix_rgb(mix_rgb(base, foreground, 0.12), seed, 0.08)
    secondary_container = mix_rgb(base, seed, 0.28 if dark else 0.19)
    primary_container = mix_rgb(base, seed, 0.42 if dark else 0.25)
    on_container = on_color(secondary_container) & 0xFFFFFF
    on_primary_container = on_color(primary_container) & 0xFFFFFF
    return {
        "surface": surface,
        "low": surface_low,
        "cont": surface_container,
        "high": surface_high,
        "variant": surface_variant,
        "pc": primary_container,
        "onpc": on_primary_container,
        "primary": primary,
        "on_primary": on_primary,
        "secondary": secondary_container,
        "on_secondary": on_container,
        "osv": foreground,
        "ol": mix_rgb(surface_variant, foreground, 0.22),
    }


def ripple(color, radius_px):

    try:
        mask = GradientDrawable()
        mask.setColor(argb(0xFF000000))
        mask.setCornerRadius(float(radius_px))
        return RippleDrawable(ColorStateList.valueOf(argb(color)), None, mask)
    except Exception:
        return None


def ios_accent(text):

    return 0xFF3E88F7 if is_dark_bg(text) else 0xFF007AFF


def theme_accent():

    return theme_color("key_chats_actionBackground", 0xFF3390EC) & 0xFFFFFF


def on_color(rgb):

    r, g, b = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    return 0xFF000000 if (0.299 * r + 0.587 * g + 0.114 * b) > 160 else 0xFFFFFFFF


def capture_blur(root, hide, scale=0.25, radius=9):

    try:
        Bitmap = jclass("android.graphics.Bitmap")
        Canvas = jclass("android.graphics.Canvas")
        rw, rh = int(root.getWidth()), int(root.getHeight())
        if rw <= 0 or rh <= 0:
            return None
        bmp = Bitmap.createBitmap(max(1, int(rw * scale)), max(1, int(rh * scale)), Bitmap.Config.ARGB_8888)
        canvas = Canvas(bmp)
        canvas.scale(float(scale), float(scale))
        saved = []
        for v in hide:
            if v is not None:
                saved.append((v, int(v.getVisibility())))
                v.setVisibility(4)
        try:
            root.draw(canvas)
        finally:
            for v, vis in saved:
                v.setVisibility(vis)
        try:
            jclass("org.telegram.messenger.Utilities").stackBlurBitmap(bmp, int(radius))
        except Exception:
            log(traceback.format_exc())
        return bmp
    except Exception:
        log(traceback.format_exc())
        return None


def paint(bg, ui, alpha, rgb, text):
    dark = is_dark_bg(text)
    a = max(0, min(255, int(alpha * ALPHA_MUL[ui])))
    skin = SKIN_STATE.get("v", "")
    if skin == "glass":

        ga = min(a, 0xE6)
        hi = mix_rgb(rgb, 0xFFFFFF, 0.14) if dark else mix_rgb(rgb, 0xFFFFFF, 0.5)
        bg.setOrientation(GradientOrientation.TL_BR)
        bg.setColors(jarray(jint)([argb((ga << 24) | hi), argb(((ga - 18 if ga > 18 else ga) << 24) | rgb)]))
        bg.setStroke(AndroidUtilities.dp(1), argb(0x66FFFFFF if dark else 0x55FFFFFF))
        return
    if skin == "amoled":
        base = 0x000000 if dark else 0xFFFFFF
        bg.setColor(argb((a << 24) | base))
        bg.setStroke(AndroidUtilities.dp(1), argb(0x26FFFFFF if dark else 0x1F000000))
        return
    if ui == 1:

        top = mix_rgb(rgb, 0xFFFFFF, 0.07) if dark else rgb
        bg.setOrientation(GradientOrientation.TOP_BOTTOM)
        bg.setColors(jarray(jint)([argb((a << 24) | top), argb((a << 24) | rgb)]))
        bg.setStroke(AndroidUtilities.dp(1), argb(0x2EFFFFFF if dark else 0x1F000000))
    elif ui == 2:

        bg.setColor(argb((a << 24) | md3_palette(rgb, text)["cont"]))
        bg.setStroke(0, argb(0))
    else:
        bg.setColor(argb((a << 24) | rgb))
        bg.setStroke(0, argb(0))


def active_bg(ui, rgb, text):
    g = GradientDrawable()
    d = AndroidUtilities.dp
    if ui == 2:
        pal = md3_palette(rgb, text)
        g.setCornerRadius(float(d(18)))
        g.setColor(argb(0xFF000000 | pal["secondary"]))
        return InsetDrawable(g, d(5), d(3), d(5), d(3))
    if ui == 1:
        col = ios_accent(text) & 0xFFFFFF
    else:
        col = theme_accent()
    g.setCornerRadius(float(d(12)))
    g.setColor(argb((0x30 << 24) | col))
    return InsetDrawable(g, d(5), d(4), d(5), d(4))


class ManyBounce(dynamic_proxy(jclass("android.animation.TimeInterpolator"))):

    def getInterpolation(self, t):
        t = float(t)
        if t >= 1.0:
            return 1.0
        return 1.0 - ((1.0 - t) ** 3) * abs(math.cos(7.0 * math.pi * t))


class ClickListener(dynamic_proxy(jclass("android.view.View$OnClickListener"))):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onClick(self, view):
        safe(self.fn)


class LongClickListener(dynamic_proxy(jclass("android.view.View$OnLongClickListener"))):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onLongClick(self, view):
        safe(self.fn)
        return True


class TouchListener(dynamic_proxy(jclass("android.view.View$OnTouchListener"))):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onTouch(self, view, event):
        return bool(safe(self.fn, view, event, default=False))


class RunnableProxy(dynamic_proxy(jclass("java.lang.Runnable"))):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def run(self):
        safe(self.fn)


class DialogClickListener(dynamic_proxy(jclass("android.content.DialogInterface$OnClickListener"))):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onClick(self, dialog, which):
        safe(self.fn, which)


class LayoutChangeListener(dynamic_proxy(jclass("android.view.View$OnLayoutChangeListener"))):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onLayoutChange(self, view, left, top, right, bottom, old_left, old_top, old_right, old_bottom):
        safe(self.fn)


class CheckedListener(dynamic_proxy(jclass("android.widget.CompoundButton$OnCheckedChangeListener"))):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onCheckedChanged(self, button, checked):
        safe(self.fn, bool(checked))


class telegramSeekBarDelegate(dynamic_proxy(jclass("org.telegram.ui.Components.SeekBarView$SeekBarViewDelegate"))):
    def __init__(self, on_drag, on_pressed=None):
        super().__init__()
        self.on_drag = on_drag
        self.on_pressed = on_pressed

    def onSeekBarDrag(self, stop, progress):
        safe(self.on_drag, float(progress), bool(stop))

    def onSeekBarPressed(self, pressed):
        if self.on_pressed is not None:
            safe(self.on_pressed, bool(pressed))

    def getContentDescription(self):
        return ""

    def getStepsCount(self):
        return 0


def chat_kind(did, obj):
    if did > 0:
        return "bot" if getattr(obj, "bot", False) else "user"
    return "channel" if getattr(obj, "broadcast", False) else "group"


def get_username(did, obj):
    if obj is None:
        return None
    try:
        if did > 0:
            try:
                un = UserObject.getPublicUsername(obj)
                if un:
                    return un
            except Exception:
                pass
            return getattr(obj, "username", None)
        return getattr(obj, "username", None)
    except Exception:
        return getattr(obj, "username", None)


def chat_info(mc, did):
    if did > 0:
        obj = mc.getUser(Long(did))
        return obj, UserObject.getUserName(obj) if obj else None
    obj = mc.getChat(Long(-did))
    return obj, obj.title if obj else None


def load_dialogs(mc):
    out = []
    dialogs = mc.getAllDialogs()
    for i in range(dialogs.size()):
        dlg = dialogs.get(i)
        did = dlg.id
        if did == 0 or DialogObject.isEncryptedDialog(did) or getattr(dlg, "folder_id", 0) != 0:
            continue
        obj, title = chat_info(mc, did)
        if obj is not None and title:
            out.append((dlg, did, obj, title))
    return out


def char_sequences(items):
    return jarray(jclass("java.lang.CharSequence"))(items)


def show_sheet(ctx, title, items, on_pick):
    listener = DialogClickListener(on_pick)
    try:
        sheet = jclass("org.telegram.ui.ActionBar.BottomSheet$Builder")(ctx)
        sheet.setTitle(title)
        sheet.setItems(char_sequences(items), listener)
        return sheet.show()
    except Exception:
        builder = jclass("android.app.AlertDialog$Builder")(ctx)
        builder.setTitle(title)
        builder.setItems(char_sequences(items), listener)
        return builder.show()


def show_dialog(ctx, title, view=None, message=None, button="готово"):
    try:
        builder = jclass("org.telegram.ui.ActionBar.AlertDialog$Builder")(ctx)
        builder.setTitle(title)
        if message:
            builder.setMessage(message)
        if view is not None:
            builder.setView(view)
        builder.setPositiveButton(button, None)
        return builder.show()
    except Exception:
        builder = jclass("android.app.AlertDialog$Builder")(ctx)
        builder.setTitle(title)
        if message:
            builder.setMessage(message)
        if view is not None:
            builder.setView(view)
        builder.setPositiveButton(button, DialogClickListener(lambda which: None))
        return builder.show()


def checklist_view(ctx, rows, on_toggle):
    dp = AndroidUtilities.dp
    LinearLayout = jclass("android.widget.LinearLayout")
    scroll = jclass("android.widget.ScrollView")(ctx)
    col = LinearLayout(ctx)
    col.setOrientation(LinearLayout.VERTICAL)
    col.setPadding(dp(12), 0, dp(12), 0)
    text_color = argb(theme_color("key_dialogTextBlack", 0xFF000000))
    tint = jclass("android.content.res.ColorStateList").valueOf(argb(theme_color("key_dialogTextBlue", 0xFF3390EC)))
    CheckBox = jclass("android.widget.CheckBox")
    for i, (label, checked) in enumerate(rows):
        box = CheckBox(ctx)
        box.setText(label)
        box.setTextSize(15.0)
        box.setTextColor(text_color)
        box.setPadding(dp(6), dp(8), dp(6), dp(8))
        box.setChecked(checked)
        safe(box.setButtonTintList, tint)
        box.setOnCheckedChangeListener(CheckedListener(lambda on, i=i: on_toggle(i, on)))
        col.addView(box)
    scroll.addView(col)
    return scroll


class TopFragment:

    def __getattr__(self, name):
        frag = get_last_fragment() if get_last_fragment is not None else None
        if frag is None:
            raise AttributeError(name)
        return getattr(frag, name)


class CreateViewHook(MethodHook):
    def __init__(self, plugin):
        self.plugin = plugin

    def after_hooked_method(self, param):
        safe(self.plugin.sp_attach, param.thisObject, param.getResult())


class ResumeHook(MethodHook):
    def __init__(self, plugin):
        self.plugin = plugin

    def after_hooked_method(self, param):
        safe(self.plugin.sp_attach_global, param.thisObject)


class DestroyHook(MethodHook):
    def __init__(self, plugin):
        self.plugin = plugin

    def before_hooked_method(self, param):
        safe(self.plugin.sp_detach, param.thisObject)


class ChatResumeHook(MethodHook):
    def __init__(self, plugin):
        self.plugin = plugin

    def after_hooked_method(self, param):
        safe(self.plugin.sp_record_fragment, param.thisObject)


class SidePanel(BasePlugin):
    sp_restyle = None
    sp_reload = None
    sp_dock_search = None

    def __init__(self):
        super().__init__()
        self._sp_cleanups = []
        self._sp_frag_cleanups = {}
        self._sp_resolved = {}
        self._sp_pending = set()
        self._sp_failed = set()
        self._sp_activity = None
        self._sp_global_cleanups = []
        self._sp_recent_mem = {}
        self._sp_recent_host_cls = None

    def on_plugin_load(self):
        context_class = jclass("android.content.Context")


        chat = find_class("org.telegram.ui.ChatActivity")
        chat_method = chat.getClass().getDeclaredMethod("createView", context_class)
        self.hook_method(chat_method, CreateViewHook(self))
        try:
            chat_resume_method = chat.getClass().getDeclaredMethod("onResume")
            self.hook_method(chat_resume_method, ChatResumeHook(self))
        except Exception:
            pass
        try:
            destroy_method = chat.getClass().getDeclaredMethod("onFragmentDestroy")
            self.hook_method(destroy_method, DestroyHook(self))
        except Exception:
            pass


        try:
            launch = find_class("org.telegram.ui.LaunchActivity")
            resume_method = launch.getClass().getDeclaredMethod("onResume")
            self.hook_method(resume_method, ResumeHook(self))
        except Exception:
            log(traceback.format_exc())
        try:
            frag = get_last_fragment() if get_last_fragment is not None else None
            if frag is not None:
                safe(self.sp_attach_global, frag.getParentActivity())
        except Exception:
            pass


    def sp_detach(self, fragment):
        if fragment is None:
            return
        fid = int(jclass("java.lang.System").identityHashCode(fragment))
        cleanups = self._sp_frag_cleanups.pop(fid, None)
        if cleanups:
            for c in cleanups:
                safe(c)
                if c in self._sp_cleanups:
                    try:
                        self._sp_cleanups.remove(c)
                    except Exception:
                        pass

    def on_plugin_unload(self):
        self._sp_frag_cleanups.clear()
        cleanups = self._sp_cleanups
        self._sp_cleanups = []
        for cleanup in cleanups:
            safe(cleanup)
        self.sp_restyle = None
        self.sp_reload = None
        self.sp_dock_search = None

    def _tr(self, value):
        try:
            lang = int(self.get_setting("language", 0))
            if lang == 0:
                return TRANSLATIONS_ZH.get(value, TRANSLATIONS.get(value, value))
            if lang == 1:
                return TRANSLATIONS.get(value, value)
        except Exception:
            pass
        return value

    def _on_language_change(self, value):
        self.set_setting("language", int(value), reload_settings=True)
        if self.sp_restyle is not None:
            safe(self.sp_restyle)

    def _on_chat_mode_change(self, value):
        self.set_setting("chat_mode", int(value), reload_settings=True)

    def _on_panel_layout_change(self, value):
        self.set_setting("panel_layout", int(value), reload_settings=True)
        safe(self.sp_rebuild_global)
        if self.sp_restyle is not None:
            safe(self.sp_restyle)

    def _on_float_global_change(self, value):
        self.set_setting("float_global", bool(value))
        safe(self.sp_rebuild_global)

    def sp_global_enabled(self):
        try:
            return (get_last_fragment is not None
                    and int(self.get_setting("panel_layout", 0)) == 1
                    and bool(self.get_setting("float_global", True)))
        except Exception:
            return False

    def sp_attach_global(self, activity):
        if activity is None or not self.sp_global_enabled():
            return False
        content = activity.getWindow().getDecorView().findViewById(16908290)
        if content is None:
            return False
        self._sp_activity = activity
        if content.findViewWithTag(TAG_FLOAT) is not None:
            return True
        added = []
        before = len(self._sp_cleanups)
        try:
            self.sp_build(TopFragment(), content, added)
        except Exception:
            log(traceback.format_exc())
            for v in added:
                safe(content.removeView, v)
            return False
        self._sp_global_cleanups = list(self._sp_cleanups[before:])
        return True

    def sp_rebuild_global(self):
        cleanups = self._sp_global_cleanups
        self._sp_global_cleanups = []
        if cleanups:
            for c in cleanups:
                safe(c)
            self.sp_restyle = None
            self.sp_reload = None
        if self._sp_activity is not None:
            safe(self.sp_attach_global, self._sp_activity)

    def _on_usernames_change(self, value):
        names = self.sp_parse_names(value)
        self.sp_save_usernames("selected_chats", names)
        self._sp_failed.clear()
        if self.sp_reload is not None:
            safe(self.sp_reload)

    def _make_slider_setting(self, key, title, minimum, maximum, default, suffix):
        sfx = self._tr(suffix) if suffix else ""
        label_text = self._tr(title)
        if sfx.strip():
            label_text = "%s (%s)" % (label_text, sfx.strip())
        if NativeSlider is not None:
            def _on_slide(val):
                try:
                    current = max(minimum, min(maximum, int(round(float(val)))))
                    self.set_setting(key, current)
                except Exception:
                    pass
                if self.sp_restyle is not None:
                    safe(self.sp_restyle)
            return NativeSlider(
                key=key,
                text=label_text,
                default=default,
                min=minimum,
                max=maximum,
                step=1,
                on_change=_on_slide,
            )

        views_map = {}

        def create_view(context, list_view, current_account, class_guid, resources_provider):
            LinearLayout = jclass("android.widget.LinearLayout")
            TextView = jclass("android.widget.TextView")
            SeekBarView = jclass("org.telegram.ui.Components.SeekBarView")
            dp = AndroidUtilities.dp
            root = LinearLayout(context)
            root.setOrientation(LinearLayout.VERTICAL)
            root.setPadding(dp(20), dp(10), dp(20), dp(10))
            label = TextView(context)
            label.setText(self._tr(title))
            label.setTextSize(16.0)
            try:
                label.setTextColor(argb(theme_color("key_windowBackgroundWhiteBlackText", 0xFFFFFFFF)))
            except Exception:
                pass
            root.addView(label, jclass("android.widget.LinearLayout$LayoutParams")(-1, dp(30)))
            value = TextView(context)
            value.setTextSize(14.0)
            value.setGravity(GRAV_CENTER)
            try:
                value.setTextColor(argb(theme_color("key_windowBackgroundWhiteValueText", 0xFFAAAAAA)))
            except Exception:
                pass
            root.addView(value, jclass("android.widget.LinearLayout$LayoutParams")(-1, dp(24)))
            seek = SeekBarView(context)
            safe(seek.setReportChanges, True)
            vid = int(jclass("java.lang.System").identityHashCode(root))
            views_map[vid] = (seek, value)

            def drag(progress, stop):
                current = minimum + int(round((maximum - minimum) * max(0.0, min(1.0, float(progress)))))
                value.setText(str(current) + sfx)
                self.set_setting(key, current)
                if self.sp_restyle is not None:
                    safe(self.sp_restyle)
            safe(seek.setDelegate, telegramSeekBarDelegate(drag))
            root.addView(seek, jclass("android.widget.LinearLayout$LayoutParams")(-1, dp(42)))
            return root

        def bind_view(view, item, divider, adapter, list_view):
            value = max(minimum, min(maximum, int(float(self.get_setting(key, default)))))
            vid = int(jclass("java.lang.System").identityHashCode(view))
            pair = views_map.get(vid)
            if pair is not None:
                seek, text = pair
                text.setText(str(value) + sfx)
                safe(seek.setProgress, (value - minimum) / float(maximum - minimum))

        return Custom(factory=SimpleSettingFactory(create_view, bind_view, is_clickable=False).instance.java)

    def sp_anim(self):
        try:
            v = self.get_setting("panel_anim", None)
            if v is None:
                old = int(self.get_setting("panel_animation", 0))
                v = ANIM_ORDER.index(old) if old in ANIM_ORDER else 0
            return ANIM_ORDER[max(0, min(len(ANIM_ORDER) - 1, int(v)))]
        except Exception:
            return 0

    def sp_ui_style(self):

        try:
            v = self.get_setting("panel_style", None)
            if v is None:
                return max(0, min(2, int(self.get_setting("ui_style", 0))))
            idx = int(v)
            if idx < 0 or idx >= len(STYLE_ORDER):
                idx = 0
            return STYLE_ORDER[idx]
        except Exception:
            return 0

    def sp_skin(self):
        try:
            v = self.get_setting("panel_style", None)
            if v is None:
                return ""
            idx = int(v)
            if idx < 0 or idx >= len(SKIN_ORDER):
                idx = 0
            return SKIN_ORDER[idx]
        except Exception:
            return ""

    def _on_style_change(self, new_index):
        idx = max(0, min(len(UI_NAMES) - 1, int(new_index)))
        self.set_setting("panel_style", idx, reload_settings=True)
        if self.sp_restyle is not None:
            safe(self.sp_restyle)

    def _on_restyle_value(self, value):
        if self.sp_restyle is not None:
            safe(self.sp_restyle)

    def _on_idle_change(self, value):
        self.set_setting("idle_mode", int(value), reload_settings=True)
        if self.sp_restyle is not None:
            safe(self.sp_restyle)

    def create_settings(self):
        try:

            current = self.get_setting("panel_style", None)
            if current is None:
                old = max(0, min(2, int(self.get_setting("ui_style", 0))))
                self.set_setting("panel_style", STYLE_ORDER.index(old))
            elif int(current) < 0 or int(current) >= len(UI_NAMES):

                self.set_setting("panel_style", 0)
        except Exception:
            pass
        try:
            if self.get_setting("panel_anim", None) is None:
                self.set_setting("panel_anim", ANIM_ORDER.index(self.sp_anim()))
        except Exception:
            pass
        mode = int(self.get_setting("chat_mode", 0))
        base = [
            Header(text=self._tr("дополнительно")),
            Selector(
                key="language",
                text=self._tr("язык"),
                default=0,
                items=["简体中文", "English", "Русский"],
                on_change=self._on_language_change,
            ),
            Header(text=self._tr("чаты")),
            Selector(
                key="chat_mode",
                text=self._tr("режим чатов"),
                default=0,
                items=[self._tr("выбранные"), self._tr("из категории")],
                on_change=self._on_chat_mode_change,
            ),
        ]

        if mode == 0:
            chat_section = [
                Header(text=self._tr("выбранные чаты")),
                Input(
                    key="selected_usernames_text",
                    text=self._tr("юзернеймы чатов"),
                    default=",".join(self.sp_selected_names()),
                    subtext=self._tr("указывайте юзернеймы (с @ или без) и id. разделяйте их запятыми или пробелами, можно смешивать оба способа. например: @exteraGram, @exteraless, 123456."),
                    on_change=self._on_usernames_change,
                ),
                Header(text=self._tr("фильтры")),
                Switch(key="show_saved", text=self._tr("избранное"), default=True),
                Switch(key="show_pinned", text=self._tr("закреплённые"), default=True),
            ]
        else:
            chat_section = [
                Header(text=self._tr("категории")),
                Switch(key="show_users", text=self._tr("лс"), default=True),
                Switch(key="show_bots", text=self._tr("боты"), default=True),
                Switch(key="show_groups", text=self._tr("группы"), default=True),
                Switch(key="show_channels", text=self._tr("каналы"), default=True),
                Text(text=self._tr("исключения"), on_click=lambda view: self.sp_open_exceptions(None)),
                Header(text=self._tr("фильтры")),
                Switch(key="show_saved", text=self._tr("избранное"), default=True),
                Switch(key="show_pinned", text=self._tr("закреплённые чаты"), default=True),
                self._make_slider_setting("count_custom", "количество чатов", 5, 500, 50, ""),
            ]

        layout_mode = int(self.get_setting("panel_layout", 0))
        floating = layout_mode == 1

        menu_section = [
            Header(text=self._tr("меню")),
            Selector(
                key="panel_layout",
                text=self._tr("вид меню"),
                default=0,
                items=[self._tr("боковая панель"), self._tr("плавающая кнопка")],
                on_change=self._on_panel_layout_change,
            ),
        ]
        if not floating:
            menu_section += [
                self._make_slider_setting("panel_width", "размер", 48, 110, 66, " dp"),
                self._make_slider_setting("vertical_offset", "смещение (боковая)", -120, 240, 0, " dp"),
                Switch(key="close_on_tap", text=self._tr("закрывать по тапу вне меню"), default=True),
                Switch(key="keyboard_hide", text=self._tr("скрывать при наборе (боковая)"), default=False),
            ]
        menu_section.append(
            Selector(
                key="panel_anim",
                text=self._tr("анимация открытия"),
                default=0,
                items=[self._tr("выезд"), self._tr("пружина"), self._tr("без анимаций"),
                       self._tr("масштаб"), self._tr("падение"), self._tr("волна"), self._tr("поворот"),
                       self._tr("растяжение")],
                on_change=self._on_restyle_value,
            )
        )
        if floating:
            menu_section.append(
                Switch(key="float_global", text=self._tr("поверх всего приложения"), default=True,
                       on_change=self._on_float_global_change)
            )
        if not floating:
            menu_section += [
                Header(text=self._tr("функции")),
                Switch(key="search_fab", text=self._tr("кнопка поиска (удержание — вверх/вниз)"), default=True, on_change=self._on_restyle_value),
                Switch(key="forward_here", text=self._tr("тап по чату пересылает выбранные сообщения"), default=True),
                Switch(key="show_indicators", text=self._tr("упоминания и счётчик на язычке"), default=True, on_change=self._on_restyle_value),
                Switch(key="edge_swipe", text=self._tr("открывать свайпом от края (может мешать жесту «назад»)"), default=False, on_change=self._on_restyle_value),
                Selector(
                    key="idle_mode",
                    text=self._tr("автосворачивание"),
                    default=1,
                    items=[self._tr("выкл"), self._tr("затухание"), self._tr("закрывать"), self._tr("закрывать и затухание")],
                    on_change=self._on_idle_change,
                ),
            ]
            idle_cfg = int(self.get_setting("idle_mode", 1))
            if idle_cfg != 0:
                menu_section.append(self._make_slider_setting("idle_seconds", "через сколько секунд", 3, 60, 10, " с"))
            if idle_cfg in (1, 3):
                menu_section.append(self._make_slider_setting("idle_alpha", "прозрачность при затухании", 5, 70, 20, " %"))

        gesture_section = [
            Header(text=self._tr("жесты")),
            Switch(key="long_press_pin", text=self._tr("долгое нажатие сразу закрепляет"), default=False),
            Selector(key="swipe_left", text=self._tr("свайп влево"), default=1,
                     items=[self._tr("выкл"), self._tr("прочитать"), self._tr("заглушить"), self._tr("скрыть"), self._tr("закрепить")],
                     on_change=self._on_restyle_value),
            Selector(key="swipe_right", text=self._tr("свайп вправо"), default=2,
                     items=[self._tr("выкл"), self._tr("прочитать"), self._tr("заглушить"), self._tr("скрыть"), self._tr("закрепить")],
                     on_change=self._on_restyle_value),
        ]
        look_section = [
            Header(text=self._tr("внешний вид")),
            Selector(
                key="panel_style",
                text=self._tr("стиль"),
                default=0,
                items=[self._tr(n) for n in UI_NAMES],
                on_change=self._on_style_change,
            ),
            Switch(key="show_titles", text=self._tr("показывать названия чатов"), default=True, on_change=self._on_restyle_value),
        ]
        return base + chat_section + menu_section + gesture_section + look_section + [
            Header(text=self._tr("дополнительно")),
            Text(text=self._tr("закреплённые в панели"), on_click=lambda view: self.sp_open_pinned(None)),
            Text(text=self._tr("скрытые чаты"), on_click=lambda view: self.sp_open_hidden(None)),
            Text(
                text=self._tr("нашли баг/есть идея?"),
                on_click=lambda view: self.sp_open_author_profile(),
            ),
        ]

    def sp_open_author_profile(self):
        try:
            fragment = get_last_fragment() if get_last_fragment is not None else None
            if fragment is None:
                return
            acc = int(jclass("org.telegram.messenger.UserConfig").selectedAccount)
            mc = jclass("org.telegram.messenger.MessagesController").getInstance(acc)
            cached = safe(mc.getUserOrChat, "d1zzy412")
            if cached is not None and hasattr(cached, "id"):
                args = jclass("android.os.Bundle")()
                args.putLong("user_id", int(cached.id))
                fragment.presentFragment(jclass("org.telegram.ui.ProfileActivity")(args))
                return

            def finish(response, error):
                def show():
                    try:
                        if response is None or error is not None:
                            return
                        users = getattr(response, "users", None)
                        if users is None or int(users.size()) <= 0:
                            return
                        mc.putUsers(users, False)
                        user = users.get(0)
                        args = jclass("android.os.Bundle")()
                        args.putLong("user_id", int(user.id))
                        fragment.presentFragment(jclass("org.telegram.ui.ProfileActivity")(args))
                    except Exception:
                        log(traceback.format_exc())
                try:
                    AndroidUtilities.runOnUIThread(RunnableProxy(show))
                except Exception:
                    show()

            class _ProfileResolveDelegate(dynamic_proxy(jclass("org.telegram.tgnet.RequestDelegate"))):
                def run(self, response, error):
                    finish(response, error)

            req = jclass("org.telegram.tgnet.TLRPC$TL_contacts_resolveUsername")()
            req.username = "d1zzy412"
            jclass("org.telegram.tgnet.ConnectionsManager").getInstance(acc).sendRequest(req, _ProfileResolveDelegate())
        except Exception as e:
            safe(log, "side_panel: не удалось открыть профиль автора внутри Telegram: " + str(e))

    def sp_context(self, ctx):
        if ctx is None and get_last_fragment is not None:
            ctx = get_last_fragment().getParentActivity()
        return ctx

    def sp_get_ids(self, key):
        try:
            raw = str(self.get_setting(key, "") or "")
            return [int(x) for x in raw.split(",") if x.strip()]
        except Exception:
            return []

    def sp_save_ids(self, key, ids):
        safe(self.set_setting, key, ",".join(str(x) for x in ids))

    def sp_pin_key(self, acc):
        return "panel_pinned_%d" % int(acc)

    def sp_get_pinned(self, acc):
        return self.sp_get_ids(self.sp_pin_key(acc))

    def sp_save_pinned(self, acc, ids):
        self.sp_save_ids(self.sp_pin_key(acc), ids)

    def sp_toggle_pin(self, acc, did):
        did = int(did)
        ids = self.sp_get_pinned(acc)
        if did in ids:
            ids = [x for x in ids if x != did]
            pinned_now = False
        else:
            ids = [did] + ids
            pinned_now = True
            hidden = self.sp_get_ids("hidden")
            if did in hidden:
                self.sp_save_ids("hidden", [x for x in hidden if x != did])
        self.sp_save_pinned(acc, ids)
        return pinned_now

    def sp_recent_key(self, acc):
        return "panel_recent_%d" % int(acc)

    def sp_get_recent(self, acc):
        acc = int(acc)
        cached = self._sp_recent_mem.get(acc)
        if cached is not None:
            return list(cached)
        ids = self.sp_get_ids(self.sp_recent_key(acc))
        self._sp_recent_mem[acc] = list(ids)
        return ids

    def sp_record_recent(self, acc, did):
        try:
            acc = int(acc)
            did = int(did)
        except Exception:
            return False
        if did == 0:
            return False
        try:
            if DialogObject.isEncryptedDialog(did):
                return False
        except Exception:
            pass
        try:
            me = int(jclass("org.telegram.messenger.UserConfig").getInstance(acc).getClientUserId())
            if did == me:
                return False
        except Exception:
            pass
        cur = self.sp_get_recent(acc)
        if cur and cur[0] == did:
            return False
        new_ids = [did] + [x for x in cur if x != did][:119]
        self._sp_recent_mem[acc] = list(new_ids)
        self.sp_save_ids(self.sp_recent_key(acc), new_ids)
        return True

    def sp_frag_account_did(self, fragment):
        if fragment is None:
            return None, 0
        try:
            acc = int(fragment.getCurrentAccount())
        except Exception:
            try:
                acc = int(jclass("org.telegram.messenger.UserConfig").selectedAccount)
            except Exception:
                acc = 0
        did = 0
        try:
            did = int(fragment.getDialogId())
        except Exception:
            did = 0
        if did == 0:
            try:
                args = fragment.getArguments()
                if args is not None:
                    cid = int(args.getLong("chat_id", 0))
                    if cid != 0:
                        did = -cid
                    else:
                        uid = int(args.getLong("user_id", 0))
                        if uid != 0:
                            did = uid
            except Exception:
                pass
        return acc, did

    def sp_record_fragment(self, fragment):
        acc, did = self.sp_frag_account_did(fragment)
        if acc is None or did == 0:
            return
        if self.sp_record_recent(acc, did) and self.sp_reload is not None:
            safe(self.sp_reload)

    def sp_host_recent(self, acc):
        cls = self._sp_recent_host_cls
        if cls is None:
            for cname in ("tw.nekomimi.nekogram.BackButtonMenuRecent", "tw.nekomimi.nekogram.RecentDialogsStore"):
                try:
                    cls = jclass(cname)
                    break
                except Exception:
                    cls = None
            self._sp_recent_host_cls = cls if cls is not None else False
        if not cls:
            return []
        try:
            lst = cls.getRecentDialogs(int(acc))
            if lst is None:
                return []
            out = []
            sz = int(lst.size())
            for i in range(sz):
                try:
                    v = int(lst.get(i).longValue())
                except Exception:
                    try:
                        v = int(lst.get(i))
                    except Exception:
                        continue
                if v != 0:
                    out.append(v)
            return out
        except Exception:
            return []

    def sp_merged_recent(self, acc):
        out = []
        seen = set()
        for src in (self.sp_get_recent(acc), self.sp_host_recent(acc)):
            for did in src:
                if did != 0 and did not in seen:
                    seen.add(did)
                    out.append(did)
        return out

    def sp_open_pinned(self, ctx):
        try:
            ctx = self.sp_context(ctx)
            if ctx is None:
                return
            UC = jclass("org.telegram.messenger.UserConfig")
            acc = int(UC.selectedAccount)
            mc = jclass("org.telegram.messenger.MessagesController").getInstance(acc)
            ids = self.sp_get_pinned(acc)
            if not ids:
                show_dialog(ctx, self._tr("закреплённые в панели"), message=self._tr("закреплённых чатов нет"), button=self._tr("ок"))
                return

            def title_of(did):
                title = chat_info(mc, did)[1]
                return str(title) if title else str(did)

            rows = [(did, title_of(did)) for did in ids]

            def toggled(which, checked):
                did = rows[which][0]
                cur = self.sp_get_pinned(acc)
                if checked and did not in cur:
                    cur.append(did)
                elif not checked and did in cur:
                    cur = [x for x in cur if x != did]
                self.sp_save_pinned(acc, cur)
                if self.sp_reload is not None:
                    safe(self.sp_reload)

            view = checklist_view(ctx, [(r[1], True) for r in rows], toggled)
            show_dialog(ctx, self._tr("закреплённые в панели"), view=view, button=self._tr("готово"))
        except Exception:
            log(traceback.format_exc())

    def sp_parse_names(self, raw):
        names = []
        text = str(raw or "").replace(";", ",").replace("\n", ",").replace(" ", ",")
        for part in text.split(","):
            part = part.strip().lower()
            for pre in ("https://", "http://"):
                if part.startswith(pre):
                    part = part[len(pre):]
            for pre in ("t.me/", "telegram.me/"):
                if part.startswith(pre):
                    part = part[len(pre):]
            part = part.split("?")[0].strip("/").lstrip("@")
            if part and part not in names:
                names.append(part)
        return names

    def sp_selected_names(self):

        text = safe(self.get_setting, "selected_usernames_text", "", default="")
        if str(text or "").strip():
            return self.sp_parse_names(text)
        return self.sp_get_usernames("selected_chats")

    def sp_save_selected(self, names):
        self.sp_save_usernames("selected_chats", names)
        safe(self.set_setting, "selected_usernames_text", ",".join(names))

    def sp_get_usernames(self, key):
        try:
            raw = str(self.get_setting(key, "") or "")
            return [x.strip().lstrip("@").lower() for x in raw.replace(";", ",").split(",") if x.strip()]
        except Exception:
            return []

    def sp_save_usernames(self, key, names):
        safe(self.set_setting, key, ",".join(str(x) for x in names))

    def sp_kind_flags(self):
        return {kind: bool(self.get_setting(key, True)) for kind, key in KIND_KEYS.items()}

    def sp_color_choice(self):
        choice = str(self.get_setting("panel_color", DEFAULT_COLOR) or DEFAULT_COLOR).strip().replace("#", "").upper()
        if len(choice) != 6:
            return DEFAULT_COLOR
        try:
            int(choice, 16)
            return choice
        except Exception:
            return DEFAULT_COLOR

    def sp_open_palette(self, ctx):
        try:
            ctx = self.sp_context(ctx)
            if ctx is None:
                return
            frag = get_last_fragment() if get_last_fragment is not None else None
            if frag is None:
                return
            act = frag.getParentActivity()
            if act is None:
                return
            try:
                resources_provider = frag.getResourceProvider()
            except Exception:
                resources_provider = None
            Color = jclass("android.graphics.Color")
            try:
                initial_color = Color.parseColor("#" + self.sp_color_choice())
            except Exception:
                initial_color = argb(theme_color("key_dialogBackground", 0xFF1B1B1B))

            try:
                from org.telegram.ui.Components.Paint import ColorPickerBottomSheet
                from androidx.core.util import Consumer
                PipetteDelegate = find_class("org.telegram.ui.Components.Paint.ColorPickerBottomSheet$PipetteDelegate")
                PipetteDelegateProxy = dynamic_proxy(PipetteDelegate)

                class _PipetteDelegate(PipetteDelegateProxy):
                    def onStartColorPipette(self): return None
                    def onStopColorPipette(self): return None
                    def getContainerView(self): return None
                    def getSnapshotDrawingView(self): return None
                    def onDrawImageOverCanvas(self, bitmap, canvas): return None
                    def isPipetteVisible(self): return False
                    def isPipetteAvailable(self): return False
                    def onColorSelected(self, color): return None

                ConsumerProxy = dynamic_proxy(Consumer)

                class _ColorConsumer(ConsumerProxy):
                    def __init__(self, plugin):
                        super().__init__()
                        self._plugin = plugin
                    def accept(self, color):
                        self._plugin.set_setting("panel_color", "{:06X}".format(int(color) & 0xFFFFFF))
                        if self._plugin.sp_restyle is not None:
                            safe(self._plugin.sp_restyle)

                self._sp_color_picker_pipette = _PipetteDelegate()
                self._sp_color_picker_consumer = _ColorConsumer(self)
                sheet = ColorPickerBottomSheet(act, resources_provider)
                safe(sheet.setCanceledOnTouchOutside, False)
                sheet.setPipetteDelegate(self._sp_color_picker_pipette)
                sheet.setColor(initial_color)
                sheet.setColorListener(self._sp_color_picker_consumer)
                run_on_ui_thread(sheet.show)
                return
            except Exception:
                pass

            try:
                ColorPicker = jclass("org.telegram.ui.Components.ColorPicker")
                ColorPickerDelegate = find_class("org.telegram.ui.Components.ColorPicker$ColorPickerDelegate")
                ColorPickerDelegateProxy = dynamic_proxy(ColorPickerDelegate)
                plugin = self

                class _PickerDelegate(ColorPickerDelegateProxy):
                    def setColor(self, color, num, applyNow):
                        plugin.set_setting("panel_color", "{:06X}".format(int(color) & 0xFFFFFF))
                        if plugin.sp_restyle is not None:
                            safe(plugin.sp_restyle)

                self._sp_color_picker_delegate = _PickerDelegate()
                picker = ColorPicker(act, False, self._sp_color_picker_delegate)
                safe(picker.setColor, initial_color, 0)
                return show_dialog(act, self._tr("цвет панели"), view=picker, button=self._tr("готово"))
            except Exception:
                log(traceback.format_exc())
        except Exception:
            log(traceback.format_exc())

    def sp_open_hidden(self, ctx):
        try:
            ctx = self.sp_context(ctx)
            if ctx is None:
                return
            UC = jclass("org.telegram.messenger.UserConfig")
            mc = jclass("org.telegram.messenger.MessagesController").getInstance(UC.selectedAccount)
            ids = self.sp_get_ids("hidden")
            if not ids:
                show_dialog(ctx, self._tr("скрытые чаты"), message=self._tr("скрытых чатов нет"), button=self._tr("ок"))
                return

            def title_of(did):
                title = chat_info(mc, did)[1]
                return str(title) if title else str(did)

            rows = [(did, title_of(did)) for did in ids]

            def toggled(which, checked):
                did = rows[which][0]
                cur = self.sp_get_ids("hidden")
                if checked and did not in cur:
                    cur.append(did)
                elif not checked and did in cur:
                    cur.remove(did)
                self.sp_save_ids("hidden", cur)

            view = checklist_view(ctx, [(r[1], True) for r in rows], toggled)
            show_dialog(ctx, self._tr("скрытые чаты"), view=view, button=self._tr("готово"))
        except Exception:
            log(traceback.format_exc())

    def sp_open_exceptions(self, ctx):
        try:
            ctx = self.sp_context(ctx)
            if ctx is None:
                return
            UC = jclass("org.telegram.messenger.UserConfig")
            mc = jclass("org.telegram.messenger.MessagesController").getInstance(UC.selectedAccount)
            me = UC.getInstance(UC.selectedAccount).getClientUserId()
            flags = self.sp_kind_flags()
            exc = self.sp_get_ids("exceptions")

            rows = []
            for _, did, obj, title in load_dialogs(mc):
                kind = chat_kind(did, obj)
                if did != me and (not flags[kind] or did in exc):
                    rows.append((did, "%s (%s)" % (title, self._tr(KIND_LABELS[kind])), did in exc))

            if not rows:
                show_dialog(
                    ctx, self._tr("всегда показывать"),
                    message=self._tr("все типы чатов включены. отключите нужный тип, и его чаты появятся здесь."),
                    button=self._tr("ок"),
                )
                return

            def toggled(which, checked):
                did = rows[which][0]
                cur = self.sp_get_ids("exceptions")
                if checked and did not in cur:
                    cur.append(did)
                elif not checked and did in cur:
                    cur.remove(did)
                self.sp_save_ids("exceptions", cur)
                if self.sp_restyle is not None:
                    safe(self.sp_restyle)

            view = checklist_view(ctx, [(r[1], r[2]) for r in rows], toggled)
            show_dialog(ctx, self._tr("всегда показывать"), view=view, button=self._tr("готово"))
        except Exception:
            log(traceback.format_exc())

    def sp_attach(self, fragment, root):
        safe(self.sp_record_fragment, fragment)
        if root is None:
            return
        if self.sp_global_enabled():

            if safe(self.sp_attach_global, root.getContext(), default=False):
                return
        if root.findViewWithTag(TAG) is not None or root.findViewWithTag(TAG_FLOAT) is not None:
            return
        added = []
        before = len(self._sp_cleanups)
        try:
            self.sp_build(fragment, root, added)
            fid = int(jclass("java.lang.System").identityHashCode(fragment))
            self._sp_frag_cleanups[fid] = list(self._sp_cleanups[before:])
        except Exception:
            log(traceback.format_exc())
            for v in added:
                safe(root.removeView, v)

    def sp_build(self, fragment, root, added):
        chat_mode = int(self.get_setting("chat_mode", 0))
        layout_mode = int(self.get_setting("panel_layout", 0))
        floating = layout_mode == 1
        show_saved = bool(self.get_setting("show_saved", True))
        panel_animation = max(0, min(11, int(self.sp_anim())))
        close_on_tap = bool(self.get_setting("close_on_tap", True))
        hide_on_keyboard = bool(self.get_setting("keyboard_hide", False))
        limit = max(1, min(3000, int(self.get_setting("count_custom", 50))))
        panel_width = max(48, min(110, int(self.get_setting("panel_width", 66))))
        S = dict(BASE_SIZE)
        scale = panel_width / 66.0
        S["width"] = panel_width
        S["avatar"] = max(30, int(round(S["avatar"] * scale)))
        S["item_height"] = max(62, int(round(S["item_height"] * scale)))
        S["text_size"] = max(9.5, round(S["text_size"] * min(scale, 1.45), 1))
        S["badge"] = max(13, int(round(S["badge"] * min(scale, 1.45))))
        S["dot_badge"] = max(14, int(round(17 * min(scale, 1.45))))
        S["online_dot"] = max(10, int(round(12 * min(scale, 1.45))))
        S["search_fab"] = max(38, min(panel_width - 8, int(round(48 * scale))))
        S["handle_width"] = max(20, int(round(S["handle_width"] * scale)))
        S["handle_height"] = max(44, int(round(S["handle_height"] * scale)))

        UC = jclass("org.telegram.messenger.UserConfig")
        CM = jclass("org.telegram.tgnet.ConnectionsManager")
        LinearLayout = jclass("android.widget.LinearLayout")
        FrameLayout = jclass("android.widget.FrameLayout")
        ScrollView = jclass("android.widget.ScrollView")
        TextView = jclass("android.widget.TextView")
        View = jclass("android.view.View")
        LP = jclass("android.widget.LinearLayout$LayoutParams")
        FLP = jclass("android.widget.FrameLayout$LayoutParams")
        Gradient = jclass("android.graphics.drawable.GradientDrawable")
        Typeface = jclass("android.graphics.Typeface")
        BackupImageView = jclass("org.telegram.ui.Components.BackupImageView")
        AvatarDrawable = jclass("org.telegram.ui.Components.AvatarDrawable")
        TextUtils = jclass("android.text.TextUtils")
        Bundle = jclass("android.os.Bundle")
        ChatActivity = jclass("org.telegram.ui.ChatActivity")

        dp = AndroidUtilities.dp
        ctx = root.getContext()
        acc = UC.selectedAccount
        mc = jclass("org.telegram.messenger.MessagesController").getInstance(acc)
        me = UC.getInstance(acc).getClientUserId()
        slop = jclass("android.view.ViewConfiguration").get(ctx).getScaledTouchSlop()
        dlg_map = {}
        style = {}
        guard = {"view": None, "since": 0.0}

        def read_style():
            style["rgb"] = theme_color("key_windowBackgroundWhite", 0xFF1B1B1B) & 0xFFFFFF
            style["text"] = theme_color("key_windowBackgroundWhiteBlackText", 0xFFFFFFFF)
            try:
                ui = int(self.sp_ui_style())
            except Exception:
                ui = 0
            style["ui"] = ui if 0 <= ui <= 2 else 0
            SKIN_STATE["v"] = self.sp_skin()
            style["alpha"] = 255

        read_style()

        def is_muted(did):
            try:
                return bool(mc.isDialogMuted(did, 0))
            except Exception:
                return bool(safe(mc.isDialogMuted, did, default=False))

        voff = {"v": max(-120, min(240, int(self.get_setting("vertical_offset", 0))))}
        corner_radius = 14

        def eff_radius():
            return min(36, corner_radius + RADIUS_BONUS[style.get("ui", 0)])

        def cur_dialog():
            try:
                return int(fragment.getDialogId())
            except Exception:
                return 0

        def top_px():
            base = dp(TOP_FALLBACK)
            try:
                ab = fragment.getActionBar()
                h = int(ab.getY()) + int(ab.getHeight())
                if h > 0:
                    base = h + dp(4) + dp(TOP_OFFSET)
            except Exception:
                pass
            return max(0, base + dp(voff["v"]))

        def make_badge(count, muted, size):
            badge = TextView(ctx)
            badge.setText("99+" if count > 99 else str(count))
            badge.setTextSize(float(size) * 0.68)
            badge.setTypeface(Typeface.DEFAULT_BOLD)
            badge.setTextColor(argb(theme_color("key_chats_unreadCounterText", 0xFFFFFFFF)))
            badge.setGravity(GRAV_CENTER)
            badge.setPadding(dp(4), 0, dp(4), 0)
            badge.setMinWidth(dp(size))
            badge.setIncludeFontPadding(False)
            bg = Gradient()
            bg.setCornerRadius(float(dp(size)) / 2.0)
            ui = style.get("ui", 0)
            if muted:
                bg.setColor(argb(theme_color("key_chats_unreadCounterMuted", 0xFF5A5A5A)))
                if ui in (1, 2):
                    badge.setTextColor(argb(0xFFFFFFFF))
            elif ui == 2:
                bg.setColor(argb(0xFFB3261E))
                badge.setTextColor(argb(0xFFFFFFFF))
            elif ui == 1:
                bg.setColor(argb(ios_accent(style["text"])))
                badge.setTextColor(argb(0xFFFFFFFF))
            else:
                bg.setColor(argb(theme_color("key_chats_unreadCounter", 0xFFB06CEC)))
            skin = SKIN_STATE.get("v", "")
            badge.setBackground(bg)
            return badge

        def is_online(did, obj):
            try:
                if did <= 0 or did == me or getattr(obj, "bot", False):
                    return False
                return bool(UserObject.isUserOnline(obj))
            except Exception:
                try:
                    status = getattr(obj, "status", None)
                    return status is not None and int(getattr(status, "expires", 0)) > int(CM.getInstance(acc).getCurrentTime())
                except Exception:
                    return False

        def make_online_dot():
            dot = View(ctx)
            bg = Gradient()
            bg.setShape(OVAL)
            bg.setColor(argb(theme_color("key_chats_onlineCircle", 0xFF4DCD5E)))

            if style.get("ui", 0) != 2:
                bg.setStroke(dp(2), argb(0xFF000000 | style["rgb"]))
            dot.setBackground(bg)
            return dot

        def make_avatar(did, obj, size_dp):
            avatar = BackupImageView(ctx)
            avatar.setRoundRadius(dp(size_dp // 2))
            ad = AvatarDrawable()
            if did == me:
                try:
                    ad.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED)
                    avatar.setImageDrawable(ad)
                    return avatar
                except Exception:
                    pass
            ad.setInfo(acc, obj)
            avatar.setForUserOrChat(obj, ad)
            return avatar

        def make_args(did):
            args = Bundle()
            if did > 0:
                args.putLong("user_id", did)
            else:
                args.putLong("chat_id", -did)
            return args

        def open_chat(did):
            safe(self.sp_record_recent, acc, did)
            fragment.presentFragment(ChatActivity(make_args(did)))
            root.postDelayed(RunnableProxy(refill), 500)
            root.postDelayed(RunnableProxy(refill), 1200)

        def open_profile(did):
            try:
                if did > 0:
                    ProfileActivity = jclass("org.telegram.ui.ProfileActivity")
                    args = Bundle()
                    args.putLong("user_id", did)
                    fragment.presentFragment(ProfileActivity(args))
                else:
                    ProfileActivity = jclass("org.telegram.ui.ProfileActivity")
                    args = Bundle()
                    args.putLong("chat_id", -did)
                    fragment.presentFragment(ProfileActivity(args))
            except Exception:
                open_chat(did)

        def refill_later():
            root.postDelayed(RunnableProxy(refill), 400)

        def mark_read(did):
            dlg = dlg_map.get(did)
            if dlg is None:
                return
            mc.markDialogAsRead(did, dlg.top_message, dlg.top_message, dlg.last_message_date, False, 0, dlg.unread_count, True, 0)
            refill_later()

        def toggle_mute(did):
            notifications = jclass("org.telegram.messenger.NotificationsController").getInstance(acc)
            mute = not is_muted(did)
            try:
                notifications.muteDialog(did, 0, mute)
            except Exception:
                notifications.muteDialog(did, mute)
            refill_later()

        def hide_chat(did):
            ids = self.sp_get_ids("hidden")
            if did not in ids:
                ids.append(did)
            self.sp_save_ids("hidden", ids)
            pinned = self.sp_get_pinned(acc)
            if did in pinned:
                self.sp_save_pinned(acc, [x for x in pinned if x != did])
            refill()

        def toggle_panel_pin(did, title=None):
            if did == me:
                return
            pinned_now = self.sp_toggle_pin(acc, did)
            refill()
            msg = self._tr("закреплено в панели") if pinned_now else self._tr("откреплено из панели")
            if title:
                msg = "%s: %s" % (msg, title)
            safe(lambda: jclass("android.widget.Toast").makeText(ctx, msg, 0).show())

        def swipe_action(did, code):
            if code == 1:
                dlg = dlg_map.get(did)
                if dlg is not None and int(dlg.unread_count) > 0:
                    mark_read(did)
            elif code == 2 and did != me:
                toggle_mute(did)
            elif code == 3 and did != me:
                hide_chat(did)
            elif code == 4 and did != me:
                entry = by_id_cache.get(did)
                toggle_panel_pin(did, entry[3] if entry else None)

        def attach_swipe(view, did):
            left_code = int(self.get_setting("swipe_left", 1))
            right_code = int(self.get_setting("swipe_right", 2))
            if left_code == 0 and right_code == 0:
                return
            MotionEvent = jclass("android.view.MotionEvent")
            st = {"x": 0.0, "y": 0.0, "on": False, "dead": False}
            limit_px = dp(44)

            def on_touch(v, e):
                a = int(e.getActionMasked())
                if a == 0:
                    st["x"], st["y"] = float(e.getRawX()), float(e.getRawY())
                    st["on"], st["dead"] = False, False
                    return False
                if a == 2:
                    if st["dead"]:
                        return False
                    dx = float(e.getRawX()) - st["x"]
                    dy = float(e.getRawY()) - st["y"]
                    if not st["on"]:
                        if abs(dy) > slop and abs(dy) > abs(dx):
                            st["dead"] = True
                            return False
                        if abs(dx) > slop * 2 and abs(dx) > abs(dy) * 1.5:
                            st["on"] = True
                            v.cancelLongPress()
                            c = MotionEvent.obtain(e)
                            c.setAction(3)
                            v.onTouchEvent(c)
                            c.recycle()
                            safe(v.getParent().requestDisallowInterceptTouchEvent, True)
                        else:
                            return False
                    v.setTranslationX(max(-float(dp(30)), min(float(dp(30)), dx * 0.5)))
                    v.setAlpha(1.0 - min(0.4, abs(dx) / float(dp(160))))
                    return True
                if a in (1, 3) and st["on"]:
                    dx = float(e.getRawX()) - st["x"]
                    st["on"] = False
                    v.animate().translationX(0.0).alpha(1.0).setDuration(160).start()
                    if a == 1 and abs(dx) >= limit_px:
                        code = left_code if dx < 0 else right_code
                        if code:
                            safe(v.performHapticFeedback, 0)
                            swipe_action(did, code)
                    return True
                return False

            view.setOnTouchListener(TouchListener(on_touch))

        def menu_entries(did, in_peek=False):
            entries = []
            if did != me:
                is_lp = did in set(self.sp_get_pinned(acc))
                entry = by_id_cache.get(did)
                t_lbl = entry[3] if entry else None
                entries.append((self._tr("открепить из панели") if is_lp else self._tr("закрепить в панели"), lambda: toggle_panel_pin(did, t_lbl)))
            entries.append((self._tr("открыть"), lambda: open_chat(did)) if in_peek else (self._tr("предпросмотр"), lambda: preview(did)))
            if did != me:
                entries.append((self._tr("открыть профиль"), lambda: open_profile(did)))
            dlg = dlg_map.get(did)
            if dlg is not None and int(dlg.unread_count) > 0:
                entries.append((self._tr("отметить прочитанным"), lambda: mark_read(did)))
            if did != me:
                entries.append((self._tr("включить звук") if is_muted(did) else self._tr("выключить звук"), lambda: toggle_mute(did)))
                entries.append((self._tr("скрыть из панели"), lambda: hide_chat(did)))
            return entries

        def show_menu(did, title):
            entries = menu_entries(did)
            show_sheet(ctx, title, [e[0] for e in entries], lambda which: entries[which][1]())

        def on_item_long_press(view, did, title):
            if did != me and bool(self.get_setting("long_press_pin", False)):
                safe(view.performHapticFeedback, 0)
                toggle_panel_pin(did, title)
                return
            show_menu(did, title)

        def clear_guard():
            view = guard["view"]
            guard["view"] = None
            if view is not None:
                safe(root.removeView, view)

        def close_preview():
            safe(fragment.finishPreviewFragment)
            clear_guard()

        def add_guard():
            clear_guard()

            def on_touch(view, event):
                if event.getActionMasked() == 0:
                    close_preview()
                return True

            view = View(ctx)
            view.setOnTouchListener(TouchListener(on_touch))
            root.addView(view, FLP(MATCH, MATCH))
            guard["view"] = view
            guard["since"] = time.monotonic()

        def preview_closed():
            if guard["view"] is None or time.monotonic() - guard["since"] < 0.8:
                return False
            return not safe(lambda: bool(fragment.getParentLayout().isInPreviewMode()), default=True)

        def after_peek(action):
            close_preview()
            root.post(RunnableProxy(action))

        def peek_menu(did):
            Layout = jclass("org.telegram.ui.ActionBar.ActionBarPopupWindow$ActionBarPopupWindowLayout")
            SubItem = jclass("org.telegram.ui.ActionBar.ActionBarMenuSubItem")
            menu = Layout(ctx)
            entries = menu_entries(did, in_peek=True)
            for i, (label, action) in enumerate(entries):
                row = SubItem(ctx, i == 0, i == len(entries) - 1)
                row.setTextAndIcon(label, 0)
                row.setMinimumWidth(dp(180))
                row.setOnClickListener(ClickListener(lambda action=action: after_peek(action)))
                menu.addView(row)
            return menu

        def preview(did):
            def go():
                chat = ChatActivity(make_args(did))
                shown = safe(lambda: bool(fragment.presentFragmentAsPreviewWithMenu(chat, peek_menu(did))), default=False)
                if shown:
                    root.postDelayed(RunnableProxy(lambda: fragment.getParentLayout().movePreviewFragment(float(dp(120)))), 150)
                elif safe(fragment.presentFragmentAsPreview, ChatActivity(make_args(did)), default=False):
                    shown = True
                else:
                    open_chat(did)
                if shown:
                    add_guard()
            root.post(RunnableProxy(go))

        dialog_cache = []
        by_id_cache = {}
        me_entry = None

        def reload_dialogs():
            nonlocal dialog_cache, by_id_cache, me_entry
            dialog_cache = load_dialogs(mc)
            by_id_cache = {e[1]: e for e in dialog_cache}
            dlg_map.clear()
            dlg_map.update({e[1]: e[0] for e in dialog_cache})
            me_entry = next((e for e in dialog_cache if e[1] == me), None)

        reload_dialogs()

        def obj_did_title(obj):
            try:
                name = str(obj.getClass().getSimpleName()).lower()
            except Exception:
                name = ""
            is_user = "user" in name
            if is_user:
                return int(obj.id), UserObject.getUserName(obj)
            return -int(obj.id), getattr(obj, "title", None)

        def resolve_remote(name):
            if name in self._sp_pending or name in self._sp_failed:
                return
            self._sp_pending.add(name)

            def finish(response, error):
                def apply():
                    self._sp_pending.discard(name)
                    ok = False
                    try:
                        if response is not None and error is None:
                            users = response.users
                            chats = response.chats
                            safe(mc.putUsers, users, False)
                            safe(mc.putChats, chats, False)
                            obj = safe(mc.getUserOrChat, name)
                            if obj is None and users is not None and users.size() > 0:
                                obj = users.get(0)
                            if obj is None and chats is not None and chats.size() > 0:
                                obj = chats.get(0)
                            if obj is not None:
                                self._sp_resolved[name] = obj
                                ok = True
                    except Exception:
                        log(traceback.format_exc())
                    if not ok:
                        self._sp_failed.add(name)
                    else:
                        safe(refill)
                AndroidUtilities.runOnUIThread(RunnableProxy(apply))

            class _Delegate(dynamic_proxy(jclass("org.telegram.tgnet.RequestDelegate"))):
                def run(self, response, error):
                    safe(finish, response, error)

            try:
                req = jclass("org.telegram.tgnet.TLRPC$TL_contacts_resolveUsername")()
                req.username = name
                CM.getInstance(acc).sendRequest(req, _Delegate())
            except Exception:
                self._sp_pending.discard(name)
                self._sp_failed.add(name)
                log(traceback.format_exc())

        def lookup_username(name):
            obj = self._sp_resolved.get(name)
            if obj is None:
                obj = safe(mc.getUserOrChat, name)
                if obj is not None:
                    self._sp_resolved[name] = obj
            return obj

        def collect():
            selected_names = set(self.sp_selected_names())
            hidden = set(self.sp_get_ids("hidden"))
            show_pinned = bool(self.get_setting("show_pinned", True))
            out = []
            if show_saved and me_entry is not None:
                out.append((me_entry[0], me_entry[1], me_entry[2], self._tr("избранное")))

            if chat_mode == 0:
                candidates = set()
                matched = set()
                for e in dialog_cache:
                    did = e[1]
                    if show_pinned and e[0] is not None and bool(getattr(e[0], "pinned", False)):
                        candidates.add(did)
                    un = get_username(did, e[2])
                    if un and un.lower().lstrip("@") in selected_names:
                        candidates.add(did)
                        matched.add(un.lower().lstrip("@"))
                for name in selected_names:
                    if name in matched:
                        continue


                    obj = None
                    if name.lstrip("-").isdigit():
                        try:
                            numeric_id = int(name)
                            if numeric_id > 0:
                                obj = safe(mc.getUser, Long(numeric_id))
                                if obj is None:
                                    obj = safe(mc.getChat, Long(numeric_id))
                            elif numeric_id < 0:
                                obj = safe(mc.getChat, Long(-numeric_id))
                        except Exception:
                            obj = None
                    else:
                        obj = lookup_username(name)
                        if obj is None:
                            resolve_remote(name)
                            continue
                    if obj is None:
                        continue
                    try:
                        r_did, r_title = obj_did_title(obj)
                    except Exception:
                        continue
                    if r_did in by_id_cache:
                        candidates.add(r_did)
                    elif r_title:
                        r_dlg = safe(mc.dialogs_dict.get, r_did)
                        by_id_cache[r_did] = (r_dlg, r_did, obj, r_title)
                        if r_dlg is not None:
                            dlg_map[r_did] = r_dlg
                        candidates.add(r_did)
            else:
                candidates = set()
                if show_pinned:
                    for e in dialog_cache:
                        if e[0] is not None and bool(getattr(e[0], "pinned", False)):
                            candidates.add(e[1])

            local_pinned = self.sp_get_pinned(acc)
            local_pinned_set = set(local_pinned)
            recent_ids = self.sp_merged_recent(acc)

            def _ensure_cached(target_did):
                if target_did == me or target_did in hidden:
                    return False
                if target_did not in by_id_cache:
                    p_obj, p_title = chat_info(mc, target_did)
                    if p_obj is not None and p_title:
                        p_dlg = safe(mc.dialogs_dict.get, target_did)
                        by_id_cache[target_did] = (p_dlg, target_did, p_obj, p_title)
                        if p_dlg is not None:
                            dlg_map[target_did] = p_dlg
                return target_did in by_id_cache

            for p_did in local_pinned:
                if _ensure_cached(p_did):
                    candidates.add(p_did)

            if chat_mode != 0:
                for r_did in recent_ids:
                    if _ensure_cached(r_did):
                        candidates.add(r_did)

            current_flags = self.sp_kind_flags()
            exceptions = set(self.sp_get_ids("exceptions"))

            normal = []
            for did in candidates:
                if did == me or did in hidden:
                    continue
                entry = by_id_cache.get(did)
                if entry is None:
                    continue
                obj = entry[2]
                if chat_mode != 0 and did not in local_pinned_set:
                    kind = chat_kind(did, obj)
                    if not current_flags.get(kind, False) and did not in exceptions:
                        continue
                normal.append(entry)

            entry_by_id = {e[1]: e for e in normal}
            recent_rank = {did: idx for idx, did in enumerate(recent_ids)}
            recent_entries = [e for e in normal if e[1] in recent_rank]
            recent_entries.sort(key=lambda e: recent_rank[e[1]])
            other_entries = [e for e in normal if e[1] not in recent_rank]
            other_entries.sort(key=lambda e: int(getattr(e[0], "last_message_date", 0)), reverse=True)
            ordered_normal = recent_entries + other_entries

            local_items = [entry_by_id[did] for did in local_pinned if did in entry_by_id]
            rest_items = [e for e in ordered_normal if e[1] not in local_pinned_set]
            if show_pinned:
                tg_pinned_items = [e for e in rest_items if e[0] is not None and bool(getattr(e[0], "pinned", False))]
                other_items = [e for e in rest_items if not (e[0] is not None and bool(getattr(e[0], "pinned", False)))]
                rest_items = tg_pinned_items + other_items
            out.extend(local_items + rest_items)
            return out[:limit]

        def split_pinned_groups(items):
            local_pinned_set = set(self.sp_get_pinned(acc))
            show_pinned_cfg = bool(self.get_setting("show_pinned", True))
            def _is_top(e):
                if e[1] == me or e[1] in local_pinned_set:
                    return True
                return show_pinned_cfg and e[0] is not None and bool(getattr(e[0], "pinned", False))
            top_g = [e for e in items if _is_top(e)]
            rest_g = [e for e in items if not _is_top(e)]
            return top_g, rest_g, local_pinned_set

        def make_group_divider(h_margin_dp):
            div = View(ctx)
            div.setBackgroundColor(argb(0x33FFFFFF if is_dark_bg(style["text"]) else 0x24000000))
            dlp = LP(MATCH, max(1, dp(1)))
            dlp.leftMargin = dp(h_margin_dp)
            dlp.rightMargin = dp(h_margin_dp)
            dlp.topMargin = dp(5)
            dlp.bottomMargin = dp(5)
            return div, dlp

        W = dp(S["width"])
        AV = S["avatar"]
        HW, HH = dp(S["handle_width"]), dp(S["handle_height"])

        if floating:
            FAB = dp(53)
            MENU_W = dp(286)
            MENU_H = dp(396)
            AV_SZ = 40

            def menu_radius():
                base = 14
                return min(30, base + RADIUS_BONUS[style["ui"]])

            fab_x = int(self.get_setting("fab_x", -1))
            fab_y = int(self.get_setting("fab_y", -1))

            def current_did():
                try:
                    did = int(fragment.getDialogId())
                    if did != 0:
                        return did
                except Exception:
                    pass
                try:
                    args = fragment.getArguments()
                    if args is not None:
                        cid = int(args.getLong("chat_id", 0))
                        if cid != 0:
                            return int(-cid)
                        uid = int(args.getLong("user_id", 0))
                        if uid != 0:
                            return int(uid)
                except Exception:
                    pass
                return 0

            def add_current_chat():
                did = current_did()
                if did == 0 or did == me:
                    return
                hidden = self.sp_get_ids("hidden")
                if did in hidden:
                    hidden = [x for x in hidden if x != did]
                    self.sp_save_ids("hidden", hidden)
                if chat_mode == 0:
                    entry = by_id_cache.get(did)
                    if entry is not None:
                        un = get_username(did, entry[2])
                        if un:
                            n = un.lower().lstrip("@")
                            names = self.sp_selected_names()
                            if n not in names:
                                names.append(n)
                                self.sp_save_selected(names)
                else:
                    entry = by_id_cache.get(did)
                    if entry is not None:
                        kind = chat_kind(did, entry[2])
                        flags = self.sp_kind_flags()
                        if not flags.get(kind, False):
                            exc = self.sp_get_ids("exceptions")
                            if did not in exc:
                                exc.append(did)
                                self.sp_save_ids("exceptions", exc)
                refill()

            scrim = View(ctx)
            scrim.setVisibility(GONE)
            scrim.setBackgroundColor(argb(0x40000000))
            root.addView(scrim, FLP(MATCH, MATCH))
            added.append(scrim)

            menu = LinearLayout(ctx)
            menu.setOrientation(LinearLayout.VERTICAL)
            menu.setVisibility(GONE)
            menu.setClipToOutline(True)
            mbg = Gradient()
            mbg.setCornerRadius(float(dp(12)))
            menu.setBackground(mbg)
            try:
                menu.setOutlineProvider(jclass("android.view.ViewOutlineProvider").BACKGROUND)
                menu.setElevation(float(dp(6)))
            except Exception:
                pass

            top = LinearLayout(ctx)
            top.setOrientation(LinearLayout.HORIZONTAL)
            top.setGravity(GRAV_CENTER_V)
            top.setPadding(dp(8), dp(8), dp(6), dp(4))

            add_btn = TextView(ctx)
            add_btn.setText("+")
            add_btn.setTextSize(22.0)
            add_btn.setGravity(GRAV_CENTER)
            add_btn.setPadding(dp(10), dp(4), dp(10), dp(4))
            add_btn.setOnClickListener(ClickListener(add_current_chat))
            top.addView(add_btn, LP(WRAP, WRAP))
            menu.addView(top, LP(MATCH, WRAP))

            sep = View(ctx)
            sep.setBackgroundColor(argb(0x22000000))
            menu.addView(sep, LP(MATCH, max(1, dp(1))))

            scroll = ScrollView(ctx)
            scroll.setVerticalScrollBarEnabled(False)
            scroll.setOverScrollMode(OVERSCROLL_NEVER)
            col = LinearLayout(ctx)
            col.setOrientation(LinearLayout.VERTICAL)
            col.setPadding(dp(4), dp(4), dp(4), dp(6))
            scroll.addView(col, FLP(MATCH, WRAP))
            scroll_lp = LP(MATCH, 0)
            scroll_lp.weight = 1.0
            menu.addView(scroll, scroll_lp)

            mlp = FLP(MENU_W, MENU_H, GRAV_TOP_LEFT)
            root.addView(menu, mlp)
            added.append(menu)

            fab = FrameLayout(ctx)
            fab.setTag(TAG_FLOAT)
            fab.setClickable(True)
            fbg = Gradient()
            fbg.setShape(OVAL)
            fab.setBackground(fbg)
            try:
                fab.setElevation(float(dp(4)))
                fab.setOutlineProvider(jclass("android.view.ViewOutlineProvider").BACKGROUND)
                fab.setClipToOutline(True)
            except Exception:
                pass

            icon_view = None
            try:
                ImageView = jclass("android.widget.ImageView")
                iv = ImageView(ctx)
                res = ctx.getResources()
                rid = 0
                for name in ("menu_sidebar", "msg_folders", "ic_ab_other", "msg_menu"):
                    rid = int(res.getIdentifier(name, "drawable", ctx.getPackageName()) or 0)
                    if rid:
                        break
                if rid:
                    iv.setImageResource(rid)
                    safe(iv.setColorFilter, argb(0xFFFFFFFF))
                    safe(iv.setScaleType, getattr(ImageView.ScaleType, "CENTER_INSIDE", None))
                    pad = dp(12)
                    iv.setPadding(pad, pad, pad, pad)
                    fab.addView(iv, FLP(MATCH, MATCH))
                    icon_view = iv
            except Exception:
                icon_view = None
            if icon_view is None:
                tv = TextView(ctx)
                tv.setText("=")
                tv.setGravity(GRAV_CENTER)
                tv.setTextSize(18.0)
                tv.setTypeface(Typeface.DEFAULT_BOLD)
                fab.addView(tv, FLP(MATCH, MATCH))
                icon_view = tv

            root.addView(fab, FLP(FAB, FAB, GRAV_TOP_LEFT))
            added.append(fab)

            def filtered_items():

                return list(collect())

            def make_item(dlg, did, obj, title, pinned_set=None):
                row = LinearLayout(ctx)
                row.setOrientation(LinearLayout.HORIZONTAL)
                row.setGravity(GRAV_CENTER_V)
                row.setPadding(dp(11), dp(9), dp(13), dp(9))
                if cur_dialog() == did:
                    ab = active_bg(style["ui"], style["rgb"], style["text"])
                    if ab is not None:
                        row.setBackground(ab)
                if style["ui"] == 2:
                    rp = ripple(0x1F000000 | (style["text"] & 0xFFFFFF), dp(12))
                    if rp is not None:
                        row.setForeground(rp)

                av = make_avatar(did, obj, AV_SZ)
                row.addView(av, LP(dp(AV_SZ), dp(AV_SZ)))

                show_titles = bool(self.get_setting("show_titles", True))
                if show_titles:
                    label = TextView(ctx)
                    pset = pinned_set if pinned_set is not None else set(self.sp_get_pinned(acc))
                    is_lp = did in pset
                    label.setText(("★ " + str(title)) if is_lp else title)
                    label.setTextSize(16.5)
                    label.setTextColor(argb(style["text"]))
                    label.setLines(1)
                    label.setEllipsize(TextUtils.TruncateAt.END)
                    lp = LP(0, WRAP)
                    lp.weight = 1.0
                    lp.leftMargin = dp(12)
                    row.addView(label, lp)

                unread = int(dlg.unread_count) if dlg is not None else 0
                if unread > 0:
                    badge = make_badge(unread, is_muted(did), 18)
                    blp = LP(WRAP, WRAP)
                    blp.leftMargin = dp(6)
                    row.addView(badge, blp)

                def _open():
                    close_menu()
                    open_chat(did)

                row.setOnClickListener(ClickListener(_open))
                row.setOnLongClickListener(LongClickListener(lambda v=row: on_item_long_press(v, did, title)))
                attach_swipe(row, did)
                return row

            def refill():
                col.removeAllViews()
                items = filtered_items()
                if not items:
                    empty = TextView(ctx)
                    empty.setText(self._tr("пусто"))
                    empty.setGravity(GRAV_CENTER)
                    empty.setTextSize(14.0)
                    empty.setPadding(0, dp(24), 0, dp(24))
                    empty.setTextColor(argb(style["text"]))
                    col.addView(empty, LP(MATCH, WRAP))
                else:
                    top_grp, rest_grp, pinned_set = split_pinned_groups(items)
                    for dlg, did, obj, title in top_grp:
                        row = safe(make_item, dlg, did, obj, title, pinned_set)
                        if row is not None:
                            col.addView(row, LP(MATCH, WRAP))
                    if top_grp and rest_grp:
                        div, dlp = make_group_divider(12)
                        col.addView(div, dlp)
                    for dlg, did, obj, title in rest_grp:
                        row = safe(make_item, dlg, did, obj, title, pinned_set)
                        if row is not None:
                            col.addView(row, LP(MATCH, WRAP))

            def apply_colors():
                ui = style["ui"]
                alpha = int(style["alpha"]) if ui == 1 else max(230, int(style["alpha"]))
                mbg.setCornerRadius(float(dp(menu_radius())))
                paint(mbg, ui, alpha, style["rgb"], style["text"])
                if ui == 2:
                    fbg.setShape(0)
                    fbg.setCornerRadius(float(dp(16)))
                else:
                    fbg.setShape(OVAL)
                paint(fbg, ui, 255, style["rgb"], style["text"])
                try:
                    menu.setElevation(float(dp(PANEL_ELEVATION[ui])))
                    fab.setElevation(float(dp(FAB_ELEVATION[ui])))
                    menu.invalidateOutline()
                    fab.invalidateOutline()
                    sep.setBackgroundColor(argb(0x33FFFFFF if ui == 1 else (0 if ui == 2 else 0x22000000)))
                except Exception:
                    pass
                try:
                    menu.setBackground(mbg)
                    menu.setClipToOutline(True)
                    fab.setBackground(fbg)
                    fab.setClipToOutline(True)
                except Exception:
                    pass
                try:
                    add_btn.setTextColor(argb(style["text"]))
                except Exception:
                    pass
                try:
                    if ui == 2:
                        pal = md3_palette(style["rgb"], style["text"])
                        fbg.setColor(argb(0xFF000000 | pal["primary"]))
                        if hasattr(icon_view, "setTextColor"):
                            icon_view.setTextColor(argb(0xFF000000 | pal["on_primary"]))
                        elif hasattr(icon_view, "setColorFilter"):
                            icon_view.setColorFilter(argb(0xFF000000 | pal["on_primary"]))
                    scrim.setBackgroundColor(argb(0x52000000 if ui == 2 else 0x40000000))
                except Exception:
                    pass
                try:
                    if hasattr(icon_view, "setTextColor"):
                        icon_view.setTextColor(argb(style["text"]))
                    elif hasattr(icon_view, "setColorFilter"):
                        icon_view.setColorFilter(argb(style["text"]))
                except Exception:
                    pass

            apply_colors()
            refill()
            open_state = {"v": False}

            def layout_menu():
                fl = fab.getLayoutParams()
                fx = int(getattr(fl, "leftMargin", 0) or 0)
                fy = int(getattr(fl, "topMargin", 0) or 0)
                rw = max(1, int(root.getWidth()))
                rh = max(1, int(root.getHeight()))
                left = fx + FAB + dp(8)
                if left + MENU_W > rw - dp(8):
                    left = max(dp(8), fx - MENU_W - dp(8))
                top = max(dp(8), min(rh - MENU_H - dp(8), fy - dp(8)))
                lp = menu.getLayoutParams()
                lp.leftMargin = left
                lp.topMargin = top
                lp.width = MENU_W
                lp.height = MENU_H
                menu.setLayoutParams(lp)

            def _interp():
                try:
                    return jclass("org.telegram.ui.Components.CubicBezierInterpolator").DEFAULT
                except Exception:
                    return None

            def update_menu_blur():
                if SKIN_STATE["v"] != "glass":
                    return
                bmp = capture_blur(root, [menu, scrim, fab])
                if bmp is None:
                    return
                Bitmap = jclass("android.graphics.Bitmap")
                lp = menu.getLayoutParams()
                k = float(bmp.getWidth()) / float(max(1, int(root.getWidth())))
                cx = max(0, int(int(lp.leftMargin) * k))
                cy = max(0, int(int(lp.topMargin) * k))
                cw = min(int(bmp.getWidth()) - cx, int(MENU_W * k))
                ch = min(int(bmp.getHeight()) - cy, int(MENU_H * k))
                if cw <= 0 or ch <= 0:
                    return
                crop = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
                bd = jclass("android.graphics.drawable.BitmapDrawable")(ctx.getResources(), crop)
                bd.setGravity(119)
                dark_g = is_dark_bg(style["text"])
                tg = Gradient()
                tg.setCornerRadius(float(dp(menu_radius())))
                tg.setColor(argb(0xA8000000 | mix_rgb(style["rgb"], 0xFFFFFF, 0.10 if dark_g else 0.45)))
                tg.setStroke(dp(1), argb(0x66FFFFFF if dark_g else 0x55FFFFFF))
                Drawable = jclass("android.graphics.drawable.Drawable")
                menu.setBackground(jclass("android.graphics.drawable.LayerDrawable")(jarray(Drawable)([mbg, bd, tg])))
                menu.setClipToOutline(True)

            def open_menu():
                if open_state["v"]:
                    return
                safe(reload_dialogs)
                refill()
                layout_menu()
                safe(update_menu_blur)
                open_state["v"] = True
                try:
                    fl = fab.getLayoutParams()
                    fx = int(getattr(fl, "leftMargin", 0) or 0)
                    ml = int(getattr(menu.getLayoutParams(), "leftMargin", 0) or 0)
                    menu.setPivotX(float(MENU_W) if ml < fx else 0.0)
                    menu.setPivotY(0.0)
                except Exception:
                    pass
                try:
                    menu.animate().cancel()
                    scrim.animate().cancel()
                    menu.setVisibility(VISIBLE)
                    scrim.setVisibility(VISIBLE)
                    menu.setTranslationX(0.0)
                    menu.setAlpha(1.0)
                    menu.setScaleX(1.0)
                    menu.setScaleY(1.0)
                    menu.setRotation(0.0)
                    if panel_animation == 3:
                        scrim.setAlpha(1.0)
                    else:
                        scrim.setAlpha(0.0)
                        scrim.animate().alpha(1.0).setDuration(180).start()

                    if panel_animation == 0:
                        direction = -1.0 if int(menu.getLeft()) < int(root.getWidth()) // 2 else 1.0
                        menu.setTranslationX(direction * max(menu.getWidth(), MENU_W))
                        menu.animate().translationX(0.0).setDuration(280).setInterpolator(
                            jclass("android.view.animation.DecelerateInterpolator")()
                        ).start()
                    elif panel_animation == 1:
                        menu.setAlpha(0.0)
                        menu.animate().alpha(1.0).setDuration(240).start()
                    elif panel_animation == 2:
                        menu.setScaleX(0.94)
                        menu.setScaleY(0.94)
                        menu.animate().scaleX(1.0).scaleY(1.0).setDuration(420).setInterpolator(
                            jclass("android.view.animation.OvershootInterpolator")(1.35)
                        ).start()
                    elif panel_animation in (4, 6, 7):
                        menu.setAlpha(0.0)
                        menu.setScaleX(0.6)
                        menu.setScaleY(0.6)
                        menu.animate().alpha(1.0).scaleX(1.0).scaleY(1.0).setDuration(320).setInterpolator(
                            jclass("android.view.animation.OvershootInterpolator")(1.2)
                        ).start()

                    elif panel_animation == 5:
                        menu.setAlpha(1.0)
                        menu.setTranslationY(float(-dp(60)))
                        menu.animate().translationY(0.0).setDuration(1000).setInterpolator(ManyBounce()).start()

                    elif panel_animation == 8:
                        menu.setAlpha(0.0)
                        menu.setScaleX(0.05)
                        menu.animate().alpha(1.0).scaleX(1.0).setDuration(380).setInterpolator(
                            jclass("android.view.animation.OvershootInterpolator")(1.5)
                        ).start()
                    elif panel_animation == 3:
                        menu.setVisibility(VISIBLE)
                except Exception:
                    menu.setVisibility(VISIBLE)
                    menu.setTranslationX(0.0)
                    menu.setAlpha(1.0)
                    menu.setScaleX(1.0)
                    menu.setScaleY(1.0)

            def close_menu():
                if not open_state["v"]:
                    menu.setVisibility(GONE)
                    scrim.setVisibility(GONE)
                    return
                open_state["v"] = False
                try:
                    menu.animate().cancel()
                    scrim.animate().cancel()
                    if panel_animation == 3:
                        menu.setVisibility(GONE)
                        scrim.setVisibility(GONE)
                        menu.setTranslationX(0.0)
                        menu.setAlpha(1.0)
                        menu.setScaleX(1.0)
                        menu.setScaleY(1.0)
                        scrim.setAlpha(1.0)
                        return
                    if panel_animation == 0:
                        direction = -1.0 if int(menu.getLeft()) < int(root.getWidth()) // 2 else 1.0
                        anim = menu.animate().translationX(direction * max(menu.getWidth(), MENU_W)).setDuration(220)
                    elif panel_animation == 1:
                        anim = menu.animate().alpha(0.0).setDuration(180)
                    elif panel_animation == 2:
                        anim = menu.animate().scaleX(0.94).scaleY(0.94).setDuration(240)
                    elif panel_animation in (4, 6, 7):
                        anim = menu.animate().alpha(0.0).scaleX(0.8).scaleY(0.8).setDuration(200)
                    elif panel_animation == 5:
                        anim = menu.animate().alpha(0.0).translationY(float(-dp(30))).setDuration(220)
                    elif panel_animation == 8:
                        anim = menu.animate().alpha(0.0).scaleX(0.05).setDuration(200)
                    else:
                        menu.setVisibility(GONE)
                        scrim.setVisibility(GONE)
                        return
                    anim.withEndAction(RunnableProxy(lambda: menu.setVisibility(GONE)))
                    anim.start()
                    scrim.animate().alpha(0.0).setDuration(160).withEndAction(
                        RunnableProxy(lambda: scrim.setVisibility(GONE))
                    ).start()
                except Exception:
                    menu.setVisibility(GONE)
                    scrim.setVisibility(GONE)
                try:
                    menu.postDelayed(RunnableProxy(lambda: (menu.setTranslationX(0.0), menu.setTranslationY(0.0), menu.setAlpha(1.0), menu.setScaleX(1.0), menu.setScaleY(1.0), menu.setRotation(0.0))), 460)
                    scrim.postDelayed(RunnableProxy(lambda: scrim.setAlpha(1.0)), 200)
                except Exception:
                    pass

            def place_fab(x, y, save=False):
                rw = max(1, int(root.getWidth()))
                rh = max(1, int(root.getHeight()))
                x = max(dp(4), min(rw - FAB - dp(4), int(x)))
                y = max(dp(4), min(rh - FAB - dp(4), int(y)))
                lp = fab.getLayoutParams()
                lp.leftMargin = x
                lp.topMargin = y
                fab.setLayoutParams(lp)
                if save:
                    self.set_setting("fab_x", int(x))
                    self.set_setting("fab_y", int(y))

            def init_pos():
                rw = int(root.getWidth())
                rh = int(root.getHeight())
                if rw <= 0 or rh <= 0:
                    root.post(RunnableProxy(init_pos))
                    return
                if fab_x >= 0 and fab_y >= 0:
                    place_fab(fab_x, fab_y)
                else:
                    place_fab(rw - FAB - dp(12), int(rh * 0.4), save=True)

            root.post(RunnableProxy(init_pos))

            drag = {"rx": 0.0, "ry": 0.0, "lx": 0, "ly": 0, "moved": False}

            def on_fab(v, e):
                a = e.getActionMasked()
                if a == 0:
                    drag["rx"] = e.getRawX()
                    drag["ry"] = e.getRawY()
                    lp = fab.getLayoutParams()
                    drag["lx"] = int(getattr(lp, "leftMargin", 0) or 0)
                    drag["ly"] = int(getattr(lp, "topMargin", 0) or 0)
                    drag["moved"] = False
                    try:
                        v.getParent().requestDisallowInterceptTouchEvent(True)
                    except Exception:
                        pass
                elif a == 2:
                    dx = e.getRawX() - drag["rx"]
                    dy = e.getRawY() - drag["ry"]
                    if not drag["moved"] and (abs(dx) > slop or abs(dy) > slop):
                        drag["moved"] = True
                        if open_state["v"]:
                            close_menu()
                    if drag["moved"]:
                        place_fab(drag["lx"] + int(dx), drag["ly"] + int(dy))
                elif a in (1, 3):
                    if drag["moved"]:
                        lp = fab.getLayoutParams()
                        place_fab(int(getattr(lp, "leftMargin", 0) or 0), int(getattr(lp, "topMargin", 0) or 0), save=True)
                    elif a == 1:
                        if open_state["v"]:
                            close_menu()
                        else:
                            open_menu()
                return True

            fab.setOnTouchListener(TouchListener(on_fab))

            def on_scrim_touch(v, e):
                if e.getActionMasked() == 0:
                    close_menu()
                return True

            scrim.setOnTouchListener(TouchListener(on_scrim_touch))

            def restyle():
                read_style()
                apply_colors()
                if open_state["v"]:
                    refill()

            self.sp_restyle = restyle

            def reload_all():
                reload_dialogs()
                if open_state["v"]:
                    refill()

            self.sp_reload = reload_all

            def cleanup_instance():
                try:
                    for v in list(added):
                        root.removeView(v)
                except Exception:
                    pass

            self._sp_cleanups.append(cleanup_instance)
            return

        cont = FrameLayout(ctx)
        cont.setTag(TAG)
        cont.setClipChildren(False)
        feat = {"dock": 0, "bottom": bool(self.get_setting("sfab_bottom", False)), "entries": []}

        wrap = FrameLayout(ctx)
        rr = float(W) if style["ui"] == 1 else float(dp(eff_radius()))
        wbg = Gradient()
        wbg.setCornerRadius(rr)
        wrap.setBackground(wbg)
        wrap.setClipChildren(True)
        wrap.setClipToOutline(True)
        try:
            wrap.setOutlineProvider(jclass("android.view.ViewOutlineProvider").BACKGROUND)
        except Exception:
            pass

        blur_img = jclass("android.widget.ImageView")(ctx)
        blur_img.setScaleType(jclass("android.widget.ImageView$ScaleType").MATRIX)
        blur_img.setVisibility(GONE)
        wrap.addView(blur_img, FLP(MATCH, MATCH))
        rim = Gradient()
        rim.setColor(argb(0))

        def update_glass_blur():
            if SKIN_STATE["v"] != "glass":
                blur_img.setVisibility(GONE)
                return
            bmp = capture_blur(root, [cont, scrim])
            if bmp is None:
                blur_img.setVisibility(GONE)
                return
            arr = jarray(jint)([0, 0])
            rarr = jarray(jint)([0, 0])
            wrap.getLocationInWindow(arr)
            root.getLocationInWindow(rarr)
            x = int(arr[0]) - int(rarr[0]) - int(cont.getTranslationX())
            y = int(arr[1]) - int(rarr[1]) - int(cont.getTranslationY())
            k = float(max(1, int(root.getWidth()))) / float(bmp.getWidth())
            m = jclass("android.graphics.Matrix")()
            m.postScale(k, k)
            m.postTranslate(float(-x), float(-y))
            blur_img.setImageBitmap(bmp)
            blur_img.setImageMatrix(m)
            blur_img.setVisibility(VISIBLE)

        scroll = ScrollView(ctx)
        scroll.setVerticalScrollBarEnabled(False)
        scroll.setClipToPadding(True)
        scroll.setOverScrollMode(OVERSCROLL_NEVER)
        wrap.addView(scroll, FLP(MATCH, MATCH))

        col = LinearLayout(ctx)
        col.setOrientation(LinearLayout.VERTICAL)
        col.setClipChildren(False)
        col.setPadding(0, dp(6), 0, dp(6))
        scroll.addView(col, FLP(MATCH, WRAP))
        cont.addView(wrap, FLP(W, MATCH, GRAV_END))

        def item_w():
            if style["ui"] == 2:
                return W - dp(14)
            return W - dp(10) if style["ui"] == 1 else W - dp(4)

        def card_rgb():
            cont = md3_palette(style["rgb"], style["text"])["cont"]
            return mix_rgb(cont, 0x000000, 0.38) if is_dark_bg(style["text"]) else mix_rgb(cont, 0xFFFFFF, 0.6)

        def show_ind():
            return bool(self.get_setting("show_indicators", True))

        def accent_rgb():
            if style["ui"] == 1:
                return ios_accent(style["text"]) & 0xFFFFFF
            if style["ui"] == 2:
                return md3_palette(style["rgb"], style["text"])["pc"]
            return theme_color("key_chats_actionBackground", 0xFF3390EC) & 0xFFFFFF

        def make_dot_badge(txt, color):
            tv = TextView(ctx)
            tv.setText(txt)
            tv.setTextSize(10.0)
            tv.setTypeface(Typeface.DEFAULT_BOLD)
            tv.setTextColor(argb(0xFFFFFFFF))
            tv.setGravity(GRAV_CENTER)
            tv.setIncludeFontPadding(False)
            g = Gradient()
            g.setShape(OVAL)
            g.setColor(argb(0xFF000000 | color))
            tv.setBackground(g)
            return tv

        def make_item(dlg, did, obj, title, pinned_set=None):
            ui = style["ui"]
            item = LinearLayout(ctx)
            item.setOrientation(LinearLayout.VERTICAL)
            item.setGravity(GRAV_CENTER_H)
            item.setClipChildren(False)
            active = cur_dialog() == did
            if active and ui != 2:
                ab = active_bg(ui, style["rgb"], style["text"])
                if ab is not None:
                    item.setBackground(ab)

            box = FrameLayout(ctx)
            box.setClipChildren(False)
            box.addView(make_avatar(did, obj, AV), FLP(dp(AV), dp(AV)))

            unread = int(dlg.unread_count) if dlg is not None else 0
            if unread > 0:
                if ui == 2:
                    bsize = max(18, S["badge"] + 2)
                    blp = FLP(WRAP, dp(bsize), 53)
                    blp.rightMargin = dp(-6)
                    blp.topMargin = dp(-4)
                elif ui == 1:
                    bsize = S["badge"] + 2
                    blp = FLP(WRAP, dp(bsize), 53)
                    blp.rightMargin = dp(-5)
                    blp.topMargin = dp(-4)
                else:
                    bsize = S["badge"]
                    blp = FLP(WRAP, dp(bsize), GRAV_TOP_LEFT)
                    blp.leftMargin = dp(-2)
                    blp.topMargin = dp(-1)
                box.addView(make_badge(unread, is_muted(did), bsize), blp)
            dot_sz = S["dot_badge"]
            if show_ind() and dlg is not None:
                try:
                    ment = int(getattr(dlg, "unread_mentions_count", 0) or 0)
                    react = int(getattr(dlg, "unread_reactions_count", 0) or 0)
                except Exception:
                    ment = react = 0
                if ment > 0 or react > 0:
                    dot = make_dot_badge("@" if ment > 0 else "\u2665", accent_rgb() if ment > 0 else 0xE53935)
                    dlp = FLP(dp(dot_sz), dp(dot_sz), GRAV_BOTTOM_LEFT)
                    dlp.leftMargin = dp(-3)
                    dlp.bottomMargin = dp(-2)
                    box.addView(dot, dlp)
            pset = pinned_set if pinned_set is not None else set(self.sp_get_pinned(acc))
            if did in pset:
                pdot = make_dot_badge("★", theme_accent())
                p_grav = 53 if ui == 0 else GRAV_TOP_LEFT
                plp = FLP(dp(dot_sz), dp(dot_sz), p_grav)
                if ui == 0:
                    plp.rightMargin = dp(-3)
                else:
                    plp.leftMargin = dp(-3)
                plp.topMargin = dp(-3)
                box.addView(pdot, plp)
            if is_online(did, obj):
                online_sz = S["online_dot"]
                olp = FLP(dp(online_sz), dp(online_sz), GRAV_BOTTOM_RIGHT)
                olp.rightMargin = dp(-1)
                olp.bottomMargin = dp(-1)
                box.addView(make_online_dot(), olp)
            item.addView(box, LP(dp(AV), dp(AV)))

            if bool(self.get_setting("show_titles", True)):
                label = TextView(ctx)
                label.setText(title)
                label.setTextSize(S["text_size"])
                if ui == 2:
                    pal = md3_palette(style["rgb"], style["text"])
                    label.setTextColor(argb(0xFF000000 | (pal["on_secondary"] if active else pal["osv"])))
                    safe(label.setTypeface, Typeface.create("sans-serif-medium", 1 if active else 0))
                elif ui == 1:
                    if active:
                        label.setTextColor(argb(ios_accent(style["text"])))
                    else:
                        label.setTextColor(argb(0xFF000000 | mix_rgb(style["text"] & 0xFFFFFF, style["rgb"], 0.42)))
                else:
                    label.setTextColor(argb(style["text"]))
                label.setLines(1 if ui == 2 else 2)
                label.setEllipsize(TextUtils.TruncateAt.END)
                label.setGravity(GRAV_TOP_CENTER_H)
                avail = item_w()
                try:
                    wmax = max([label.getPaint().measureText(w) for w in str(title).split()] or [0.0])
                    if wmax > avail:
                        label.setTextSize(max(7.5, float(S["text_size"]) * avail / wmax))
                except Exception:
                    pass
                if ui == 2:
                    llp = LP(avail, dp(int(S["text_size"] * 1.4) + 2))
                else:
                    llp = LP(avail, dp(int(S["text_size"] * 2.4) + 2))
                llp.topMargin = dp(4 if ui == 2 else 2)
                item.addView(label, llp)

            item.setOnClickListener(ClickListener(lambda: on_item_click(did, title)))
            item.setOnLongClickListener(LongClickListener(lambda v=item: (touch(), on_item_long_press(v, did, title))[1]))
            attach_swipe(item, did)
            return item

        def refill():
            col.removeAllViews()
            ui = style["ui"]
            entries = collect()
            feat["entries"] = entries
            show_titles = bool(self.get_setting("show_titles", True))
            top_grp, rest_grp, pinned_set = split_pinned_groups(entries)
            if ui == 2:

                col.setPadding(dp(6), dp(8), dp(6), dp(8))
                if show_titles:
                    item_h = dp(AV + 8 + 4 + int(S["text_size"] * 1.4) + 2 + 6)
                else:
                    item_h = dp(AV + 16)
                first = True
                for grp in (top_grp, rest_grp):
                    if not grp:
                        continue
                    card = LinearLayout(ctx)
                    card.setOrientation(LinearLayout.VERTICAL)
                    card.setClipChildren(False)
                    cbg = Gradient()
                    cbg.setCornerRadius(float(dp(22)))
                    cbg.setColor(argb(0xFF000000 | card_rgb()))
                    card.setBackground(cbg)
                    card.setPadding(0, dp(5), 0, dp(5))
                    for dlg, did, obj, title in grp:
                        item = safe(make_item, dlg, did, obj, title, pinned_set)
                        if item is not None:
                            card.addView(item, LP(MATCH, item_h))
                    clp2 = LP(MATCH, WRAP)
                    if not first:
                        clp2.topMargin = dp(9)
                    first = False
                    col.addView(card, clp2)
            else:
                vp = dp(18) if ui == 1 else dp(6)
                col.setPadding(0, vp, 0, vp)
                item_h = dp(S["item_height"]) if show_titles else dp(AV + 16)
                for dlg, did, obj, title in top_grp:
                    item = safe(make_item, dlg, did, obj, title, pinned_set)
                    if item is not None:
                        col.addView(item, LP(MATCH, item_h))
                if top_grp and rest_grp:
                    div, dlp = make_group_divider(10)
                    col.addView(div, dlp)
                for dlg, did, obj, title in rest_grp:
                    item = safe(make_item, dlg, did, obj, title, pinned_set)
                    if item is not None:
                        col.addView(item, LP(MATCH, item_h))

        refill()

        handle = TextView(ctx)
        handle.setGravity(GRAV_CENTER)
        handle.setTypeface(Typeface.DEFAULT_BOLD)
        handle.setTextSize(float(S["handle_height"]) * 0.55)
        handle.setIncludeFontPadding(False)
        hbg = Gradient()
        r = float(HH) / 2.0
        hbg.setCornerRadii([r, r, 0.0, 0.0, 0.0, 0.0, r, r])
        handle.setBackground(hbg)

        gbg = Gradient()
        gbg.setCornerRadius(float(dp(3)))
        g_ih = max(0, (HW - dp(6)) // 2)
        g_iv = max(0, (HH - dp(40)) // 2)
        grabber = InsetDrawable(gbg, g_ih, g_iv, g_ih, g_iv)

        def apply_colors():
            ui = style["ui"]
            alpha = style["alpha"]
            wbg.setCornerRadius(float(W) if ui == 1 else float(dp(eff_radius())))
            paint(wbg, ui, alpha, style["rgb"], style["text"])
            safe(wrap.setElevation, 0.0)
            glass = SKIN_STATE["v"] == "glass"
            if glass:
                dark_g = is_dark_bg(style["text"])
                tint = mix_rgb(style["rgb"], 0xFFFFFF, 0.10 if dark_g else 0.45)
                safe(blur_img.setColorFilter, argb(0xA8000000 | tint), jclass("android.graphics.PorterDuff$Mode").SRC_OVER)
                rim.setCornerRadius(float(W))
                rim.setStroke(dp(1), argb(0x66FFFFFF if dark_g else 0x55FFFFFF))
                safe(wrap.setForeground, rim)
            else:
                blur_img.setVisibility(GONE)
                safe(wrap.setForeground, None)
            paint(hbg, ui, alpha, style["rgb"], style["text"])
            handle.setTextColor(argb(style["text"]))
            gbg.setColor(argb(0x99000000 | 0x8E8E93))
            if ui == 2:
                rr2 = float(dp(max(24, eff_radius())))
                wbg.setCornerRadii([rr2, rr2, 0.0, 0.0, 0.0, 0.0, rr2, rr2])
                handle.setTextColor(argb(0xFF000000 | md3_palette(style["rgb"], style["text"])["osv"]))
            handle.setBackground(grabber if ui == 1 else hbg)
            wrap.setBackground(wbg)
            wrap.setClipToOutline(True)
            safe(wrap.invalidateOutline)
            cb = feat.get("style_cb")
            if cb is not None:
                safe(cb)

        apply_colors()

        def restyle():
            nonlocal corner_radius, panel_animation
            read_style()
            corner_radius = 14
            panel_animation = max(0, min(11, int(self.sp_anim())))
            try:
                voff["v"] = max(-120, min(240, int(self.get_setting("vertical_offset", 0))))
            except Exception:
                pass
            apply_colors()
            refill()
            try:
                refresh_handle()
            except Exception:
                pass
            try:
                lp = cont.getLayoutParams()
                if lp is not None:
                    lp.topMargin = top_px()
                    lp.rightMargin = gap_px()
                    cont.setLayoutParams(lp)
                if not arrow["open"]:
                    set_tx(travel())
                safe(fit)
            except Exception:
                pass
            cb2 = feat.get("restyle_cb")
            if cb2 is not None:
                safe(cb2)

        self.sp_restyle = restyle

        def reload_all():
            reload_dialogs()
            refill()

        self.sp_reload = reload_all

        hlp = FLP(HW, HH, GRAV_START_CENTER_V)
        hlp.topMargin = dp(HANDLE_SHIFT * 2)
        cont.addView(handle, hlp)

        scrim = View(ctx)
        scrim.setVisibility(GONE)
        root.addView(scrim, FLP(MATCH, MATCH))
        added.append(scrim)

        def gap_px():

            return dp(10) if style["ui"] == 1 else 0

        def travel():
            return float(W + gap_px())

        clp = FLP(W + HW, MATCH, GRAV_END)
        clp.rightMargin = gap_px()
        clp.topMargin = top_px()
        clp.bottomMargin = dp(BOTTOM_MARGIN)
        root.addView(cont, clp)
        added.append(cont)

        pos_bar = jarray(jint)([0, 0])
        pos_root = jarray(jint)([0, 0])

        def bottom_limit():
            root_h = int(root.getHeight())
            limit = root_h - dp(BOTTOM_MARGIN)
            top = int(top_px())
            min_h = dp(110)

            bar = safe(fragment.getChatActivityEnterView)
            if bar is not None and bar.getVisibility() == VISIBLE and bar.getHeight() > 0:
                try:
                    bar.getLocationOnScreen(pos_bar)
                    root.getLocationOnScreen(pos_root)
                    bar_bottom = int(pos_bar[1]) - int(pos_root[1]) - dp(6)
                    if bar_bottom > top + min_h:
                        limit = min(limit, bar_bottom)
                except Exception:
                    pass

            try:
                rect = jclass("android.graphics.Rect")()
                root.getWindowVisibleDisplayFrame(rect)
                loc = jarray(jint)([0, 0])
                root.getLocationOnScreen(loc)
                visible_bottom = int(rect.bottom) - int(loc[1]) - dp(6)
                if visible_bottom > top + min_h:
                    limit = min(limit, visible_bottom)
            except Exception:
                pass

            try:
                kh = int(root.getKeyboardHeight())
                keyboard_bottom = root_h - kh - dp(6)
                if kh > dp(80) and keyboard_bottom > top + min_h:
                    limit = min(limit, keyboard_bottom)
            except Exception:
                pass

            return max(top + min_h, limit)

        def place_handle():

            try:
                lp = cont.getLayoutParams()
                H = int(lp.height) if int(lp.height) > 0 else int(cont.getHeight())
                if H <= 0:
                    return
                dock = int(feat.get("dock", 0))
                at_bottom = bool(feat.get("bottom"))
                pt = 0 if at_bottom else dock
                pb = H - (dock if at_bottom else 0)
                half = HH // 2 + dp(6)
                want = H // 2 + dp(HANDLE_SHIFT)
                lo, hi = pt + half, pb - half
                c = (pt + pb) // 2 if lo > hi else max(lo, min(hi, want))
                tm = 2 * (c - H // 2)
                hl = handle.getLayoutParams()
                if int(hl.topMargin) != tm:
                    hl.topMargin = tm
                    handle.setLayoutParams(hl)
            except Exception:
                pass

        def fit():
            if root.getHeight() <= 0:
                return
            lp = cont.getLayoutParams()
            t = top_px()
            nh = bottom_limit() - t
            if nh > 0 and (lp.height != nh or lp.topMargin != t):
                lp.height = nh
                lp.topMargin = t
                cont.setLayoutParams(lp)
            place_handle()

        root.post(RunnableProxy(fit))

        try:
            interpolator = jclass("org.telegram.ui.Components.CubicBezierInterpolator").DEFAULT
        except Exception:
            interpolator = None
        arrow = {"open": None}

        def keyboard_open():
            try:
                return int(root.getKeyboardHeight()) > dp(80)
            except Exception:
                rect = jclass("android.graphics.Rect")()
                root.getWindowVisibleDisplayFrame(rect)
                return root.getRootView().getHeight() - rect.bottom > dp(120)

        keyboard = {"hidden": False}
        frame_state = {"height": -1, "top": -1, "bottom": -1, "keyboard": None}

        def sync_geometry():
            try:
                height = int(root.getHeight())
                if height <= 0:
                    return
                typing = keyboard_open()
                top = int(top_px())
                bottom = int(bottom_limit())
                changed = (frame_state["height"] != height or
                           frame_state["top"] != top or
                           frame_state["bottom"] != bottom or
                           frame_state["keyboard"] != typing)
                if not changed:
                    return
                frame_state["height"] = height
                frame_state["top"] = top
                frame_state["bottom"] = bottom
                frame_state["keyboard"] = typing
                try:
                    sfab.setVisibility(GONE if (typing or sstate.get("open")) else (VISIBLE if search_on() else GONE))
                except Exception:
                    pass
                hidden = bool(hide_on_keyboard and bottom - top < dp(110))
                if hidden != keyboard["hidden"]:
                    keyboard["hidden"] = hidden
                    cont.setVisibility(GONE if hidden else VISIBLE)
                    scrim.setVisibility(VISIBLE if (not hidden and arrow["open"] and close_on_tap) else GONE)
                if not hidden:
                    fit()
            except Exception:
                pass

        root_layout_listener = LayoutChangeListener(sync_geometry)
        root.addOnLayoutChangeListener(root_layout_listener)
        enter_view_listener = None
        try:
            enter_view = fragment.getChatActivityEnterView()
            if enter_view is not None:
                enter_view_listener = LayoutChangeListener(sync_geometry)
                enter_view.addOnLayoutChangeListener(enter_view_listener)
        except Exception:
            enter_view_listener = None

        def cleanup_instance():
            try:
                if enter_view_listener is not None:
                    ev = fragment.getChatActivityEnterView()
                    if ev is not None:
                        ev.removeOnLayoutChangeListener(enter_view_listener)
            except Exception:
                pass
            try:
                root.removeOnLayoutChangeListener(root_layout_listener)
            except Exception:
                pass
            try:
                cont.animate().cancel()
            except Exception:
                pass
            try:
                for v in list(added):
                    root.removeView(v)
            except Exception:
                pass

        self._sp_cleanups.append(cleanup_instance)
        root.post(RunnableProxy(sync_geometry))

        def arrow_text(is_open):
            return "" if style["ui"] == 1 else ("\u203a" if is_open else "\u2039")

        def set_arrow(is_open):
            if arrow["open"] is is_open:
                return
            arrow["open"] = is_open
            handle.setText(arrow_text(is_open))
            cb = feat.get("arrow_cb")
            if cb is not None:
                safe(cb)

        def refresh_handle():
            if arrow["open"] is not None:
                handle.setText(arrow_text(arrow["open"]))
            cb = feat.get("arrow_cb")
            if cb is not None:
                safe(cb)

        def set_tx(x):
            x = max(0.0, min(travel(), x))
            cont.setTranslationX(x)
            set_arrow(x < travel() / 2.0)

        def reset_fx():
            cont.setRotation(0.0)
            cont.setScaleX(1.0)
            cont.setScaleY(1.0)
            cont.setRotationY(0.0)
            cont.setTranslationY(0.0)
            cont.setPivotX(cont.getWidth() / 2.0)
            cont.setPivotY(cont.getHeight() / 2.0)

        def wave_in(vertical=False):
            views = []
            for i in range(col.getChildCount()):
                c = col.getChildAt(i)
                if style["ui"] == 2:
                    for j in range(c.getChildCount()):
                        views.append(c.getChildAt(j))
                else:
                    views.append(c)
            for i, v in enumerate(views[:14]):
                v.setAlpha(0.0)
                if vertical:
                    v.setTranslationY(float(dp(28)))
                    v.animate().alpha(1.0).translationY(0.0).setStartDelay(i * 40).setDuration(260).start()
                else:
                    v.setTranslationX(float(dp(24)))
                    v.animate().alpha(1.0).translationX(0.0).setStartDelay(i * 35).setDuration(240).start()

        def animate_to(x):
            try:
                cont.animate().cancel()
            except Exception:
                pass
            target = max(0.0, min(travel(), float(x)))
            is_open = target < travel() / 2.0
            if is_open and SKIN_STATE["v"] == "glass" and cont.getTranslationX() >= travel() - 2:
                safe(update_glass_blur)
            set_arrow(is_open)
            if is_open:
                touch()
                cont.setAlpha(1.0)
            scrim.setVisibility(VISIBLE if (is_open and close_on_tap) else GONE)
            try:
                reset_fx()
                if panel_animation == 3:
                    set_tx(target)
                    cont.setAlpha(1.0)
                    return

                DecI = jclass("android.view.animation.DecelerateInterpolator")
                OvI = jclass("android.view.animation.OvershootInterpolator")
                anim = cont.animate().setDuration(300)
                if panel_animation in (0, 6):
                    anim.translationX(target).setInterpolator(DecI())
                    if panel_animation == 6:
                        anim.setDuration(260)
                        if is_open:
                            wave_in()
                elif panel_animation == 1:
                    if is_open:
                        set_tx(0.0)
                        cont.setAlpha(0.0)
                        anim.alpha(1.0)
                    else:
                        cont.setAlpha(1.0)
                        anim.alpha(0.0).withEndAction(
                            RunnableProxy(lambda: (set_tx(travel()), cont.setAlpha(1.0)))
                        )
                elif panel_animation == 2:
                    if is_open:
                        cont.setScaleX(0.94)
                        cont.setScaleY(0.94)
                        anim.translationX(target).scaleX(1.0).scaleY(1.0).setInterpolator(OvI(1.15))
                    else:
                        anim.translationX(target).scaleX(1.0).scaleY(1.0).setInterpolator(DecI())
                elif panel_animation == 4:
                    cont.setPivotX(float(cont.getWidth()))
                    cont.setPivotY(cont.getHeight() / 2.0)
                    if is_open:
                        set_tx(0.0)
                        cont.setAlpha(0.0)
                        cont.setScaleX(0.6)
                        cont.setScaleY(0.6)
                        anim.setDuration(340).alpha(1.0).scaleX(1.0).scaleY(1.0).setInterpolator(OvI(1.3))
                    else:
                        anim.setDuration(220).alpha(0.0).scaleX(0.6).scaleY(0.6).withEndAction(
                            RunnableProxy(lambda: (set_tx(travel()), cont.setAlpha(1.0), reset_fx()))
                        )
                elif panel_animation == 5:
                    if is_open:
                        set_tx(0.0)
                        cont.setAlpha(1.0)
                        cont.setTranslationY(-cont.getHeight() * 0.4)
                        anim.setDuration(1000).translationY(0.0).setInterpolator(ManyBounce())
                    else:
                        anim.setDuration(240).alpha(0.0).translationY(-cont.getHeight() * 0.3).withEndAction(
                            RunnableProxy(lambda: (set_tx(travel()), cont.setAlpha(1.0), reset_fx()))
                        )
                elif panel_animation == 7:
                    try:
                        cont.setCameraDistance(float(AndroidUtilities.density) * 9000.0)
                    except Exception:
                        pass
                    cont.setPivotX(float(cont.getWidth()))
                    cont.setPivotY(cont.getHeight() / 2.0)
                    if is_open:
                        set_tx(0.0)
                        cont.setAlpha(0.2)
                        cont.setRotationY(75.0)
                        anim.setDuration(380).alpha(1.0).rotationY(0.0).setInterpolator(DecI())
                    else:
                        anim.setDuration(260).alpha(0.0).rotationY(75.0).withEndAction(
                            RunnableProxy(lambda: (set_tx(travel()), cont.setAlpha(1.0), reset_fx()))
                        )
                elif panel_animation == 8:
                    cont.setPivotX(float(cont.getWidth()))
                    cont.setPivotY(cont.getHeight() / 2.0)
                    if is_open:
                        set_tx(0.0)
                        cont.setAlpha(0.0)
                        cont.setScaleX(0.05)
                        anim.setDuration(380).alpha(1.0).scaleX(1.0).setInterpolator(OvI(1.6))
                    else:
                        anim.setDuration(220).alpha(0.0).scaleX(0.05).withEndAction(
                            RunnableProxy(lambda: (set_tx(travel()), cont.setAlpha(1.0), reset_fx()))
                        )
                anim.start()
            except Exception:
                set_tx(target)
                cont.setAlpha(1.0)
                reset_fx()

        def toggle():
            if cont.getTranslationX() >= travel() / 2.0:
                fit()
                safe(reload_dialogs)
                refill()
                animate_to(0.0)
            else:
                animate_to(travel())

        drag = {"x0": 0.0, "base": 0.0, "moved": False}

        def on_handle(v, e):
            action = e.getActionMasked()
            if action == 0:
                drag["x0"] = e.getRawX()
                drag["base"] = float(cont.getTranslationX())
                drag["moved"] = False
                cont.animate().cancel()
                cont.setAlpha(1.0)
                reset_fx()
                touch()
                v.getParent().requestDisallowInterceptTouchEvent(True)
            elif action == 2:
                dx = e.getRawX() - drag["x0"]
                if not drag["moved"] and abs(dx) > slop:
                    drag["moved"] = True
                    if drag["base"] >= travel() / 2.0:
                        fit()
                        safe(reload_dialogs)
                        refill()
                if drag["moved"]:
                    set_tx(drag["base"] + dx)
            elif action in (1, 3):
                if drag["moved"]:
                    animate_to(0.0 if cont.getTranslationX() < travel() / 2.0 else travel())
                elif action == 1:
                    toggle()
            return True

        handle.setOnTouchListener(TouchListener(on_handle))

        def on_scrim(v, e):
            if e.getActionMasked() == 0:
                animate_to(travel())
            return True

        scrim.setOnTouchListener(TouchListener(on_scrim))


        System = jclass("java.lang.System")
        activity = {"t": int(System.currentTimeMillis()), "dimmed": False}

        def dim_alpha():
            try:
                return max(0.05, min(0.7, int(self.get_setting("idle_alpha", 20)) / 100.0))
            except Exception:
                return 0.2

        def dim_views():
            return [cont]

        def set_dim(on):
            activity["dimmed"] = bool(on)
            target = float(dim_alpha() if on else 1.0)
            for v in dim_views():
                try:
                    v.animate().alpha(target).setDuration(700 if on else 150).start()
                except Exception:
                    v.setAlpha(target)

        def touch():
            activity["t"] = int(System.currentTimeMillis())
            if activity["dimmed"]:
                set_dim(False)

        def toast(msg):
            safe(lambda: jclass("android.widget.Toast").makeText(ctx, msg, 0).show())


        def selected_msgs():
            ids = get_private_field(fragment, "selectedMessagesIds") if get_private_field is not None else None
            out = []
            if ids is None:
                return out
            for a in (0, 1):
                arr = ids[a]
                for i in range(arr.size()):
                    out.append(arr.valueAt(i))
            out.sort(key=lambda m: int(m.getId()))
            return out

        def forward_selected(did):
            try:
                msgs = selected_msgs()
                if not msgs:
                    return
                helper = jclass("org.telegram.messenger.SendMessagesHelper").getInstance(acc)
                lst = jclass("java.util.ArrayList")()
                for m in msgs:
                    lst.add(m)
                BoolC = jclass("java.lang.Boolean")
                IntC = jclass("java.lang.Integer")
                LongC = jclass("java.lang.Long")
                done = False
                for m in helper.getClass().getMethods():
                    if str(m.getName()) != "sendMessage":
                        continue
                    pts = m.getParameterTypes()
                    if len(pts) < 5 or str(pts[0].getName()) != "java.util.ArrayList" or str(pts[1].getName()) != "long":
                        continue
                    flags = [False, False, True]
                    bi = 0
                    args = []
                    for idx, pt in enumerate(pts):
                        n = str(pt.getName())
                        if idx == 0:
                            args.append(lst)
                        elif idx == 1:
                            args.append(LongC(int(did)))
                        elif n == "boolean":
                            args.append(BoolC(flags[bi] if bi < 3 else False))
                            bi += 1
                        elif n == "int":
                            args.append(IntC(0))
                        elif n == "long":
                            args.append(LongC(0))
                        else:
                            args.append(None)
                    m.invoke(helper, *args)
                    done = True
                    break
                if done:
                    safe(fragment.clearSelectionMode)
                    toast(self._tr("переслано"))
                else:
                    toast(self._tr("не удалось переслать"))
            except Exception:
                log(traceback.format_exc())
                toast(self._tr("не удалось переслать"))

        def on_item_click(did, title):
            touch()
            if bool(self.get_setting("forward_here", True)):
                try:
                    n = len(selected_msgs())
                except Exception:
                    n = 0
                if n > 0:
                    def pick(which):
                        if int(which) == 0:
                            forward_selected(did)
                        else:
                            open_chat(did)
                    show_sheet(ctx, "%s: %s (%d)" % (self._tr("переслать"), title, n),
                               [self._tr("переслать"), self._tr("открыть чат")], pick)
                    return
            open_chat(did)

        scroll_listener_ok = False
        try:
            class _ScrollL(dynamic_proxy(jclass("android.view.View$OnScrollChangeListener"))):
                def onScrollChange(self, v, sx, sy, ox, oy):
                    touch()
            scroll.setOnScrollChangeListener(_ScrollL())
        except Exception:
            pass
        try:
            scroll.setOnTouchListener(TouchListener(lambda v, e: (touch(), False)[1]))
        except Exception:
            pass


        SFD = dp(S["search_fab"])
        GRAV_TOP_END = GRAV_END | 48
        sfab = FrameLayout(ctx)
        sbg = Gradient()
        sfab.setBackground(sbg)
        sfab.setClipToOutline(True)
        try:
            sfab.setOutlineProvider(jclass("android.view.ViewOutlineProvider").BACKGROUND)
        except Exception:
            pass
        sicon = None
        try:
            ImageViewC = jclass("android.widget.ImageView")
            sicon = ImageViewC(ctx)
            res = ctx.getResources()
            rid = 0
            for name in ("msg_search", "ic_ab_search", "search_menu"):
                rid = int(res.getIdentifier(name, "drawable", ctx.getPackageName()) or 0)
                if rid:
                    break
            if rid:
                sicon.setImageResource(rid)
                safe(sicon.setScaleType, getattr(ImageViewC.ScaleType, "CENTER_INSIDE", None))
                sicon.setPadding(dp(11), dp(11), dp(11), dp(11))
                sfab.addView(sicon, FLP(MATCH, MATCH))
            else:
                sicon = None
        except Exception:
            sicon = None
        if sicon is None:
            sglyph = TextView(ctx)
            sglyph.setText("\u2315")
            sglyph.setTextSize(24.0)
            sglyph.setGravity(GRAV_CENTER)
            sfab.addView(sglyph, FLP(MATCH, MATCH))
        cont.addView(sfab, FLP(SFD, SFD, GRAV_TOP_END))

        added.append(sfab)

        def search_on():
            return bool(self.get_setting("search_fab", True))

        def dock_px():
            if not search_on():
                return 0
            return SFD + dp(8)

        def set_search_icon(icol):
            if sicon is not None:
                safe(sicon.setColorFilter, argb(icol))
            else:
                safe(sglyph.setTextColor, argb(icol))

        GRAV_BOTTOM_END = GRAV_END | 80

        def apply_search_style():
            ui = style["ui"]
            on = search_on()
            bottom = bool(feat.get("bottom"))
            dock = dock_px()
            feat["dock"] = dock
            try:
                wl = wrap.getLayoutParams()
                wl.topMargin = 0 if bottom else dock
                wl.bottomMargin = dock if bottom else 0
                wrap.setLayoutParams(wl)
            except Exception:
                pass
            safe(place_handle)
            sfab.setVisibility(VISIBLE if on else GONE)
            if not on:
                return
            sl = sfab.getLayoutParams()
            sl.gravity = GRAV_BOTTOM_END if bottom else GRAV_TOP_END
            if ui == 2:
                pal = md3_palette(style["rgb"], style["text"])
                sl.width = SFD
                sl.height = SFD
                sl.rightMargin = max(0, (W - SFD) // 2)
                sbg.setShape(OVAL)
                sbg.setColor(argb(0xFF000000 | pal["primary"]))
                sbg.setStroke(0, argb(0))
                safe(sfab.setElevation, float(dp(3)))
                icol = 0xFF000000 | pal["on_primary"]
            else:
                sl.width = SFD
                sl.height = SFD
                sl.rightMargin = (W - SFD) // 2
                sbg.setShape(OVAL)
                paint(sbg, ui, 255, style["rgb"], style["text"])
                safe(sfab.setElevation, float(dp(4 if ui == 0 else 6)))
                icol = style["text"]
            sfab.setLayoutParams(sl)
            safe(sfab.invalidateOutline)
            set_search_icon(icol)

        feat["style_cb"] = apply_search_style


        sdrag = {"y0": 0.0, "by": 0.0, "down": False, "active": False, "moved": False, "gen": 0,
                 "ly": 0.0}
        try:
            LONG_PRESS_MS = int(jclass("android.view.ViewConfiguration").getLongPressTimeout())
        except Exception:
            LONG_PRESS_MS = 450

        def sfab_range():

            h = int(sfab.getHeight()) or SFD
            span = max(0, int(cont.getHeight()) - h)
            return (-span, 0) if feat.get("bottom") else (0, span)

        def lift_look():

            if style["ui"] != 2:
                safe(sfab.setElevation, float(dp(8)))
                return
            sl = sfab.getLayoutParams()
            sl.width = SFD
            sl.height = SFD
            sl.rightMargin = (W - SFD) // 2
            pal = md3_palette(style["rgb"], style["text"])
            sbg.setShape(0)
            sbg.setCornerRadius(float(dp(16)))
            sbg.setColor(argb(0xFF000000 | pal["primary"]))
            sbg.setStroke(0, argb(0))
            sfab.setLayoutParams(sl)
            safe(sfab.setElevation, float(dp(8)))
            safe(sfab.invalidateOutline)
            set_search_icon(0xFF000000 | pal["on_primary"])

            def rebase():
                sdrag["by"] = float(sfab.getTranslationY())
                sdrag["y0"] = sdrag["ly"]
            sfab.post(RunnableProxy(rebase))

        def sfab_lift(on):
            try:
                k = 1.12 if on else 1.0
                sfab.animate().scaleX(k).scaleY(k).setDuration(120).start()
            except Exception:
                pass

        def finish_search_drag():
            sdrag["active"] = False
            try:
                sfab.animate().cancel()
            except Exception:
                pass
            sfab.setScaleX(1.0)
            sfab.setScaleY(1.0)
            ch = int(cont.getHeight())
            h_cur = int(sfab.getHeight()) or SFD
            ty = float(sfab.getTranslationY())
            was_bottom = bool(feat.get("bottom"))
            vis_top = (ch - h_cur + ty) if was_bottom else ty
            to_bottom = (vis_top + h_cur / 2.0) > ch / 2.0
            feat["bottom"] = to_bottom
            safe(self.set_setting, "sfab_bottom", bool(to_bottom))
            safe(apply_colors)
            h_new = SFD
            nat = (ch - h_new) if to_bottom else 0
            sfab.setTranslationY(float(vis_top - nat))
            sfab.animate().translationY(0.0).setDuration(200).start()

        def reset_search_pos():
            feat["bottom"] = False
            safe(self.set_setting, "sfab_bottom", False)
            sfab.setTranslationY(0.0)
            safe(apply_colors)

        self.sp_dock_search = lambda: root.post(RunnableProxy(reset_search_pos))

        def on_search_touch(v, e):
            a = e.getActionMasked()
            if a == 0:
                sdrag["gen"] += 1
                gen = sdrag["gen"]
                sdrag["down"] = True
                sdrag["active"] = False
                sdrag["moved"] = False
                sdrag["y0"] = e.getRawY()
                sdrag["ly"] = sdrag["y0"]
                sdrag["by"] = float(sfab.getTranslationY())
                touch()
                v.getParent().requestDisallowInterceptTouchEvent(True)

                def arm():
                    if sdrag["gen"] == gen and sdrag["down"] and not sdrag["moved"]:
                        sdrag["active"] = True
                        safe(sfab.performHapticFeedback, 0)
                        safe(lift_look)
                        sfab_lift(True)

                root.postDelayed(RunnableProxy(arm), LONG_PRESS_MS)
            elif a == 2:
                sdrag["ly"] = e.getRawY()
                dy = e.getRawY() - sdrag["y0"]
                if not sdrag["active"]:

                    if abs(dy) > slop:
                        sdrag["moved"] = True
                    return True
                lo, hi = sfab_range()
                sfab.setTranslationY(float(max(lo, min(hi, sdrag["by"] + dy))))
                touch()
            elif a in (1, 3):
                sdrag["down"] = False
                sdrag["gen"] += 1
                if sdrag["active"]:
                    finish_search_drag()
                elif a == 1 and not sdrag["moved"]:
                    open_search()
            return True

        sfab.setOnTouchListener(TouchListener(on_search_touch))


        EditTextC = jclass("android.widget.EditText")
        sscrim = View(ctx)
        sscrim.setVisibility(GONE)
        root.addView(sscrim, FLP(MATCH, MATCH))
        added.append(sscrim)

        spanel = LinearLayout(ctx)
        spanel.setOrientation(LinearLayout.VERTICAL)
        spanel.setVisibility(GONE)
        sbg2 = Gradient()
        spanel.setBackground(sbg2)
        spanel.setClipToOutline(True)
        try:
            spanel.setOutlineProvider(jclass("android.view.ViewOutlineProvider").BACKGROUND)
        except Exception:
            pass
        edit = EditTextC(ctx)
        edit.setHint(self._tr("поиск чатов"))
        edit.setSingleLine(True)
        edit.setTextSize(16.5)
        edit.setBackgroundColor(argb(0))
        edit.setPadding(dp(16), dp(13), dp(16), dp(13))
        spanel.addView(edit, LP(MATCH, WRAP))
        res_scroll = ScrollView(ctx)
        res_scroll.setVerticalScrollBarEnabled(False)
        res_col = LinearLayout(ctx)
        res_col.setOrientation(LinearLayout.VERTICAL)
        res_col.setPadding(dp(4), 0, dp(4), dp(8))
        res_scroll.addView(res_col, FLP(MATCH, WRAP))
        spanel.addView(res_scroll, LP(MATCH, dp(308)))
        root.addView(spanel, FLP(dp(352), WRAP, GRAV_TOP_CENTER_H))
        added.append(spanel)
        sstate = {"open": False}

        def apply_search_panel_style():
            ui = style["ui"]
            rad = 28 if ui == 2 else (20 if ui == 1 else 16)
            sbg2.setCornerRadius(float(dp(rad)))
            paint(sbg2, ui, 255, style["rgb"], style["text"])
            safe(spanel.setElevation, float(dp(12)))
            safe(spanel.invalidateOutline)
            safe(edit.setTextColor, argb(style["text"]))
            safe(edit.setHintTextColor, argb(0x88FFFFFF if is_dark_bg(style["text"]) else 0x88000000))

        def refresh_results():
            q = str(edit.getText() or "").strip().lower()
            res_col.removeAllViews()
            n = 0
            for dlg, did, obj, title in list(dialog_cache):
                t = str(title or "")
                un = str(getattr(obj, "username", "") or "")
                if q and q not in t.lower() and q not in un.lower():
                    continue
                row = LinearLayout(ctx)
                row.setOrientation(LinearLayout.HORIZONTAL)
                row.setGravity(GRAV_CENTER_V)
                row.setPadding(dp(11), dp(7), dp(11), dp(7))
                row.addView(make_avatar(did, obj, 40), LP(dp(40), dp(40)))
                tv = TextView(ctx)
                tv.setText(t)
                tv.setTextSize(16.5)
                tv.setTextColor(argb(style["text"]))
                tv.setLines(1)
                tv.setEllipsize(TextUtils.TruncateAt.END)
                tlp = LP(0, WRAP)
                tlp.weight = 1.0
                tlp.leftMargin = dp(12)
                row.addView(tv, tlp)
                rp = ripple(0x1F000000 | (style["text"] & 0xFFFFFF), dp(12))
                if rp is not None:
                    row.setForeground(rp) if hasattr(row, "setForeground") else None
                row.setOnClickListener(ClickListener(lambda d=did: (close_search(), open_chat(d))))
                res_col.addView(row, LP(MATCH, WRAP))
                n += 1
                if n >= 40:
                    break
            if n == 0:
                empty = TextView(ctx)
                empty.setText(self._tr("ничего не найдено"))
                empty.setTextSize(14.0)
                empty.setTextColor(argb(style["text"]))
                empty.setGravity(GRAV_CENTER)
                empty.setPadding(dp(8), dp(20), dp(8), dp(20))
                res_col.addView(empty, LP(MATCH, WRAP))

        TextWatcherC = jclass("android.text.TextWatcher")

        class _SearchTW(dynamic_proxy(TextWatcherC)):
            def beforeTextChanged(self, s_, a, b, c):
                pass
            def onTextChanged(self, s_, a, b, c):
                safe(refresh_results)
            def afterTextChanged(self, s_):
                pass

        edit.addTextChangedListener(_SearchTW())

        def open_search():
            if sstate["open"]:
                return
            safe(reload_dialogs)
            sstate["open"] = True
            apply_search_panel_style()
            try:
                lp = spanel.getLayoutParams()
                lp.width = max(dp(240), min(dp(352), int(root.getWidth()) - dp(24)))
                lp.topMargin = max(dp(8), int(top_px()) - dp(8))
                spanel.setLayoutParams(lp)
            except Exception:
                pass
            edit.setText("")
            refresh_results()
            sscrim.setVisibility(VISIBLE)
            spanel.setAlpha(0.0)
            spanel.setScaleX(0.94)
            spanel.setScaleY(0.94)
            spanel.setVisibility(VISIBLE)
            try:
                spanel.animate().alpha(1.0).scaleX(1.0).scaleY(1.0).setDuration(180).start()
            except Exception:
                spanel.setAlpha(1.0)
                spanel.setScaleX(1.0)
                spanel.setScaleY(1.0)
            root.postDelayed(RunnableProxy(lambda: (edit.requestFocus(), AndroidUtilities.showKeyboard(edit))), 120)

        def close_search():
            if not sstate["open"]:
                return
            sstate["open"] = False
            safe(AndroidUtilities.hideKeyboard, edit)
            sscrim.setVisibility(GONE)
            try:
                sfab.setVisibility(VISIBLE if search_on() else GONE)
            except Exception:
                pass
            try:
                spanel.animate().alpha(0.0).setDuration(130).withEndAction(
                    RunnableProxy(lambda: spanel.setVisibility(GONE))
                ).start()
            except Exception:
                spanel.setVisibility(GONE)

        def on_sscrim(v, e):
            if e.getActionMasked() == 0:
                close_search()
            return True

        sscrim.setOnTouchListener(TouchListener(on_sscrim))


        hbadge = make_dot_badge("0", 0xE53935)
        hbadge.setPadding(dp(4), 0, dp(4), 0)
        hbadge.setMinWidth(dp(18))
        hbadge.setVisibility(GONE)
        hblp = FLP(WRAP, dp(18), GRAV_START_CENTER_V)
        hblp.topMargin = max(0, dp(HANDLE_SHIFT * 2) - HH // 2 - dp(11))
        cont.addView(hbadge, hblp)

        def update_handle_badge():
            total = 0
            if show_ind():
                for dlg, did, obj, title in feat.get("entries", []):
                    try:
                        if dlg is not None and not is_muted(did):
                            total += int(dlg.unread_count)
                    except Exception:
                        pass
            if total > 0 and not arrow["open"]:
                hbadge.setText("99+" if total > 99 else str(total))
                g = Gradient()
                g.setCornerRadius(float(dp(9)))
                g.setColor(argb(0xFF000000 | accent_rgb()))
                hbadge.setBackground(g)
                hbadge.setVisibility(VISIBLE)
            else:
                hbadge.setVisibility(GONE)


        edge = View(ctx)
        edge.setVisibility(GONE)
        root.addView(edge, FLP(dp(14), MATCH, GRAV_END))
        added.append(edge)
        edrag = {"x0": 0.0, "moved": False}

        def update_edge():
            on = bool(self.get_setting("edge_swipe", False)) and not arrow["open"]
            edge.setVisibility(VISIBLE if on else GONE)
            try:
                cl = cont.getLayoutParams()
                el = edge.getLayoutParams()
                if el.topMargin != cl.topMargin or el.height != cl.height:
                    el.topMargin = cl.topMargin
                    el.height = cl.height
                    edge.setLayoutParams(el)
            except Exception:
                pass

        def on_edge(v, e):
            a = e.getActionMasked()
            if a == 0:
                edrag["x0"] = e.getRawX()
                edrag["moved"] = False
                try:
                    cont.animate().cancel()
                except Exception:
                    pass
                v.getParent().requestDisallowInterceptTouchEvent(True)
                return True
            if a == 2:
                dx = edrag["x0"] - e.getRawX()
                if not edrag["moved"] and dx > slop:
                    edrag["moved"] = True
                    fit()
                    safe(reload_dialogs)
                    refill()
                    reset_fx()
                    cont.setAlpha(1.0)
                if edrag["moved"]:
                    set_tx(travel() - dx)
                return True
            if a in (1, 3):
                if edrag["moved"]:
                    animate_to(0.0 if cont.getTranslationX() < travel() * 0.6 else travel())
                return True
            return True

        edge.setOnTouchListener(TouchListener(on_edge))

        def arrow_cb():
            safe(update_handle_badge)
            safe(update_edge)

        feat["arrow_cb"] = arrow_cb

        def restyle_cb():
            safe(apply_search_panel_style)
            safe(update_handle_badge)
            safe(update_edge)
            if not search_on():
                safe(close_search)

        feat["restyle_cb"] = restyle_cb
        safe(apply_search_style)
        safe(update_handle_badge)


        tick_state = {"alive": True}

        def tick():
            if not tick_state["alive"]:
                return
            try:
                update_handle_badge()
                update_edge()
                mode = max(0, min(3, int(self.get_setting("idle_mode", 1))))
                busy = drag["moved"] or sstate["open"] or sdrag["active"]
                if mode and not busy:
                    secs = max(3, min(60, int(self.get_setting("idle_seconds", 10))))
                    if int(System.currentTimeMillis()) - activity["t"] > secs * 1000:
                        if mode in (2, 3) and arrow["open"]:
                            animate_to(travel())
                        elif mode in (1, 3):
                            if not activity["dimmed"]:
                                set_dim(True)
                            elif cont.getAlpha() > dim_alpha() + 0.25:
                                cont.setAlpha(float(dim_alpha()))
                elif not mode and activity["dimmed"]:
                    set_dim(False)
            except Exception:
                pass
            root.postDelayed(RunnableProxy(tick), 1000)

        root.postDelayed(RunnableProxy(tick), 1000)
        self._sp_cleanups.append(lambda: tick_state.update(alive=False))
        set_tx(travel())
