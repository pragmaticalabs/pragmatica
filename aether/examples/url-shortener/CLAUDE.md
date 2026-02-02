# JBCT Project

## AI Agent Policy (MANDATORY)

**Use `jbct-coder` agent for ALL coding and fixing tasks.** This ensures code follows JBCT patterns and conventions.

To invoke: Use `/jbct` skill or spawn `jbct-coder` agent via Task tool.

## Implementation Workflow

1. **Clarify** - Ask questions if requirements are ambiguous or multiple approaches exist
2. **Plan** - Create implementation plan before coding
3. **Implement** - Execute plan stages using `jbct-coder`
4. **Commit** - Commit after each plan stage completion
5. **Review** - After plan completion, review ALL updated files using `/jbct-review`
6. **Fix** - Fix all found issues using `/fix-all`

### Review Strategy

- **Small/medium plans** (1-5 stages): Review after all stages complete
- **Large plans** (6+ stages): Review after every 2-3 stages, then final review after all complete

## Git Commits

- **Format**: Conventional commits (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`)
- **Style**: Single line, imperative mood, no period at end
- **Examples**:
  - `feat: add user authentication endpoint`
  - `fix: handle null response in API client`
  - `refactor: extract validation logic to separate class`

## Conversation Style

**Core Principles:**
1. **Extreme brevity** - Answer directly without preamble. No "Let me help you" or "Great question!". Just the answer.
2. **Action-first** - Execute immediately. Explain only when necessary for safety or clarity.
3. **No fluff** - Skip politeness markers, acknowledgments, and summaries unless requested.
4. **Ask when needed** - If requirements are ambiguous or multiple valid approaches exist, ask before acting.

**When to Ask:**
- Ambiguous requirements with multiple valid interpretations
- Missing critical information (file paths, values, choices)
- Destructive operations with risk of data loss
- Technical decisions requiring user preference

**When NOT to Ask:**
- Clear, unambiguous requests
- Standard patterns following project conventions
- Recoverable operations (git, file edits)
- Obvious next steps (99% certain of intent)

**Execution Pattern:**
- Read → Act → Verify (show work incrementally)
- Parallel operations when independent
- Immediate verification after significant actions
