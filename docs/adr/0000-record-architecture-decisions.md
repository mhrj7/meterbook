# ADR-0000: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-10-23
- **Task:** MB-T01
- **Deciders:** Mohit Raj

## Context

Meterbook spans several services and languages. Decisions such as "Kafka or NATS", "SERIALIZABLE or
row locks" or "ClickHouse or a custom store" will be questioned later, by reviewers, interviewers and future
contributors. Without a written record, the reasoning is lost and decisions get relitigated or silently reversed.

## Options considered

### Option A — Architecture Decision Records in the repo (Nygard format)
- Pros: versioned with the code; reviewed in PRs; cheap to write; widely recognised.
- Cons: needs discipline to keep current.

### Option B — Wiki or external docs
- Pros: easier rich formatting.
- Cons: drifts from the code; not reviewed alongside changes; lost if the tool changes.

### Option C — No formal record
- Pros: zero overhead.
- Cons: reasoning lost; no evidence of engineering judgement.

## Decision

We will record every significant decision as an ADR in `docs/adr/`, using `docs/adr/template.md`, numbered
sequentially and listed in `docs/adr/README.md`. A decision is significant if it is hard to reverse, affects more
than one service, or selects a technology. ADRs are immutable once accepted; a changed decision gets a new ADR
that supersedes the old one.

## Consequences

- **Positive:** reasoning is reviewable and searchable; interview-ready explanations exist for every major choice.
- **Negative / accepted trade-offs:** small writing overhead per decision.
- **Follow-up work:** ADR-0001 (standalone product repository).

## How we will know it was wrong

PRs routinely make significant decisions with no ADR, or ADRs stop being read in reviews.
