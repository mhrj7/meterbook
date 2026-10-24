#!/usr/bin/env python3
"""GitOps policy check for infra/argocd (ADR-0006). Exits non-zero with one line per violation.

Rules:
  - dev and staging Applications/ApplicationSets track `main`.
  - Every git source in prod is pinned to the same full commit SHA, and that commit is on main.
  - Helm chart sources use an exact version (no ranges).
  - Roots track main and use the `bootstrap` project.
  - staging and prod sync automatically with prune and self-heal; dev syncs automatically.
  - Services (project meterbook-apps) deploy only into meterbook-<env>.

Usage: scripts/gitops_policy.py [--repo-root PATH] [--no-ancestry]
"""
import argparse
import pathlib
import re
import subprocess
import sys

import yaml

REPO_URL = "https://github.com/mhrj7/meterbook.git"
SHA = re.compile(r"^[0-9a-f]{40}$")
EXACT_VERSION = re.compile(r"^\d+\.\d+\.\d+$")
APP_PROJECTS = {"platform", "meterbook-apps"}


def documents(directory):
    for path in sorted(pathlib.Path(directory).glob("*.yaml")):
        for doc in yaml.safe_load_all(path.read_text()):
            if doc:
                yield path, doc


def sources(spec):
    if "sources" in spec:
        return list(spec["sources"])
    return [spec["source"]] if "source" in spec else []


def revisions(doc):
    """(label, repoURL, revision, chart) for every source of an Application or ApplicationSet."""
    out = []
    if doc["kind"] == "Application":
        for s in sources(doc["spec"]):
            out.append(("source", s.get("repoURL"), str(s.get("targetRevision")), s.get("chart")))
    elif doc["kind"] == "ApplicationSet":
        for g in doc["spec"].get("generators", []):
            if "git" in g:
                out.append(("generator", g["git"].get("repoURL"), str(g["git"].get("revision")), None))
        for s in sources(doc["spec"]["template"]["spec"]):
            out.append(("template source", s.get("repoURL"), str(s.get("targetRevision")), s.get("chart")))
    return out


def app_spec(doc):
    return doc["spec"]["template"]["spec"] if doc["kind"] == "ApplicationSet" else doc["spec"]


def on_main(repo_root, sha):
    for ref in ("origin/main", "main"):
        # Exit status is the answer (0: ancestor, 1: not), so a non-zero status is not an error here.
        result = subprocess.run(["git", "-C", str(repo_root), "merge-base", "--is-ancestor", sha, ref],
                                capture_output=True, check=False)
        if result.returncode == 0:
            return True
    return False


def check(repo_root, check_ancestry=True):
    argocd = pathlib.Path(repo_root) / "infra" / "argocd"
    errors = []

    for path, doc in documents(argocd / "roots"):
        name = f"{path.name}:{doc['metadata']['name']}"
        if doc["spec"].get("project") != "bootstrap":
            errors.append(f"{name}: roots must use project 'bootstrap'")
        for label, repo, rev, _ in revisions(doc):
            if repo == REPO_URL and rev != "main":
                errors.append(f"{name}: root {label} must track main, found {rev}")

    prod_pins = set()
    for env in ("dev", "staging", "prod"):
        for path, doc in documents(argocd / "envs" / env):
            if doc["kind"] not in ("Application", "ApplicationSet"):
                continue
            name = f"envs/{env}/{path.name}:{doc['metadata']['name']}"
            spec = app_spec(doc)

            for label, repo, rev, chart in revisions(doc):
                if chart:
                    if not EXACT_VERSION.match(rev):
                        errors.append(f"{name}: chart {chart} must use an exact version, found {rev}")
                elif repo == REPO_URL:
                    if env == "prod":
                        if not SHA.match(rev):
                            errors.append(f"{name}: prod {label} must be pinned to a full commit SHA, found {rev}")
                        else:
                            prod_pins.add(rev)
                    elif rev != "main":
                        errors.append(f"{name}: {env} {label} must track main, found {rev}")
                else:
                    errors.append(f"{name}: {label} uses an unexpected repository {repo}")

            project = spec.get("project")
            if project not in APP_PROJECTS:
                errors.append(f"{name}: project must be one of {sorted(APP_PROJECTS)}, found {project}")
            namespace = spec.get("destination", {}).get("namespace")
            if project == "meterbook-apps" and namespace != f"meterbook-{env}":
                errors.append(f"{name}: services must deploy to meterbook-{env}, found {namespace}")

            automated = (spec.get("syncPolicy") or {}).get("automated")
            if automated is None:
                errors.append(f"{name}: automated sync is required")
            elif env in ("staging", "prod") and not (automated.get("prune") and automated.get("selfHeal")):
                errors.append(f"{name}: {env} must sync with prune and selfHeal")

    if len(prod_pins) > 1:
        errors.append(f"envs/prod: all git sources must pin the same commit, found {sorted(prod_pins)}")
    if check_ancestry:
        for sha in prod_pins:
            if not on_main(repo_root, sha):
                errors.append(f"envs/prod: pinned commit {sha} is not on main")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo-root", default=pathlib.Path(__file__).resolve().parent.parent)
    parser.add_argument("--no-ancestry", action="store_true", help="skip the 'pinned commit is on main' check")
    args = parser.parse_args()
    errors = check(args.repo_root, check_ancestry=not args.no_ancestry)
    for e in errors:
        print(f"FAIL  {e}")
    if errors:
        return 1
    print("PASS  GitOps policy: dev/staging track main, prod pinned to one commit on main, charts exact, sync policies ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
