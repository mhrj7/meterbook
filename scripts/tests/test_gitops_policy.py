"""Unit tests for scripts/gitops_policy.py: the real manifests pass, and each rule catches a violation."""
import pathlib
import shutil
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import gitops_policy

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SHA_A = "a" * 40
SHA_B = "b" * 40


@pytest.fixture
def tree(tmp_path):
    shutil.copytree(REPO_ROOT / "infra" / "argocd", tmp_path / "infra" / "argocd")
    return tmp_path


def edit(tree, rel, old, new):
    path = tree / "infra" / "argocd" / rel
    text = path.read_text()
    assert old in text, f"{old!r} not in {rel}"
    path.write_text(text.replace(old, new))


def current_prod_pin(tree):
    text = (tree / "infra/argocd/envs/prod/platform-secrets.yaml").read_text()
    return next(line.split()[-1] for line in text.splitlines() if "targetRevision:" in line)


def errors(tree):
    return gitops_policy.check(tree, check_ancestry=False)


def test_repository_manifests_pass(tree):
    assert errors(tree) == []


def test_prod_tracking_a_branch_fails(tree):
    edit(tree, "envs/prod/platform-secrets.yaml", current_prod_pin(tree), "main")
    assert any("prod source must be pinned to a full commit SHA" in e for e in errors(tree))


def test_prod_pins_must_agree(tree):
    pin = current_prod_pin(tree)
    edit(tree, "envs/prod/platform-secrets.yaml", pin, SHA_A)
    edit(tree, "envs/prod/services.yaml", pin, SHA_B)
    assert any("must pin the same commit" in e for e in errors(tree))


def test_staging_must_track_main(tree):
    edit(tree, "envs/staging/services.yaml", "targetRevision: main", f"targetRevision: {SHA_A}")
    assert any("staging template source must track main" in e for e in errors(tree))


def test_chart_version_ranges_fail(tree):
    edit(tree, "envs/dev/external-secrets.yaml", "targetRevision: 2.13.0", "targetRevision: 2.x")
    assert any("must use an exact version" in e for e in errors(tree))


def test_staging_without_self_heal_fails(tree):
    edit(tree, "envs/staging/platform-secrets.yaml", "selfHeal: true", "selfHeal: false")
    assert any("staging must sync with prune and selfHeal" in e for e in errors(tree))


def test_services_cannot_target_another_namespace(tree):
    edit(tree, "envs/dev/services.yaml", "namespace: meterbook-dev", "namespace: kube-system")
    assert any("services must deploy to meterbook-dev" in e for e in errors(tree))


def test_roots_must_track_main(tree):
    edit(tree, "roots/dev.yaml", "targetRevision: main", f"targetRevision: {SHA_A}")
    assert any("root source must track main" in e for e in errors(tree))


def test_unknown_project_fails(tree):
    edit(tree, "envs/dev/platform-secrets.yaml", "project: platform", "project: default")
    assert any("project must be one of" in e for e in errors(tree))


def test_pinned_commit_must_be_on_main(tree):
    pin = current_prod_pin(tree)
    edit(tree, "envs/prod/platform-secrets.yaml", pin, SHA_A)
    edit(tree, "envs/prod/services.yaml", pin, SHA_A)
    shutil.copytree(REPO_ROOT / ".git", tree / ".git", symlinks=True)
    found = gitops_policy.check(tree, check_ancestry=True)
    assert any(f"pinned commit {SHA_A} is not on main" in e for e in found)
