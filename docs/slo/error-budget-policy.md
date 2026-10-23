# Error-Budget Policy

- **Status:** Approved
- **Applies to:** every service with an SLO in `docs/slo/`
- **Task:** MB-T02
- **Last reviewed:** 2026-10-23

## Purpose

SLOs define how reliable each service must be. The error budget (100% minus the SLO) is the amount of
unreliability we accept in a rolling 30-day window. This policy says what happens as that budget is spent, so
the trade-off between shipping features and fixing reliability is decided in advance, not argued during an
incident.

## Budget states and required actions

| State | Budget remaining (30 d) | Feature work | Required actions |
|---|---|---|---|
| **Healthy** | > 50% | Normal | Chaos experiments and load tests may run in staging and, if planned, production. |
| **Caution** | 25–50% | Normal, with extra review | Every production change needs a rollback plan in the PR. Review top error sources in the weekly log. |
| **Critical** | 0–25% | Only changes that are low-risk or improve reliability | No risky migrations or infra changes. At least one reliability task is in progress at all times. |
| **Exhausted** | < 0% | **Frozen** | Only fixes for reliability, security, and S1-critical bugs ship. A postmortem is required. The freeze lifts when the budget is back above 0% over the 30-day window **and** the postmortem actions with a fix date in the window are done. |

## Single-incident rule

Any single incident that consumes more than **20%** of a 30-day budget requires a postmortem within 48 hours,
with at least one action item that would prevent or shorten a repeat, tracked as a task in the Plan sheet.

## Postmortems

- Blameless: focus on systems and signals, not individuals.
- Template sections: summary, impact (users, duration, budget consumed), timeline, root causes, what went well,
  what went badly, action items with owners and dates.
- Stored in `docs/postmortems/YYYY-MM-DD-<slug>.md` and linked in the tracker's `Defects` sheet.

## Exceptions

- **Dependency outages** (e.g. a cloud provider region failure) still count against the budget, because users
  felt them. The postmortem decides whether more redundancy is worth its cost.
- **Load tests and chaos experiments** tagged `synthetic=true` do not count, provided they run in staging or were
  announced in advance.
- The SLO owner may grant a written, time-boxed exception for a business-critical launch while in Critical. No
  exceptions in Exhausted.

## Review

- Monthly: budget consumption per service is recorded in the weekly log and the Metrics_SLOs sheet.
- Quarterly: each SLO is reviewed. If the budget is never touched, the SLO is probably too loose or the service
  is over-engineered; if it is always exhausted, the SLO or the architecture needs to change.
