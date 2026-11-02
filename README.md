# letnievpn

Android-приложение (Kotlin + Jetpack Compose) с настоящим VPN на ядре xray.
Дизайн — как в прототипе letnievpn.html.

## Что умеет

- Кнопка «+» берёт подписку из буфера обмена (ссылка на подписку или список ссылок) — без сообщений и всплывашек.
- Протоколы: VLESS (в том числе Reality), VMess, Trojan, Shadowsocks. Транспорты: TCP, WS, HTTPUpgrade, XHTTP, H2, gRPC.
- Умное подключение: само измеряет пинг всех серверов, выбирает самый быстрый, проверяет, что через него идёт трафик, и при неудаче берёт следующий. Если телефон перезагрузили, а VPN был включён, поднимает его само (разрешение VPN должно быть выдано заранее — достаточно один раз включить вручную).
- Ручной выбор сервера выключает умное подключение.
- Подписки обновляются при открытии приложения.

## Сборка release APK через GitHub Actions

1. Создайте репозиторий на GitHub и залейте туда содержимое архива (корень репозитория — папка с `gradlew`).
2. Откройте вкладку **Actions** → **Build release APK** → **Run workflow** (или просто сделайте push в `main`).
3. Готовые APK — в **Artifacts** запуска: `letnievpn-arm64-v8a` (для современных телефонов) и `letnievpn-universal`.

Собирается только release. Debug-сборка в проекте отключена.

### Своя подпись (чтобы обновляться поверх прошлой версии)

Без секретов workflow создаёт одноразовый ключ: APK установится, но следующая сборка будет подписана другим ключом, и обновить поверх не получится.
Чтобы подпись была постоянной, создайте ключ один раз:

```
keytool -genkeypair -v -keystore release.jks -alias letnie -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 release.jks
```

и добавьте в **Settings → Secrets and variables → Actions** секреты:
`KEYSTORE_BASE64` (вывод base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Ядро

`libv2ray.aar` (xray-core) workflow скачивает сам из релизов
[2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite/releases). Для локальной сборки положите файл в `app/libs/`.

## Структура

- `app/src/main/java/app/letnievpn/ui` — экран на Compose
- `.../vpn` — `LetnieVpnService` (TUN + ядро), `BootReceiver` (автозапуск)
- `.../data` — подписки, разбор ссылок, конфиг xray, пинг
