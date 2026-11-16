#!/usr/bin/env python3
# Release notes of a v* tag: the commits since the previous v* tag, what each APK is for and the compare link. CI writes
# them as Markdown for the GitHub release and posts them to Telegram with the APKs. Runs inside a full clone with tags.
import argparse
import glob
import json
import os
import subprocess
import sys

PRODUCT = "NukeApp-OSS"
INSTALL = [
    ("module-release.apk", "the module. Install it and enable it in an Xposed framework with Douyin in scope, "
                           "or rename a copy to .zip and flash it in a root manager for Zygisk."),
    ("patcher-release.apk", "NukePatcher. Embeds the module into a Douyin APK for use without root."),
]
# A Telegram caption holds at most 1024 characters.
CAPTION_LIMIT = 1024


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def release(tag):
    found = subprocess.run(["git", "describe", "--tags", "--abbrev=0", "--match", "v*", f"{tag}^"],
                           capture_output=True, text=True)
    previous = found.stdout.strip() if found.returncode == 0 else None
    span = f"{previous}..{tag}" if previous else tag
    commits = [line.split("\t", 1) for line in git("log", "--no-merges", "--format=%h%x09%s", span).splitlines()]
    base = f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{os.environ['GITHUB_REPOSITORY']}"
    # The first release has nothing to compare against, so its changelog is the tag's own history.
    changelog = f"{base}/compare/{previous}...{tag}" if previous else f"{base}/commits/{tag}"
    return commits, f"{base}/releases/tag/{tag}", changelog


def markdown(tag):
    commits, _, changelog = release(tag)
    lines = ["## Changes", ""]
    lines += [f"- {subject} ({sha})" for sha, subject in commits]
    lines += ["", "## Install", ""]
    lines += [f"- **`{name}`**: {text}" for name, text in INSTALL]
    lines += ["", "Check the APKs against `SHA256SUMS`.", "", f"**Full changelog:** {changelog}"]
    return "\n".join(lines) + "\n"


# MarkdownV2 escapes these everywhere outside code and links; code only needs ` and \, a link target ) and \.
def escape(text): return "".join("\\" + c if c in "_*[]()~`>#+-=|{}.!\\" else c for c in text)
def code(text): return "`" + text.replace("\\", "\\\\").replace("`", "\\`") + "`"
def link(label, url): return "[" + escape(label) + "](" + url.replace("\\", "\\\\").replace(")", "\\)") + ")"


def caption(tag):
    commits, page, changelog = release(tag)
    title = f"*{escape(f'{PRODUCT} {tag}')}*" + (escape(" (pre-release)") if "-" in tag else "")

    def build(shown):
        lines = [title, "", "*Changes*"]
        lines += [f"• {escape(subject)} \\({code(sha)}\\)" for sha, subject in commits[:shown]]
        if shown < len(commits):
            lines.append(escape(f"• …and {len(commits) - shown} more"))
        lines += ["", "*Install*"]
        lines += [f"• {code(name)}: {escape(text)}" for name, text in INSTALL]
        lines += ["", f"{link('Release', page)} \\| {link('Full changelog', changelog)}"]
        return "\n".join(lines)

    # A long list loses its oldest commits first; the release page always has all of them.
    shown = len(commits)
    while shown > 0 and len(build(shown)) > CAPTION_LIMIT:
        shown -= 1
    return build(shown)


def send(tag, directory):
    token, chat = os.environ.get("BOT_TOKEN"), os.environ.get("CHAT_ID")
    if not token or not chat:
        sys.exit("TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID is not set.")
    files = sorted(glob.glob(os.path.join(directory, "**", "*.apk"), recursive=True))
    if not files:
        sys.exit("no APK to send")
    media = [{"type": "document", "media": f"attach://file{i}"} for i in range(len(files))]
    media[-1].update({"caption": caption(tag), "parse_mode": "MarkdownV2"})
    command = ["curl", "--fail-with-body", "--silent", "--show-error",
               f"https://api.telegram.org/bot{token}/sendMediaGroup",
               # --form-string: -F would cut the JSON at the first ';' of the caption.
               "--form-string", f"chat_id={chat}", "--form-string", f"media={json.dumps(media)}"]
    for i, path in enumerate(files):
        command += ["-F", f"file{i}=@{path}"]
    result = subprocess.run(command, capture_output=True, text=True)
    # The token is in the URL, so never print the command.
    print(result.stdout[:500], result.stderr[:500])
    sys.exit(result.returncode)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("format", choices=["markdown", "telegram"])
    parser.add_argument("tag")
    parser.add_argument("--send", metavar="APK_DIR", help="post the caption with the APKs under APK_DIR to Telegram")
    args = parser.parse_args()
    if args.send:
        if args.format != "telegram":
            parser.error("--send needs the telegram format")
        send(args.tag, args.send)
    print(markdown(args.tag) if args.format == "markdown" else caption(args.tag), end="")


if __name__ == "__main__":
    main()
