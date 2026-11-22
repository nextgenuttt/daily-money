# 05 — UI Design Review & Storage Choice

Reviews the 5 reference designs shared on 2026-11-22 and answers "is Supabase the right storage?".
The references are Dribbble-style concepts. We use them **for ideas only** and build our own
look on Material 3. Copying them as-is would also clash with each other: five different styles.

---

## Part A — Design review

### A.1 Reference by reference

| # | Reference | Keep | Drop / fix |
|---|---|---|---|
| 1 | **Events Calendar** (blue/pink/yellow, month grid + agenda) | Month grid with **coloured dots** per day; dark date block ("21 MAY") next to each item; day-by-day agenda view; search on the agenda | Event chips that run **off the right edge** (sideways scroll inside a vertical list is hard to use) → stack items vertically under each day. The **play button + progress bar** on events means nothing for a reminder app. Four colours with no legend. Pale grey text fails contrast. |
| 2 | **My Reminders** (purple/pink) | "Today" hero card with **completed / pending** counts; event detail with a **week strip** + **preparation agenda** (Buy gift → Decorations → Celebrate). That is exactly our event-prep feature (spec §8). The create form as a **bottom sheet**, with date picker and colour tag. | A monthly "event statistic" chart is a vanity number; use that space for **spending** instead. Text on the gradient is low contrast. Typos in the mock ("Oktober", "Des"). |
| 3 | **Bill Reminder** (dark green, minimal) | **Best base for our visual style**: clean, calm, finance-trustworthy. **Upcoming / Overdue** segmented tabs, amount on the card, "Repeats Quarterly · 20 days to go", round **mark-as-paid** check. Add form with Bill type, Name, Amount, Frequency, Date and **Expense / Income** toggle (Income = our "money collection" reminder). | A green tick on an *upcoming* bill reads as "already paid" → use an empty circle, filled once paid. Add a **Paid** tab (history). Text is too small (≈11 sp) → 14 sp minimum. Show the category icon by type, not a cart for everything. |
| 4 | **Bill Reminder** (orange) | **Summary strip** UPCOMING 1 · PAID 18 · OVERDUE 1; **filter chips** All / Upcoming / Overdue / Paid; status shown as **text + colour** (good for colour-blind users); **relative due dates** ("19 days ago", "in 3 days") | Decorative orange blob takes space. Unselected chips are almost invisible. The amount colour (navy) doesn't follow meaning. |
| 5 | **Hey, Dan!** (sage green, friendly) | Greeting + "Today" list; **swipe to complete**; conversational add flow with **quick templates** (Drink water, HW due, Custom…); big time picker + **day-of-week selector** for repeats; bottom nav with a **centre + button** | Illustration header pushes content below the fold. Grey-on-grey cards have low contrast. Tilted mockups hide how it really looks. No money anywhere (it's a habits app). |

### A.2 Verdict

Use **#3's visual language** (clean, dark green, plenty of white space) as the base, then add:
- structure from **#4**: summary counters + filter chips,
- **#2**: Today card, week strip, preparation checklist, bottom-sheet create,
- **#1**: month calendar with category dots + date block,
- **#5**: greeting, swipe-to-complete, quick templates, centre + button.

One design language across the app, built from Material 3 components. Nothing custom-drawn
except charts and the calendar dots.

### A.3 Design system (tokens)

| Token | Light | Dark | Use |
|---|---|---|---|
| `primary` | `#1E4D3B` deep green | `#8CD3B0` | Buttons, selected tab, FAB, brand |
| `onPrimary` | `#FFFFFF` | `#00382A` | Text on primary |
| `surface` | `#FFFFFF` | `#121614` | Cards |
| `background` | `#F5F7F6` | `#0C0F0E` | Screen background |
| `surfaceVariant` | `#E8EEEB` | `#1E2522` | Inputs, unselected chips |
| `textPrimary` / `textSecondary` | `#16201C` / `#5A6661` | `#E6ECE9` / `#A3B0AA` | Body / meta text (≥ 4.5:1 contrast) |
| `statusOverdue` | `#C62828` | `#FF8A80` | Overdue, always with "Overdue" text + icon |
| `statusUpcoming` | `#B26A00` | `#FFCC80` | Due soon |
| `statusPaid` | `#2E7D32` | `#A5D6A7` | Paid / done |
| `money.expense` / `money.income` | `#16201C` with "−" / `#2E7D32` with "+" | | Expenses stay neutral (not red), so the app doesn't feel alarming |
| Category palette (6) | blue `#3B6FD8`, pink `#D6336C`, amber `#E0A100`, teal `#14998A`, purple `#6B4FBB`, slate `#5F6B7A` | lighter tints | Calendar dots, chart slices, category chips |

- **Type:** one family (Inter or the default Roboto Flex). Amounts use **tabular numbers**.
  Sizes: Display 32 (hero amount), Title 20, Body 16, Label 14; nothing under 12.
- **Shape:** cards 16 dp radius, chips 8 dp, sheets 28 dp top. **Spacing** on a 4 dp grid, 16 dp screen gutter.
- **Elevation:** flat cards with 1 dp outline in light mode, tonal surfaces in dark mode (no heavy shadows).
- Android **edge-to-edge** with system bars (the references show iOS status bars).
- Dynamic colour (Material You) **off by default**, so the brand colours stay recognisable; available as an option in Settings.

### A.4 Component mapping (Material 3, Compose)

| Pattern from references | Compose component |
|---|---|
| Upcoming / Overdue / Paid tabs | `SingleChoiceSegmentedButtonRow` |
| All / Upcoming / Overdue / Paid filters | `FilterChip` row |
| Summary counters | 3 × `Card` in a `Row`, each tappable → applies the filter |
| Bill / event cards | `ElevatedCard` / `OutlinedCard` + `ListItem` |
| Mark paid circle | `IconToggleButton` → opens the "Paid amount & date" sheet (spec §7) |
| Swipe to complete / snooze | `SwipeToDismissBox` (right = done, left = snooze) with undo `Snackbar` |
| Create reminder / bill | `ModalBottomSheet` (full screen on small phones) |
| Date / time | `DatePickerDialog`, `TimePicker`, custom day-of-week `FilterChip` row |
| Month calendar + dots | Custom `LazyVerticalGrid` (7 columns), max 3 dots + "+" |
| Week strip | `LazyRow` of day pills, snaps to today |
| Prep checklist | `ListItem` + `Checkbox` with left colour bar |
| Bottom nav + centre + | `NavigationBar` (Home, Money, ➕, Reminders, Groups) + quick-add sheet. To-Do moves under Reminders as a tab (keeps 4 items + the + button). |

### A.5 Key screens (our version)

```
HOME                                   BILLS (Money → Bills)                ADD BILL (bottom sheet)
┌──────────────────────────────┐       ┌──────────────────────────────┐     ┌──────────────────────────────┐
│ Hi, Vignesh 👋        🔔 2    │       │ ← Bills                 🔍   │     │ ──                            │
│ Personal ▾                   │       │ ┌────────┬────────┬────────┐ │     │ New bill reminder             │
│ ┌──────────────────────────┐ │       │ │UPCOMING│  PAID  │OVERDUE │ │     │ [ Expense | Income ]          │
│ │ November spent           │ │       │ │   4    │   18   │   1    │ │     │ Type     [⚡ Electricity ▾]   │
│ │ ₹18,450  of ₹25,000      │ │       │ └────────┴────────┴────────┘ │     │ Name     [TNEB bill      ]    │
│ │ ▓▓▓▓▓▓▓▓▓▓▓░░░░  74%     │ │       │ (All)(Upcoming)(Overdue)(Paid)│     │ Amount   [₹ 2,000        ]    │
│ │ Income ₹60,000  Saved ₹… │ │       │ ┌──────────────────────────┐ │     │ Repeats  [Monthly ▾]          │
│ └──────────────────────────┘ │       │ │⚡ Electricity     ₹2,000 │ │     │ Due      [📅 5 Dec 2026]      │
│ Today                         │       │ │ Due 5 Dec · in 3 days ⚠  │ │     │ Remind   (3 days)(1 day)(On day)│
│ ┌──────────────────────────┐ │       │ │ Repeats monthly      ○   │ │     │ Account  [HDFC UPI ▾]         │
│ │○ Pay rent        ₹12,000 │ │       │ └──────────────────────────┘ │     │ Group    [Personal ▾]         │
│ │○ Ana's birthday — wish 🎂│ │       │ ┌──────────────────────────┐ │     │                               │
│ │✓ Gym (done)              │ │       │ │💳 Credit card    ₹3,459 │ │     │ [      Save reminder      ]   │
│ └──────────────────────────┘ │       │ │ Due 1 Dec · 19 days ago  │ │     └──────────────────────────────┘
│ Upcoming bills      View all  │       │ │ OVERDUE              ○   │ │
│ Spending by category  [donut]│       │ └──────────────────────────┘ │
│ Family: you owe ₹750  Settle │       │                              │
├──────────────────────────────┤       └──────────────────────────────┘
│ 🏠   💰    (+)    🔔   👥     │
└──────────────────────────────┘

CALENDAR                               EVENT DETAIL
┌──────────────────────────────┐       ┌──────────────────────────────┐
│ ‹   December 2026   ›  [▦|☰] │       │ ←  Ana's birthday        ✎   │
│ S  M  T  W  T  F  S          │       │ Sun 10 Dec · All day         │
│       1  2  3  4  5          │       │ [Sun8][Mon9][Tue10][Wed11]   │
│ 6  7  8  9 10 11 12          │       │ Preparation                  │
│          •• •               │       │ ▌☑ Buy gift      Dec 5       │
│ … (dots = category colours)  │       │ ▌☐ Order cake    Dec 9       │
│ ─ Thu 10 Dec ─────────────── │       │ ▌☐ Wish Ana      Dec 10      │
│ [10 DEC] Ana's birthday  🎂  │       │ + Add step                   │
│ [10 DEC] EB bill ₹2,000  ⚡  │       │ Notes · Attachments          │
└──────────────────────────────┘       └──────────────────────────────┘
```

### A.6 Rules every screen follows (from spec §23)

- Every list has **empty**, **loading** (skeleton) and **error** (retry) states, with a friendly
  empty state + action ("No bills yet. Add your first bill").
- Status is never shown by colour alone: always **icon + text**.
- Amounts are right-aligned, tabular, with a currency sign; a "hide amounts" toggle (eye icon) for use in public.
- Touch targets ≥ 48 dp; works at 200 % font size; TalkBack reads "Electricity bill, 2,000 rupees, due in 3 days, not paid".
- Destructive actions (delete, sign out) ask for confirmation; swipe actions offer **Undo**.
- Light + dark themes from the same tokens.

Next design step: a clickable prototype of these 5 screens (HTML or Figma) to approve the look
before writing Compose code.

---

## Part B — Storage: is Supabase right?

**Yes. Keep Supabase.** Short reason: this app's data is **relational money data** (users ↔
organizations ↔ roles ↔ permissions, expenses ↔ splits ↔ settlements). It needs **ACID transactions**,
**foreign keys**, **check constraints** ("split shares must add up to the total") and **SQL reports**.
That is what Postgres is built for, and the spec already asks for PostgreSQL.

### B.1 Comparison

| Option | Type | Fit for this app | Main issue |
|---|---|---|---|
| **Supabase** ✅ | Managed Postgres + file Storage (we use only these two) | Excellent: relational, transactions, constraints, RLS, file storage with signed URLs | Free plan pauses inactive projects |
| Firebase Firestore | NoSQL documents | Weak for money | No joins, limited aggregation, hard multi-document invariants (split sums, balances), complex permission rules, cost grows with every read |
| MongoDB Atlas | NoSQL documents | Weak | Same relational problems; we'd rebuild constraints in code |
| Neon / AWS RDS / Cloud SQL | Plain managed Postgres | Good DB | No file storage built in, so a second service is needed |
| Appwrite / PocketBase | Backend-as-a-service | OK for small apps | Smaller ecosystem; PocketBase is SQLite (single server) |
| Self-hosted Postgres on a VPS | Postgres | Cheap at scale | You handle backups, upgrades, security, uptime |

**Lock-in is low:** Supabase is standard Postgres. Our Spring Boot backend talks plain SQL, so
moving later to Neon/RDS is a `pg_dump` + a new auth provider. That's why Spring Boot stays in the
design instead of putting all logic in Supabase functions.

### B.2 The full storage picture

| Data | Where | Why |
|---|---|---|
| Everything on the phone (offline) | **Room (SQLite) + SQLCipher** encryption | Works offline, instant UI, encrypted at rest |
| Settings, session | **DataStore + Tink** | Small encrypted key-value |
| Server database | **Supabase Postgres** (schema `app`) | Source of truth for shared/group data |
| Receipts, attachments | **Supabase Storage** (private bucket) | Same platform and auth; signed URLs |
| Push notifications | **FCM** | Supabase has no push |
| Later, if file volume grows | **Cloudflare R2** for files | Cheaper storage and no download (egress) fees; switch behind our `attachment` module without app changes |

### B.3 Size estimate — when do you need to pay?

Rough sizing (row + indexes ≈ 1 KB per transaction; a receipt photo compressed on device to WebP ≈ 150–250 KB):

| Active users | Transactions / user / month | DB growth / year | Receipts (20 % of txns) / year |
|---|---|---|---|
| 100 | 100 | ≈ 0.12 GB | ≈ 5 GB |
| 1,000 | 100 | ≈ 1.2 GB | ≈ 50 GB |
| 10,000 | 100 | ≈ 12 GB | ≈ 500 GB |

With Supabase's limits (check current pricing; at the time of writing the **Free** plan is about
500 MB DB, 1 GB files, 50k monthly active users, and pauses after a week with no activity, while
**Pro** is about US$25/month with roughly 8 GB DB and 100 GB files included):
- **Development and R1 testing:** Free plan is fine.
- **Public launch:** Pro plan, mainly because Free pauses inactive projects and has no backups you can rely on.
- **Thousands of users with many receipts:** move files to R2 before the DB needs upgrading.

To keep storage small:
- Compress receipts on the phone (max 1600 px, WebP quality 75) and make a 300 px thumbnail.
- R1 personal data can stay on the phone only (Room + backup), so the server holds only R2 group data.
- Partition `audit_log` by month and move old notifications to an archive after 90 days.

### B.4 Decision

| Item | Decision |
|---|---|
| Server DB | **Supabase Postgres** — confirmed |
| Auth | **Firebase Authentication** (doc 04); Supabase Auth not used |
| Files | Supabase Storage now → Cloudflare R2 when files exceed the plan |
| On device | Room + SQLCipher, DataStore + Tink |
| Push | FCM |
| Plan | Free for development; Pro before public launch |
