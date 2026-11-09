#!/usr/bin/env python3
"""Compare compiled JVM signatures without loading classes or resolving dependencies."""
import argparse
import hashlib
import json
from pathlib import Path
import struct


def class_api(data):
    offset = 0
    def take(length):
        nonlocal offset
        result = data[offset:offset + length]
        if len(result) != length:
            raise ValueError("Truncated class file")
        offset += length
        return result
    def u2():
        return struct.unpack(">H", take(2))[0]
    def u4():
        return struct.unpack(">I", take(4))[0]
    if u4() != 0xCAFEBABE:
        raise ValueError("Not a JVM class")
    u2()
    major = u2()
    count = u2()
    pool = [None] * count
    index = 1
    while index < count:
        tag = take(1)[0]
        if tag == 1:
            pool[index] = take(u2()).decode("utf-8", errors="replace")
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = u2()
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            take(4)
        elif tag in (5, 6):
            take(8)
            index += 1
        elif tag == 15:
            take(3)
        else:
            raise ValueError(f"Unknown constant pool tag {tag}")
        index += 1
    access, name, parent = u2(), u2(), u2()
    interfaces = [pool[pool[u2()]] for _ in range(u2())]
    def attributes():
        names = set()
        for _ in range(u2()):
            names.add(pool[u2()])
            take(u4())
        return names
    def members():
        result = {}
        for _ in range(u2()):
            flags, name, descriptor = u2(), u2(), u2()
            attributes()
            if flags & 5:
                result[(pool[name], pool[descriptor])] = flags
        return result
    fields, methods = members(), members()
    class_attributes = attributes()
    return dict(name=pool[pool[name]], parent=pool[pool[parent]] if parent else None,
                access=access, interfaces=interfaces, fields=fields, methods=methods, major=major,
                local="EnclosingMethod" in class_attributes)


def compare(old, new):
    problems = []
    old_flags, new_flags = old.get("access", 1), new.get("access", 1)
    if old_flags & 1 and not new_flags & 1 or not old_flags & 16 and new_flags & 16 or not old_flags & 1024 and new_flags & 1024:
        problems.append("incompatible class visibility/final/abstract flags")
    if old["parent"] != new["parent"] or not set(old["interfaces"]).issubset(new["interfaces"]):
        problems.append("superclass/interfaces changed")
    for kind in ("fields", "methods"):
        for key, flags in old[kind].items():
            current = new[kind].get(key)
            if current is None:
                problems.append(f"missing {kind}: {key}")
            elif flags & 8 != current & 8 or flags & 1 and not current & 1 or not flags & 16 and current & 16 or not flags & 1024 and current & 1024:
                problems.append(f"incompatible flags for {kind}: {key}")
    return problems


def inventory(directory):
    files = sorted(directory.rglob("*.class"))
    if not files:
        raise ValueError(f"No compiled classes in {directory}")
    return {p.relative_to(directory).as_posix(): p.read_bytes() for p in files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--max-major", type=int, default=61)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    before, after = inventory(args.before), inventory(args.after)
    problems = {}
    local = []
    for name, data in before.items():
        old = class_api(data)
        if old["local"]:
            local.append(name)
            continue
        if name not in after:
            problems[name] = ["class removed"]
        else:
            issues = compare(old, class_api(after[name]))
            if issues:
                problems[name] = issues
    newer = [name for name, data in after.items() if class_api(data)["major"] > args.max_major]
    report = dict(before_classes=len(before), after_classes=len(after), added_classes=sorted(after.keys() - before.keys()),
                  local_implementation_classes=local,
                  max_major=args.max_major, incompatible=problems, newer_bytecode=newer,
                  before_sha256=hashlib.sha256(b"".join(name.encode() + hashlib.sha256(data).digest() for name, data in before.items())).hexdigest(),
                  after_sha256=hashlib.sha256(b"".join(name.encode() + hashlib.sha256(data).digest() for name, data in after.items())).hexdigest())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    if problems or newer:
        raise SystemExit(f"ABI/bytecode differences require review: {args.output}")
    print(f"Verified {len(before) - len(local)} prior non-local class signatures; {len(after)} current classes, JVM major <= {args.max_major}")


if __name__ == "__main__":
    main()
