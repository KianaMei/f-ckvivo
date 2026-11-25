"""
归档即全隐 — 聊天一旦归档，就从所有自定义文件夹里消失（不只是「全部」）。

Telegram 原生逻辑（MessagesController$DialogFilter.includesDialog）：
  neverShow → alwaysShow（手动加入的聊天直接放行，不看归档）→ EXCLUDE_ARCHIVED 标志 → 类型条件。
所以归档只影响「全部」，自定义文件夹照样显示归档聊天。

本插件做法（不改服务器上的文件夹设置，纯本地）：
1. sortDialogs / updateFilterDialogs 跑完后，把归档列表 (dialogsByFolder[1]) 整体
   从当前文件夹的 dialogs / dialogsForward 里 removeAll 掉——一次 Java 调用，无逐条 Python 循环，
   不会撞 ExteraLess 250ms/s 主线程看门狗。
2. calcUnreadCounters 期间临时给每个文件夹补 EXCLUDE_ARCHIVED，结束立即还原，
   让文件夹标签的未读角标也不再算归档聊天（手动加入且已归档的聊天角标仍可能被计入，属原生计数另一分支）。

兼容：ExteraLess 13.1.x、NagramXF 13.2.x（exteraGram 兼容插件引擎）。
"""

from base_plugin import BasePlugin, MethodHook
from java import jclass

__id__ = "folder_hide_archived"
__name__ = "归档即全隐"
__description__ = "聊天归档后从所有文件夹中隐藏，而不仅是「全部」。纯本地，不改服务器文件夹设置。"
__author__ = "ganvivo"
__version__ = "1.0.0"
__min_version__ = "13.1.0"
__app_version__ = ">=13.1.0"
__sdk_version__ = ">=1.4.5.0"
__permissions__ = ["hooks", "messages.read"]  # messages.read：ExteraLess 反射 MessagesStorage（角标修正）所需

_MC = "org.telegram.messenger.MessagesController"
_MS = "org.telegram.messenger.MessagesStorage"
_FLAG_EXCLUDE_ARCHIVED = 128  # MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_ARCHIVED
_ARCHIVE_FOLDER_ID = 1


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


class FolderHideArchived(BasePlugin):
    def __init__(self):
        super().__init__()
        self._unhooks = []
        self._HashSet = None
        self._f_ms_filters = None
        self._tls = None
        self.status = []

    # ---------- 核心 ----------

    def _strip(self, mc, flt):
        if flt is None or flt.isDefault():
            return
        archived = mc.dialogsByFolder.get(_ARCHIVE_FOLDER_ID)
        if archived is None or archived.isEmpty():
            return
        s = self._HashSet(archived)
        flt.dialogs.removeAll(s)
        flt.dialogsForward.removeAll(s)

    def _after_sort(self, param):
        mc = param.thisObject
        sel = mc.selectedDialogFilter
        if sel is None:
            return
        for i in range(len(sel)):
            self._strip(mc, sel[i])

    def _after_update_filter(self, param):
        self._strip(param.thisObject, param.args[0])

    def _before_calc(self, param):
        filters = self._f_ms_filters.get(param.thisObject)
        patched = []
        if filters is not None:
            for i in range(filters.size()):
                f = filters.get(i)
                if f is not None and (f.flags & _FLAG_EXCLUDE_ARCHIVED) == 0:
                    f.flags = f.flags | _FLAG_EXCLUDE_ARCHIVED
                    patched.append(f)
        self._tls.patched = patched

    def _after_calc(self, param):
        patched = getattr(self._tls, "patched", None)
        self._tls.patched = None
        if patched:
            for f in patched:
                f.flags = f.flags & ~_FLAG_EXCLUDE_ARCHIVED

    # ---------- 安装 ----------

    def _hook_all(self, cls_name, method_name, before=None, after=None):
        try:
            cls = jclass("java.lang.Class").forName(cls_name)
            methods = cls.getDeclaredMethods()
        except Exception as e:
            self.status.append("%s: 类不可用 %s" % (method_name, e))
            return 0
        n = 0
        for m in methods:
            if str(m.getName()) != method_name:
                continue
            try:
                m.setAccessible(True)
                u = self.hook_method(m, _Hook(before, after))
                if u is not None:
                    self._unhooks.append(u)
                    n += 1
            except Exception as e:
                self.status.append("%s: hook 失败 %s" % (method_name, e))
        self.status.append("%s: %d" % (method_name, n))
        return n

    def on_plugin_load(self):
        import threading
        self._tls = threading.local()
        self._HashSet = jclass("java.util.HashSet")
        self.status = []

        self._hook_all(_MC, "sortDialogs", after=self._after_sort)
        self._hook_all(_MC, "updateFilterDialogs", after=self._after_update_filter)

        try:
            f = jclass("java.lang.Class").forName(_MS).getDeclaredField("dialogFilters")
            f.setAccessible(True)
            self._f_ms_filters = f
            self._hook_all(_MS, "calcUnreadCounters", before=self._before_calc, after=self._after_calc)
        except Exception as e:
            self.status.append("角标修正未启用: %s" % e)

        try:
            self.log("[folder_hide_archived] " + " | ".join(self.status))
        except Exception:
            pass
        self._refresh()

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
        self._refresh()

    def _refresh(self):
        """让当前账号立即重排一次列表，装上/卸下即时生效。"""
        try:
            mc_cls = jclass(_MC)
            acc = jclass("org.telegram.messenger.UserConfig").selectedAccount
            mc = mc_cls.getInstance(acc)
            AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
            NC = jclass("org.telegram.messenger.NotificationCenter")
            from java import dynamic_proxy
            Runnable = jclass("java.lang.Runnable")

            class _R(dynamic_proxy(Runnable)):
                def run(self_inner):
                    try:
                        mc.sortDialogs(None)
                        NC.getInstance(acc).postNotificationName(NC.dialogsNeedReload)
                    except Exception:
                        pass

            AndroidUtilities.runOnUIThread(_R())
        except Exception:
            pass
