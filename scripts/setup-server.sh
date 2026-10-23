#!/usr/bin/env bash
# CodeTether — настройка сервера OpenCode и вывод QR для подключения телефона.
#
# Запуск:
#   curl -fsSL https://raw.githubusercontent.com/frantishak0/codetether/main/scripts/setup-server.sh | bash
#
# Что делает:
#   1. включает прослушивание локальной сети (hostname 0.0.0.0)
#   2. задаёт пароль сервера (или использует существующий)
#   3. открывает порт 49374 в ufw для локальной подсети
#   4. если установлен Tailscale — добавляет его адрес как WAN
#   5. печатает QR-код и ссылку для приложения

set -uo pipefail

PORT=49374
BOLD=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; OFF=$'\033[0m'

say()  { printf '%s\n' "$*"; }
ok()   { printf '%s✓%s %s\n' "$GREEN" "$OFF" "$*"; }
warn() { printf '%s!%s %s\n' "$YELLOW" "$OFF" "$*"; }
die()  { printf '%s✗%s %s\n' "$RED" "$OFF" "$*"; exit 1; }

say "${BOLD}CodeTether — настройка сервера${OFF}"

# ---------- 1. opencode ----------
command -v opencode >/dev/null 2>&1 || die "opencode не найден в PATH. Установите: curl -fsSL https://opencode.ai/install | bash"
ok "opencode $(opencode --version 2>/dev/null | head -1)"

# ---------- 2. пароль ----------
PW="$(opencode service get password 2>/dev/null | head -1 || true)"
if [ -z "${PW:-}" ]; then
  PW="$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"
  opencode service set password "$PW" >/dev/null 2>&1 || true
  ok "сгенерирован пароль сервера"
else
  ok "использую существующий пароль сервера"
fi

# ---------- 3. слушать локальную сеть ----------
CURRENT_HOST="$(opencode service get hostname 2>/dev/null | head -1 || true)"
if [ "$CURRENT_HOST" = "0.0.0.0" ]; then
  ok "сервер уже слушает 0.0.0.0:$PORT"
else
  opencode service set hostname 0.0.0.0 >/dev/null 2>&1 || true
  opencode service start >/dev/null 2>&1 || true
  sleep 1
  ok "сервер переключён на 0.0.0.0:$PORT"
fi

# ---------- 4. локальный адрес и файрвол ----------
CIDR="$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | grep -vE '^100\.' | head -1)"
LAN_IP="${CIDR%%/*}"
if command -v python3 >/dev/null 2>&1 && [ -n "$CIDR" ]; then
  CIDR="$(python3 -c "import ipaddress,sys;print(ipaddress.ip_interface(sys.argv[1]).network)" "$CIDR" 2>/dev/null || echo "$CIDR")"
fi
if [ -z "$LAN_IP" ]; then
  warn "не удалось определить локальный IP — укажите адрес в приложении вручную"
  LAN_IP="127.0.0.1"
fi

if command -v ufw >/dev/null 2>&1; then
  if sudo -n true 2>/dev/null; then
    sudo ufw allow from "$CIDR" to any port "$PORT" proto tcp >/dev/null 2>&1 \
      && ok "ufw: разрешён порт $PORT для $CIDR" || warn "ufw: не удалось добавить правило"
  else
    warn "для правила ufw нужен sudo — выполните вручную:"
    say  "    sudo ufw allow from $CIDR to any port $PORT proto tcp"
  fi
else
  warn "ufw не найден — убедитесь, что порт $PORT открыт"
fi

# ---------- 5. tailscale ----------
WAN=""
if command -v tailscale >/dev/null 2>&1; then
  TS_IP="$(tailscale ip -4 2>/dev/null | head -1 || true)"
  if [ -n "$TS_IP" ]; then
    WAN="http://$TS_IP:$PORT"
    ok "Tailscale: $TS_IP"
    if sudo -n true 2>/dev/null; then
      sudo ufw allow in on tailscale0 >/dev/null 2>&1 || true
    fi
  else
    warn "Tailscale установлен, но не подключён (tailscale up)"
  fi
else
  warn "Tailscale не установлен — доступ из интернета работать не будет"
  say  "    ${DIM}curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up${OFF}"
fi

# ---------- 6. полезная нагрузка и QR ----------
LAN="http://$LAN_IP:$PORT"
JSON="$(printf '{"v":1,"lan":"%s","wan":"%s","password":"%s"}' "$LAN" "${WAN:-}" "$PW")"
DEEP="codetether://setup?d=$(printf '%s' "$JSON" | base64 -w0 | tr '+/' '-_' | tr -d '=')"

render_qr() {
  local payload="$1" tmp
  command -v npm >/dev/null 2>&1 || return 1
  command -v node >/dev/null 2>&1 || return 1
  tmp="$(mktemp -d)"
  if npm install --silent --no-fund --no-audit --prefix "$tmp" qrcode-terminal >/dev/null 2>&1; then
    node -e "require('$tmp/node_modules/qrcode-terminal').generate(process.argv[1],{small:true})" "$payload"
    local rc=$?
    rm -rf "$tmp"
    return $rc
  fi
  rm -rf "$tmp"
  return 1
}

say
say "${BOLD}Отсканируйте QR в приложении CodeTether (кнопка 📷):${OFF}"
say
if ! render_qr "$JSON"; then
  warn "не удалось отрисовать QR (нужны node/npm и сеть) — используйте ссылку ниже"
fi

say
say "${BOLD}Ссылка для вставки вручную:${OFF}"
say "$DEEP"
say
say "${DIM}LAN: $LAN${OFF}"
[ -n "$WAN" ] && say "${DIM}WAN: $WAN${OFF}"
say "${DIM}Пароль сервера: $PW${OFF}"
say
say "Скачать приложение: https://github.com/frantishak0/codetether/releases/latest"
