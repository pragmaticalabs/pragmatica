# JBCT Skill for Claude Code

A comprehensive Claude Code skill for designing, implementing, and reviewing Java Backend Coding Technology (JBCT) code.

## Installation

1. **Create skills directory** (if it doesn't exist):
   ```bash
   mkdir -p ~/.claude/skills
   ```

2. **Copy this skill**:
   ```bash
   cp -r skills/jbct ~/.claude/skills/
   ```

3. **Verify installation**:
   ```bash
   ls ~/.claude/skills/jbct/SKILL.md
   ```

## Structure

The skill is organized by topic for progressive detalization:

```
jbct/
├── SKILL.md                      # Entry point with quick reference
├── README.md                     # This file
├── fundamentals/                 # Core principles
│   ├── four-return-kinds.md     # T, Option, Result, Promise
│   ├── parse-dont-validate.md   # Value object patterns
│   └── no-business-exceptions.md # Typed failures with Cause
├── patterns/                     # Six structural patterns
│   ├── leaf.md                  # Atomic operations
│   ├── sequencer.md             # Sequential composition
│   ├── fork-join.md             # Parallel operations
│   ├── condition.md             # Branching logic
│   ├── iteration.md             # Collection processing
│   └── aspects.md               # Cross-cutting concerns
├── use-cases/                    # Use case design
│   ├── structure.md             # Anatomy and conventions
│   └── complete-example.md      # RegisterUser walkthrough
├── testing/                      # Testing strategies
│   └── patterns.md              # Functional assertions, test organization
└── project-structure/            # Project organization
    └── organization.md          # Vertical slicing, package layout
```

## What This Skill Provides

The JBCT skill gives Claude Code deep understanding of:

- **Four Return Kinds**: `T`, `Option<T>`, `Result<T>`, `Promise<T>`
- **Parse, Don't Validate**: Making invalid states unrepresentable
- **Six Structural Patterns**: Leaf, Sequencer, Fork-Join, Condition, Iteration, Aspects
- **Use Case Design**: Factories, validated inputs, step composition
- **Project Structure**: Vertical slicing, package organization
- **Naming Conventions**: Factory methods, validated inputs, error types
- **Testing Patterns**: Functional assertions with `onSuccess`/`onFailure`
- **Common Anti-Patterns**: Mistakes to avoid

## How It Works

Once installed, Claude Code automatically activates this skill when:
- Working with `Result`, `Option`, or `Promise` types
- Implementing value objects or use cases
- Discussing JBCT patterns or monadic composition
- Reviewing code for functional Java backend patterns

No explicit invocation needed - the skill activates based on context.

## Progressive Detalization

The skill uses a three-tier structure:

1. **SKILL.md** - Quick reference with essential patterns and rules
2. **Topic files** - Detailed explanations with full examples (fundamentals/, patterns/, use-cases/)
3. **Advanced topics** - Testing strategies, project organization, complete examples

Claude Code navigates to specific files as needed, optimizing context usage.

## Related Resources

- **[CODING_GUIDE.md](../../CODING_GUIDE.md)** - Complete technical reference (100+ pages)
- **[series/](../../series/)** - 6-part progressive learning path
- **[jbct-coder.md](../../jbct-coder.md)** - Code generation subagent
- **[jbct-reviewer.md](../../jbct-reviewer.md)** - Code review subagent

## Version

Based on Java Backend Coding Technology v2.1.1

## License

MIT License - see [LICENSE](../../LICENSE) for details
