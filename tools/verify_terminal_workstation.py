#!/usr/bin/env python3
"""Run terminal lifecycle regressions against the application's compiled production sources."""
import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SUITES = (
    "InteractiveTerminalProcessTest",
    "TerminalStartupCommandQueueTest",
    "LocalProcessSupervisorTest",
    "BackgroundCommandManagerTest",
    "TerminalLaunchSpecTest",
    "TerminalPipeShellTest",
    "TerminalBackendStartTest",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", default=str(ROOT / "gradlew"), help="Gradle executable; defaults to the verified project wrapper")
    parser.add_argument("--test-task", default=":app:testDebugUnitTest",
                        choices=(":app:testDebugUnitTest", ":app:testDebugUiIsolated"))
    parser.add_argument("--init-script", type=Path, help="Optional host test setup; production compilation remains required")
    parser.add_argument("--gradle-arg", action="append", default=[], help="Additional Gradle argument, e.g. --gradle-arg=-Pkey=value")
    parser.add_argument("--jars", action="append", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.jars:
        print("--jars is obsolete: terminal verification now uses Gradle's complete application classpath.", file=sys.stderr)
    command = [args.gradle, "--no-daemon", "--no-build-cache", "--no-configuration-cache", "--dependency-verification=strict"]
    if args.init_script:
        command += ["--init-script", str(args.init_script.resolve(strict=True))]
    command += args.gradle_arg + [args.test_task]
    for suite in SUITES:
        command += ["--tests", f"com.baystudio.droide.core.{suite}"]
    return subprocess.call(command, cwd=ROOT)


if __name__ == "__main__":
    raise SystemExit(main())
