#!/system/bin/sh
set -eu
umask 077
ROOT=${1:?ROOT}; LIBDIR=${2:?LIBDIR}; ACTION=${3:?ACTION}; ID=${4:?ID}
case "$ROOT" in /data/local/tmp/backstage-omp) ;; *) exit 2;; esac
case "$ID" in ''|*[!a-z0-9]*) echo 'Invalid job id' >&2; exit 2;; esac
[ ${#ID} -ge 8 ] && [ ${#ID} -le 32 ] || exit 2
DIR=$ROOT/jobs/$ID
[ -d "$DIR" ] && [ ! -L "$DIR" ] || { echo 'Unknown job' >&2; exit 2; }
alive() {
    [ -f "$DIR/pid" ] && [ -f "$DIR/birth" ] || return 1
    pid=$(cat "$DIR/pid")
    case "$pid" in ''|*[!0-9]*) return 1;; esac
    [ -r "/proc/$pid/stat" ] || return 1
    birth=$(awk '{print $22}' "/proc/$pid/stat")
    [ "$birth" = "$(cat "$DIR/birth")" ] && kill -0 "$pid" 2>/dev/null
}
case "$ACTION" in
start)
    [ -s "$DIR/task" ] || exit 2
    mkdir "$DIR/started" 2>/dev/null || { echo 'Job already started' >&2; exit 2; }
    setsid /system/bin/sh "$ROOT/job.sh" "$ROOT" "$LIBDIR" _run "$ID" </dev/null >/dev/null 2>&1 &
    # Return only after the detached session has published its identity.
    n=0
    while [ ! -f "$DIR/pid" ] && [ ! -f "$DIR/exit" ]; do
        n=$((n + 1)); [ "$n" -lt 50 ] || {
            # The detached runner may not have been scheduled yet. Fence it before reporting
            # failure so a retry cannot leave two subagents working on the same task.
            : > "$DIR/cancelled"
            echo 'OMP job failed to start' >&2
            printf '127\n' > "$DIR/exit"
            exit 1
        }
        sleep 0.1
    done
    printf '%s\n' "$ID"
    ;;
_run)
    [ ! -f "$DIR/exit" ] && [ ! -f "$DIR/cancelled" ] || exit 0
    printf '%s\n' "$$" > "$DIR/pid"
    awk '{print $22}' "/proc/$$/stat" > "$DIR/birth"
    finish() { printf '%s\n' "$1" > "$DIR/exit.new"; mv "$DIR/exit.new" "$DIR/exit"; }
    trap 'finish 143; sleep 2; exit 143' TERM
    trap 'finish 130; sleep 2; exit 130' INT
    [ ! -f "$DIR/exit" ] || exit 0
    [ ! -f "$DIR/cancelled" ] || { finish 143; exit 0; }
    task=$(cat "$DIR/task")
    set -- omp -p --no-title --approval-mode yolo
    if [ -s "$DIR/model" ]; then set -- "$@" --model "$(cat "$DIR/model")"; fi
    if [ -s "$DIR/cwd" ]; then set -- "$@" --cwd "$(cat "$DIR/cwd")"; fi
    set -- "$@" -- "$task"
    # cmd is diagnostic only, never evaluated as shell code.
    printf '%s\n' "$@" > "$DIR/cmd"
    rc=0
    /system/bin/sh "$ROOT/run.sh" "$ROOT" "$LIBDIR" -- "$@" > "$DIR/out" 2> "$DIR/err" &
    child=$!
    wait "$child" || rc=$?
    finish "$rc"
    ;;
status)
    state=starting; code=null
    if [ -f "$DIR/exit" ]; then
        code=$(cat "$DIR/exit"); case "$code" in ''|*[!0-9]*) code=127;; esac
        state=finished
    elif alive; then state=running
    elif [ -f "$DIR/pid" ]; then state=failed; code=127
    fi
    if [ -f "$DIR/cancelled" ]; then state=cancelled; fi
    bytes=0
    [ ! -f "$DIR/out" ] || bytes=$(wc -c < "$DIR/out" | tr -d ' ')
    printf '{"state":"%s","exit":%s,"outBytes":%s}\n' "$state" "$code" "$bytes"
    ;;
cancel)
    : > "$DIR/cancelled"
    if [ ! -f "$DIR/exit" ] && alive; then
        # setsid makes the runner PID the group ID; terminate the complete proot tree.
        kill -TERM "-$pid" 2>/dev/null || :
        sleep 1
        if alive; then kill -KILL "-$pid" 2>/dev/null || :; fi
    fi
    if [ ! -f "$DIR/exit" ]; then printf '143\n' > "$DIR/exit"; fi
    printf '%s\n' '{"state":"cancelled"}'
    ;;
*) exit 2;;
esac
