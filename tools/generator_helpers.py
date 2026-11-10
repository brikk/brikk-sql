"""Shared generator helpers; escaping variants intentionally preserve existing output."""

import json
from pathlib import Path
import subprocess


SQLGLOT = Path(__file__).resolve().parents[1] / "reference" / "sqlglot"


def sqlglot_version() -> str:
    """Describe the reference clone, retaining the generators' broad unknown fallback."""
    try:
        return subprocess.run(
            ["git", "-C", str(SQLGLOT), "describe", "--tags", "--abbrev=9"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except Exception:
        return "unknown"


def kotlin_string(s: str) -> str:
    """Quoted literal: escape Kotlin metacharacters, C0 controls and DEL."""
    out = []
    for ch in s:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "$":
            out.append("\\$")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ord(ch) < 0x20 or ord(ch) == 0x7F:
            out.append(f"\\u{ord(ch):04X}")
        else:
            out.append(ch)
    return '"' + "".join(out) + '"'


def kotlin_quoted_string(s: str) -> str:
    """Quoted literal escaping Kotlin metacharacters, C0 controls and DEL."""
    return kotlin_string(s)


def kotlin_text_string(s: str) -> str:
    """Quoted literal also escaping newline, carriage return and tab, no other controls."""
    out = s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    out = out.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return '"' + out + '"'


def kotlin_json_string(value):
    """JSON's nullable, non-ASCII-preserving spelling, with Kotlin dollar escaping."""
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$") if value is not None else "null"
