#!/usr/bin/env python3
"""Compose a GitHub Release body: the notes with a download section of badge links built from the assets.

The notes are the Chinese text followed by an English translation in a <details> block; the download
section goes between them (or at the end), followed by any "## SHA-256" section of the notes.

  release_notes.py --tag v3.0.1 --repo owner/name --assets-dir release-assets --notes notes.md
  release_notes.py --tag v3.0.1 --repo owner/name --assets-json assets.json --notes notes.md
"""
import argparse
import base64
import json
import os
import re
import sys
from urllib.parse import quote

WINDOWS_LOGO = "data:image/svg+xml;base64," + base64.b64encode(
	b'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="white">'
	b'<rect x="0" y="0" width="7.5" height="7.5"/><rect x="8.5" y="0" width="7.5" height="7.5"/>'
	b'<rect x="0" y="8.5" width="7.5" height="7.5"/><rect x="8.5" y="8.5" width="7.5" height="7.5"/></svg>'
).decode()

COLORS = {"installer": "0078D4", "portable": "5B5FC7", "ffmpeg": "2E7D32", "deb": "A81D33"}


def esc(text):
	return quote(text.replace("-", "--").replace("_", "__"), safe="")


def size_text(size):
	mb = size / 1048576
	return f"{mb:.0f} MB" if mb >= 10 else f"{mb:.1f} MB"


def badge(label, message, color, logo, url, alt):
	img = (f"https://img.shields.io/badge/{esc(label)}-{esc(message)}-{color}"
	       f"?style=for-the-badge&logo={quote(logo, safe='')}&logoColor=white")
	return f"[![{alt}]({img})]({url})"


def kind(name):
	lower = name.lower()
	if lower.endswith(".deb"):
		return "deb"
	if lower.endswith(".zip"):
		return "zip"
	if lower.endswith(".exe"):
		return "exe"
	if lower.endswith(".msi"):
		return "msi"
	return None


def is_arm64(name):
	return "arm64" in name.lower()


def version_tuple(tag):
	return tuple(int(p) for p in re.findall(r"\d+", tag)[:3])


def downloads(tag, repo, assets):
	base = f"https://github.com/{repo}/releases/download/{tag}/"
	order = {"exe": 0, "msi": 1, "zip": 2}
	label = {"exe": "Windows EXE", "msi": "Windows MSI", "zip": "Portable ZIP"}
	win = sorted((a for a in assets if kind(a[0]) in order), key=lambda a: order[kind(a[0])])
	ffmpeg = [a for a in win if "-ffmpeg" in a[0]]
	debs = [a for a in assets if kind(a[0]) == "deb"]

	def win_badges(items, with_ffmpeg):
		out = []
		for name, size in items:
			k = kind(name)
			text = label[k] + (" + ffmpeg" if with_ffmpeg else "")
			color = COLORS["ffmpeg"] if with_ffmpeg else COLORS["portable" if k == "zip" else "installer"]
			logo = "ffmpeg" if with_ffmpeg else WINDOWS_LOGO
			out.append(badge(text, size_text(size), color, logo, base + quote(name), name))
		return " ".join(out)

	lines = ["## 下载 · Downloads", ""]
	for arch, arm in (("x86_64", False), ("ARM64", True)):
		items = [a for a in win if is_arm64(a[0]) == arm]
		if not items:
			continue
		lines += [f"**Windows** {arch}", ""]
		plain_items = [a for a in items if "-ffmpeg" not in a[0]]
		ffmpeg_items = [a for a in items if "-ffmpeg" in a[0]]
		if plain_items:
			lines += [win_badges(plain_items, False), ""]
		if ffmpeg_items:
			lines += [win_badges(ffmpeg_items, True), ""]
	for arch, arm in (("amd64", False), ("arm64", True)):
		items = [a for a in debs if is_arm64(a[0]) == arm]
		if items:
			lines += [f"**Linux** {arch}", ""]
			lines += [" ".join(badge("Linux DEB", size_text(s), COLORS["deb"], "debian", base + quote(n), n) for n, s in items), ""]

	notes = []
	if any(kind(a[0]) == "zip" for a in win):
		notes.append(("便携版 ZIP 解压即可运行，无需安装。", "Portable ZIP: extract and run, no installation needed."))
	if ffmpeg:
		notes.append(("带 ffmpeg 的包可直接导出 MP4、WebM、ProRes 4444、APNG 和 WebP 动图；其余包导出视频和动图需自行安装 ffmpeg。",
		              "Packages with ffmpeg export MP4, WebM, ProRes 4444, APNG and animated WebP directly; the others need ffmpeg installed for video and animation export."))
	# Releases without an MSI have the Inno Setup installer, which also replaces the MSI installs of 3.1.x and earlier.
	if any(kind(a[0]) == "exe" for a in win) and not any(kind(a[0]) == "msi" for a in win):
		notes.append(("EXE 在已安装时装回原来的目录并替换旧版本，3.1.x 及更早的 EXE / MSI 安装会被自动移除。",
		              "The EXE installs into the existing folder and replaces the installed version; EXE / MSI installs of 3.1.x and earlier are removed."))
	elif any(kind(a[0]) in ("exe", "msi") for a in win) and version_tuple(tag) >= (3, 0, 1):
		notes.append(("EXE / MSI 在已安装时装回原来的目录并替换旧版本。",
		              "EXE / MSI install into the existing folder and replace the installed version."))
	if any(is_arm64(a[0]) for a in win):
		notes.append(("Windows ARM64 包不含 Cubism Native 预览（Live2D 未提供该平台的 Cubism Core），预览使用 PSD2Live 运行时；没有内置 ffmpeg 的版本。",
		              "The Windows ARM64 packages have no Cubism Native preview (Live2D ships no Cubism Core for it); the preview uses the PSD2Live runtime. There is no build with ffmpeg."))
	if any(is_arm64(a[0]) for a in win + debs):
		# Until the arm64 packages have been tried on real hardware.
		notes.append(("**ARM64 包未经实机测试**，只在 CI 上构建和跑过单元测试。Linux arm64 的 Cubism 预览使用 Live2D 标为实验性的 Cubism Core；只支持 OpenGL 3.1 的 GPU（如树莓派）上画布可能无法绘制。遇到问题请提交 Issue。",
		              "**The ARM64 packages are untested on real hardware**; they are only built and unit-tested in CI. The Cubism preview on Linux arm64 uses the Cubism Core Live2D marks experimental, and on GPUs with only OpenGL 3.1 (such as a Raspberry Pi) the canvas may fail to draw. Please report problems as issues."))
	if debs:
		notes.append(("Linux Deb 需 X11/GLX，内含 Cubism Native 预览桥接；请遵守 Live2D SDK 许可，勿公开再分发专有组件。",
		              "Linux Deb requires X11/GLX and includes the Cubism Native preview bridge; follow the Live2D SDK license and do not publicly redistribute the proprietary components."))
	for zh, en in notes:
		lines.append(f"- {zh}<br><sub>{en}</sub>")
	return "\n".join(lines).rstrip() + "\n"


def split_sha(text):
	match = re.search(r"^## SHA-256\s*$.*?(?=^#{1,2} |\Z)", text, re.M | re.S)
	if not match:
		return text, ""
	return (text[:match.start()] + text[match.end():]).strip(), match.group(0).strip()


def compose(notes, section):
	idx = notes.find("<details>")
	head, tail = (notes[:idx], notes[idx:]) if idx >= 0 else (notes, "")
	head, sha = split_sha(head.strip())
	if re.fullmatch(r"<details>\s*<summary>[^<]*</summary>\s*</details>", tail.strip()):
		tail = ""
	parts = [p for p in (head, section.strip(), sha, tail.strip()) if p]
	return "\n\n".join(parts) + "\n"


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument("--tag", required=True)
	ap.add_argument("--repo", required=True)
	ap.add_argument("--notes", required=True)
	group = ap.add_mutually_exclusive_group(required=True)
	group.add_argument("--assets-dir")
	group.add_argument("--assets-json", help="JSON list of {name, size}")
	args = ap.parse_args()
	if args.assets_dir:
		assets = [(n, os.path.getsize(os.path.join(args.assets_dir, n))) for n in sorted(os.listdir(args.assets_dir))]
	else:
		assets = [(a["name"], a["size"]) for a in json.load(open(args.assets_json, encoding="utf-8"))]
	notes = open(args.notes, encoding="utf-8").read()
	sys.stdout.reconfigure(encoding="utf-8", newline="\n")
	sys.stdout.write(compose(notes, downloads(args.tag, args.repo, assets)))


if __name__ == "__main__":
	main()
