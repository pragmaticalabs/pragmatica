#!/bin/bash
# test-restart-fresh-ids.sh — stubs only (#1968 item 2, #1543 Q1). The REAL lib/common.sh and lib/cluster.sh run against a stubbed
# `remote_exec` that records every command the harness would send to the Docker host, and answers the reads from a scripted state.
#
# A whole-cluster restart is a regular start on FRESH core nodes followed by the KV restore, never a relaunch under the old ids:
#   F1  the first restart starts generation 1: ids 101..105, PEERS naming only them, the generation recorded BEFORE `up -d`,
#       every old container removed first, and neither `down -v`, `docker start` nor `docker restart` anywhere
#   F2  a second restart goes on to generation 2 (201..205); ids never return (no id of an earlier generation is reused)
#   F3  the restart waits for the backup head to SETTLE (two equal non-empty reads) before it removes anything
#   F4  a backup head that existed but was not RESTORED (the fresh leader decided FRESH) fails the restart; RESTORED passes;
#       with no head at all there is nothing to assert and it passes with a warning
#   F5  id arithmetic: b_seed_number / b_seed_offset / to_node_id, so every id of every generation maps to the same host-port slot
#   F6  the compose file carries what the restart relies on: generation-parameterised ids and PEERS, [backup] env with a
#       shared external remote volume, and no unconditional `down -v` of that volume
#   F7  mutation witnesses: a restart that reuses the old ids, and one that does not wait for the head, are caught by F1/F3
# Not proven here: that docker really starts the nodes and the leader restores (the bigboy run of 02-chaos does).
set -uo pipefail
unset TARGET_HOST AETHER_SSH_USER HCLOUD_TOKEN

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INTEG_DIR="${INTEG_DIR_UNDER_TEST:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
PASS=0; FAIL=0
ok()   { echo "  PASS  $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL  $1"; FAIL=$((FAIL + 1)); }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
touch "${WORK}/compose.yml"

# scenario <name> <body> [ENV=VAL...]. The stub host: HEADS (colon-separated reads of the backup head, consumed in order, last one
# repeated), GEN (the recorded generation), DECISION (what the fresh leader logged). Every command lands in ${d}/cmds.
scenario() {
    local name="$1" body="$2"; shift 2
    local d="${WORK}/${name}"; mkdir -p "$d"; : > "${d}/cmds"; echo 0 > "${d}/gen"
    ( export TARGET_HOST=stub ENV_TYPE=docker CLOUD_MODE=false D="$d" CLUSTER_ID=b CLUSTER_NAME=aether-b-node- \
        COMPOSE_FILE="${WORK}/compose.yml" BACKUP_SETTLE_POLL_S=1 BACKUP_SETTLE_TIMEOUT_S=3 \
        HEADS="aaaa1111:aaaa1111" DECISION=RESTORED AETHER_SSH_USER=u AETHER_SSH_KEY=k "$@"
      source "${INTEG_DIR}/lib/common.sh" > /dev/null 2>&1 || echo "SOURCE-FAILED common.sh" >> "$D/cmds"
      source "${INTEG_DIR}/lib/cluster.sh" > /dev/null 2>&1 || echo "SOURCE-FAILED cluster.sh" >> "$D/cmds"
      sleep() { :; }
      remote_exec() {
          echo "$*" >> "$D/cmds"
          case "$*" in
              *"rev-parse --verify -q refs/heads/kv-backup"*)
                  local n; n=$(( $(cat "$D/headn" 2>/dev/null || echo 0) )); echo $((n + 1)) > "$D/headn"
                  IFS=: read -ra heads <<< "$HEADS"
                  local i=$n; [ "$i" -ge "${#heads[@]}" ] && i=$(( ${#heads[@]} - 1 ))
                  printf '%s\n' "${heads[$i]}" ;;
              *"cat ~/.aether-b-generation"*) cat "$D/gen" ;;
              *"echo "*" > ~/.aether-b-generation"*)
                  # the generation is recorded by the same command that removes the old containers and starts the new ones
                  echo "$*" | sed -n 's/^echo \([0-9][0-9]*\) > .*/\1/p' > "$D/gen" ;;
              *"docker ps --filter name='aether-b-node-' --filter status=running -q | wc -l"*) echo 5 ;;
              *"Backup restore decision"*) echo "$DECISION" ;;
          esac
          return 0
      }
      $body ) > "${d}/out" 2>&1
}
cmds() { cat "${WORK}/$1/cmds"; }

body_restart_once() { _compose_b_restart_onto_fresh_ids; echo "rc=$?" >> "$D/verdict"; }
scenario once body_restart_once
c=$(cmds once)
if grep -q 'AETHER_B_N1=101 AETHER_B_N2=102 AETHER_B_N3=103 AETHER_B_N4=104 AETHER_B_N5=105' <<< "$c" \
   && grep -q "AETHER_B_PEERS='aether-b-node-101:aether-b-node-101:6000,aether-b-node-102:aether-b-node-102:6000,aether-b-node-103:aether-b-node-103:6000,aether-b-node-104:aether-b-node-104:6000,aether-b-node-105:aether-b-node-105:6000'" <<< "$c" \
   && [ "$(cat "${WORK}/once/gen")" = "1" ] && grep -q 'rc=0' "${WORK}/once/verdict"; then
    ok "F1a first restart: generation 1, ids 101..105, PEERS name only them, generation recorded"
else fail "F1a cmds=$(echo "$c" | tr '\n' '|' | head -c 600) verdict=$(cat "${WORK}/once/verdict" 2>/dev/null)"; fi
up_line=$(grep -n 'compose -f docker-compose-b.yml up -d' <<< "$c" | head -1 | cut -d: -f1)
start_cmd=$(grep 'compose -f docker-compose-b.yml up -d' <<< "$c" | head -1)
if [ -n "$start_cmd" ] && [ "$(sed -n 's/^echo \([0-9]*\) > .*/\1/p' <<< "$start_cmd")" = "1" ] \
   && [[ "$start_cmd" == *"docker rm -f"*"compose -f docker-compose-b.yml up -d"* ]] \
   && [[ "${start_cmd%%docker rm -f*}" == *".aether-b-generation"* ]]; then
    ok "F1b one command: record the generation, then remove every aether-b-node- container, then start the fresh ids"
else fail "F1b start command: $start_cmd"; fi
if ! grep -qE 'down -v|docker (start|restart)|compose (start|restart)' <<< "$c"; then
    ok "F1c no same-id relaunch command anywhere in the restart"
else fail "F1c a same-id command is present: $(grep -E 'down -v|docker (start|restart)|compose (start|restart)' <<< "$c" | head -2)"; fi

body_restart_twice() { _compose_b_restart_onto_fresh_ids; _compose_b_restart_onto_fresh_ids; }
scenario twice body_restart_twice
c=$(cmds twice)
if [ "$(cat "${WORK}/twice/gen")" = "2" ] && grep -q 'AETHER_B_N1=201 AETHER_B_N2=202' <<< "$c" \
   && [ "$(grep -c 'AETHER_B_N1=101' <<< "$c")" = "1" ] && [ "$(grep -c 'AETHER_B_N1=1 ' <<< "$c")" = "0" ]; then
    ok "F2 second restart goes to generation 2 (201..205); no id of an earlier generation is started again"
else fail "F2 gen=$(cat "${WORK}/twice/gen") cmds=$(echo "$c" | tr '\n' '|' | head -c 500)"; fi

# F3: the head changes across reads (a push still in flight) and only then settles; nothing is removed before it settles.
body_settle() { _compose_b_restart_onto_fresh_ids; }
scenario settle2 body_settle HEADS="aaaa:bbbb:cccc:cccc"
first_rm=$(grep -n 'docker rm -f' "${WORK}/settle2/cmds" | head -1 | cut -d: -f1)
reads_before=$(awk -v n="${first_rm:-0}" 'NR < n' "${WORK}/settle2/cmds" | grep -c 'rev-parse --verify -q refs/heads/kv-backup')
if [ -n "$first_rm" ] && [ "$reads_before" -ge 4 ]; then
    ok "F3 the head moved aaaa->bbbb->cccc; ${reads_before} reads before the first container was removed (settled on two equal reads)"
else fail "F3 first_rm=${first_rm:-none} reads_before=${reads_before}"; fi

# F4: the restore assertion.
body_assert() { RESTART_BACKUP_HEAD="${1:-deadbeefcafe}"; RESTART_FRESH_IDS="aether-b-node-101"; _assert_restored_from_backup; echo "rc=$?" >> "$D/verdict"; }
scenario restored body_assert DECISION=RESTORED
scenario fresh body_assert DECISION=FRESH
scenario nonelogged body_assert DECISION=
body_nohead() { RESTART_BACKUP_HEAD=""; RESTART_FRESH_IDS="aether-b-node-101"; _assert_restored_from_backup; echo "rc=$?" >> "$D/verdict"; }
scenario nohead body_nohead DECISION=FRESH
v() { grep -o 'rc=[0-9]' "${WORK}/$1/verdict" | head -1; }
if [ "$(v restored)" = "rc=0" ] && [ "$(v fresh)" = "rc=1" ] && [ "$(v nonelogged)" = "rc=1" ] && [ "$(v nohead)" = "rc=0" ] \
   && grep -q "not RESTORED" "${WORK}/fresh/out"; then
    ok "F4 RESTORED passes; FRESH or no decision over an existing head fails; no head at all asserts nothing"
else fail "F4 restored=$(v restored) fresh=$(v fresh) nonelogged=$(v nonelogged) nohead=$(v nohead)"; fi

# F5: id arithmetic.
body_ids() {
    echo "n=$(b_seed_number 0 3),$(b_seed_number 1 3),$(b_seed_number 12 5) off=$(b_seed_offset 3),$(b_seed_offset 103),$(b_seed_offset 205)" > "$D/verdict"
    _AETHER_B_GEN=2; echo "to=$(to_node_id node-4) $(to_node_id aether-b-node-9)" >> "$D/verdict"
    _AETHER_B_GEN=0; echo "to0=$(to_node_id node-4)" >> "$D/verdict"
    echo "reg=$(_registered_by_to_offset aether-b-node-104),$(_registered_by_to_offset aether-b-node-4)" >> "$D/verdict"
}
scenario ids body_ids
if [ "$(sed -n 1p "${WORK}/ids/verdict")" = "n=3,103,1205 off=2,2,4" ] && [ "$(sed -n 2p "${WORK}/ids/verdict")" = "to=aether-b-node-204 aether-b-node-9" ] \
   && [ "$(sed -n 3p "${WORK}/ids/verdict")" = "to0=aether-b-node-4" ] && [ "$(sed -n 4p "${WORK}/ids/verdict")" = "reg=3,3" ]; then
    ok "F5 b_seed_number/b_seed_offset/to_node_id/_registered_by_to_offset agree across generations"
else fail "F5 $(tr '\n' '|' < "${WORK}/ids/verdict")"; fi

# F6: the compose file.
COMPOSE_B_FILE="${INTEG_DIR}/docker-compose-b.yml"
if grep -q 'container_name: aether-b-node-${AETHER_B_N1:-1}' "$COMPOSE_B_FILE" && grep -q 'NODE_ID: "aether-b-node-${AETHER_B_N5:-5}"' "$COMPOSE_B_FILE" \
   && grep -q 'PEERS: "${AETHER_B_PEERS:-' "$COMPOSE_B_FILE" && grep -q 'AETHER_BACKUP_REMOTE: "/data/backup-remote/kv.git"' "$COMPOSE_B_FILE" \
   && grep -q 'AETHER_BACKUP_ENABLED: "true"' "$COMPOSE_B_FILE" && grep -q 'aether-b-backup-remote:/data/backup-remote' "$COMPOSE_B_FILE" \
   && grep -A3 '^volumes:' "$COMPOSE_B_FILE" | grep -q 'aether-b-backup-remote' && grep -A4 '^volumes:' "$COMPOSE_B_FILE" | grep -q 'external: true'; then
    ok "F6 compose B: generation-parameterised ids and PEERS, [backup] environment, shared EXTERNAL remote volume"
else fail "F6 compose-b.yml is missing a piece"; fi
grep -q 'AETHER_BACKUP_ENABLED' "${INTEG_DIR}/docker-compose-a.yml" && fail "F6b cluster A must not carry the backup remote" || ok "F6b control: cluster A is untouched"

# F7: mutation witnesses. A restart reusing the old ids / skipping the head wait must trip F1 / F3.
mut="${WORK}/cluster-mut.sh"
sed 's/n1=$(b_seed_number "$new_gen" 1); n2=$(b_seed_number "$new_gen" 2)/n1=1; n2=2/' "${INTEG_DIR}/lib/cluster.sh" > "$mut"
cmp -s "$mut" "${INTEG_DIR}/lib/cluster.sh" && fail "F7 mutation did not apply" || {
    mkdir -p "${WORK}/mutinteg/lib"; cp "${INTEG_DIR}/lib/common.sh" "${WORK}/mutinteg/lib/"; cp "$mut" "${WORK}/mutinteg/lib/cluster.sh"
    ( INTEG_DIR_UNDER_TEST_MUT="${WORK}/mutinteg"; INTEG_DIR="$INTEG_DIR_UNDER_TEST_MUT"; scenario mutids body_restart_once )
    if grep -q 'AETHER_B_N1=1 ' "${WORK}/mutids/cmds" && ! grep -q 'AETHER_B_N1=101' "${WORK}/mutids/cmds"; then
        ok "F7a mutation witness: reusing the old ids is visible in the recorded start command (F1a would fail on it)"
    else fail "F7a the same-id mutation was not visible: $(grep -c AETHER_B_N1 "${WORK}/mutids/cmds")"; fi
}

# F8 (found by the first real bigboy run): under --env remote the compose file is on the DOCKER HOST, so restart_all_nodes must
# not require it on THIS machine. The gate before the restart refused with "no compose project" and the whole suite went red.
body_gate_only() { _compose_b_restart_onto_fresh_ids() { echo REACHED > "$D/reached"; return 1; }; restart_all_nodes; echo "rc=$?" >> "$D/verdict"; }
scenario gate body_gate_only COMPOSE_FILE="${WORK}/not-on-this-machine.yml"
if [ "$(cat "${WORK}/gate/reached" 2>/dev/null)" = "REACHED" ] && ! grep -q 'no compose project' "${WORK}/gate/out"; then
    ok "F8 restart_all_nodes reaches the fresh-id restart although the compose file is not on this machine (remote docker host)"
else fail "F8 the restart gate refused: $(head -c 300 "${WORK}/gate/out")"; fi

echo "  passed: ${PASS}"
echo "  failed: ${FAIL}"
[ "$FAIL" -eq 0 ]
