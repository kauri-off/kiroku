# Shared by tools/gw and tools/emu: submit a job to Kiroku's job queue, tools/q, and wait for it.
SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
ROOT=$SELF
Q=$SELF/tools/q
Q_API=1
WAIT=100
export ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.local/share/android-sdk/Sdk}}
# No USB, no mDNS, no scan of the emulator port range: an adb server sees only emulators that register with it.
ADB_ISOLATION='ADB_USB=0 ADB_MDNS=0 ADB_MDNS_AUTO_CONNECT=0 ADB_LOCAL_TRANSPORT_MAX_PORT=5553'
ADB_REDIRECTS='ADB_SERVER_SOCKET ANDROID_ADB_SERVER_ADDRESS ADB_VENDOR_KEYS ANDROID_ADB_LOG_PATH'
QUEUE_UNIT='/kiroku-q-[0-9]{8}-[0-9]{6}-[0-9a-f]{4}\.service$'

# Test settings are read only in the self-test's sandbox: an untracked copy of tools/ holding this file.
declare -A HOOK=()
if [[ -f $SELF/tools/test-hooks ]] && ! git -C "$SELF" ls-files --error-unmatch tools/emu > /dev/null 2>&1; then
  while IFS='=' read -r k v; do HOOK[$k]=$v; done < "$SELF/tools/test-hooks"
  Q=${HOOK[q]:-$Q}
  QUEUE_UNIT=${HOOK[unit]:-$QUEUE_UNIT}
fi

die() {
  echo "$(basename "$0"): $*" >&2
  exit 2
}

usage() {
  echo "$USAGE" >&2
  exit 2
}

in_queue_unit() {
  [[ $(< /proc/self/cgroup) =~ $QUEUE_UNIT ]]
}

in_emu_job() {
  local p=${KIROKU_EMU_JOB:-} argv
  [[ $p =~ ^[0-9]+$ && -r /proc/$p/cgroup ]] || return 1
  argv=$(tr '\0' ' ' < "/proc/$p/cmdline" 2> /dev/null) || return 1
  [[ $argv == *" $SELF/tools/emu _job "* && $(< "/proc/$p/cgroup") == "$(< /proc/self/cgroup)" ]] && in_queue_unit
}

qsubmit() {
  local tool=$1 kind=$2 top branch id
  shift 2
  top=$(git rev-parse --show-toplevel 2> /dev/null) && top=$(cd "$top" && pwd -P) || top=
  [[ $top == "$SELF" ]] || die "run tools/$tool from inside its own worktree, $SELF"
  [[ -x $Q ]] || die "job queue not found at $Q"
  branch=$(git -C "$ROOT" branch --show-current 2> /dev/null || true)
  id=$(env -u ANDROID_SERIAL -u ANDROID_AVD_HOME -u ANDROID_ADB_SERVER_PORT -u KIROKU_EMU_JOB -u KIROKU_RUN \
    -u ADB_SERVER_SOCKET -u ANDROID_ADB_SERVER_ADDRESS -u ADB_VENDOR_KEYS -u ANDROID_ADB_LOG_PATH \
    "$Q" submit --api "$Q_API" --kind "$kind" --label "${branch:-$(basename "$ROOT")}" -C "$ROOT" -- "$@") ||
    die "the queue refused the job"
  echo "job $id (kind $kind) submitted; waiting up to $WAIT s"
  qwait "$tool" "$id"
}

qwait() {
  local tool=$1 id=$2 rc=0 out
  out=$("$Q" wait "$id" --max "$WAIT" --tail 60 2>&1) || rc=$?
  if ((rc == 1)) && [[ $out != *"; full output: "* ]]; then
    echo "$out" >&2
    [[ $out != "no job "* && $out != "ambiguous job "* ]] || die "the queue has no single job '$id'"
    echo "QUEUE ERROR: the queue could not report job $id; it may still be running." \
      "Next: tools/$tool --wait $id (do not start it again)"
    exit 75
  fi
  printf '%s\n' "$out"
  case $rc in
    69) echo "NO DEVICE: no emulator could be started (reason above). This is not your command's result: run it" \
      "once more; if it repeats, stop and report the reason." ;;
    75) echo "NOT FINISHED: job $id is queued or running. Next: tools/$tool --wait $id (repeat while it exits 75)" ;;
    76) echo "STALLED: job $id. If the output above explains the wait, run tools/$tool --wait $id again;" \
      "otherwise stop it with tools/q kill $id" ;;
    125) echo "STOPPED: job $id was cancelled or killed before it finished; nothing of it is running." \
      "Start it again if you still need its result." ;;
  esac
  exit "$rc"
}
