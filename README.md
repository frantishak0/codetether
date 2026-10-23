# CodeTether

Android-клиент для [OpenCode](https://opencode.ai), работающего на вашем компьютере.
Планшет или телефон подключается к серверу OpenCode **в локальной сети** и **через интернет**
(Tailscale), переключаясь между ними автоматически.

> Неофициальный клиент. Не связан с проектом OpenCode и не поддерживается им.
> Название и логотип OpenCode принадлежат их владельцам.

![Экран сессий](docs/screenshots/01-sessions.png)

## Возможности

- Автоматический выбор пути: локальная сеть (быстро) → Tailscale (работает откуда угодно).
- Ручное переключение LAN ⇄ WAN и повторная проверка доступности.
- Авторизация по HTTP Basic (пароль сервера), пароль хранится в `EncryptedSharedPreferences`.
- Загрузка файлов из веб-интерфейса OpenCode, полноценная история навигации по кнопке «Назад».
- Edge-to-edge, тёмная/светлая тема наследуется от системы, локализация: English, Русский.

## Как это работает

```
[Android]  ──LAN──▶  http://192.168.x.x:49374
     │                        │
     └────Tailscale──▶  http://100.x.y.z:49374
                              ▼
                   opencode serve (ваш компьютер)
```

Приложение периодически проверяет оба адреса (`GET /api/info`, ответ 401 тоже считается
доступностью) и загружает веб-интерфейс OpenCode в WebView с той стороны, которая отвечает.

## Подготовка сервера

```bash
# 1. слушать локальную сеть и tailnet
opencode service set hostname 0.0.0.0
opencode service start

# 2. задать пароль (он же используется приложением)
opencode service set password "длинный-секрет"

# 3. разрешить порт в файрволе
sudo ufw allow from 192.168.0.0/16 to any port 49374 proto tcp
```

Для доступа из интернета — [Tailscale](https://tailscale.com) на компьютере и на планшете:

```bash
curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up
sudo ufw allow in on tailscale0
tailscale ip -4          # этот адрес указывается как WAN
```

## Настройка приложения

Кнопка **⚙** — адреса и пароль:

| Поле | Пример |
|---|---|
| LAN | `http://192.168.31.39:49374` |
| WAN | `http://100.104.12.20:49374` |
| Таймаут | `800` мс |
| Пароль | пароль сервера OpenCode |

## Сборка

Нужны JDK 17+, Android SDK 36.

```bash
./gradlew assembleRelease     # APK
./gradlew bundleRelease       # AAB
```

Подпись берётся из `keystore.properties` (не коммитится) или переменных
`CT_STORE_FILE`, `CT_STORE_PASSWORD`, `CT_KEY_ALIAS`, `CT_KEY_PASSWORD`.
Без них release-сборка собирается неподписанной.

## Конфиденциальность

Приложение не содержит аналитики, рекламы и не отправляет данные третьим лицам.
Все соединения идут напрямую к вашему серверу OpenCode. Подробнее — [PRIVACY.md](PRIVACY.md).

## Лицензия

MIT — см. [LICENSE](LICENSE).
