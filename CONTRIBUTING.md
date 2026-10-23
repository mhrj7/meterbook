# Contributing

1. Pick a task from `tracker/Meterbook_Tracker.xlsx` (sheet `Plan`) and set it to **In Progress**.
2. Branch from `main`: `mb-t<nn>-<short-name>`, e.g. `mb-t07-oidc-login`.
3. If the change touches an API, update `contracts/` first and get it reviewed.
4. If the change makes a significant decision, add an ADR (`docs/adr/template.md`).
5. Write code and tests together, following [`docs/standards/`](docs/standards/).
6. Open a PR titled `MB-T07: <summary>` using the PR template. CI must be green.
7. After merge, update the tracker: task status, Done Date, Evidence link, and any Test_Cases or Metrics rows the
   task closed.

Full rules: [git workflow](docs/standards/git-workflow.md) · [coding](docs/standards/coding-standards.md) ·
[testing](docs/standards/testing-standards.md) · [APIs](docs/standards/api-standards.md)
