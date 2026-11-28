#!/usr/bin/env python3
"""#3846 inventory: every `[verified]` count or list of the redesign memo, as one ROW (pattern, search space, expected).

    scripts/inventory-3846.py [ROOT] [--control] [--list ID]

Default: runs every row over the working tree at ROOT (default: the repo this script lives in) and prints one line per row:
`OK|RED id  measured/expected  memo-claim  pattern  [space]`. Exit status 1 when any row is RED. A lane opens by running it at
its own head and quoting it; a hit that is not in a row is a stop, never "add a route as you go" (ruling R3).

Counts are OCCURRENCES of the regex (`re.finditer`) over the raw text of each file, never lines: a line holding two hits counts
twice. A row may also pin `lines` (distinct lines hit) and `files` (distinct files hit). Nothing here is `grep -c`.

`--control`: positive control. Each row's `plant` text is added as one extra file inside the row's own search space (in memory)
and the row must go RED (measured above expected). Exit status 0 only when EVERY row went red. A row that cannot go red
measures nothing.

How to add a row (lane A2, A3 and later lanes): append one `row(...)` call to ROWS below.
    row(id, memo_ref, pattern, space, occ=<expected>, plant=<text that matches pattern>, lines=None, files=None, memo=None, code_only=False, note="")
  * `space` is a key of SPACES, or a (space, not-regex-on-path) pair.
  * `memo` is the number the memo claims, when it differs from the measured `occ`; `note` says why (a withdrawn claim is a finding).
  * `code_only` drops import, package and comment lines before matching (the memo's `\\bfsm\\b` in LM means code, not imports).
  * Do not edit an expected count to turn a row green without naming the hit that moved.
The scan sources themselves (SCAN_SOURCES) are excluded from every space: they quote the patterns they scan."""
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {".m2-local", "target", ".git", "node_modules", ".idea"}
SCAN_SOURCES = {"SourceScans.java", "LeaderSinkScanTest.java", "LeaderOrderPathScanTest.java", "FsmOrderPathScanTest.java",
                "LeaderTripwireRowsTest.java", "LeaderTxFreeArmTripwireTest.java", "LeaderAuthorityCensusTest.java", "AuthorityCensus.java"}

CONSENSUS = "integrations/consensus/"
LEADER_MAIN = CONSENSUS + "src/main/java/org/pragmatica/consensus/leader/"
LEADER_FSM_TEST = CONSENSUS + "src/test/java/org/pragmatica/consensus/leader/fsm/"

SPACES = {
    "all": lambda p: True,
    "main": lambda p: "/src/main/" in p,
    "test": lambda p: "/src/test/" in p,
    "integrations": lambda p: p.startswith("integrations/"),
    "integrations-test": lambda p: p.startswith("integrations/") and "/src/test/" in p,
    "consensus-test": lambda p: p.startswith(CONSENSUS + "src/test/"),
    "leader-fsm-test": lambda p: p.startswith(LEADER_FSM_TEST),
    "leader-main": lambda p: p.startswith(LEADER_MAIN),
    "LM": lambda p: p == LEADER_MAIN + "LeaderManager.java",
    "State": lambda p: p == LEADER_MAIN + "fsm/LeaderElectionState.java",
    "RabiaNode": lambda p: p.endswith("/RabiaNode.java") and p.startswith("integrations/cluster/src/main/"),
}

ROWS = []


def row(rid, ref, pattern, space, occ, plant, lines=None, files=None, memo=None, code_only=False, note=""):
    ROWS.append(dict(id=rid, ref=ref, pattern=pattern, space=space, occ=occ, plant=plant, lines=lines, files=files,
                     memo=memo, code_only=code_only, note=note))


def load(root):
    """Every *.java under root, as {relative posix path: text}."""
    out = {}
    for d, dirs, names in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for n in names:
            if n.endswith(".java") and n not in SCAN_SOURCES:
                p = os.path.join(d, n)
                with open(p, encoding="utf-8") as f:
                    out[os.path.relpath(p, root).replace(os.sep, "/")] = f.read()
    return out


def in_space(space, path):
    name, excl = (space, None) if isinstance(space, str) else space
    return SPACES[name](path) and not (excl and re.search(excl, path))


def strip_non_code(text):
    return "\n".join(l for l in text.split("\n") if not re.match(r"\s*(import\b|package\b|//|/\*|\*)", l))


def measure(r, sources):
    pat = re.compile(r["pattern"])
    occ, lines, files = 0, set(), set()
    for path, text in sources.items():
        if not in_space(r["space"], path):
            continue
        text = strip_non_code(text) if r["code_only"] else text
        hits = list(pat.finditer(text))
        if hits:
            occ += len(hits)
            files.add(path)
            lines.update((path, text.count("\n", 0, m.start())) for m in hits)
    return occ, len(lines), len(files)


def verdict(r, got):
    occ, lines, files = got
    ok = occ == r["occ"] and (r["lines"] is None or lines == r["lines"]) and (r["files"] is None or files == r["files"])
    return ok


def plant_into(r, sources):
    """The row's own space, plus one virtual file holding the planted text."""
    name = r["space"] if isinstance(r["space"], str) else r["space"][0]
    probe = {"all": "x/src/main/P.java", "main": "x/src/main/P.java", "test": "x/src/test/P.java",
             "integrations": "integrations/x/P.java", "integrations-test": "integrations/x/src/test/P.java",
             "consensus-test": CONSENSUS + "src/test/P.java", "leader-fsm-test": LEADER_FSM_TEST + "P.java",
             "leader-main": LEADER_MAIN + "P.java", "LM": LEADER_MAIN + "LeaderManager.java",
             "State": LEADER_MAIN + "fsm/LeaderElectionState.java",
             "RabiaNode": "integrations/cluster/src/main/java/x/RabiaNode.java"}[name]
    planted = dict(sources)
    real = next((q for q in sorted(sources) if in_space(r["space"], q)), None)  # a restricted space: plant into a real member
    probe = real or probe
    planted[probe] = planted.get(probe, "") + "\n" + r["plant"] + "\n"
    return planted


def show(r, got, status):
    memo = "" if r["memo"] is None else f"memo={r['memo']}"
    shown = f"occ={got[0]}/{r['occ']}"
    if r["lines"] is not None:
        shown += f" lines={got[1]}/{r['lines']}"
    if r["files"] is not None:
        shown += f" files={got[2]}/{r['files']}"
    return f"{status:3} {r['id']:5} {shown:34} {memo:9} {r['pattern']}  [{r['space'] if isinstance(r['space'], str) else r['space'][0] + ' minus ' + r['space'][1]}] {r['note']}"


# ---------------------------------------------------------------- the rows (memo r3 4a0d69bb §3.6, §5.0, P6-P9, S3; ruling R2)
FM = r"FsmBackedLeaderManager\("
row("M1", "3.6 row 1", FM, "all", 22, "new FsmBackedLeaderManager(a, b, c, d);", files=16,
    note="LM:387 call, LM:400 declaration, 20 test calls in 15 files")
FSM_CALL = r"\.fsm\(\)"
row("M2a", "3.6 row 2; 5.19 f3846n F10", FSM_CALL, "integrations-test", 48, "x.fsm();", lines=47, files=20, memo=45,
    note="RE-DECIDED: 46 occurrences on 45 lines in 19 files; LEEPT:177 holds two. The memo's 45 is the LINE count (grep -c). Lane C1: LeaderEpisodeDispatchTest adds 2 occurrences on 2 lines in 1 file (harness.fsm())")
row("M2b", "3.6 row 2 manager receivers", FSM_CALL, ("integrations-test", r"/(?!LeaderManagerTest|LeaderReconnectTransportTest|PeerObservationOrderTransportTest)[^/]+$"),
    15, "x.fsm();", files=3, note="LMT 1 + LRTT 7 + POTT 7, the receivers the manager row removes")
row("M2c", "3.6 row 2 LEEPT javac strings and docs", FSM_CALL, ("integrations-test", r"/(?!LeaderEpisodeEntryPathTest)[^/]+$"), 7, "x.fsm();",
    lines=6, files=1, note="LEEPT:98, 99, 107, 161, 162, 177 (177 holds two)")
row("M2d", "3.6 row 2 harness lines", FSM_CALL, ("integrations-test", r"/(LeaderManagerTest|LeaderReconnectTransportTest|PeerObservationOrderTransportTest|LeaderEpisodeEntryPathTest)\.java$"),
    26, "x.fsm();", files=16, memo=24, note="FsmTestHarness.fsm() on test-built machines; 46 = 15 + 7 + 24. Lane C1: LeaderEpisodeDispatchTest adds 2 in 1 file")
row("M3", "3.6 row 3", r"\.inbound\(\)", "all", 2, "x.inbound();", files=2, memo=1,
    note="memo 1 missed LeaderInboundForTest:28, a doc mention; code: LM:387 only (row M3m)")
row("M3m", "3.6 row 3 main", r"\.inbound\(\)", "main", 1, "x.inbound();", files=1)
row("M4", "3.6 row 4", r"\.localMode\(\)", "all", 0, "x.localMode();")
row("M5", "3.6 row 5", r"\.context\(\)", "integrations", 34, "x.context();", files=7, memo=34, note="LM:387 + 33 test lines in 6 files")
row("M6", "3.6 row 6", r"FsmWithContext\(", "all", 2, "new FsmWithContext(a, b);", files=1, note="LEF:37 declaration, LEF:195 construction")
row("M7a", "3.6 row 7", r"leaderElectionFsm\(", ("all", r"/LeaderElectionFsm\.java$"), 1, "LeaderElectionFsm.leaderElectionFsm(a);",
    note="LM:374 outside LEF")
row("M7b", "3.6 row 7 test machines", r"var fsm = Fsm\.<", "consensus-test", 4, "var fsm = Fsm.<A, B> fsm(c);", files=3, memo=4,
    note="LEPAT:309, LHFT:203, LSDCT:151 and :241 (the memo's 3 files)")
row("M7c", "3.6 row 7 test machines, wider", r"Fsm\.<[^>]*>\s*fsm\(", "consensus-test", 7, "Fsm.<A, B> fsm(c);", files=6,
    note="FINDING: also LDCT:259, LTSCT:557 (field assignments) and LKFUT:293 (a return); the memo's `var fsm = Fsm.` form missed them")
row("M7d", "3.6 row 7", r"new LeaderElectionContext\(", "integrations", 37, "new LeaderElectionContext(a);", files=34,
    note="LEF:214 in main + 36 test sites in 33 files (memo 34 sites in 31 files; LeaderRunTimeRowsTest adds 1 site, 1 file; lane C1: LeaderEpisodeDispatchTest adds 1 site, 1 file)")
row("M7e", "3.6 row 7", r"new LeaderElectionContext\(", "test", 36, "new LeaderElectionContext(a);", files=33, memo=34, note="LeaderRunTimeRowsTest adds 1 site in 1 file; lane C1: LeaderEpisodeDispatchTest adds 1 site in 1 file")
row("M7f", "3.6 row 7", r"FsmTestHarness", "consensus-test", 121, "FsmTestHarness h;", files=30, memo=28, note="the memo counts files: 28; LeaderRunTimeRowsTest adds 11 occurrences in 1 file; lane C1: LeaderEpisodeDispatchTest adds 3 in 1 file")
row("M8", "3.6 row 8; 5.0", r"claimInbound", "all", 9, "claimInbound();", files=4, memo=9)
row("M9", "3.6 row 9", r"leaderPreVote\(", "all", 4, "leaderPreVote(a);", files=3, note="PreVote definition (lane C2: now package-private), Ctx:765, and the 2 calls in LeaderRunTimeRowsTest (K24 forged pre-vote and its twin). Lane C2: 5 -> 4, because the enabled K24 head tripwire (a forged-verdict-acts pin) was deleted when its twin was enabled")
row("M10", "3.6 row 10; 5.0", r"current\(\)", "leader-main", 13, "x.current();", files=3, memo=12,
    note="13 raw hits; 9 count, 4 do not (Ctx:146, Ctx:1152 the definition, State:1647, and lane C1's fsm.current() in dispatchIfLive, Ctx:1342)")
row("M10t", "3.6 row 10 tests", r"(?:ctx|context|ctx\(\)|context\(\)|contextOf\([^)]*\))\.current\(\)", "leader-fsm-test", 26, "ctx.current();",
    memo=25, note="26 lines, all in leader.fsm; lane C1: LeaderEpisodeDispatchTest adds 1")
row("M11", "3.6 row 11", r"dispatchSelf", "main", 14, "dispatchSelf(e);", files=2,
    note="State:114 definition + 12 calls (13 in State) + 1 doc in Ctx")
row("M11t", "3.6 row 11 tests", r"dispatchSelf", "test", 8, "dispatchSelf(e);", files=3, memo=8,
    note="LDCT:174, 38, 40, 169, 170, 180; LTSCT:58; OnDrainer:25")
row("M12", "3.6 row 12", r"dispatchConfined\(", "all", 11, "dispatchConfined(e);", files=4, note="7 in main, 4 in tests. Lane C2: 12 -> 11 and 5 -> 4 files, because PreVote:281 (the verdict) no longer calls dispatchConfined: it dispatches through the suspicion handle (dispatchIfLive calls it, Ctx:1334), so the PreVote file left the row. Equals the memo's 11 again")
row("M13", "3.6 row 13", r"unentered\(", "all", 7, "unentered();", files=4, note="lane C1: LeaderEpisodeDispatchTest.isCurrent_isFalseForAHandleOverAnEndedLifetime... adds 1 test site")
row("M14", "3.6 row 14", r"episodeOf\(", "all", 3, "episodeOf(a);", files=1,
    note="lane C2: 7 -> 3 and 4 -> 1 files. Ctx.episodeOf(EpisodeIdentity) became suspicionEpisode(Suspicion): its definition, the 2 PreVote calls and the LEHFT:170 call are gone. The 3 left are LeaderElectionState's own private static episodeOf (declaration + 2 calls), lane St's")
row("M15a", "3.6 row 15; 5.0", r"episodeHandle", "all", 10, "episodeHandle();", files=1, memo=10)
row("M15b", "3.6 row 15", r"new (?:\w+\.)*(?:Electing|ReElecting|AwaitingKvSync)\(", "all", 3, "new Electing(a);", files=1)
row("M16", "3.6 row 16", r"new TransitionRequest", "all", 1, "new TransitionRequest(a);", files=1)
row("M18", "3.6 row 18", r"rescheduleCurrentTick|electLocallyOnTick", "test", 0, "rescheduleCurrentTick();")
row("M19", "3.6 row 19", r"refusedDispatchesForTest|handlerEpisode|suspicionEpisode", "integrations", 25, "handlerEpisode();",
    note="the new members: 0 before lane C1; C1 adds the Ctx definitions and docs and 10 test rows (LeaderEpisodeDispatchTest); lane C2 18 -> 25: +5 suspicionEpisode (Ctx definition, 3 PreVote sites: openRound, refrain, verdict; LEHFT:170) and +2 refusedDispatchesForTest in the enabled K24 row (instrument baseline + assertion); lane St adds the nine handler calls")
row("W1", "P9", r"ClusterFsmRouter\.wire\(", ("all", r"/src/test/"), 0, "ClusterFsmRouter.wire(a);", note="main callers: 0")
row("W2", "P9", r"ClusterFsmRouter\.wire\(", "test", 3, "ClusterFsmRouter.wire(a);", files=2, note="its 3 callers are tests")
row("L1", "5.0, S3", r"\.end\(\)", "leader-main", 0, "x.end();")
row("L2", "5.0, S3", r"\.(?:schedule|scheduleAtFixedRate)\(", "leader-main", 12, "x.schedule(a);", files=3, memo=12)
row("L3", "S3", r"SharedScheduler\.(?:schedule|scheduleAtFixedRate)\(", "leader-main", 12, "SharedScheduler.schedule(a);", files=3, memo=12,
    note="L2 == L3: every lifetime schedule is a SharedScheduler call")
row("D1", "5.0, S1", r"fsm\.dispatch\(", "leader-main", 1, "fsm.dispatch(e);", files=1, memo=1)
row("D2", "5.0, S1", r"fsm\.followUp\(", "leader-main", 1, "fsm.followUp(e);", files=1, memo=1)
row("F1", "P7; r3846p F1", r"(?:\.|::)followUp\b", "leader-main", 6, "episode.followUp(e);", files=2, memo=3,
    note="CORRECTED by r3846p F1: State has 5 (178, 187, 287, 587, 1458), Ctx:1300 is fsm.followUp (D2); the memo's 3 missed :287, :1458")
row("F2", "3.6 intro", r"\bfsm\b", "LM", 2, "var fsm = 1;", code_only=True, memo=2, note="LM:387 and LM:400, outside imports and docs")
row("R1", "7.10 f3846n F10", r"leaderManager\.(?:installKvSyncProgress|enableLeaderPreVote|installTransportView)\(", "RabiaNode", 3,
    "leaderManager.installTransportView(a);", memo=3, note="RabiaNode:632, :636, :638")


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    root = os.path.abspath(args[0]) if args else REPO
    control = "--control" in argv
    sources = load(root)
    red = 0
    for r in ROWS:
        if control:
            got = measure(r, plant_into(r, sources))
            base = measure(r, sources)
            went_red = not verdict(r, got) and got[0] == base[0] + 1
            red += 0 if went_red else 1
            print(show(r, got, "RED" if went_red else "DEAD"))
        else:
            got = measure(r, sources)
            ok = verdict(r, got)
            red += 0 if ok else 1
            print(show(r, got, "OK" if ok else "RED"))
    print(f"-- {len(ROWS)} rows over {len(sources)} java files at {root}; "
          + (f"{red} rows DID NOT go red under the planted hit" if control else f"{red} RED"))
    return 1 if red else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
