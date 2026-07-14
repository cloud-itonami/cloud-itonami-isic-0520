# cloud-itonami-isic-0520

Open Business Blueprint for **ISIC Rev.4 0520**: Mining of lignite —
an ISIC Wave 3 (production/mining) operations-coordination actor per
ADR-2607121000. Back-office and coordination workflow for lignite
(brown coal) mining operations, modeled closely on
`cloud-itonami-isic-0510`'s (Mining of hard coal) governed-actor
discipline.

**Maturity: `:implemented`** — LigniteOpsAdvisor ⊣ LigniteMiningGovernor
as a langgraph-clj StateGraph (`intake → advise → govern → decide →
commit/hold`, human-approval interrupt). All source `.cljc` (portable
to JVM / ClojureScript / GraalVM), no JVM-only interop.

## CRITICAL: Scope Exclusions

This actor **DOES NOT** and **NEVER WILL**:

- **Direct extraction sequencing** — blasting order, drilling patterns, cutting schedules, or overburden-removal/excavation sequencing
- **Mine-safety decisions** — subsidence-control determinations, dust-suppression system overrides, groundwater-drawdown control
- **Mine-safety-authority decisions** — permit issuance, license suspension, or compliance enforcement

This actor **only** coordinates back-office operations: production-record
logging, maintenance scheduling, safety-concern flagging (subsidence,
dust, groundwater — always routed to a human), and outbound lignite-
shipment coordination. Every proposal the advisor drafts carries
`:effect :propose` — never a direct actuation — and
`ligniteops.governor` independently re-scans every proposal's content
for the excluded scope areas above, regardless of op or confidence.

## Operations

Closed proposal-op allowlist (`ligniteops.governor/allowed-ops`), all
`:effect :propose`:

- `:log-production-record` — output/tonnage data logging
- `:schedule-maintenance` — equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface a mine-safety concern (subsidence, dust, groundwater) — **ALWAYS escalates**
- `:coordinate-shipment` — outbound lignite shipment coordination

**HARD invariants** (always `:hold`, never human-overridable):

1. **Site unverified** — the target mine/site record must exist AND be
   independently `:registered?`/`:verified?` in the store before any
   proposal for it may commit or even escalate.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/value touches
   blasting/drilling-pattern/cutting-schedule/extraction-sequencing/
   overburden-removal-sequencing/mine-safety-authority territory, is a
   permanent, un-overridable block. Evaluated unconditionally on every
   proposal.

**ESCALATE** (always human sign-off, when the governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

## Rollout phases (`ligniteops.phase`)

Phase 0 (read-only) → 1 (production logging, approval-gated) → 2 (adds
maintenance + shipment coordination, approval-gated) → 3 (supervised
auto: production-record/maintenance/shipment may auto-commit when
governor-clean and confident). `:flag-safety-concern` is deliberately
absent from every phase's `:auto` set — a permanent structural fact,
not a rollout milestone still to come — matching
`ligniteops.governor`'s own `always-escalate-ops` independently.

## Development

```bash
clojure -M:test   # run the full suite
clojure -M:run    # walk the demo scenarios (ligniteops.sim)
clojure -M:lint    # clj-kondo
```

AGPL-3.0-or-later, forkable by any qualified operator. Part of cloud-itonami.
