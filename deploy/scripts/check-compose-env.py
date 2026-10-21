#!/usr/bin/env python3
"""Fail if a required TCRM_* placeholder is not passed to its service or listed in .env.example.

A placeholder without a default (``${TCRM_X}``) makes the application fail at startup when the
variable is missing. Unit and integration tests set these values themselves, so a Compose file that
forgets to pass one only shows up when the container starts (task 34, fixed in #87).
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
APPS = {"crm-admin": "backend", "crm-redirect": "redirect"}
COMPOSE_FILES = ("docker-compose.prod.yml", "docker-compose.preview.yml")

REQUIRED_RE = re.compile(r"\$\{(TCRM_[A-Z0-9_]+)\}")
SERVICE_RE = re.compile(r"(?m)^  ([a-z][a-z0-9-]*):\n((?:    .*\n|\n)*)")
ENV_KEY_RE = re.compile(r"(?m)^      (TCRM_[A-Z0-9_]+):")
EXAMPLE_KEY_RE = re.compile(r"(?m)^(TCRM_[A-Z0-9_]+)=")


def required_vars(app: str) -> set[str]:
    found: set[str] = set()
    for config in sorted((ROOT / app / "src/main/resources").glob("application*.yml")):
        for line in config.read_text(encoding="utf-8").splitlines():
            if not line.lstrip().startswith("#"):
                found.update(REQUIRED_RE.findall(line))
    return found


def compose_env(path: pathlib.Path) -> dict[str, set[str]]:
    text = path.read_text(encoding="utf-8")
    services = text.split("\nservices:\n", 1)[1].split("\nnetworks:", 1)[0]
    return {
        name: set(ENV_KEY_RE.findall(body)) for name, body in SERVICE_RE.findall(services + "\n")
    }


def main() -> int:
    errors: list[str] = []
    example = set(EXAMPLE_KEY_RE.findall((ROOT / "deploy/.env.example").read_text("utf-8")))
    for compose_name in COMPOSE_FILES:
        env = compose_env(ROOT / "deploy" / compose_name)
        for service, app in APPS.items():
            if service not in env:
                errors.append(f"{compose_name}: missing service {service}")
                continue
            for var in sorted(required_vars(app) - env[service]):
                errors.append(f"{compose_name}: {service} does not pass required {var}")
    for app in APPS.values():
        for var in sorted(required_vars(app) - example):
            errors.append(f"deploy/.env.example: required {var} ({app}) is not listed")
    for error in errors:
        print(error, file=sys.stderr)
    if not errors:
        print("required TCRM_* variables are passed in every Compose file")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
