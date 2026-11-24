"""Write generated/rexglue.cmake from the SDK's template.

`rexglue codegen` only (re)writes generated/rexglue.cmake while migrating a
project to a newer SDK, so a fresh clone whose manifest is already stamped with
the current SDK version gets generated/default/ but no rexglue.cmake. The
template only substitutes a handful of names, rendered here the same way.

Usage: python3 scripts/render_rexglue_cmake.py <sdk_dir> <out_file>
"""
import re
import sys
from pathlib import Path


def main() -> int:
    sdk_dir, out_file = Path(sys.argv[1]), Path(sys.argv[2])
    text = (sdk_dir / "resources/templates/init/rexglue_cmake.inja").read_text()
    text = re.sub(r'\{\{ cmake_var\("([A-Za-z_]+)"\) \}\}', r"${\1}", text)
    text = (text.replace("{{ sdk_version }}", "0.9.2")
                .replace("{{ entrypoint_out_dir }}", "generated/default")
                .replace("{{ names.snake_case }}", "daytona"))
    leftover = re.findall(r"\{\{.*?\}\}|\{%.*?%\}", text)
    if leftover:
        print(f"error: unrendered template fields: {leftover}", file=sys.stderr)
        return 1
    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text(text)
    print(f"Wrote {out_file}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
