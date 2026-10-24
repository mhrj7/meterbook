"""Fails if a job sets its own `defaults.run` without `shell: bash`.

Job-level defaults replace the workflow-level ones, so a job that only sets a working directory runs its steps with
`bash -e` instead of `bash -eo pipefail`: a failing command piped into tee or grep then passes. That hid a failed
verification once (MB-T07); this check keeps it from coming back.
"""

import glob
import sys

import yaml


def main() -> int:
    problems = []
    for path in sorted(glob.glob(".github/workflows/*.yml")):
        with open(path, encoding="utf-8") as f:
            workflow = yaml.safe_load(f)
        for name, job in (workflow.get("jobs") or {}).items():
            run = ((job or {}).get("defaults") or {}).get("run")
            if run is not None and run.get("shell") != "bash":
                problems.append(f"{path}: job {name} sets defaults.run without shell: bash")
    for problem in problems:
        print(f"FAIL  {problem}")
    if not problems:
        print("PASS  every job-level defaults.run keeps shell: bash (pipefail)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
