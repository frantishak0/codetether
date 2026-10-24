## CodeTether 1.1.4

### Исправлено

- **«ClientError: Transport: Failed to fetch» / «Что-то пошло не так» больше не вернётся.**
  Причина была не только в HTTP-кэше: у веб-интерфейса OpenCode есть **Service Worker**,
  который держит собственную копию файлов. При обновлении OpenCode имена файлов меняются,
  а Service Worker продолжал отдавать старые — отсюда 404 на `/_assets/health-*.js`.

  Теперь при смене версии сервера приложение **полностью очищает данные WebView** —
  HTTP-кэш, Code Cache, Local Storage и Service Worker — и перезапускается само.
  Cookies не трогаются, повторный вход не нужен.

- Первый запуск после установки тоже делает такую очистку (однократно).

- В меню **⚙ → Ещё** пункт «Очистить кэш» заменён на **«Сбросить кэш (перезапуск)»** —
  полный сброс данных WebView с перезапуском приложения.

### Как обновить

`⚙ → Ещё → Проверить обновления` или APK:
`https://github.com/frantishak0/codetether/releases/latest/download/CodeTether-latest.apk`

### Файлы

- `CodeTether-1.1.4.apk` — подписанный APK
- `CodeTether-1.1.4.aab` — Android App Bundle
