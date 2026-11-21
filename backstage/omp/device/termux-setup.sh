#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
set +x
umask 077
[[ ${PREFIX:-} == /data/data/com.termux/files/usr ]] || { echo 'Run this in the Termux app' >&2; exit 1; }
token=${1:?Paste the complete setup line from Backstage}
[[ $token =~ ^[A-Za-z0-9_-]{43}$ ]] || exit 2
port=@BROKER_PORT@
base=http://127.0.0.1:$port/omp/v1
stage=$(mktemp -d "$HOME/.backstage-omp-setup.XXXXXX")
trap 'rm -rf "$stage"' EXIT
fetch() { printf 'header = "Authorization: Bearer %s"\n' "$token" | curl -q --noproxy '*' --config - -fsS --connect-timeout 3 --max-time 300 "$base/$1" -o "$2"; }
fetch payload/manifest.json "$stage/manifest.json"
manifest=$(tr -d '\n\r' < "$stage/manifest.json")
for file in bun-linux-aarch64-android.zip omp-tree.tgz omp-termux device/omp device/backstage-omp-key device/backstage-sh; do
    mkdir -p "$(dirname "$stage/$file")"
    fetch "payload/$file" "$stage/$file"
    hash=$(printf '%s\n' "$manifest" | sed -n 's|.*"'"$file"'"[[:space:]]*:[[:space:]]*{[^}]*"sha256"[[:space:]]*:[[:space:]]*"\([a-f0-9]*\)".*|\1|p')
    [[ ${#hash} == 64 ]] || { echo "Missing payload hash: $file" >&2; exit 1; }
    printf '%s  %s\n' "$hash" "$stage/$file" | sha256sum -c - >/dev/null
done
mkdir -p "$HOME/.bun/bin" "$PREFIX/bin" "$HOME/.config/backstage-omp" "$HOME/.omp/agent"
unzip -q "$stage/bun-linux-aarch64-android.zip" -d "$stage/bun"
bun=$(find "$stage/bun" -type f -name bun -print -quit)
[[ -n $bun ]]
cp "$bun" "$HOME/.bun/bin/bun"
tar -xzf "$stage/omp-tree.tgz" -C "$HOME/.bun"
cp "$stage/omp-termux" "$PREFIX/bin/omp-termux"
cp "$stage/device/omp" "$HOME/.bun/bin/omp.new"
mv -f "$HOME/.bun/bin/omp.new" "$HOME/.bun/bin/omp"
cp "$stage/device/backstage-omp-key" "$PREFIX/bin/backstage-omp-key"
cp "$stage/device/backstage-sh" "$PREFIX/bin/backstage-sh"
ln -sf "$HOME/.bun/bin/omp" "$PREFIX/bin/omp"
chmod 700 "$HOME/.bun/bin/bun" "$HOME/.bun/bin/omp" "$PREFIX/bin/omp-termux" "$PREFIX/bin/backstage-omp-key" "$PREFIX/bin/backstage-sh"
printf 'port=%s\ntoken=%s\n' "$port" "$token" > "$HOME/.config/backstage-omp/broker"
chmod 600 "$HOME/.config/backstage-omp/broker"
fetch config/models.yml "$stage/models.yml"
if [[ -f $HOME/.omp/agent/models.yml ]]; then cp "$HOME/.omp/agent/models.yml" "$HOME/.omp/agent/models.yml.bak-$(date +%Y%m%d-%H%M%S)"; fi
mv "$stage/models.yml" "$HOME/.omp/agent/models.yml"
# Backstage's settings are an overlay (loaded by the omp wrapper); omp's own config.yml stays the user's.
fetch config/config.yml "$stage/backstage.yml"
mv "$stage/backstage.yml" "$HOME/.omp/agent/backstage.yml"
fetch config/mcp.json "$stage/mcp.json"
if [[ -f $HOME/.omp/agent/mcp.json ]]; then cp "$HOME/.omp/agent/mcp.json" "$HOME/.omp/agent/mcp.json.bak-$(date +%Y%m%d-%H%M%S)"; fi
mv "$stage/mcp.json" "$HOME/.omp/agent/mcp.json"
# Optional when updating against an older/offline broker; the wrapper retries on every launch.
if fetch config/instructions.txt "$stage/instructions.txt"; then
    mv "$stage/instructions.txt" "$HOME/.omp/agent/instructions.txt"
fi
# BEGIN BACKSTAGE SKILLS SYNC
# ZIP entries are skills/<name>/...; extraction is staged under an agent directory first.
backstage_sync_skills() (
    local agent=$HOME/.omp/agent stage d target empty=0 changing=0 committed=0 name hash applied
    [[ ! -L $agent/skills ]] || return 1
    mkdir -p "$agent/skills" || return 1
    # flock ships in the pinned Termux bootstrap. Keep the inode: the kernel drops the lock on exit/crash.
    exec 9>>"$agent/.skills-sync.lock" || return 1
    flock -x -w 10 9 || return 1
    stage=$(mktemp -d "$agent/.backstage-skills.XXXXXX") || return 1
    shopt -s nullglob dotglob
    backstage_skills_cleanup() {
        local status=$? rollback_failed=0 saved
        trap - EXIT
        if (( changing && ! committed )); then
            # Only paths recorded before our install moves may be removed during rollback.
            while IFS= read -r name; do
                target=$agent/skills/$name
                if [[ -d $target && ! -L $target && -f $target/.backstage ]]; then
                    rm -rf -- "$target" || rollback_failed=1
                fi
            done < "$stage/installed"
            for saved in "$stage/backup"/*; do
                target=$agent/skills/${saved##*/}
                if [[ -e $target || -L $target ]] || ! mv -T -- "$saved" "$target"; then rollback_failed=1; fi
            done
        fi
        if (( rollback_failed )); then
            echo "Backstage omp skills: rollback incomplete; backups retained at $stage/backup" >&2
        else
            rm -rf -- "$stage"
        fi
        return "$status"
    }
    trap backstage_skills_cleanup EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    printf 'header = "Authorization: Bearer %s"\n' "$token" |
        curl -q --noproxy '*' --config - -fsS --connect-timeout 1 --max-time 60 --max-filesize 2000000000 \
        "http://127.0.0.1:$port/omp/v1/config/skills.zip" > "$stage/skills.zip" 2>/dev/null || return 1
    [[ $(wc -c < "$stage/skills.zip") -le 2000000000 ]] || return 1
    hash=$(sha256sum "$stage/skills.zip") || return 1
    hash=${hash%% *}
    applied=
    [[ ! -r $agent/.skills-backstage.sha256 ]] || read -r applied < "$agent/.skills-backstage.sha256" || true
    [[ $hash != "$applied" ]] || return 0
    # Some unzip builds reject a valid empty ZIP. Recognize only its exact 22-byte encoding.
    { printf 'PK\005\006'; printf '\0%.0s' {1..18}; } > "$stage/empty.zip"
    if cmp -s "$stage/skills.zip" "$stage/empty.zip"; then
        empty=1
    else
        LC_ALL=C unzip -l "$stage/skills.zip" > "$stage/list" 2>/dev/null || return 1
        # Bound files, path depth and DISTINCT implied directories before extraction, using portable unzip -l.
        awk '
            $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9-]+$/ && $3 ~ /^[0-9:]+$/ {
                size = $1; path = $0
                sub(/^[[:space:]]*[0-9]+[[:space:]]+[0-9-]+[[:space:]]+[0-9:]+[[:space:]]+/, "", path)
                n = split(path, parts, "/")
                if (n < 3 || n > 12 || parts[1] != "skills" || length(parts[2]) < 1 || length(parts[2]) > 64 ||
                    parts[2] !~ /^[a-z0-9._-]+$/ || path ~ /[[:cntrl:]\\]/) exit 1
                prefix = ""
                for (i = 1; i <= n; i++) {
                    if (parts[i] == ".." || parts[i] == "." || parts[i] == "") exit 1
                    prefix = prefix (i > 1 ? "/" : "") parts[i]
                    if (i < n && !(prefix in directories)) {
                        directories[prefix] = 1
                        if (++directory_count > 20000000) exit 1
                    }
                }
                if (!(parts[2] in skill_entries) && ++skill_count > 5000) exit 1
                if (++skill_entries[parts[2]] > 20001) exit 1
                bytes += size; count++
            }
            END { if (bytes > 2000000000 || count > 2000000 || count == 0) exit 1 }
        ' "$stage/list" || return 1
        mkdir "$stage/unpacked" || return 1
        unzip -o -q "$stage/skills.zip" -d "$stage/unpacked" >/dev/null 2>&1 || return 1
        [[ -z $(find "$stage/unpacked" -type l -print) ]] || return 1
    fi
    if (( ! empty )); then
        for d in "$stage/unpacked/skills"/*; do
            [[ -d $d && -f $d/.backstage && ! -s $d/.backstage && -f $d/SKILL.md ]] || return 1
        done
    fi
    mkdir "$stage/backup" || return 1
    : > "$stage/installed" || return 1
    changing=1
    # Move the old managed set aside; the EXIT trap restores it if any later move fails.
    for d in "$agent/skills"/*; do
        if [[ -d $d && ! -L $d && -f $d/.backstage ]]; then
            mv -T -- "$d" "$stage/backup/${d##*/}" || return 1
        fi
    done
    if (( ! empty )); then
        for d in "$stage/unpacked/skills"/*; do
            target=$agent/skills/${d##*/}
            if [[ -e $target || -L $target ]]; then
                echo "Backstage omp skills: keeping user skill ${d##*/}" >&2
            else
                printf '%s\n' "${d##*/}" >> "$stage/installed" || return 1
                mv -T -- "$d" "$target" || return 1
            fi
        done
    fi
    committed=1
    printf '%s\n' "$hash" > "$stage/applied.sha256" || return 1
    mv -f -- "$stage/applied.sha256" "$agent/.skills-backstage.sha256" || return 1
)
if ! backstage_sync_skills; then
    echo 'Open Backstage to refresh omp skills; keeping cached skills' >&2
fi
# END BACKSTAGE SKILLS SYNC
export PATH=$HOME/.bun/bin:$PREFIX/bin:$PATH OMP_TERMUX_NO_UPDATE_CHECK=1
omp-termux verify
echo 'Paired. Type omp while Backstage is running.'
