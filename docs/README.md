# Daily Money & Life Management — Design Pack

Built from `daily_money_life_management_app_specification.md` (v1.0), 2026-11-22.

| File | Contents |
|---|---|
| `01-ANALYSIS.md` | What the spec gets right, 17 gaps/contradictions with decisions, optimisation choices, release slices, risks |
| `02-HLD.md` | System context, Android + backend architecture, Gradle modules, tech stack, core engines, data flows, security, deployment, non-functional targets |
| `03-LLD.md` | ER diagram, enums, full PostgreSQL DDL, permission engine, sync protocol, approval state machine, split algorithm, recurrence/reminder engine, notification pipeline, API contract, Android LLD (Room, ViewModels, navigation, screen map), backend LLD, test mapping, task breakdown |
| `04-AUTH-LOGIN-LOGOUT.md` | Login/logout plan on **Firebase Auth** (Supabase = DB + Storage only): email+password and Google (Credential Manager), email action callbacks, screens + wireframes, flows, logout data wipe, Android classes, backend session endpoints, setup checklist, tests, tasks |
| `05-DESIGN-AND-STORAGE.md` | Review of the 5 reference designs, chosen design language, colour/type tokens, Material 3 component mapping, key screen wireframes; storage comparison (Supabase vs Firebase, MongoDB, Neon…), sizing and plan advice |

Diagrams are Mermaid; they render in GitHub, GitLab, IntelliJ/Android Studio (Markdown plugin) and VS Code.

Spec §26 says: review these documents before starting Phase 1.
