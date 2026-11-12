#!/usr/bin/env python3
"""Checks that the Lua snippets in the tutorial pages are taken from the chapter's checkpoint files.

Every ```lua block in site/src/content/docs/tutorial/NN-*.mdx must consist of lines that appear in one of
site/tutorial/arena/NN-*.lua (indentation included). Blocks marked ```lua nocheck are skipped.
"""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent
pages = root.parent / "src" / "content" / "docs" / "tutorial"
code = root / "arena"

errors = 0
for page in sorted(pages.glob("[0-9][0-9]-*.mdx")):
    chapter = page.name[:2]
    files = sorted(code.glob(chapter + "-*.lua"))
    if not files:
        print(f"{page.name}: no checkpoint file {chapter}-*.lua")
        errors += 1
        continue
    known = set()
    for f in files:
        known.update(f.read_text(encoding="utf-8").splitlines())
    text = page.read_text(encoding="utf-8")
    for block in re.finditer(r"^```lua([^\n]*)\n(.*?)^```", text, re.M | re.S):
        if "nocheck" in block.group(1):
            continue
        first = text[: block.start()].count("\n") + 2
        for n, line in enumerate(block.group(2).splitlines()):
            if line.strip() and line not in known:
                print(f"{page.name}:{first + n}: not in {', '.join(f.name for f in files)}: {line.strip()}")
                errors += 1

if errors:
    sys.exit(1)
print("tutorial snippets match their checkpoints")
