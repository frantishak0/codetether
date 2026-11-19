#!/usr/bin/env bash
#
# Апгрейд OpenCode 2.1.2 -> 2.3.2 с бэкапом, проверками и авто-откатом.
#
#   upgrade-opencode.sh --check     только предпроверка, ничего не меняет (по умолчанию)
#   upgrade-opencode.sh --apply     выполнить апгрейд
#   upgrade-opencode.sh --rollback  вернуть 2.1.2 из последнего бэкапа
#   upgrade-opencode.sh --status    показать текущее состояние
#
# Ночной запуск: systemd user timer oc-upgrade.timer (03:30).
#
set -uo pipefail

export PATH="/home/ilia/.opencode/bin:/home/ilia/.local/bin:/usr/local/bin:/usr/bin:/bin"

TARGET_VERSION="2.3.2"
FROM_VERSION="2.1.2"

STAGE="/home/ilia/oc-client/dist/opencode-${TARGET_VERSION}"
NEW_BIN="${STAGE}/bin/opencode"
TARBALL="${STAGE}/opencode-linux-x64.tar.gz"
SUMS="${STAGE}/SHA256SUMS"

INSTALL_BIN="/home/ilia/.opencode/bin/opencode"
BIN_BAK="${INSTALL_BIN}.bak-${FROM_VERSION}"
DATA_DIR="/home/ilia/.local/share/opencode"
DB="${DATA_DIR}/opencode.db"
CONFIG_DIR="/home/ilia/.config/opencode"
SECRETS="/home/ilia/oc-thin/secrets.env"

BKROOT="/home/ilia/oc-client/dist/backups"
SUMMARY="/home/ilia/oc-client/dist/upgrade-latest.txt"
HISTORY="/home/ilia/oc-client/dist/upgrade-history.log"
LOCK="/tmp/opencode/opencode-upgrade.lock"

MAIN_PORT=49374
MAC_PORT=49375
BUS_PORT=49376
GW_PORT=49377
PUBLIC_HOST="ilia-herobook-pro.tail304661.ts.net"

SQLITE="$(command -v sqlite3 2>/dev/null || true)"
[[ -n "$SQLITE" ]] || SQLITE="/home/ilia/.local/opt/android-sdk/platform-tools/sqlite3"

MODE="${1:---check}"
TS="$(date +%Y%m%d-%H%M%S)"
BK="${BKROOT}/opencode-${FROM_VERSION}-${TS}"
LOG="${BKROOT}/upgrade-${TS}.log"
RESULT="unknown"

mkdir -p "$BKROOT" /tmp/opencode
: > "$LOG"

log() { printf '%s %s\n' "$(date '+%F %T')" "$*" | tee -a "$LOG"; }

# ------------------------------------------------------------------ helpers

port_open() { ss -ltnH "sport = :$1" 2>/dev/null | grep -q .; }

port_pids() {
    ss -ltnpH "sport = :$1" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u
}

api_version() { # url [basic_pass] [token]
    local url="$1" pass="${2:-}" tok="${3:-}" args=()
    [[ -n "$pass" ]] && args+=(-u "opencode:$pass")
    [[ -n "$tok" ]] && args+=(-H "x-auth-token: $tok")
    curl -s -m 8 "${args[@]}" "$url" 2>/dev/null | python3 -c '
import json, sys
try:
    print((json.load(sys.stdin) or {}).get("version", ""))
except Exception:
    print("")
' 2>/dev/null
}

wait_version() { # url pass token want seconds label
    local url="$1" pass="$2" tok="$3" want="$4" tmo="$5" label="$6" v=""
    local steps=$(( tmo / 2 ))
    for ((i = 0; i < steps; i++)); do
        v="$(api_version "$url" "$pass" "$tok")"
        if [[ "$v" == "$want" ]]; then
            log "OK   $label: $v"
            return 0
        fi
        sleep 2
    done
    log "FAIL $label: ждал $want, получил '${v:-нет ответа}'"
    return 1
}

load_secrets() {
    MAIN_PASS="$(python3 -c "import json;print(json.load(open('${CONFIG_DIR}/service.json'))['password'])" 2>/dev/null)"
    MAC_PASS="$(awk -F= '/^GW_PASS=/{print $2}' "$SECRETS" 2>/dev/null)"
    GW_TOKEN="$(awk -F= '/^GW_TOKEN=/{print $2}' "$SECRETS" 2>/dev/null)"
    [[ -n "$MAIN_PASS" && -n "$MAC_PASS" && -n "$GW_TOKEN" ]]
}

active_sessions() { # сколько сессий обновлялось за последние N минут
    local mins="${1:-15}"
    curl -s -m 8 -u "opencode:${MAIN_PASS:-}" "http://127.0.0.1:${MAIN_PORT}/api/session" 2>/dev/null |
        MIN="$mins" python3 -c '
import json, os, sys, time
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); raise SystemExit
rows = d.get("data", d) if isinstance(d, dict) else d
now = time.time() * 1000
limit = now - int(os.environ.get("MIN", "15")) * 60 * 1000
n = 0
for s in rows or []:
    t = (s.get("time") or {})
    upd = t.get("updated") or t.get("created") or 0
    if upd and upd >= limit:
        n += 1
print(n)
' 2>/dev/null || echo 0
}

health_all() { # expected_version
    local want="$1"
    wait_version "http://127.0.0.1:${MAIN_PORT}/api/info" "$MAIN_PASS" "" "$want" 120 "main :${MAIN_PORT}" || return 1
    wait_version "http://127.0.0.1:${MAC_PORT}/api/info" "$MAC_PASS" "" "$want" 90 "mac :${MAC_PORT}" || return 1
    wait_version "http://127.0.0.1:${GW_PORT}/api/info" "" "$GW_TOKEN" "$want" 30 "gateway :${GW_PORT}" || return 1
    wait_version "https://${PUBLIC_HOST}/api/info" "" "$GW_TOKEN" "$want" 30 "public funnel" || return 1
    port_open "$BUS_PORT" || { log "FAIL bus :${BUS_PORT} не слушает"; return 1; }
    log "OK   bus :${BUS_PORT}"
    return 0
}

# ------------------------------------------------------------------ actions

stop_mac() {
    log "останавливаю oc-main (Mac :${MAC_PORT})"
    systemctl --user stop oc-main.service >>"$LOG" 2>&1 || true
}

stop_main() {
    log "останавливаю основной сервис :${MAIN_PORT}"
    timeout 30 "$INSTALL_BIN" service stop >>"$LOG" 2>&1 || true

    for ((i = 0; i < 20; i++)); do
        port_open "$MAIN_PORT" || return 0
        sleep 1
    done

    local pids
    pids="$(port_pids "$MAIN_PORT")"
    if [[ -n "$pids" ]]; then
        log "порт занят, SIGTERM: $pids"
        # shellcheck disable=SC2086
        kill $pids 2>/dev/null || true
        sleep 3
    fi
    pids="$(port_pids "$MAIN_PORT")"
    if [[ -n "$pids" ]]; then
        log "порт всё ещё занят, SIGKILL: $pids"
        # shellcheck disable=SC2086
        kill -9 $pids 2>/dev/null || true
        sleep 2
    fi
    ! port_open "$MAIN_PORT"
}

start_main() {
    log "запускаю основной сервис :${MAIN_PORT}"
    timeout 60 "$INSTALL_BIN" service start >>"$LOG" 2>&1 || true
    sleep 3
    if ! port_open "$MAIN_PORT"; then
        log "service start не поднял порт — пробую serve --service напрямую"
        setsid nohup "$INSTALL_BIN" serve --service >>"$LOG" 2>&1 &
        sleep 3
    fi
}

start_mac() {
    log "запускаю oc-main (Mac :${MAC_PORT})"
    systemctl --user start oc-main.service >>"$LOG" 2>&1 || true
}

backup() {
    mkdir -p "$BK" || return 1

    log "бэкап БД (sqlite .backup, консистентный снимок)"
    if ! "$SQLITE" "$DB" ".backup '${BK}/opencode.db'" >>"$LOG" 2>&1; then
        log "FAIL sqlite backup"
        return 1
    fi
    if ! "$SQLITE" "${BK}/opencode.db" "pragma integrity_check;" 2>>"$LOG" | grep -qx 'ok'; then
        log "FAIL integrity_check"
        return 1
    fi

    cp -a "$INSTALL_BIN" "${BK}/opencode-${FROM_VERSION}" || return 1
    cp -a "$CONFIG_DIR" "${BK}/config" || return 1
    cp -a "$CONFIG_DIR/service.json" "${BK}/service.json" 2>/dev/null || true

    log "бэкап готов: ${BK} ($(du -sh "$BK" 2>/dev/null | cut -f1))"
    return 0
}

swap_binary() {
    local v
    v="$("$NEW_BIN" --version 2>/dev/null)"
    if [[ "$v" != *"$TARGET_VERSION"* ]]; then
        log "FAIL подготовленный бинарник: '${v}'"
        return 1
    fi

    cp -a "$INSTALL_BIN" "$BIN_BAK" || return 1
    install -m 0755 "$NEW_BIN" "${INSTALL_BIN}.new" || return 1
    mv -f "${INSTALL_BIN}.new" "$INSTALL_BIN" || return 1

    v="$("$INSTALL_BIN" --version 2>/dev/null)"
    if [[ "$v" != *"$TARGET_VERSION"* ]]; then
        log "FAIL после замены: '${v}'"
        return 1
    fi
    log "OK   бинарник заменён: ${v}"
}

rollback() {
    log "=== ОТКАТ на ${FROM_VERSION} ==="
    stop_main
    stop_mac

    local src="${BIN_BAK}"
    [[ -f "$src" ]] || src="${BK}/opencode-${FROM_VERSION}"
    if [[ -f "$src" ]]; then
        cp -a "$src" "$INSTALL_BIN" && log "бинарник восстановлен из ${src}"
    else
        log "FAIL нет бэкапа бинарника"
    fi

    rm -f "$DB" "${DB}-wal" "${DB}-shm"
    if [[ -f "${BK}/opencode.db" ]]; then
        cp -a "${BK}/opencode.db" "$DB" && log "БД восстановлена из бэкапа"
    else
        log "FAIL нет бэкапа БД"
    fi

    start_main
    start_mac

    if health_all "$FROM_VERSION"; then
        RESULT="rolled_back"
        log "откат успешен, работает ${FROM_VERSION}"
    else
        RESULT="rollback_failed"
        log "ОТКАТ НЕ УДАЛСЯ — нужно вмешательство"
    fi
}

prune_backups() {
    local keep=3
    find "$BKROOT" -maxdepth 1 -mindepth 1 -type d -name 'opencode-*' -printf '%T@ %p\n' 2>/dev/null |
        sort -rn | tail -n +$((keep + 1)) | cut -d' ' -f2- |
        while read -r d; do
            [[ "$d" == "$BK" ]] && continue
            log "удаляю старый бэкап: $d"
            rm -rf "$d"
        done
}

# ------------------------------------------------------------------ modes

mode_check() {
    local ok=0
    log "=== ПРОВЕРКА (ничего не меняется) ==="

    if [[ -x "$NEW_BIN" ]]; then
        log "OK   подготовленный бинарник: $("$NEW_BIN" --version 2>/dev/null)"
    else
        log "FAIL нет ${NEW_BIN}"; ok=1
    fi

    if [[ -f "$TARBALL" && -f "$SUMS" ]]; then
        if (cd "$STAGE" && grep 'opencode-linux-x64.tar.gz' SHA256SUMS | sha256sum -c - >/dev/null 2>&1); then
            log "OK   sha256 архива совпадает"
        else
            log "FAIL sha256 не совпадает"; ok=1
        fi
    else
        log "FAIL нет архива или SHA256SUMS"; ok=1
    fi

    local free_gb
    free_gb="$(df -BG --output=avail /home/ilia | tail -1 | tr -dc '0-9')"
    if (( free_gb >= 5 )); then log "OK   свободно ${free_gb} GB"; else log "FAIL свободно ${free_gb} GB"; ok=1; fi

    if load_secrets; then log "OK   пароли/токены прочитаны"; else log "FAIL секреты"; ok=1; fi

    log "сейчас main: $(api_version "http://127.0.0.1:${MAIN_PORT}/api/info" "$MAIN_PASS")"
    log "сейчас mac:  $(api_version "http://127.0.0.1:${MAC_PORT}/api/info" "$MAC_PASS")"
    log "сейчас gw:   $(api_version "http://127.0.0.1:${GW_PORT}/api/info" "" "$GW_TOKEN")"

    local active
    active="$(active_sessions 15)"
    log "активных сессий за 15 мин: ${active}"

    if systemctl --user is-enabled oc-upgrade.timer >/dev/null 2>&1; then
        log "таймер: $(systemctl --user list-timers oc-upgrade.timer --no-pager 2>/dev/null | sed -n '2p' | tr -s ' ')"
    else
        log "таймер oc-upgrade.timer выключен"
    fi

    RESULT="check_ok"
    (( ok == 0 )) || RESULT="check_failed"
    return "$ok"
}

mode_status() {
    load_secrets || true
    echo "main  :${MAIN_PORT}  $(api_version "http://127.0.0.1:${MAIN_PORT}/api/info" "${MAIN_PASS:-}")"
    echo "mac   :${MAC_PORT}  $(api_version "http://127.0.0.1:${MAC_PORT}/api/info" "${MAC_PASS:-}")"
    echo "gw    :${GW_PORT}  $(api_version "http://127.0.0.1:${GW_PORT}/api/info" "" "${GW_TOKEN:-}")"
    echo "public       $(api_version "https://${PUBLIC_HOST}/api/info" "" "${GW_TOKEN:-}")"
    if port_open "$BUS_PORT"; then echo "bus   :${BUS_PORT}  слушает"; else echo "bus   :${BUS_PORT}  нет"; fi
    echo
    [[ -f "$SUMMARY" ]] && cat "$SUMMARY" || echo "нет отчёта о последнем апгрейде"
    RESULT="status"
}

mode_apply() {
    local hour
    hour="$(date +%H)"

    log "=== APPLY ${FROM_VERSION} -> ${TARGET_VERSION} ==="

    # не трогаем систему днём, если таймер сработал с опозданием (Persistent)
    if (( 10#$hour < 1 || 10#$hour > 6 )); then
        log "сейчас ${hour}:xx — вне ночного окна 01:00-06:59, выхожу без изменений"
        log "запустить вручную: $0 --apply"
        RESULT="aborted"
        return 0
    fi

    load_secrets || { log "FAIL секреты"; RESULT="failed"; return 1; }

    local active
    active="$(active_sessions 15)"
    if [[ "$active" != "0" ]]; then
        log "активных сессий за 15 мин: ${active} — не трогаю, переношу"
        RESULT="aborted"
        return 0
    fi

    [[ -x "$NEW_BIN" ]] || { log "FAIL нет ${NEW_BIN}"; RESULT="failed"; return 1; }
    "$NEW_BIN" --version 2>/dev/null | grep -q "$TARGET_VERSION" || {
        log "FAIL подготовленный бинарник не той версии"; RESULT="failed"; return 1; }

    # 1. бэкап, пока работает старый бинарник (снимок БД консистентный)
    backup || { log "бэкап не удался — ничего не меняю"; RESULT="aborted"; return 1; }

    # 2. подмена бинарника (атомарно; запущенные процессы не страдают)
    swap_binary || { log "подмена не удалась — откат"; rollback; return 1; }

    # 3. перезапуск: Mac-инстанс и основной сервис
    stop_mac
    stop_main
    start_main
    start_mac

    # 4. проверки
    if health_all "$TARGET_VERSION"; then
        RESULT="success"
        log "апгрейд завершён: ${TARGET_VERSION}"
        prune_backups
    else
        log "проверки не прошли — откатываю"
        rollback
    fi

    # 5. таймер — одноразовый
    systemctl --user disable --now oc-upgrade.timer >>"$LOG" 2>&1 || true

    [[ "$RESULT" == "success" ]]
}

finish() {
    local msg
    load_secrets >/dev/null 2>&1 || true
    case "$RESULT" in
        success)         msg="OpenCode обновлён: ${FROM_VERSION} -> ${TARGET_VERSION}" ;;
        rolled_back)     msg="Обновление не удалось, выполнен откат на ${FROM_VERSION}" ;;
        rollback_failed) msg="ОТКАТ НЕ УДАЛСЯ — нужно вмешательство" ;;
        aborted)         msg="Обновление отменено, ничего не изменено" ;;
        check_ok)        msg="Проверка пройдена, всё готово" ;;
        check_failed)    msg="Проверка не пройдена" ;;
        failed)          msg="Обновление завершилось ошибкой" ;;
        *)               msg="Состояние: ${RESULT}" ;;
    esac

    {
        echo "$msg"
        echo "время: $(date '+%F %T')"
        echo "сейчас: main=$(api_version "http://127.0.0.1:${MAIN_PORT}/api/info" "${MAIN_PASS:-}") mac=$(api_version "http://127.0.0.1:${MAC_PORT}/api/info" "${MAC_PASS:-}") gw=$(api_version "http://127.0.0.1:${GW_PORT}/api/info" "" "${GW_TOKEN:-}")"
        echo "лог: ${LOG}"
        [[ -d "$BK" ]] && echo "бэкап: ${BK}"
    } > "$SUMMARY"

    printf '%s | %-14s | %s\n' "$(date '+%F %T')" "$RESULT" "$msg" >> "$HISTORY"

    # уведомление на рабочем столе (если сессия доступна)
    DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$(id -u)/bus" \
        notify-send "OpenCode upgrade" "$msg" 2>/dev/null || true
    log "$msg"
}

# ------------------------------------------------------------------ main

exec 9>"$LOCK"
if ! flock -n 9; then
    echo "апгрейд уже выполняется (${LOCK})"
    exit 1
fi

case "$MODE" in
    --check)    mode_check ;;
    --apply)    mode_apply ;;
    --rollback) load_secrets || true; rollback ;;
    --status)   mode_status ;;
    *)          echo "usage: $0 [--check|--apply|--rollback|--status]"; exit 2 ;;
esac
rc=$?

finish
exit "$rc"
