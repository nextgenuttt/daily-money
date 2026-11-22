# 01 — Specification Analysis & Optimisation Decisions

Source: `~/Downloads/daily_money_life_management_app_specification.md` (v1.0)
Date: 2026-11-22

This file reviews the spec before design starts: what is sound, what is missing or
contradictory, and the decisions that make the app lighter, faster and cheaper to build.
The HLD (`02-HLD.md`) and LLD (`03-LLD.md`) apply these decisions.

---

## 1. What the spec gets right (keep as-is)

| Area | Why it is right |
|---|---|
| Expense stored once, shared by reference | Avoids N copies per member, keeps totals consistent |
| Creator-only editing, no `EXPENSE_EDIT_ALL` | Removes a whole class of edit conflicts and audit disputes |
| Explicit deny beats role allow | Standard, predictable permission semantics |
| `BigDecimal` / `NUMERIC`, never float | Mandatory for money |
| Instant approval as default | Family use case stays friction-free |
| Transactional outbox | Reliable events without distributed transactions |
| SMS detection deferred, Play-policy aware | Avoids the most common Play Store rejection |
| Phased roadmap, "design first, wait for review" | Correct for a scope this large |

---

## 2. Gaps, ambiguities and contradictions

| # | Issue | Impact | Decision |
|---|---|---|---|
| G1 | "Transaction" (§5) vs "Expense" (§11–20) used interchangeably. APIs are `/expenses` but table is `transactions`; income is never mentioned for organizations. | Duplicate models / endpoints | One `transactions` table with `type = DEBIT/CREDIT`. API resource is `/transactions`; `/expenses` is a filtered alias (`type=DEBIT`). Organizations support income too (e.g. shared trip fund). |
| G2 | Offline-first + approval: an org expense created offline cannot know its approval outcome. | Wrong totals on device | Local status `PENDING_SYNC`. Server decides `APPROVED` / `PENDING_APPROVAL` on push. Org totals on device only count `APPROVED` rows. |
| G3 | Conflict resolution "resolve explicitly" is undefined. | Biggest sync risk | Creator-only editing means the **only** possible writer of a financial row is one user (possibly on 2 devices). Use optimistic `version` check; on mismatch, server wins and the client shows a "changed on another device" banner with a re-apply option. No CRDTs needed. |
| G4 | Delta sync with visibility: when a member loses access (removed, or creator narrows `SELECTED_MEMBERS`), the device still holds the row. Spec does not say how it is purged. | Privacy leak (spec §22) | Server emits per-user **access-revoked tombstones** in the sync feed (LLD §6.4). Device deletes the row + attachments cache. |
| G5 | WebSocket events "filtered by visibility" means re-implementing authorization in a second channel. | Duplicate security logic, leak risk | WebSocket and FCM carry **no business data** — only a "sync poke" (`{scope, cursor}`). Client pulls via REST delta, where the single authorization path runs. |
| G6 | Notification previews must respect privacy, but FCM payloads pass through Google and show on lock screen. | Leak via push | FCM data-only messages with a generic template ID; device renders text from local, already-authorized data. Sensitive amounts hidden on lock screen (`VISIBILITY_PRIVATE` + public version). |
| G7 | Currency precision: `NUMERIC(19,2)` breaks for JPY (0 dp) or KWD/BHD (3 dp). Multi-currency conversion not specified. | Wrong amounts | Store `amount_minor BIGINT` + `currency CHAR(3)` (ISO 4217 exponent table). One currency per organization in v1; personal accounts may differ; reports convert with a stored daily rate (phase 11+). |
| G8 | Room has no `BigDecimal` type; storing as TEXT makes `SUM()` and sorting wrong. | Slow, buggy reports | Room stores `amountMinor: Long`. Domain layer exposes a `Money(minor: Long, currency)` value class; `BigDecimal` only for split/percent math and display. |
| G9 | Recurrence format not specified for reminders, events, tasks, transactions. | 4 different engines | One recurrence model: RFC 5545 `RRULE` string + `DTSTART` + time zone. Occurrences materialized in a rolling window (next 60 days). |
| G10 | Exact alarms: Android 14+ denies `SCHEDULE_EXACT_ALARM` by default; `USE_EXACT_ALARM` is restricted by Play to alarm/calendar apps. | Reminders fire late or app rejected | Request `SCHEDULE_EXACT_ALARM` with an in-app explanation; fall back to `setAndAllowWhileIdle` / WorkManager when denied. Only bill-due and event-start use exact alarms. |
| G11 | Account deletion vs "never delete financial audit records" vs shared org expenses others depend on. | Legal / data conflict | On account deletion: personal data hard-deleted; org expenses kept but creator anonymised ("Former member"); audit rows keep a pseudonymous user ID. Documented in privacy policy. |
| G12 | Removed member's own org expenses: spec says they "lose organization access" but expenses still count. | Ambiguous totals | Expenses remain in org totals and become read-only for everyone (creator can no longer edit — membership inactive). |
| G13 | "Reapproval for material changes" undefined. | Inconsistent behaviour | Material = amount, category, date, or visibility widened. Edit of a material field on an approved expense in `REQUIRE_APPROVAL`/matching `CONDITIONAL` org → back to `PENDING_APPROVAL`. Description/notes edits do not trigger reapproval. |
| G14 | Splits on `SELECTED_MEMBERS` / approval interplay undefined. | Leaks / wrong balances | Split participants must be able to see the expense (auto-added to allowed members). Balances only count splits on `APPROVED` expenses. |
| G15 | Authentication method left open ("JWT or managed"). | Blocks phase 1 | Email + password and Google Sign-In via **Firebase Authentication** (see 04-AUTH-LOGIN-LOGOUT.md). The app sends the Firebase ID token on every call; Spring Boot verifies it. **Supabase is used only for data**: Postgres + file Storage, reached only by the backend. |
| G16 | Notification data model has 9 tables with overlapping meaning. | Over-engineering | Consolidated to 5 tables (LLD §4.7). |
| G17 | No non-functional targets (latency, scale, battery, APK size). | Can't verify "optimised" | Targets defined in HLD §9. |

---

## 3. Optimisation decisions (summary)

### 3.1 Scope — ship an MVP first

34 screens and ~65 tables is 9–12 months for a small team. Recommended release slices:

| Release | Contents | Roadmap phases |
|---|---|---|
| **R1 — Personal (MVP)** | Auth, accounts, categories, transactions, budgets, reports, reminders + events, to-do, local notifications, offline, backup | 1–4 |
| **R2 — Groups** | Organizations, roles + overrides, visibility, shared expenses, splits, settlements, sync feed, push | 5–7, 9 |
| **R3 — Governance** | Approval modes, expense messages, notification center + preferences | 8, 10 |
| **R4 — Smart** | OCR, statement import, categorization, optional SMS | 11 |

R1 can run **without a backend at all** (Room + local backup). That gives a usable Play Store
app early and validates the UI while R2's backend is built.

### 3.2 Architecture — fewer moving parts

| Spec suggests | Optimised choice | Reason |
|---|---|---|
| Implicit services | **Modular monolith** (Spring Modulith) | One deployable, enforced module boundaries, split later only if needed |
| WebSocket with filtered events | FCM data message = **poke only**, REST delta pull | One authorization path, no WebSocket server to run |
| 9 notification tables | 5 tables | Simpler queries, same features |
| Per-request permission evaluation from 3 tables | **Effective-permission bitmask cached** per `(org, user)`, invalidated by `perm_version` | O(1) checks |
| Visibility check per row | Visibility as **one SQL predicate** + Postgres RLS as backstop | No N+1, defence in depth |
| `NUMERIC(19,2)` | `BIGINT amount_minor` | Exact, fast `SUM`, Room-compatible |
| Separate recurrence per module | One `RRULE` engine | Less code, fewer bugs |
| Per-feature dashboards computed on read | Monthly **aggregate tables** updated in the same transaction | Dashboard in 1 query |

### 3.3 Android performance

- Multi-module Gradle (`:core:*`, `:feature:*`) — faster incremental builds, enforced layering.
- R8 full mode + Baseline Profiles + Startup Profiles — cold start < 1 s target.
- Room with indexed `(owner, date)` columns, paging 3 for lists, `Flow` with `distinctUntilChanged`.
- Compose: stable/immutable UI models, `LazyColumn` keys, no heavy work in composition.
- Sync in a single `WorkManager` unique work with constraints (network, battery not low), exponential back-off — no polling loops.
- Charts drawn with Compose `Canvas` (no heavy chart library) — smaller APK.

### 3.4 Risks to track

| Risk | Mitigation |
|---|---|
| Play policy (SMS, exact alarms, financial app declaration) | SMS only in R4 behind permissions declaration; exact alarm fallback; complete Data Safety form |
| Data leak through sync / push / attachments | Single authz path, tombstones, signed short-lived attachment URLs, security test suite (spec §24 tests 9–15) |
| Split rounding disputes | Largest-remainder method, deterministic order, sum invariant enforced by DB check |
| Scope creep | Release slices above; each phase ends with the spec §26 report |
| Battery drain from sync | Poke-based sync, batched pulls, no periodic sync < 15 min |
