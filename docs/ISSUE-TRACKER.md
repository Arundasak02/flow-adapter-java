# Flow Java adapter — issue & enhancement tracker

Single place to record **open** problems, **verify** whether they still reproduce after changes, and **archive** what is done. Cursor agents should read this file when working in this repo and keep it honest (no stale “open” items).

## Conventions

| Column / field | Meaning |
|----------------|---------|
| **ID** | Stable id: `ISSUE-###` (bugs) or `ENH-###` (enhancements). Never reuse ids. |
| **Status** | `open` / `verified-active` / `resolved-pending-verify` / `resolved` |
| **Verify** | How to confirm fixed or still broken (command, module, or checklist). |

**Strikethrough:** use `~~text~~` in the Resolved table for the summary line once `resolved` is confirmed.

**Tracing:** Before closing an item, the agent (or developer) must run the **Verify** steps. If verify fails, set status back to `verified-active` and note the date.

## Active

| ID | Opened | Status | Area | Summary | Verify |
|----|--------|--------|------|---------|--------|
| *(none)* | | | | | |

_Add rows only after the user confirms they want the item tracked (see project rule `issue-tracker-workflow`)._

## Candidates (not tracked — confirm with user first)

_Do not copy into **Active** until the user agrees these are real issues for this project._

- **Classpath / JAR symbol resolution:** inherited `JpaRepository` methods (`findAll`, `save`, …) do not produce `CALL` edges because declarations live in dependency JARs, not project source. _Enhancement: compile classpath + `JarTypeSolver`._
- **Spring `HANDLES` vs accessor skip:** `@GetMapping` handler named like `getPetTypes()` may be omitted as a bean accessor while the Spring plugin still emits `HANDLES` → orphan edge. _Fix: don’t skip mapped controller methods or align plugin ids._

## Resolved

| ID | Resolved | Summary | Verify (last passed) |
|----|----------|---------|----------------------|
| ~~TEMPLATE~~ | YYYY-MM-DD | Replace this row when first real closure is recorded | n/a |

---

## Changelog (optional)

- YYYY-MM-DD — Tracker created; workflow enforced via `.cursor/rules/issue-tracker-workflow.mdc`.
