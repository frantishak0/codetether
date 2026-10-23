## CodeTether 1.1.0

Подключение теперь занимает одно сканирование QR, а обновления приходят сами.

### Что нового

- **QR-подключение.** На компьютере запускается один скрипт, который настраивает сервер
  и печатает QR. В приложении — кнопка **📷**: адреса LAN/WAN и пароль подставляются автоматически.
- **Deep link** `codetether://setup?d=…` — ту же настройку можно отправить ссылкой.
- **Автообновление** из GitHub Releases (раз в сутки и по кнопке ⬆).
- Более надёжный выбор локальной сети: две попытки проверки и повторный возврат на LAN
  через 6 секунд после перехода на WAN.


### Windows

Приложение работает с сервером на любой ОС. Для Windows добавлено:

- `scripts/setup-server.ps1` — аналог bash-скрипта: сам определяет порт (`28894` по умолчанию),
  создаёт правило Windows Defender Firewall, подхватывает адрес Tailscale и печатает QR.
- Раздел «Windows» в README: установка OpenCode из архива, настройка и отдельно — случай
  с OpenCode внутри WSL2 (mirrored networking либо `netsh portproxy`).
- Bash-скрипт теперь читает фактический порт сервера, а не предполагает `49374`.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup-server.ps1
```

### Быстрый старт

```bash
curl -fsSL https://raw.githubusercontent.com/frantishak0/codetether/main/scripts/setup-server.sh | bash
```

Затем на телефоне: установить `CodeTether-1.1.0.apk` → открыть → **📷** → навести на QR.

### Файлы

- `CodeTether-1.1.0.apk` — подписанный APK для прямой установки.
- `CodeTether-1.1.0.aab` — Android App Bundle.

Проверено на Lenovo TB710FU (Android 16), Linux Mint 22.3, OpenCode 2.1.2.
