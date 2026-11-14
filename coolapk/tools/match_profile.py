#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「酷安净化」的 target manifest 对着 16.7.3 的运行时结构做匹配。

输入：
  coolapk_target_manifest.json         模块自带的 profile（最新只到 16.7.0）
  work/runtime/classes5.json           本机 16.7.3 运行时 dump（4,739 个类，含方法/字段/父类）

匹配策略（从稳到虚）：
  1. ownerClass 在新版本里同名存在            -> exact
  2. ownerClass 的短名（去包名）唯一命中        -> short-name
  3. 用 manifest 里给出的结构锚点（父类/字段名/模板名/参数类型）找候选 -> structural
  4. 都没有                                   -> unresolved

用法：
  python tools/match_profile.py work/runtime/classes5.json coolapk_target_manifest.json
"""
import json
import sys


def load_dump(path):
    raw = open(path, encoding='utf-8').read()
    try:
        d = json.loads(raw)
    except Exception:
        cut = raw.rfind('}]}')
        d = json.loads(raw[:cut + 3] + '\n]}\n')
    by = {}
    for c in d['classes']:
        by[c['name']] = c
    return by


def short(name):
    return name.rsplit('.', 1)[-1]


def has_method(cls, name, params=None):
    for m in cls.get('methods', []):
        if m['n'] != name:
            continue
        if params is None or m['p'] == params:
            return m
    return None


def match(target, by, by_short):
    owner = target.get('ownerClass') or target.get('rendererOwner')
    method = target.get('methodName') or target.get('rendererMethod')
    rows = []

    if owner:
        if owner in by:
            rows.append(('exact', owner))
        else:
            cands = by_short.get(short(owner), [])
            if len(cands) == 1:
                rows.append(('short-name', cands[0]))
            elif len(cands) > 1:
                rows.append(('short-name-ambiguous', ','.join(cands[:3])))
            else:
                rows.append(('unresolved-owner', owner))

    # 结构锚点：非混淆的父类/字段名/模板名直接在新 dump 里找
    hints = []
    for key in ('holderClass', 'fragmentBaseClass', 'hostFragmentClass', 'viewHolderClass',
                'cardClass', 'entityClass', 'viewModelClass', 'adHelperClass',
                'configClass', 'layoutOwner', 'ownerParentClass'):
        v = target.get(key)
        if v and not v.startswith(('androidx.', 'android.', 'java.', 'kotlin.')):
            hints.append((key, v, v in by))
    for key, v, ok in hints:
        rows.append(('anchor-' + key, v + (' [OK]' if ok else ' [missing]')))

    # 方法是否还在
    resolved = [r for r in rows if r[0] in ('exact', 'short-name')]
    if resolved and method:
        cls = by.get(resolved[0][1])
        if cls is None:
            cls = by.get(by_short.get(short(resolved[0][1]), ['?'])[0])
        m = has_method(cls, method) if cls else None
        rows.append(('method', method + (' [OK] ' + str(m['p']) if m else ' [MISSING]')))

    return rows


def main():
    dump, manifest_path = sys.argv[1], sys.argv[2]
    by = load_dump(dump)
    by_short = {}
    for name in by:
        by_short.setdefault(short(name), []).append(name)
    print('dump classes: %d' % len(by))

    manifest = json.load(open(manifest_path, encoding='utf-8'))
    profile = manifest['profiles'][-1]
    print('参考 profile: %s (%s)，16.7.3 需要重新映射\n'
          % (profile['versionName'], profile['versionCode']))

    exact = 0
    for kind, target in profile['targets'].items():
        rows = match(target, by, by_short)
        status = 'EXACT' if any(r[0] in ('exact', 'short-name') for r in rows) else 'NEEDS-WORK'
        exact += 1 if status == 'EXACT' else 0
        print('[%s] %s' % (status, kind))
        for key, val in rows:
            print('    %-22s %s' % (key, val))
        print()
    print('可直接复用 ownerClass 的 target: %d / %d' % (exact, len(profile['targets'])))


if __name__ == '__main__':
    main()
