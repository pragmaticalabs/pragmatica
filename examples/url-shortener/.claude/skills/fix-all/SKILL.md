---
name: fix-all
description: Fix ALL issues found in the preceding review. Thorough, relentless, complete.
---

# Fix All Issues

Your mission: Fix **every single issue** identified in the preceding review. No exceptions. No shortcuts.

## Prerequisites

This skill is used AFTER running a review (e.g., `/jbct-review`, code review, or similar). The issues are already known from the review output in conversation context.

## Execution Protocol

1. **Issue Collection**
   - Extract all issues from the preceding review output
   - Create a todo list with every issue to fix
   - Prioritize: errors before warnings, blocking before cosmetic

2. **Systematic Resolution**
   - Fix issues one by one using appropriate agent (e.g., `jbct-coder`)
   - After each fix, mark the issue as resolved
   - Move to next issue immediately

3. **Verification**
   - After all issues are fixed, run the same review again
   - If new issues appear, add them to the list and fix them
   - Repeat until zero issues remain

4. **Completion Criteria**
   - All issues from the original review are resolved
   - Verification review passes with no new issues

## Mindset

Partial fixes are not acceptable. Either ALL issues are resolved, or the task is incomplete.

Be thorough. Be relentless. Don't stop until every issue is fixed.
