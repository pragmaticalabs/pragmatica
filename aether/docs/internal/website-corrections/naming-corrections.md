# Naming Consistency Corrections

Two naming changes to apply across all public-facing materials:
1. **"Pragmatica Lite Core"** → **"Pragmatica Core"**
2. **"Distributed runtime"** → **"Unified Application Runtime"** (for Aether)

Note: Maven groupId `org.pragmatica-lite` and artifactId `core` remain unchanged — these are published coordinates on Maven Central.

---

## 1. GitHub Repo "About" Description

**Current:** "Pragmatica Monorepo: Functional Java library, JBCT tooling, and Aether distributed runtime"

**Fix:** "Pragmatica Monorepo: Functional Java library, JBCT tooling, and Aether Unified Application Runtime"

**Command:** `gh repo edit pragmaticalabs/pragmatica --description "..."`

---

## 2. Root README.md

| Line | Current | Fix |
|------|---------|-----|
| 9 | `Pragmatica Lite Core` | `Pragmatica Core` |
| 10 | `Pragmatica Lite Integrations` | `Pragmatica Integrations` |
| 12 | `Distributed runtime for Java` | `Unified Application Runtime for Java` |
| 19 | `pragmatica-lite: 0.11.3` | Keep (historical version) |
| 135 | `Using Pragmatica Lite Core` | `Using Pragmatica Core` |
| 177 | `# Pragmatica Lite Core` | `# Pragmatica Core` |
| 206 | `pragmatica-lite/` | Keep (directory name) |
| 219 | `Distributed runtime` | `Unified Application Runtime` |

Maven coordinates (`org.pragmatica-lite`) stay as-is — they're published artifacts.

---

## 3. core/README.md

| Line | Current | Fix |
|------|---------|-----|
| 1 | `# Pragmatica Lite` | `# Pragmatica Core` |
| 9 | `Pragmatica Lite brings the power` | `Pragmatica Core brings the power` |
| 11 | `Why Pragmatica Lite?` | `Why Pragmatica Core?` |
| 82 | `Pragmatica Lite targets Java 25` | `Pragmatica Core targets Java 25` |
| 90 | `Pragmatica Lite is available on Maven Central` | `Pragmatica Core is available on Maven Central` |

---

## 4. aether/README.md

| Line | Current | Fix |
|------|---------|-----|
| 1 | `# Pragmatica Aether` | `# Aether` or `# Aether — Unified Application Runtime` |
| 3 | `Distributed runtime for Java` | `Unified Application Runtime for Java` |

---

## 5. aether/pom.xml

| Current | Fix |
|---------|-----|
| `<name>Pragmatica Aether Distributed Runtime</name>` | `<name>Aether Unified Application Runtime</name>` |

---

## 6. core/pom.xml

| Current | Fix |
|---------|-----|
| `<name>Pragmatica Lite Core</name>` | `<name>Pragmatica Core</name>` |

---

## 7. Aether Docs

| File | Line | Current | Fix |
|------|------|---------|-----|
| aether-overview.md | 7 | `built on Pragmatica Lite` | `built on Pragmatica Core` |
| aether-overview.md | 58 | `Pragmatica Lite core` | `Pragmatica Core` |
| architecture/11-slice-container.md | 11 | `Pragmatica Lite core` | `Pragmatica Core` |
| architecture/11-slice-container.md | 47 | `Pragmatica Lite core` | `Pragmatica Core` |
| contributors/consensus.md | 18 | `Pragmatica Lite Cluster` | `Pragmatica Cluster` |
| contributors/concepts.md | 1 | `Distributed Runtime Without the Complexity` | `Unified Application Runtime` |
| contributors/architecture.md | 3 | `distributed runtime architecture` | `Unified Application Runtime architecture` |
| contributors/slice-runtime.md | 3 | `Aether distributed runtime` | `Aether runtime` |
| docs/README.md | 3 | `Aether distributed runtime` | `Aether Unified Application Runtime` |
| reference/feature-catalog.md | 3 | `Aether distributed runtime` | `Aether Unified Application Runtime` |
| specs/hierarchical-storage-spec.md | 37 | `Aether distributed runtime` | `Aether runtime` |
| specs/rbac-spec.md | 1 | `Aether Distributed Runtime` | `Aether Unified Application Runtime` |
| specs/cloud-integration-spi-spec.md | 32 | `Aether distributed runtime` | `Aether runtime` |

**Archive files** (aether/docs/archive/) — leave as-is, they're historical.

---

## 8. JBCT Skill (system)

**File:** `~/.claude/skills/jbct/SKILL.md`

| Current | Fix |
|---------|-----|
| `Pragmatica Core 0.25.0` | Already correct! Uses "Pragmatica Core" |
| `org.pragmatica-lite` (Maven coords) | Keep as-is |

Already uses "Pragmatica Core" — no changes needed.

---

## 9. aether-coder Skill (system)

No references to "Pragmatica Lite" — clean.

---

## 10. CHANGELOG.md

| Line | Current | Fix |
|------|---------|-----|
| 865 | `Pragmatica Lite CHANGELOG` | `Pragmatica Core CHANGELOG` |

---

## Summary

| Scope | "Pragmatica Lite" → "Pragmatica Core" | "distributed runtime" → "Unified Application Runtime" |
|-------|---------------------------------------|------------------------------------------------------|
| Repo description | — | 1 change |
| Root README | 4 changes | 2 changes |
| core/README | 5 changes | — |
| aether/README | — | 1 change |
| POM names | 1 change (core) | 1 change (aether) |
| Aether docs | 4 changes | 8+ changes |
| CHANGELOG | 1 change | — |
| Archive docs | Skip (historical) | Skip (historical) |
| Skills | Already correct | N/A |
| Maven groupId | **Keep as-is** | N/A |
