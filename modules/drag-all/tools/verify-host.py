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
               f"r(){DRAG_BEANS}DragInfo;", "n:Ljava/lang/ref/WeakReference;"},
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

    def definitions(self):
        count, offset = self.unpack("II", 96)
        for index in range(count):
            definition = offset + index * 32
            owner = self.types[self.unpack("I", definition)[0]]
            if owner not in EXPECTED:
                continue
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
            yield owner, members


def main():
    parser = argparse.ArgumentParser(description="Verify drag-all targets against declared host DEX members")
    parser.add_argument("apk", type=Path)
    parser.add_argument("--framework", type=Path)
    parser.add_argument("--catcher", type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--vivo-framework", type=Path)
    parser.add_argument("--telegram", type=Path)
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
            f"q1(Ljava/lang/Class;{STRING}{component})Z"}
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
            "g:Ljava/util/HashMap;", "createAccessibilityNodeInfo(I)Landroid/view/accessibility/AccessibilityNodeInfo;"}
        EXPECTED["Lzq;"] = {"getAccessibilityNodeProvider()Landroid/view/accessibility/AccessibilityNodeProvider;"}
        EXPECTED["Lcom/vivo/common/dragdrop/SharedFileProvider;"] = {
            "c(Landroid/content/Context;Ljava/io/File;)Landroid/net/Uri;"}
    if arguments.vivo_framework:
        EXPECTED["Lcom/vivo/vdragdrop/DragClientProxy;"] = {
            f"requestClipDataFromServer({STRING * 5}Landroid/graphics/PointF;{STRING}Landroid/graphics/Rect;{LIST}I{STRING}Lcom/vivo/vdragdrop/bean/ViewTree;)V"}
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
    for apk_path in inputs:
        with zipfile.ZipFile(apk_path) as archive:
            for name in archive.namelist():
                if name.startswith("classes") and name.endswith(".dex"):
                    actual.update(Dex(archive.read(name)).definitions())
    missing = {owner: sorted(members - actual.get(owner, set()))
               for owner, members in EXPECTED.items() if members - actual.get(owner, set())}
    report = {
        "apk": str(arguments.apk.resolve()),
        "sha256": hashlib.sha256(arguments.apk.read_bytes()).hexdigest(),
        "classes_checked": len(EXPECTED),
        "members_checked": sum(map(len, EXPECTED.values())),
        "share_capability_return": "java.lang.Boolean",
        "missing": missing,
        "result": "FAIL" if missing else "PASS",
        "scope": "Static declared-member verification only; no runtime execution",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(1 if missing else 0)


if __name__ == "__main__":
    main()
