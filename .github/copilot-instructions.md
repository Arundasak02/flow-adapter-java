# Copilot Instructions — flow-adapter-java

## What This Repo Does

`flow-adapter-java` is a **build-time CLI tool**. It scans Java source code using JavaParser (AST parsing — not compilation), extracts method calls, class structure, Spring annotations, and Kafka usage, then produces a `flow.json` file in GEF 1.1 format. This file is either saved to disk or POSTed to FCS.

Multi-module Maven project:
- `flow-adapter/` — core scanner
- `flow-adapter-cli/` — Picocli entry point
- `flow-adapter-spring/` — Spring annotation plugin (SPI)
- `flow-adapter-kafka/` — Kafka plugin (SPI)

## Full Context

Complete documentation lives in the workspace at:
- **Quick start:** `flow-docs/agent/00-QUICK-START.md`
- **System map:** `flow-docs/agent/01-SYSTEM.md`
- **nodeId contract (critical):** `flow-docs/agent/02-CONTRACTS.md`
- **Current bugs:** `flow-docs/agent/03-BUGS-P0.md`
- **This repo deep-dive:** `flow-docs/agent/repos/flow-adapter-java.md`

## Critical Rules

1. **nodeId format is a cross-repo contract** — `SignatureNormalizer.java` must use the same algorithm as `NodeIdBuilder.java` in `flow-runtime-agent`. Format: `{FQCN}#{method}({shortParams}):{shortReturn}`. Never change one without the other.

2. **FQN_PATTERN regex** — the normalization regex `\b[a-z][a-z0-9]*(?:\.[a-z0-9$_]+)*\.([A-Z][a-zA-Z0-9$_]*)` is a shared contract. Both repos must keep it identical.

3. **Plugin system is SPI-based** — plugins are discovered via `ServiceLoader` at runtime. New plugins need a `META-INF/services/` registration file.

4. **No compilation required** — the scanner uses JavaParser which parses source text. Do not add anything that requires the project to be compilable.

## Known Bug to Avoid Making Worse

**Bug #3:** `SignatureNormalizer` false deduplication — if two methods have parameters of classes with the same simple name from different packages (e.g., `com.a.Order` and `com.b.Order`), they get the same nodeId. This is open. Don't make it worse by changing the normalization in isolation.

## When You Change Code — Checklist

- [ ] Changed `SignatureNormalizer` normalization algorithm → MUST also update `NodeIdBuilder.java` in `flow-runtime-agent` + update `flow-docs/agent/02-CONTRACTS.md` §1
- [ ] Changed `flow.json` output schema (GEF 1.1) → update `flow-docs/agent/02-CONTRACTS.md` §2
- [ ] Added a new plugin → document in `flow-docs/agent/repos/flow-adapter-java.md` (Plugin System section)
- [ ] Changed `GraphPublisher` field names → update `flow-docs/agent/02-CONTRACTS.md` §5 + verify FCS `IngestionController` expects same names
- [ ] Fixed Bug #3 → update `flow-docs/agent/03-BUGS-P0.md` + `flow-docs/issues/P0-CRITICAL.md` + `flow-docs/issues/resolved.md`

## Git Workflow — Required for Every Change

Before making **any** code change in this repo:

1. **Create a feature branch** — never commit directly to `main`/`master`.
   ```
   git checkout -b feature/<short-description>   # new feature
   git checkout -b fix/<short-description>        # bug fix
   git checkout -b chore/<short-description>      # refactor / docs / config
   ```

2. **Keep changes focused** — one concern per branch.

3. **Commit atomically** — one logical change, one commit.
   Message format: `<type>(<scope>): <short summary>`
   e.g. `fix(normalizer): prevent false dedup on same-simple-name params`

4. **Push immediately** after committing:
   ```
   git push -u origin <branch-name>
   ```

5. **Raise a Pull Request** against `main` and report the PR URL to the user.

> Do NOT push directly to `main`, force-push, or squash history without user approval.
