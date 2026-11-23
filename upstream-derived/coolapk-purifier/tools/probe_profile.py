#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""酷安模块 profile 兼容性探测。

用途：酷安「酷安净化」(io.github.yylsping.coolapkpurifier) 用一份
`assets/coolapk_target_manifest.json` 记录每个酷安版本里被混淆后的类/方法名。
酷安一升级，对应 profile 就 missing，manifest 类功能直接 DISABLED。

这个脚本把 manifest 里的每个 target 拿到指定 APK 里核对：
  class / method(参数) / 字段 是否存在 → 输出 OK / MISSING / DRIFT

用法：
  python tools/probe_profile.py apk/coolapk-16.7.3.apk coolapk_target_manifest.json
  python tools/probe_profile.py apk/coolapk-16.7.3.apk ... --profile 16.7.0
"""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                '..', '..', 'modules', 'fq-fold-pad', 'tools'))
import dexscan  # noqa: E402


class Index(object):
    """按类名/方法名/字段名建索引，供 manifest 逐条核对。"""

    def __init__(self, apk):
        self.classes = {}          # name -> detail
        for dex_name, dex in dexscan.load(apk):
            for off in dex.class_def_offsets():
                try:
                    info = dex.class_detail(off)
                except Exception:
                    continue
                info['_dex'] = dex_name
                self.classes[info['name']] = info

    def has_class(self, name):
        return name in self.classes

    def methods(self, class_name):
        info = self.classes.get(class_name)
        if not info:
            return {}
        out = {}
        for sig in info['methods']:
            # sig: name(param,param)ret
            name = sig.split('(', 1)[0]
            params = sig[sig.find('(') + 1:sig.rfind(')')]
            plist = [p for p in params.split(',') if p]
            out.setdefault(name, []).append(plist)
        return out

    def fields(self, class_name):
        info = self.classes.get(class_name)
        return {f[0] for f in info['fields']} if info else set()


def class_short(name):
    """manifest 里会出现 'b25'、'x2f' 这类无包名的短名，需要在索引里模糊匹配。"""
    return name


def resolve(idx, name):
    """精确 → 去包名后缀 → 短名唯一匹配。"""
    if idx.has_class(name):
        return name
    tail = '.' + name
    hits = [c for c in idx.classes if c.endswith(tail)]
    if len(hits) == 1:
        return hits[0]
    return None


def check_target(idx, kind, t):
    """返回 [(检查项, 状态, 细节)]"""
    rows = []
    owner = t.get('ownerClass') or t.get('holderClass') or t.get('rendererOwner')
    if owner and not t.get('ownerClass'):
        owner = None  # 只有 holder 的情况单独处理

    if t.get('ownerClass'):
        real = resolve(idx, t['ownerClass'])
        if not real:
            rows.append(('ownerClass ' + t['ownerClass'], 'MISSING', 'class not found'))
            return rows
        rows.append(('ownerClass ' + t['ownerClass'], 'OK', real if real != t['ownerClass'] else ''))
        method = t.get('methodName')
        if method:
            ms = idx.methods(real).get(method)
            if ms is None:
                rows.append(('method ' + method, 'MISSING', 'name not found'))
            else:
                want = t.get('parameterTypes')
                if want is None:
                    rows.append(('method ' + method, 'AMBIG', '%d overload(s)' % len(ms)))
                else:
                    want = [w for w in want]
                    hit = [p for p in ms if p == want]
                    rows.append(('method ' + method, 'OK' if hit else 'DRIFT',
                                 '' if hit else 'params now: ' + str(ms)))
        if t.get('layoutField'):
            f = idx.fields(real)
            rows.append(('field ' + t['layoutField'],
                         'OK' if t['layoutField'] in f else 'MISSING', ''))
        if t.get('callbackField'):
            f = idx.fields(real)
            rows.append(('field ' + t['callbackField'],
                         'OK' if t['callbackField'] in f else 'MISSING', ''))

    for key in ('holderClass', 'callbackClass', 'fragmentBaseClass', 'hostFragmentClass',
                'bindingComponentClass', 'viewHolderClass', 'cardClass', 'entityClass',
                'viewModelClass', 'dataClass', 'adHelperClass', 'configClass',
                'itemClass', 'postSlotClass', 'layoutOwner', 'ownerParentClass',
                'eventClass', 'fragmentClass'):
        v = t.get(key)
        if not v:
            continue
        if v.startswith(('java.', 'kotlin.', 'android.', 'androidx.')):
            rows.append((key, 'SKIP', 'platform class'))
            continue
        real = resolve(idx, v)
        rows.append((key + ' ' + v, 'OK' if real else 'MISSING', real or ''))
    return rows


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    apk, manifest_path = sys.argv[1], sys.argv[2]
    want_profile = None
    if '--profile' in sys.argv:
        want_profile = sys.argv[sys.argv.index('--profile') + 1]

    manifest = json.load(open(manifest_path, encoding='utf-8'))
    profiles = manifest['profiles']
    if want_profile:
        profiles = [p for p in profiles if p['versionName'] == want_profile]
        if not profiles:
            raise SystemExit('no profile for %s' % want_profile)

    print('indexing %s ...' % apk)
    idx = Index(apk)
    print('classes indexed: %d' % len(idx.classes))
    print()

    for profile in profiles:
        targets = profile['targets']
        total = ok = 0
        per_kind = []
        for kind, t in targets.items():
            rows = check_target(idx, kind, t)
            good = all(s == 'OK' or s == 'SKIP' for _, s, _ in rows)
            total += 1
            ok += 1 if good else 0
            per_kind.append((kind, good, rows))
        print('== profile %s (%s): %d/%d targets intact'
              % (profile['versionName'], profile['versionCode'], ok, total))
        for kind, good, rows in per_kind:
            print('  [%s] %s' % ('OK ' if good else 'BAD', kind))
            for item, status, detail in rows:
                if status in ('OK', 'SKIP') and not detail:
                    continue
                print('       %-8s %s %s' % (status, item, detail))
        print()


if __name__ == '__main__':
    main()
