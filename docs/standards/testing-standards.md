# Testing Standards

The full catalogue of test types, tools per language and pass gates is the `Test_Catalog` sheet in
`tracker/Meterbook_Tracker.xlsx`. This page says **which tests a change must include**.

## Required per change

| Change | Required tests |
|---|---|
| New or changed function / class | Unit (white box), incl. boundary values and error paths |
| Business invariant (money, ordering, convergence) | Property-based test of the invariant |
| Anything touching a database, queue or cache | Integration test with real dependencies (Testcontainers) |
| Public endpoint | Functional (black box) + contract/schema test + tenant-isolation test + auth test |
| Mutating endpoint | Idempotency test (same key twice, concurrently) |
| Parser or decoder of untrusted input | Fuzz target |
| Concurrency (locks, goroutines, async) | Race detector / concurrency test |
| Schema migration | Migration up/down test on realistic data volume |
| New dependency on another service | Resilience test (timeout, error, slow response via Toxiproxy) |
| Bug fix | Regression test that fails before the fix |

## Gates (enforced in CI)

| Gate | Target |
|---|---|
| Line coverage | >= 90% |
| Branch coverage | >= 85% |
| Mutation score (nightly) | >= 75% |
| Lint / static analysis | 0 errors |
| SAST, dependency and image scans | 0 high/critical |

Coverage is a floor, not a goal: a test without a meaningful assertion is a defect.

## Rules

- **Tests are deterministic.** No sleeps for synchronisation; use clocks you control and await conditions with
  timeouts. A flaky test is quarantined within one day and fixed or deleted within one week.
- **Test names state behaviour:** `rejects_duplicate_idempotency_key_with_original_response`.
- **Property-based and fuzz tests log their seed** so failures reproduce.
- **No mocking what you don't own.** Wrap third-party clients behind an interface and mock that, or use the real
  thing in a container.
- **Test data is synthetic.** Never copy real customer data into fixtures.

## Pyramid and where tests run

| Stage | Runs | Budget |
|---|---|---|
| Pre-commit | lint, format, unit for changed packages, secrets scan | < 30 s |
| Pull request | unit, integration, contract, API schema, isolation, SAST/SCA | < 10 min |
| Nightly | full regression, E2E, mutation, fuzz (1 h), DAST | < 2 h |
| Pre-release | load, stress, spike, scalability, chaos, DR drill | per plan |
| Weekly | soak (8–24 h), chaos game day | per plan |

## Recording results

Every run that closes a tracker row gets its result and an evidence link recorded in the matching sheet:
`Test_Cases`, `Load_Stress_Runs`, `Chaos_Resilience`, `Coverage` or `Metrics_SLOs`.
