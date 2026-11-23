import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


BEANS = "Lcom/vivo/intentservice/bean/"
DRAG_BEANS = "Lcom/vivo/common/dragdrop/data/bean/"
STRING = "Ljava/lang/String;"
LIST = "Ljava/util/List;"
DRAWABLE = "Landroid/graphics/drawable/Drawable;"
INPUT = DRAG_BEANS + "TransIslandInfo;"
SERVICE = BEANS + "IntentionService;"
CONFIG = BEANS + "IntentionServiceBaseConfig;"
INFO = BEANS + "IntentionServiceInfo;"
APP = DRAG_BEANS + "AIEAppInfo;"

EXPECTED = {
    "Lyb/f;": {"a(Landroid/os/Message;)V", "handleMessage(Landroid/os/Message;)V"},
    "Lvb/p;": {f"h({STRING}{STRING})Z", "g(Landroid/content/Context;Z)V",
               f"r(){DRAG_BEANS}DragInfo;", "n:Ljava/lang/ref/WeakReference;", f"d:{STRING}"},
    DRAG_BEANS + "DragInfo;": {f"getCurrentWidgetItem(){DRAG_BEANS}WidgetItemBean;",
        "getUri()Landroid/net/Uri;", f"setWidgetType({STRING})V", f"setType({STRING})V",
        f"setUri(Landroid/net/Uri;)V", "setLocalBitmap(Landroid/graphics/Bitmap;)V"},
    "Lcom/vivo/dragdrop/service/DragService;": {"j(I)V"},
    DRAG_BEANS + "WidgetItemBean;": {f"getPackageName(){STRING}"},
    "Lvb/o;": {"p(Ljava/lang/Object;)Ljava/lang/Object;", "k:Landroid/os/Message;"},
    "Lbb/a;": {f"a({STRING}{STRING})Ljava/lang/Object;", f"c(){LIST}"},
    "Ljh/z;": {"run()V"},
    "Lkb/i1;": {f"b(Landroid/content/Context;{STRING})Z"},
    "Lne/f;": {f"a({INPUT}{SERVICE}Lee/a;)V"},
    "Lge/a;": {f"b({INPUT}{STRING}{SERVICE})Ljava/lang/Boolean;"},
    "Lhe/n;": {f"q({CONFIG})Z"},
    INPUT: {"getClipdata()Landroid/content/ClipData;"},
    BEANS + "TransIslandIntentionBean;": {f"getMServiceInfo(){LIST}"},
    CONFIG: {f"<init>({STRING * 4}I{STRING * 3}IZ{LIST}{LIST}Ljava/util/Map;)V",
             f"getServiceId(){STRING}"},
    INFO: {f"<init>({STRING}{APP}Z{LIST})V"},
    APP: {"<init>()V", "setOpenMode(I)V", f"setIconRes({DRAWABLE})V"}
         | {f"{name}({STRING})V" for name in
            ("setAppName", "setPkgName", "setServiceId", "setUniqueId", "setIntentId", "setIconLink")},
    SERVICE: {"<init>()V", f"getConfigs(){CONFIG}", f"setConfigs({CONFIG})V",
              f"setIntentionServiceInfo({INFO})V", f"setCacheIconRes({DRAWABLE})V",
              "setCanSupportShare(I)V"},
    "Lcom/vivo/transisland/ui/SpringTransIslandView;": {
        "g()V", "h:Landroid/graphics/Paint;", "onDraw(Landroid/graphics/Canvas;)V",
        "getMCurrentRect()Landroid/graphics/RectF;"},
    DRAG_BEANS + "EnhancedDragWhiteList$EnhancedConfigBean;": {
        "<init>()V", f"setPackageName({STRING})V", f"setStrictLevel({STRING})V",
        f"getLabel(){STRING}", f"setLabel({STRING})V", "getDisabled()Ljava/lang/Boolean;",
        "setDisabled(Ljava/lang/Boolean;)V"}
        | {f"get{name}()Ljava/util/ArrayList;" for name in
           ("TextWhiteList", "ImageWhiteList", "CustomWhiteList", "TargetViewsList", "BlackList", "NoWidgetLimitWhiteList")}
        | {f"set{name}(Ljava/util/ArrayList;)V" for name in
           ("TextWhiteList", "ImageWhiteList", "CustomWhiteList", "TargetViewsList", "BlackList", "NoWidgetLimitWhiteList")},
}


class Dex:
    def __init__(self, data):
        if data[:4] != b"dex\n":
            raise ValueError("Not a standard DEX")
        self.data = data
        strings_count, strings_offset = self.unpack("II", 56)
        self.strings = []
        for index in range(strings_count):
            offset = self.unpack("I", strings_offset + index * 4)[0]
            unused_length, offset = self.uleb(offset)
            self.strings.append(data[offset:data.index(b"\0", offset)].decode("utf-8", errors="replace"))
        types_count, types_offset = self.unpack("II", 64)
        self.types = [self.strings[self.unpack("I", types_offset + index * 4)[0]]
                      for index in range(types_count)]
        self.proto_offset = self.unpack("I", 76)[0]
        self.field_offset = self.unpack("I", 84)[0]
        self.method_offset = self.unpack("I", 92)[0]

    def unpack(self, pattern, offset):
        return struct.unpack_from("<" + pattern, self.data, offset)

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.data[offset]
            offset += 1
            value |= (byte & 0x7f) << shift
            if byte < 0x80:
                return value, offset
        raise ValueError("Invalid ULEB128")

    def method(self, index):
        owner, proto, name = self.unpack("HHI", self.method_offset + index * 8)
        short_name, result, parameters = self.unpack("III", self.proto_offset + proto * 12)
        arguments = ""
        if parameters:
            count = self.unpack("I", parameters)[0]
            arguments = "".join(self.types[self.unpack("H", parameters + 4 + index * 2)[0]]
                                for index in range(count))
        return f"{self.strings[name]}({arguments}){self.types[result]}"

    def field(self, index):
        owner, field_type, name = self.unpack("HHI", self.field_offset + index * 8)
        return f"{self.strings[name]}:{self.types[field_type]}"

    def definitions(self, wanted=None):
        """Yields (owner, members, superclass, interfaces) for each wanted class this DEX defines."""
        if wanted is None:
            wanted = EXPECTED
        count, offset = self.unpack("II", 96)
        for index in range(count):
            definition = offset + index * 32
            owner = self.types[self.unpack("I", definition)[0]]
            if owner not in wanted:
                continue
            superclass_index, interfaces_offset = self.unpack("II", definition + 8)
            superclass = None if superclass_index == 0xffffffff else self.types[superclass_index]
            interfaces = []
            if interfaces_offset:
                size = self.unpack("I", interfaces_offset)[0]
                interfaces = [self.types[self.unpack("H", interfaces_offset + 4 + position * 2)[0]]
                              for position in range(size)]
            cursor = self.unpack("I", definition + 24)[0]
            members = set()
            if cursor:
                sizes = []
                for unused in range(4):
                    size, cursor = self.uleb(cursor)
                    sizes.append(size)
                for group, size in enumerate(sizes):
                    member_index = 0
                    for unused in range(size):
                        delta, cursor = self.uleb(cursor)
                        flags, cursor = self.uleb(cursor)
                        member_index += delta
                        if group >= 2:
                            code_offset, cursor = self.uleb(cursor)
                            members.add(self.method(member_index))
                        else:
                            members.add(self.field(member_index))
            yield owner, members, superclass, interfaces


PREVIEW = "Lkntr/common/photonic/aphro/ui/preview/ImagePageItemKt$ImagePageItem$"
BILI_GESTURE = PREVIEW + "5$5$1$1;"
BILI_LOADER = PREVIEW + "5$1$1;"
PAGER_STATE = "Landroidx/compose/foundation/pager/PagerState;"
PAGE_LIST = "Landroidx/compose/runtime/snapshots/SnapshotStateList;"


def missing_members(expected, declared):
    """Members a class lacks. "name:*" accepts a field of any type, for types the app renames."""
    absent = []
    for member in sorted(expected):
        if member.endswith(":*"):
            if not any(candidate.startswith(member[:-1]) for candidate in declared):
                absent.append(member)
        elif member not in declared:
            absent.append(member)
    return absent


def bili_viewer_shape(dexes):
    """Follows, on declared types, the chain the module walks on live objects in the image viewer."""
    def read(owners):
        found = {}
        for dex in dexes:
            for owner, members, superclass, interfaces in dex.definitions(owners):
                found[owner] = (members, superclass, interfaces)
        return found

    def field_type(members, name):
        for member in members:
            if member.startswith(name + ":"):
                return member[len(name) + 1:]
        return None

    problems = []
    lambdas = read({BILI_GESTURE, BILI_LOADER})
    gesture = lambdas.get(BILI_GESTURE)
    loader = lambdas.get(BILI_LOADER)
    if gesture is None or loader is None:
        return ["viewer coroutine classes missing"], {}
    state = field_type(gesture[0], "$state")
    item = field_type(loader[0], "$item")
    asset = field_type(loader[0], "$asset")
    derived = {"state": state, "item": item, "asset": asset}
    if not state or not item or not asset:
        return problems + ["captured viewer fields missing: " + str(derived)], derived
    shapes = read({state, item, asset})
    for name in (state, item, asset):
        if name not in shapes:
            problems.append("class not found: " + name)
    if problems:
        return problems, derived
    state_fields = [member.split(":", 1)[1] for member in shapes[state][0] if ":" in member and "(" not in member]
    if state_fields.count(PAGER_STATE) != 1:
        problems.append("viewer state needs exactly one PagerState field")
    if state_fields.count(PAGE_LIST) != 1:
        problems.append("viewer state needs exactly one SnapshotStateList field")
    if not any(member.endswith(":" + asset) for member in shapes[item][0]):
        problems.append("page item has no asset field")
    # getIdentifier is declared on the asset interface or on one it extends.
    pending, seen, named = [asset], set(), False
    while pending and not named:
        current = pending.pop()
        if current in seen:
            continue
        seen.add(current)
        shape = shapes.get(current) or read({current}).get(current)
        if shape is None:
            continue
        named = f"getIdentifier(){STRING}" in shape[0]
        pending.extend(shape[2])
    if not named:
        problems.append("asset cannot name itself: getIdentifier() missing")
    return problems, derived


def main():
    parser = argparse.ArgumentParser(description="Verify drag-all targets against declared host DEX members")
    parser.add_argument("apk", type=Path)
    parser.add_argument("--framework", type=Path)
    parser.add_argument("--catcher", type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--vivo-framework", type=Path)
    parser.add_argument("--telegram", type=Path)
    parser.add_argument("--bili", type=Path)
    arguments = parser.parse_args()
    if arguments.framework:
        callback = "Lvivo/accessibilityenhance/server/AccessibilityEnhanceClient$AccessibilityEnhanceCallback;"
        enhance_info = "Lvivo/accessibilityenhance/server/AccessibilityEnhanceInfo;"
        EXPECTED["Landroid/view/accessibility/AccessibilityManager;"] = {
            f"requestAccessibilityEnhanceExt(Landroid/os/Bundle;{callback})V"}
        EXPECTED[callback] = {f"onRequestResult({enhance_info})V"}
        EXPECTED[enhance_info] = {
            f"getRequestId(){STRING}", "getComponentName()Landroid/content/ComponentName;",
            "getResultCode()I", "setResultCode(I)V", "getData()Landroid/os/Bundle;",
            "setData(Landroid/os/Bundle;)V"}
        EXPECTED["Landroid/view/View;"] = {
            "setMaterialBlurRadius(F)V",
            "getBlurGeometry(I)Landroid/view/blur/IVivoBlurRender$BlurGeometry;"}
        EXPECTED["Landroid/view/blur/IVivoBlurRender$BlurGeometry;"] = {"mBlurRadius:F", "mStyle:I"}
    if arguments.catcher:
        prefix = "Lcom/vivo/accessibilityenhance/"
        component = "Landroid/content/ComponentName;"
        EXPECTED[prefix + "dc/zb;"] = {
            f"N({STRING}Ljava/lang/Class;{STRING}{STRING}{component})Z",
            f"q1(Ljava/lang/Class;{STRING}{component})Z",
            f"T({component})Z"}
        EXPECTED[prefix + "dc/xa;"] = {f"T({component})Z"}
        EXPECTED["Lcom/vivo/accessibilityenhanceconfig/list/business/DragDropWhiteList;"] = {"<init>()V"}
        EXPECTED[prefix + "process/process/GrabDragDropContentProcess;"] = {
            f"finishAnalyzeNode({prefix}dc/pw;)V"}
        EXPECTED[prefix + "dc/y5;"] = {f"getComponentName(){component}"}
        EXPECTED[prefix + "dc/j50;"] = {"f()Z", "g(I)I", f"k(I){LIST}"}
        EXPECTED[prefix + "dc/im;"] = {"a(Landroid/os/Bundle;)V", f"m(){STRING}", "n()I"}
        EXPECTED[prefix + "dc/tp;"] = {"l()Landroid/os/Bundle;", f"m(){STRING}"}
        EXPECTED[prefix + "dc/gm;"] = {"<init>()V"}
        EXPECTED[prefix + "dc/zb0;"] = {"<init>(Lcom/vivo/accessibilityenhance/dc/vg;)V"}
        EXPECTED[prefix + "dc/m6;"] = {"<init>(Lcom/vivo/accessibilityenhance/dc/vg;)V"}
    if arguments.source:
        EXPECTED["Lio/flutter/view/b;"] = {
            "g:Ljava/util/HashMap;", "createAccessibilityNodeInfo(I)Landroid/view/accessibility/AccessibilityNodeInfo;",
            "performAction(IILandroid/os/Bundle;)Z"}
        EXPECTED["Lzq;"] = {"w:Lio/flutter/view/b;"}
        EXPECTED["Ln0;"] = {"X:Ljava/util/ArrayList;", "V:Ln0;", "b:I", f"p:{STRING}", "Q:F", "S:F", "h(I)Z"}
        EXPECTED["Lc6;"] = {"i:Ljava/lang/Object;", "onMethodCall(Lo60;Lt60;)V"}
        EXPECTED["Lsh0;"] = {"h:Ljava/util/concurrent/atomic/AtomicBoolean;"}
        EXPECTED["Lo60;"] = {f"a:{STRING}", "b:Ljava/lang/Object;", f"a({STRING})Ljava/lang/Object;"}
        EXPECTED["Lt60;"] = {"success(Ljava/lang/Object;)V"}
        EXPECTED["Lj40;"] = {"i:Lkt;", "k:Landroid/app/Activity;", "onMethodCall(Lo60;Lt60;)V"}
        EXPECTED["Lkt;"] = {"h:Ljava/lang/Object;"}
        EXPECTED["Lzr0;"] = {"c:Landroid/view/Surface;"}
        EXPECTED["Lio/flutter/embedding/engine/FlutterJNI;"] = {
            "accessibilityDelegate:Lbq;", "setSemanticsEnabled(Z)V"}
        EXPECTED["Lei/p;"] = {"a:Lei/p;"}
        EXPECTED[DRAG_BEANS + "DragInfo;"].update({
            f"setCurrentWidgetItem({DRAG_BEANS}WidgetItemBean;)V", "setVisibleRect(Landroid/graphics/Rect;)V",
            f"setCatchItem({STRING})V", f"setSelectionContent({STRING})V"})
        EXPECTED[DRAG_BEANS + "WidgetItemBean;"].update({
            "<init>()V", f"setPackageName({STRING})V", f"setActivityName({STRING})V", f"setWidgetName({STRING})V"})
        EXPECTED["Lcom/vivo/common/dragdrop/SharedFileProvider;"] = {
            "c(Landroid/content/Context;Ljava/io/File;)Landroid/net/Uri;"}
    if arguments.vivo_framework:
        EXPECTED["Lcom/vivo/vdragdrop/DragClientProxy;"] = {
            f"requestClipDataFromServer({STRING * 5}Landroid/graphics/PointF;{STRING}Landroid/graphics/Rect;{LIST}I{STRING}Lcom/vivo/vdragdrop/bean/ViewTree;)V"}
        EXPECTED["Lcom/vivo/vdragdrop/manager/DragManager;"] = {
            "handleMessageUnchecked(ILandroid/os/Bundle;)V", f"mRequestId:{STRING}", "triggerDrag(Landroid/os/Bundle;)V"}
        manager = "Lcom/vivo/vdragdrop/manager/VivoDragManager;"
        EXPECTED[manager] = {
            f"getInstance(){manager}",
            "triggerHandleDragAndDrop(Landroid/view/View;Landroid/view/MotionEvent;)V"}
    guest_owners = set()
    if arguments.bili:
        guest = {
            "Landroidx/compose/ui/platform/AndroidComposeView;": {
                "dispatchTouchEvent(Landroid/view/MotionEvent;)Z"},
            # The image viewer: the page coroutines whose captured fields lead to the shown address.
            BILI_GESTURE: {"invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;", "$state:*"},
            BILI_LOADER: {"$item:*", "$asset:*"},
            PAGER_STATE: {"getCurrentPage()I"},
        }
        guest_owners = set(guest)
        EXPECTED.update(guest)
    if arguments.telegram:
        message = "Lorg/telegram/messenger/MessageObject;"
        receiver = "Lorg/telegram/messenger/ImageReceiver;"
        caption = "Lorg/telegram/messenger/MessageObject$TextLayoutBlocks;"
        EXPECTED["Lorg/telegram/ui/Cells/ChatMessageCell;"] = {
            "onTouchEvent(Landroid/view/MotionEvent;)Z", f"getMessageObject(){message}",
            f"getPhotoImage(){receiver}", "hasSpoilers()Z", f"getCaptionLayout(){caption}",
            "textX:I", "textY:I", "captionX:F", "captionY:F"}
        EXPECTED[message] = {"messageText:Ljava/lang/CharSequence;", "caption:Ljava/lang/CharSequence;",
            "textWidth:I", "textHeight()I", "isSecretMedia()Z", "canForwardMessage()Z",
            "hasMediaSpoilers()Z"}
        EXPECTED["Lorg/telegram/ui/PhotoViewer$FrameLayoutDrawer;"] = {
            "this$0:Lorg/telegram/ui/PhotoViewer;"}
        EXPECTED["Lorg/telegram/ui/PhotoViewer;"] = {
            f"centerImage:{receiver}", f"currentMessageObject:{message}"}
        EXPECTED[caption] = {"textWidth:I", "textHeight()I"}
        EXPECTED[receiver] = {"getBitmap()Landroid/graphics/Bitmap;", "getImageX()F", "getImageY()F",
            "getImageWidth()F", "getImageHeight()F", "isInsideImage(FF)Z"}
    actual = {}
    inputs = [arguments.apk] + ([arguments.framework] if arguments.framework else [])
    if arguments.catcher:
        inputs.append(arguments.catcher)
    if arguments.source:
        inputs.append(arguments.source)
    if arguments.vivo_framework:
        inputs.append(arguments.vivo_framework)
    if arguments.telegram:
        inputs.append(arguments.telegram)
    if arguments.bili:
        inputs.append(arguments.bili)
    guest_dexes = []
    for apk_path in inputs:
        guest = arguments.bili is not None and apk_path == arguments.bili
        with zipfile.ZipFile(apk_path) as archive:
            for name in archive.namelist():
                if name.startswith("classes") and name.endswith(".dex"):
                    dex = Dex(archive.read(name))
                    if guest:
                        guest_dexes.append(dex)
                    for owner, members, superclass, interfaces in dex.definitions():
                        # A guest app's obfuscated classes can share a name with a host class
                        # (Bilibili also has a vb.p), so it may only answer for its own anchors.
                        if (owner in guest_owners) == guest:
                            actual[owner] = members
    missing = {}
    for owner, members in EXPECTED.items():
        absent = missing_members(members, actual.get(owner, set()))
        if absent:
            missing[owner] = absent
    viewer = None
    if arguments.bili:
        problems, derived = bili_viewer_shape(guest_dexes)
        viewer = {"derived": derived, "problems": problems}
    failed = bool(missing) or bool(viewer and viewer["problems"])
    report = {
        "apk": str(arguments.apk.resolve()),
        "sha256": hashlib.sha256(arguments.apk.read_bytes()).hexdigest(),
        "classes_checked": len(EXPECTED),
        "members_checked": sum(map(len, EXPECTED.values())),
        "share_capability_return": "java.lang.Boolean",
        "missing": missing,
        "result": "FAIL" if failed else "PASS",
        "scope": "Static declared-member verification only; no runtime execution",
    }
    if arguments.bili:
        report["bili"] = str(arguments.bili.resolve())
        report["bili_sha256"] = hashlib.sha256(arguments.bili.read_bytes()).hexdigest()
        report["bili_viewer"] = viewer
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1 if failed else 0)


if __name__ == "__main__":
    main()
