import hashlib
import json
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

TOKEN = os.environ.get("GH_TOKEN", "")
H = {
    "Authorization": "token " + TOKEN,
    "Accept": "application/vnd.github+json",
    "User-Agent": "xhook-fetch",
}
REPO_ROOT = Path(__file__).resolve().parents[3]
ROOT = Path(os.environ.get("LAB_CACHE") or REPO_ROOT / "cache") / "xhook"
APK_DIR = os.path.join(ROOT, "apks")
SRC_DIR = os.path.join(ROOT, "src-upstream")

REPOS = [
    ("rex", "Xposed-Modules-Repo/one.dot.rex"),
    ("twitterhideads", "MrxSiN/TwitterHideAds"),
    ("xvideosave", "qifangcat/x-video-save"),
    ("xvideocatcher", "Jieoz/x-video-catcher"),
    ("xadblock", "JinShichang/X-ADBlockModule"),
    ("twifucker", "Mangi-11/TwiFucker"),
    ("xregion", "BeepSleepySheep/X-region"),
    ("xshortlink", "Karuizawakeii/XShortLinkCleaner"),
    ("birdify", "lewisdoesstuff/birdify"),
    ("piko", "crimera/piko"),
]

WANT_ASSET_EXT = (".apk", ".mpp", ".jar", ".zip", ".apks", ".apkm")
WANT_SRC = {
    "twitterhideads",
    "xvideosave",
    "xvideocatcher",
    "xadblock",
    "twifucker",
    "xregion",
    "xshortlink",
    "birdify",
    "piko",
}


def api(path):
    req = urllib.request.Request("https://api.github.com" + path, headers=H)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def dl(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return os.path.getsize(dest)
    req = urllib.request.Request(url, headers={"User-Agent": "xhook-fetch", "Accept": "*/*"})
    tmp = dest + ".part"
    with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    os.replace(tmp, dest)
    return os.path.getsize(dest)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


os.makedirs(SRC_DIR, exist_ok=True)
manifest = []
for slug, full in REPOS:
    print("=" * 60)
    print(full)
    try:
        repo = api("/repos/" + full)
        try:
            rel = api("/repos/%s/releases/latest" % full)
        except Exception:
            rels = api("/repos/%s/releases?per_page=1" % full)
            rel = rels[0] if rels else None
        if not rel:
            print("  no release")
            continue
        print("  release:", rel.get("tag_name"), (rel.get("published_at") or "")[:10])
        outdir = os.path.join(APK_DIR, slug)
        os.makedirs(outdir, exist_ok=True)
        assets = rel.get("assets") or []
        picked = 0
        for a in assets:
            name = a["name"]
            if not name.lower().endswith(WANT_ASSET_EXT):
                continue
            dest = os.path.join(outdir, name)
            size = dl(a["browser_download_url"], dest)
            digest = sha256(dest)
            print("   asset %-46s %10d  %s" % (name, size, digest[:16]))
            manifest.append(
                {
                    "slug": slug,
                    "repo": full,
                    "tag": rel.get("tag_name"),
                    "published_at": rel.get("published_at"),
                    "asset": name,
                    "bytes": size,
                    "sha256": digest,
                    "path": os.path.relpath(dest, ROOT),
                }
            )
            picked += 1
        if picked == 0:
            print("   (no matching assets; assets=%s)" % [a["name"] for a in assets])

        if slug in WANT_SRC:
            branch = repo.get("default_branch") or "main"
            zipname = "%s-%s.zip" % (slug, branch)
            dest = os.path.join(SRC_DIR, zipname)
            url = "https://api.github.com/repos/%s/zipball/%s" % (full, branch)
            req = urllib.request.Request(url, headers=H)
            size = dl(url, dest)
            print("   source %-46s %10d  %s" % (zipname, size, sha256(dest)[:16]))
            manifest.append(
                {
                    "slug": slug,
                    "repo": full,
                    "kind": "source",
                    "branch": branch,
                    "bytes": size,
                    "sha256": sha256(dest),
                    "path": os.path.relpath(dest, ROOT),
                }
            )
    except Exception as e:
        print("  ERR:", e)

with open(os.path.join(ROOT, "fetch-manifest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
print("\nDONE", len(manifest), "artifacts")
