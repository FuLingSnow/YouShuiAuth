#!/usr/bin/env python3
"""从 CHANGELOG.md 抽取指定版本段落，供 GitHub Release 作为正文使用。

用法：
    python3 scripts/extract_changelog.py v2.0.0 [CHANGELOG.md]

规则：
    · 定位 `## v<version>` 或 `## <version>` 开头的标题行
    · 截取到下一个 `## ` 标题之前
    · 去掉标题行本身（Release 页面已显示 tag 名）
    · 找不到对应版本时返回非 0，由 CI 步骤失败暴露问题
"""
import re
import sys
from pathlib import Path


def extract(tag: str, path: Path) -> str:
    version = tag[1:] if tag.lower().startswith("v") else tag
    if not path.is_file():
        raise FileNotFoundError(f"未找到 {path}")

    lines = path.read_text(encoding="utf-8").splitlines()
    pattern = re.compile(r"^##\s+v?" + re.escape(version) + r"(\s|$)")

    start = next((i for i, line in enumerate(lines) if pattern.match(line.strip())), None)
    if start is None:
        raise LookupError(f"CHANGELOG 中未找到版本 {version}")

    end = next(
        (j for j in range(start + 1, len(lines)) if lines[j].startswith("## ")),
        len(lines),
    )
    body = "\n".join(lines[start + 1:end]).strip()
    return body or f"（CHANGELOG 中 {version} 段落为空）"


def main() -> int:
    if len(sys.argv) < 2:
        print("用法: extract_changelog.py <tag> [CHANGELOG.md]", file=sys.stderr)
        return 2

    tag = sys.argv[1].strip()
    path = Path(sys.argv[2] if len(sys.argv) > 2 else "CHANGELOG.md")

    try:
        print(extract(tag, path))
    except (FileNotFoundError, LookupError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
