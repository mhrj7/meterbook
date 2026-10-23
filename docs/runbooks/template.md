# Runbook: <Alert or scenario name>

- **Service:**
- **Alert:** `<alert name>` (link to rule)
- **Severity:** SEV1 | SEV2 | SEV3
- **Owner:**
- **Last tested:** YYYY-MM-DD

## What this alert means

One paragraph: which SLO is burning and what users experience.

## Immediate actions (first 5 minutes)

1. Acknowledge the page.
2. Check the service dashboard: <link>
3. Check recent deploys: <link>. If a deploy happened in the last 30 minutes, roll it back first.
4. Post in the incident channel; open an incident if user-facing.

## Diagnosis

| Symptom | Likely cause | Check | Fix |
|---|---|---|---|
| | | | |

## Escalation

Who to page next, and when.

## After the incident

- Write a postmortem within 48 hours (blameless).
- Add a regression test or alert that would have caught it earlier.
- Update this runbook.
