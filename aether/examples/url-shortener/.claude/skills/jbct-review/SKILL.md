---
name: jbct-review
description: Thorough parallel JBCT code review. Launches 10 focused reviewers plus aggregator for comprehensive compliance checking.
---

# JBCT Parallel Code Review

Comprehensive JBCT compliance review using parallel focused workers with aggregation.

## Usage

```
/jbct-review                           # Full codebase, all focus areas
/jbct-review src/main/java             # Specific path, all focus areas
/jbct-review --focus="Composition,Null"  # Specific focus areas only
/jbct-review src --focus="ValueObjects,UseCases"   # Combined path and focus
```

## Arguments

- `path` (optional): Directory or file to review. Default: entire codebase
- `--focus` (optional): Comma-separated list of focus areas. Default: all 10 areas

## Focus Areas

| Short Name | Full Focus Area |
|------------|-----------------|
| `ValueObjects` | Value Objects (factories + immutability) |
| `UseCases` | Use Cases (structure + composition) |
| `ReturnTypes` | Return Types (Result/Promise, Void→Unit, no exceptions) |
| `Structural` | Structural Patterns (Leaf, Sequencer, Fork-Join) |
| `Composition` | Composition Rules (fold() abuse, lambda complexity) |
| `Null` | Null Policy (Option usage) |
| `ThreadSafety` | Thread Safety (immutability, shared state) |
| `Naming` | Naming Conventions (factories, zones, acronyms) |
| `Testing` | Testing Patterns (assertions, organization) |
| `CrossCutting` | Cross-Cutting Concerns (security, performance, logging) |

## Execution Steps

### Step 1: Parse Arguments

```
1. Extract path argument (if provided, default to current directory)
2. Extract --focus argument (if provided, default to all 10 areas)
3. Parse focus areas into list
```

### Step 2: Discover Files

```
1. Use Glob to find all Java files in target path: **/*.java
2. Filter out test files if reviewing production code only
3. Count total files to review
```

### Step 3: Launch Parallel Workers

**For each focus area**, launch a jbct-reviewer agent with the focus parameter:

```
Task tool call:
  subagent_type: "jbct-reviewer"
  prompt: |
    Review the following Java files for JBCT compliance.

    **Focus Area:** [FOCUS_AREA_NAME]

    Check ONLY for violations in this specific area. Ignore other issues.

    Files to review:
    [LIST_OF_FILES]

    Report findings with severity levels (Critical/Warning/Suggestion/Nitpick).
```

**Launch all workers in parallel** using multiple Task tool calls in a single message.

### Step 4: Collect Results

Wait for all workers to complete. Each returns structured findings.

### Step 5: Aggregate Results

Launch a **general-purpose** agent to consolidate all reports (using jbct-reviewer for aggregation would exceed the 10-agent limit):

```
Task tool call:
  subagent_type: "general-purpose"
  prompt: |
    Aggregate the following JBCT review reports into a unified assessment.

    **Individual Reports:**

    --- ValueObjects Report ---
    [REPORT_1]

    --- UseCases Report ---
    [REPORT_2]

    ... (all 10 reports)

    Tasks:
    1. Deduplicate findings (same file:line across reports)
    2. Count issues by severity (Critical/Warning/Suggestion/Nitpick)
    3. Determine overall compliance level and recommendation
    4. Generate unified report following the format in Step 6
```

### Step 6: Output Final Report

Present the aggregated report to the user:

```markdown
# JBCT Parallel Review Report

**Path:** [TARGET_PATH]
**Files Reviewed:** [COUNT]
**Focus Areas:** [LIST or "All 18"]

## Summary

| Severity | Count |
|----------|-------|
| Critical | X |
| Warning | Y |
| Suggestion | Z |
| Nitpick | W |

**Recommendation:** ✅ APPROVE | ⚠️ APPROVE WITH CHANGES | ❌ REQUEST CHANGES

---

## 🔒 Critical Issues

[All critical findings from all workers, with focus area tag]

### [Focus Area]: Issue Title
**File:** `path/to/file.java:line`
**Problem:** [Description]
**Fix:** [Suggested code]

---

## ⚠️ Warnings

[All warnings from all workers]

---

## 🛠️ Suggestions

[All suggestions from all workers]

---

## 🧹 Nitpicks

[All nitpicks from all workers]

---

## 🔧 Quick Fixes Summary

1. **Critical:** [One-line summary]
2. **Patterns:** [Key pattern improvements]
3. **Composition:** [fold() and lambda fixes]
```

## Example Execution

```
User: /jbct-review src/main/java

1. Parse: path = "src/main/java", focus = all 10 areas
2. Discover: 47 Java files found
3. Launch 10 parallel workers:
   - Worker 1: focus="Value Objects"
   - Worker 2: focus="Use Cases"
   - Worker 3: focus="Return Types"
   - Worker 4: focus="Structural Patterns"
   - Worker 5: focus="Composition Rules"
   - Worker 6: focus="Null Policy"
   - Worker 7: focus="Thread Safety"
   - Worker 8: focus="Naming Conventions"
   - Worker 9: focus="Testing Patterns"
   - Worker 10: focus="Cross-Cutting Concerns"
4. Wait for all workers to complete
5. Launch aggregator: focus="Aggregate" with all 10 reports
6. Output unified report: 3 Critical, 12 Warning, 8 Suggestion, 5 Nitpick
```

## Notes

- Each worker reviews ALL files but only checks for violations in its focus area
- This ensures thoroughness: narrow focus = deeper analysis
- Parallel execution: all 10 workers run simultaneously
- Aggregator deduplicates and consolidates findings into unified report
