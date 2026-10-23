# Git Workflow

## Branches

- `main` is always releasable and protected: PR required, CI green, no force pushes.
- Feature branches: `mb-t<nn>-<short-name>`, e.g. `mb-t12-rate-limits`. Setup work spanning several
  tasks may use a short phase branch such as `foundation`.
- Short-lived: merge within a few days; rebase on `main` rather than merging `main` in.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/) with the task ID as scope:

```
feat(MB-T07): add OIDC login
fix(MB-T12): return Retry-After on quota exhaustion
test(MB-T08): add cross-tenant isolation tests
docs(MB-T01): add ADR template
```

Types: `feat`, `fix`, `test`, `docs`, `refactor`, `perf`, `build`, `ci`, `chore`.

## Pull requests

- Title: `MB-T07: Add OIDC login`.
- Use the PR template; link the task, ADRs, and evidence.
- One logical change per PR; aim for under 400 changed lines excluding generated code.
- Squash-merge to keep `main` history one-commit-per-change.

## Releases

- Services are versioned with SemVer tags per service: `<service>/v1.2.0` (e.g. `gateway/v1.2.0`).
- Changelog generated from Conventional Commits.
