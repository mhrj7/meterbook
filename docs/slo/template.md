# SLO: <Service name>

- **Product:** Meterbook
- **Owner:**
- **Status:** Draft | Approved
- **Last reviewed:** YYYY-MM-DD (review every quarter)
- **Error-budget policy:** [error-budget-policy.md](error-budget-policy.md)

## 1. Users and critical journeys

Who depends on this service and which journeys matter most. SLOs measure these journeys, not machine health.

| Journey | User | Why it matters |
|---|---|---|
| | | |

## 2. Service level indicators (SLIs)

Each SLI is a ratio of good events to valid events, measured at the load balancer or server edge unless
stated otherwise.

| SLI | Good event | Valid event | Source (PromQL / query) |
|---|---|---|---|
| Availability | Response status not 5xx | All requests except health checks | `sum(rate(http_requests_total{code!~"5.."}[5m])) / sum(rate(http_requests_total[5m]))` |
| Latency | Request served in <= X ms | All successful requests | `histogram_quantile` or bucket ratio `le="X"` |
| Correctness / freshness | <domain-specific> | | |

## 3. SLOs

Window: rolling 30 days.

| SLI | Objective | Error budget (30 d) | Tracker metric ID |
|---|---|---|---|
| Availability | 99.95% | 21.6 min of failed requests equivalent | M-MB-?? |
| Latency p99 | <= X ms for 99% of requests | 1% of requests may exceed X ms | M-MB-?? |
| | | | |

Budget arithmetic: 30 days = 43,200 minutes. 99.9% leaves 43.2 min; 99.95% leaves 21.6 min; 99.99% leaves 4.3 min.

## 4. Alerting (multi-window, multi-burn-rate)

Page only when the budget is burning fast enough to matter. Based on the Google SRE Workbook approach.

| Severity | Burn rate | Long window | Short window | Budget consumed if sustained | Action |
|---|---|---|---|---|---|
| Page | 14.4x | 1 h | 5 min | 2% in 1 h | Page on-call |
| Page | 6x | 6 h | 30 min | 5% in 6 h | Page on-call |
| Ticket | 3x | 1 d | 2 h | 10% in 1 d | Ticket, next business day |
| Ticket | 1x | 3 d | 6 h | 10% in 3 d | Ticket, review in weekly ops |

An alert fires only when both the long and the short window exceed the burn rate, which keeps alerts fast to
fire and fast to clear.

## 5. Dependencies

| Dependency | Its SLO | Our mitigation if it fails |
|---|---|---|
| | | |

Our objective must be achievable given our dependencies; a service cannot promise more availability than the
product of its hard dependencies without redundancy.

## 6. Exclusions

What does not count against the budget (e.g. client 4xx errors, planned maintenance announced 7 days ahead,
traffic from load tests tagged `synthetic=true`).

## 7. Evidence

Link the dashboard, the alert rules (with `promtool test rules` output), and the tracker rows that record
measured values.
