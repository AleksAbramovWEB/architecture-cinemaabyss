#!/usr/bin/env python3
"""Check Strangler Fig routing on a running Compose stack; restore true/50 at exit."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import time
from urllib.error import URLError
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
BASE_URL = "http://localhost:8000"


def configure(gradual, percentage):
    env = dict(os.environ, GRADUAL_MIGRATION=str(gradual).lower(),
               MOVIES_MIGRATION_PERCENT=str(percentage))
    result = subprocess.run(
        ["docker", "compose", "up", "-d", "--no-deps", "--force-recreate", "proxy-service"],
        cwd=ROOT, env=env, capture_output=True, text=True, timeout=90,
    )
    if result.returncode:
        raise RuntimeError(result.stderr)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            with urlopen(BASE_URL + "/health", timeout=2) as response:
                if response.read().decode() == "Strangler Fig Proxy is healthy":
                    return
        except (URLError, OSError):
            pass
        time.sleep(0.25)
    raise TimeoutError("Proxy did not become ready")


def sample(count):
    routes = Counter()
    for _ in range(count):
        with urlopen(BASE_URL + "/api/movies", timeout=10) as response:
            if response.status != 200 or not isinstance(json.load(response), list):
                raise AssertionError("Movies response does not match the API")
            routes[response.headers.get("X-Cinemaabyss-Upstream", "missing")] += 1
    return dict(routes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=100)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.samples < 20:
        parser.error("use at least 20 samples to observe gradual routing")
    results = []
    try:
        for gradual, percentage in [(True, 0), (True, 50), (True, 100), (False, 0)]:
            configure(gradual, percentage)
            routes = sample(args.samples)
            expected = {"monolith"} if gradual and percentage == 0 else {"movies-service"}
            if gradual and percentage == 50:
                expected = {"monolith", "movies-service"}
            if set(routes) != expected:
                raise AssertionError(f"Unexpected routing for {gradual}/{percentage}: {routes}")
            results.append({"gradual_migration": gradual, "movies_migration_percent": percentage,
                            "requests": args.samples, "upstreams": routes})
    finally:
        configure(True, 50)
    report = {"checked_at": datetime.now(timezone.utc).isoformat(), "cases": results,
              "restored": {"gradual_migration": True, "movies_migration_percent": 50}}
    rendered = json.dumps(report, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered)
    print(rendered, end="")


if __name__ == "__main__":
    main()
