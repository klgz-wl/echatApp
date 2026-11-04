#!/usr/bin/env python3
"""独立校验文档包摘要、相对链接和路径约束；可生成完整 ZIP。仅用标准库。"""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


ROOT = Path(__file__).resolve().parent
MANIFEST = "版本清单.json"
REQUIRED = {"归因实施与校验规范.md", "实施与验收记录模板.md", "检查文档包.py"}
LINK = re.compile(r"\[[^\]\n]*\]\(([^\s)]+)\)")
ANCHOR = re.compile(r'<a\s+id="([^"]+)"\s*></a>')
HOST_PATH = re.compile(r"/(?:Users|home|tmp|private|var)/|~[/\\]|(?<![A-Za-z0-9])[A-Za-z]:[\\/]|file://")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def package_files():
    paths = list(ROOT.rglob("*"))
    require(not any(p.is_symlink() for p in paths), "文档包不能包含符号链接")
    return {p.relative_to(ROOT).as_posix(): p for p in paths if p.is_file()}


def check_links(name, document):
    anchors = ANCHOR.findall(document)
    require(len(anchors) == len(set(anchors)), f"{name}：存在重复章节锚点")
    # 本包链接使用无空格的 Markdown 内联形式；代码块不作为附件引用解析。
    prose = re.sub(r"```[^\n]*\n.*?```", "", document, flags=re.S)
    for raw in LINK.findall(prose):
        target = urlsplit(raw)
        if target.scheme:
            require(target.scheme == "https" and bool(target.netloc),
                    f"{name}：仅允许 HTTPS 外链或包内相对链接：{raw}")
            continue
        require(not target.netloc and not target.query, f"{name}：非法相对链接：{raw}")
        part = unquote(target.path)
        require(not Path(part).is_absolute() and "\\" not in part,
                f"{name}：链接不能使用电脑绝对路径：{raw}")
        resolved = (ROOT / name).parent.joinpath(part).resolve() if part else ROOT / name
        require(resolved.is_relative_to(ROOT), f"{name}：链接越出文档包：{raw}")
        require(resolved.is_file(), f"{name}：附件不存在：{raw}")
        if target.fragment:
            content = resolved.read_text(encoding="utf-8")
            require(unquote(target.fragment) in ANCHOR.findall(content),
                    f"{name}：章节锚点不存在：{raw}")


def validate():
    files = package_files()
    require(MANIFEST in files, "缺少版本清单")
    manifest = json.loads(files[MANIFEST].read_text(encoding="utf-8"))
    require(manifest.get("schema") == 1, "不支持的清单格式")
    version = manifest.get("version", "")
    require(bool(re.fullmatch(r"\d+\.\d+\.\d+", version)), "版本号格式错误")
    require(manifest.get("entry") == "归因实施与校验规范.md", "主文档入口错误")
    records = manifest.get("files")
    require(isinstance(records, dict) and set(records) == REQUIRED, "清单文件集合不完整")
    require(set(files) == REQUIRED | {MANIFEST}, "目录中存在缺失或未列入清单的文件")
    for name, expected in records.items():
        require(digest(files[name]) == expected, f"{name}：SHA-256 不匹配，请核对交付版本")
        if name.endswith(".md"):
            document = files[name].read_text(encoding="utf-8")
            require(version in document, f"{name}：未标注对应版本")
            require(not HOST_PATH.search(document), f"{name}：存在开发电脑路径或本地文件 URI")
            check_links(name, document)
    return manifest, files


def archive(destination, manifest, files):
    output = Path(destination).expanduser().resolve()
    require(not output.is_relative_to(ROOT), "ZIP 必须输出到文档包目录之外")
    require(not output.exists(), "ZIP 输出已存在，不覆盖原文件")
    # 使用固定包名，不依赖接收方将本目录重命名成什么。
    prefix = "android-attribution-spec-" + manifest["version"]
    with ZipFile(output, mode="x", compression=ZIP_DEFLATED) as bundle:
        for name in sorted(files):
            info = ZipInfo(prefix + "/" + name, date_time=(2026, 11, 3, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            bundle.writestr(info, files[name].read_bytes())
    print(f"已生成 ZIP：{output.name}，包含 {len(files)} 个文件")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zip", metavar="输出文件", help="检查通过后生成完整 ZIP，不覆盖已有文件")
    args = parser.parse_args()
    try:
        manifest, files = validate()
        print(f"文档包 {manifest['version']} 检查通过：摘要、相对链接、章节锚点及路径约束")
        if args.zip:
            archive(args.zip, manifest, files)
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"检查失败：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
