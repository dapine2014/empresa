#!/usr/bin/env python3
# Diagnóstico 360 (2026-10-03): los tests que fallan en el mismo formato que dotnet test, para que Java los lea.
import json, re, sys

names, errors, prints, failed, package = {}, {}, {}, [], None
try:
    with open('/work/pubspec.yaml') as f:
        m = re.search(r'^name:\s*([a-z0-9_]+)', f.read(), re.M)
        package = m.group(1) if m else None
except OSError:
    pass
for line in open(sys.argv[1], encoding='utf-8', errors='replace'):
    try:
        event = json.loads(line)
    except ValueError:
        continue
    if not isinstance(event, dict):
        continue
    if event.get('type') == 'testStart':
        test = event.get('test', {})
        names[test.get('id')] = test.get('name', '')
    elif event.get('type') == 'print':
        # flutter_test deja el detalle (Expected/Actual y el stack) en mensajes print del test.
        prints.setdefault(event.get('testID'), []).append(str(event.get('message', '')))
    elif event.get('type') == 'error':
        errors.setdefault(event.get('testID'), []).append((event.get('error', ''), event.get('stackTrace', '')))
    elif event.get('type') == 'testDone' and event.get('result') != 'success' and not event.get('hidden'):
        failed.append(event.get('testID'))

frame = re.compile(r'(?:\((?:file:///work/)?((?:package:)?[^\s()]+\.dart):(\d+):\d+\))|(?:(package:[^\s]+\.dart|[^\s()]+\.dart) (\d+):\d+)')
for test_id in failed:
    print(f"  Failed {names.get(test_id, test_id)}")
    print("  Error Message:")
    detail = "\n".join(prints.get(test_id, []))
    useful = [l for l in detail.splitlines() if re.search(r'Expected:|Actual:|Which:|Exception|Error|was thrown', l)]
    for line in (useful[:15] or [str(e) for e, _ in errors.get(test_id, [])][:5]):
        print(f"   {line.strip()}")
    print("  Stack Trace:")
    stacks = [str(s) for _, s in errors.get(test_id, [])] + [detail]
    for stack in stacks:
        for m in frame.finditer(stack):
            path = m.group(1) or m.group(3)
            line_no = m.group(2) or m.group(4)
            if package and path.startswith(f"package:{package}/"):
                path = "lib/" + path[len(f"package:{package}/"):]
            elif path.startswith("package:") or path.startswith("dart:"):
                continue
            path = path.replace('file:///work/', '')
            if path.startswith('/'):
                continue
            print(f"     at {path} in /work/{path}:line {line_no}")
