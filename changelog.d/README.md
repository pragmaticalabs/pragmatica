# changelog.d — one fragment per pull request

`CHANGELOG.md` is assembled, not edited. A pull request that changes sources adds one file here and
leaves `CHANGELOG.md` alone; `scripts/changelog-assemble.sh` folds the fragments into the `Unreleased`
section at release prep and deletes them. The published file keeps its current shape.

Why: every pull request used to append under the same unreleased heading, so the first merge put every
other open pull request into conflict and each needed a re-merge round before it could land.

## File name

`<number>-<slug>.md` — the issue number the pull request fixes (the pull request number when there is
no issue), a dash, a short lowercase slug. Examples: `684-compose-secret-out-of-env.md`,
`726-network-metrics-honest-home.md`.

## Content

Exactly the block that would have gone into `CHANGELOG.md`: one `###` sub-heading in the existing
style, then the bullets. The sub-heading names the section, the date, the issue and its title:

```markdown
### Fixed (2026-09-04 — #684: generated docker-compose wrote the cluster secret in plaintext)
- **What was wrong**, in one bold lead.
- What changed, with `[verified: <test path>]` for claims a live-path test pins and
  `[mechanism: ...]` for claims traced through the code.
```

Sections: `Added`, `Changed`, `Deprecated`, `Removed`, `Fixed`, `Security`, `Performance`. One section
per fragment; a change that needs two sections uses two fragments (`684-compose-secret-fixed.md`,
`684-compose-secret-security.md`).

## The check

`scripts/changelog-check.sh <base-ref>` runs on every pull request (`.github/workflows/changelog.yml`).
It fails when:

- the pull request edits `CHANGELOG.md` and does not carry the `release-prep` label;
- the pull request touches something other than documentation, workflows or this directory, and adds
  no well-formed fragment, and does not carry the `no-changelog` label.

Documentation-only pull requests (`*.md`, `docs/`, `.github/`) need no fragment.

## Release prep

```sh
scripts/changelog-assemble.sh            # writes CHANGELOG.md, git-removes the fragments
scripts/changelog-assemble.sh --dry-run  # prints the assembled section, changes nothing
```

Fragments are inserted at the top of the `Unreleased` section, newest date first, so the section reads
the way it always has. The pull request that runs the assembly carries the `release-prep` label.
