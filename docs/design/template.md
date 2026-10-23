# Design Doc: <System or feature name>

- **Author(s):**
- **Status:** Draft | In review | Approved | Implemented
- **Last updated:** YYYY-MM-DD
- **Tasks:** MB-T??
- **Reviewers:**

## 1. Overview

Two or three sentences: what this is and why it matters.

## 2. Goals and non-goals

**Goals** (each measurable):
- G1:

**Non-goals** (explicitly out of scope):
- NG1:

## 3. Background

What exists today, what users need, and why now.

## 4. Requirements

### Functional
- FR1:

### Non-functional (link the SLO doc)
| Requirement | Target | How measured |
|---|---|---|
| Availability | | |
| Latency (p99) | | |
| Throughput | | |
| Durability / data loss | | |
| Tenant isolation | | |

## 5. Capacity estimate

Back-of-envelope: requests/s, bytes per request, storage growth per day, peak-to-average ratio, and the
resulting instance, partition and storage counts. Show the arithmetic.

## 6. High-level design

Diagram (Mermaid or image) of components and data flow, then one paragraph per component.

## 7. Detailed design

### 7.1 API (link `contracts/`)
### 7.2 Data model and storage
### 7.3 Consistency, ordering and idempotency
### 7.4 Multi-tenancy and isolation
### 7.5 Failure modes

| Failure | Detection | Impact | Mitigation |
|---|---|---|---|
| | | | |

## 8. Alternatives considered

What else was evaluated and why it lost. Link ADRs.

## 9. Security and privacy

Threat model summary (STRIDE), authentication and authorization, data classification, PII handling, audit.

## 10. Observability

Metrics, traces, logs, dashboards, alerts (link SLO doc).

## 11. Testing and rollout plan

Test levels (link Test_Cases IDs), load and chaos plan (link run IDs), migration and rollout steps, rollback plan.

## 12. Cost

Estimated monthly infrastructure cost at launch scale and at 10x.

## 13. Open questions

- Q1:
