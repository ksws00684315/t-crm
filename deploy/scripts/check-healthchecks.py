#!/usr/bin/env python3
"""Fail if crm-admin / crm-redirect healthchecks still use dash's CMD-SHELL /dev/tcp."""
from __future__ import annotations

import pathlib
import re
import sys

COMPOSE = pathlib.Path(__file__).resolve().parents[1] / "docker-compose.prod.yml"
text = COMPOSE.read_text(encoding="utf-8")

# Top-level services only (two-space indent under "services:").
service_re = re.compile(r"(?m)^  ([a-z][a-z0-9-]*):\n((?:    .*\n|      .*\n|\n)*)")
services = {m.group(1): m.group(2) for m in service_re.finditer(text.split("networks:", 1)[0])}

for name in ("crm-admin", "crm-redirect"):
    block = services.get(name)
    if not block:
        print(f"missing service {name}", file=sys.stderr)
        sys.exit(1)
    hc = re.search(r"(?m)^    healthcheck:\n((?:      .*\n)+)", block)
    if not hc:
        print(f"{name}: no healthcheck", file=sys.stderr)
        sys.exit(1)
    # Drop comment lines before inspecting the probe form.
    body = "\n".join(
        line for line in hc.group(1).splitlines() if not line.lstrip().startswith("#")
    )
    if "CMD-SHELL" in body:
        print(
            f"{name}: healthcheck still uses CMD-SHELL (dash has no /dev/tcp)",
            file=sys.stderr,
        )
        sys.exit(1)
    if not re.search(r"\bbash\b", body):
        print(f"{name}: healthcheck does not invoke bash", file=sys.stderr)
        sys.exit(1)
    if "/dev/tcp/" not in body:
        print(f"{name}: healthcheck lost the /dev/tcp probe", file=sys.stderr)
        sys.exit(1)
    print(f"{name}: healthcheck uses bash + /dev/tcp OK")
print("healthcheck form OK")
