<#
.SYNOPSIS
    CodeTether — настройка сервера OpenCode на Windows и вывод QR для подключения телефона.

.DESCRIPTION
    Скрипт включает прослушивание локальной сети, задаёт пароль сервера, создаёт правило
    Windows Defender Firewall, подхватывает адрес Tailscale и печатает QR-код настройки.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\setup-server.ps1

.NOTES
    Требуется Windows PowerShell 5.1 или PowerShell 7+.
    Для создания правила файрвола нужны права администратора (иначе будет подсказка).
#>

[CmdletBinding()]
param(
    [int]$Port = 0,
    [switch]$NoFirewall
)

$ErrorActionPreference = 'Stop'

function Write-Ok   { param($m) Write-Host '✓ ' -ForegroundColor Green -NoNewline; Write-Host $m }
function Write-Warn2{ param($m) Write-Host '! ' -ForegroundColor Yellow -NoNewline; Write-Host $m }
function Write-Die  { param($m) Write-Host '✗ ' -ForegroundColor Red -NoNewline; Write-Host $m; exit 1 }

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return ([Security.Principal.WindowsPrincipal]$id).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-PrivateIPv4 {
    $addrs = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.IPAddress -notlike '100.6*' -and
            $_.IPAddress -notlike '100.7*'
        }
    $patterns = @('^192\.168\.', '^10\.', '^172\.(1[6-9]|2\d|3[01])\.')
    foreach ($p in $patterns) {
        $hit = $addrs | Where-Object { $_.IPAddress -match $p } | Select-Object -First 1
        if ($hit) { return $hit.IPAddress }
    }
    $any = $addrs | Select-Object -First 1
    if ($any) { return $any.IPAddress }
    return $null
}

function Get-QrModulePath {
    $npm = Get-Command npm -ErrorAction SilentlyContinue
    $node = Get-Command node -ErrorAction SilentlyContinue
    if (-not $npm -or -not $node) { return $null }
    $tmp = Join-Path $env:TEMP ('codetether-qr-' + [guid]::NewGuid().ToString('N'))
    try {
        & npm install --silent --no-fund --no-audit --prefix $tmp qrcode-terminal 2>$null | Out-Null
        $mod = Join-Path $tmp 'node_modules\qrcode-terminal'
        if (Test-Path $mod) { return @{ Path = $mod; Temp = $tmp } }
    } catch { }
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
    return $null
}

Write-Host 'CodeTether — настройка сервера' -ForegroundColor Cyan

# ---------- 1. opencode ----------
$oc = Get-Command opencode -ErrorAction SilentlyContinue
if (-not $oc) {
    if (Get-Command wsl -ErrorAction SilentlyContinue) {
        Write-Die @"
opencode не найден в PATH Windows.
Похоже, у вас установлен WSL — тогда запустите bash-версию внутри WSL:

    wsl bash -lc "curl -fsSL https://raw.githubusercontent.com/frantishak0/codetether/main/scripts/setup-server.sh | bash"

и откройте доступ к порту из Windows (portproxy), либо поставьте OpenCode нативно:
https://opencode.ai/update/api/latest/cli/opencode/opencode-windows-x64.zip
"@
    }
    Write-Die 'opencode не найден в PATH. Скачайте сборку для Windows: https://opencode.ai/update/api/latest/cli/opencode/opencode-windows-x64.zip'
}
$ocVersion = (& opencode --version 2>$null | Select-Object -First 1)
Write-Ok "opencode $ocVersion"

# ---------- 2. пароль ----------
$pw = (& opencode service get password 2>$null | Select-Object -First 1)
if ([string]::IsNullOrWhiteSpace($pw)) {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pw = ([Convert]::ToBase64String($bytes) -replace '[+/=]', '')
    $pw = $pw.Substring(0, [Math]::Min(24, $pw.Length))
    & opencode service set password $pw | Out-Null
    Write-Ok 'сгенерирован пароль сервера'
} else {
    Write-Ok 'использую существующий пароль сервера'
}

# ---------- 3. слушать локальную сеть ----------
$currentHost = (& opencode service get hostname 2>$null | Select-Object -First 1)
if ($currentHost -ne '0.0.0.0') {
    & opencode service set hostname 0.0.0.0 | Out-Null
    & opencode service start | Out-Null
    Start-Sleep -Seconds 1
    Write-Ok 'сервер переключён на 0.0.0.0'
} else {
    Write-Ok 'сервер уже слушает 0.0.0.0'
}

# ---------- 4. порт ----------
if ($Port -le 0) {
    $status = (& opencode service status 2>$null | Select-Object -First 1)
    if ($status -match ':(\d+)') { $Port = [int]$Matches[1] } else { $Port = 49374 }
}
Write-Ok "порт сервера: $Port"

# ---------- 5. локальный адрес ----------
$lanIp = Get-PrivateIPv4
if (-not $lanIp) {
    Write-Warn2 'не удалось определить локальный IP — впишите адрес в приложении вручную'
    $lanIp = '127.0.0.1'
}

# ---------- 6. файрвол ----------
if (-not $NoFirewall) {
    if (Test-Admin) {
        $rule = Get-NetFirewallRule -DisplayName 'CodeTether' -ErrorAction SilentlyContinue
        if ($rule) {
            Write-Ok 'правило файрвола CodeTether уже существует'
        } else {
            New-NetFirewallRule -DisplayName 'CodeTether' -Direction Inbound -Action Allow `
                -Protocol TCP -LocalPort $Port -Profile Private | Out-Null
            Write-Ok "файрвол: разрешён порт $Port (профиль Private)"
        }
    } else {
        Write-Warn2 'для правила файрвола нужны права администратора. Выполните в PowerShell от админа:'
        Write-Host "    New-NetFirewallRule -DisplayName 'CodeTether' -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -Profile Private"
    }
}

# ---------- 7. tailscale ----------
$wan = ''
if (Get-Command tailscale -ErrorAction SilentlyContinue) {
    $tsIp = (& tailscale ip -4 2>$null | Select-Object -First 1)
    if ($tsIp) {
        $wan = "http://${tsIp}:$Port"
        Write-Ok "Tailscale: $tsIp"
    } else {
        Write-Warn2 'Tailscale установлен, но не подключён (выполните: tailscale up)'
    }
} else {
    Write-Warn2 'Tailscale не установлен — доступ из интернета работать не будет'
    Write-Host '    Скачать: https://tailscale.com/download/windows'
}

# ---------- 8. QR и ссылка ----------
$lan = "http://${lanIp}:$Port"
$payload = [ordered]@{ v = 1; lan = $lan; wan = $wan; password = $pw } | ConvertTo-Json -Compress
$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
$deep = "codetether://setup?d=$b64"

Write-Host ''
Write-Host 'Отсканируйте QR в приложении CodeTether (кнопка 📷):' -ForegroundColor White
Write-Host ''

$qr = Get-QrModulePath
$qrOk = $false
if ($qr) {
    try {
        $modPath = ($qr.Path -replace '\\', '/')
        & node -e "require(process.argv[1]).generate(process.argv[2],{small:true})" $modPath $payload
        $qrOk = ($LASTEXITCODE -eq 0)
    } catch {
        $qrOk = $false
    } finally {
        Remove-Item -Recurse -Force $qr.Temp -ErrorAction SilentlyContinue
    }
}
if (-not $qrOk) {
    Write-Warn2 'не удалось отрисовать QR (нужны Node.js и npm) — используйте ссылку ниже'
}

Write-Host ''
Write-Host 'Ссылка для вставки вручную:' -ForegroundColor White
Write-Host $deep
Write-Host ''
Write-Host "LAN: $lan" -ForegroundColor DarkGray
if ($wan) { Write-Host "WAN: $wan" -ForegroundColor DarkGray }
Write-Host "Пароль сервера: $pw" -ForegroundColor DarkGray
Write-Host ''
Write-Host 'Скачать приложение: https://github.com/frantishak0/codetether/releases/latest'
