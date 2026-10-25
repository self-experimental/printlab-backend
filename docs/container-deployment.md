# Контейнеры и автодеплой

## Схема

Публичный Nginx на VPS завершает HTTPS, продлевает сертификат через существующий
Certbot и ограничивает публичные API-маршруты. Он проксирует API на
`127.0.0.1:8080`, статику — на `127.0.0.1:8081`.

Docker Compose запускает два независимых контейнера:

- Backend: Java 25.0.5+8, Bambu Studio 02.08.02.61 и его Linux-библиотеки.
  Контрольные суммы скачанных компонентов проверяются при сборке.
  Процесс работает с UID/GID 10001, лимитами 3 CPU / 5 ГБ RAM и одним подбором
  одновременно. Данные находятся в отдельном volume `printlab-data`.
- Frontend: собранный Vite `dist` и Nginx без root, UID/GID 101.
  Только порт 8080 внутри контейнера, 128 МБ RAM.

Корневые файловые системы доступны только для чтения; временные STL/G-code
создаются в ограниченном tmpfs backend. Docker socket, ключи SSH и сертификаты
не монтируются в приложения. В контекст сборки не попадают пользовательские STL.
Порты опубликованы только на loopback: публикация Docker на всех интерфейсах
может обходить UFW.

Будущий Telegram-бот подключается отдельным Compose-сервисом. Бот пока не написан;
его токен не входит в образы, окружение фронта или текущую конфигурацию.

## Цепочка CI/CD

В обоих репозиториях `.github/workflows/ci.yml`:

1. Pull request запускает проверки без доступа к production-ключу и публикации образа.
2. Push в `main` запускает те же проверки. Backend: Maven verify и тесты приёмника
   деплоя. Frontend: `npm run check` и браузерные тесты.
3. Проверенный артефакт собирается в Linux/amd64 образ и публикуется в GHCR.
   SHA коммита записан в OCI label; сервер использует неизменяемый digest.
4. Задание `Deploy production` получает ключ из environment `production`
   и короткоживущий `GITHUB_TOKEN` с `packages:read`. Пароль GitHub/PAT не нужен.
5. SSH запускает только root-owned приёмник для соответствующего компонента.
   Он скачивает образ, сверяет SHA и платформу, обновляет один сервис и ждёт healthcheck.
6. Backend дополнительно выполняет настоящий подбор для публичного синтетического
   `deploy/stepped-block.stl`: требуются все 6 вариантов и положительные оценки.
   Ошибка запуска/проверки возвращает предыдущий образ.

Деплои фронта и бэка сериализуются общей серверной блокировкой. Более старый
номер запуска не может заменить уже успешно установленную новую версию.
Запущенный деплой `main` не отменяется новым push; промежуточные ожидающие запуски
GitHub может объединять. После успешного завершения установлен актуальный код.
Контейнер пересоздаётся с короткой паузой; бесшовный blue/green не реализован.
Несовместимые изменения API требуют совместимого перехода в два этапа.
Миграций данных автоматически нет: откат образа не откатывает содержимое volume.

## Однократное подключение

Конфигурация на VPS, принадлежит root:

```text
/etc/printlab/compose.yaml
/etc/printlab/compose.env
/opt/printlab/deploy/receive-deploy.py
/opt/printlab/deploy/smoke.py
/opt/printlab/deploy/stepped-block.stl
/var/lib/printlab-deploy/{backend,frontend}.json
```

`compose.env` содержит только `BACKEND_IMAGE` и `FRONTEND_IMAGE`; остальные
настройки заданы в Compose. Не копировать туда host-only `SERVER_ADDRESS=127.0.0.1`:
внутри контейнера приложение должно слушать `0.0.0.0`.

Отдельный пользователь `printlab-deploy` не входит в Docker group. Его root-owned
`authorized_keys` содержит два разных Ed25519 ключа с `restrict` и forced command:

```text
sudo -n /usr/bin/python3 -I /opt/printlab/deploy/receive-deploy.py backend
sudo -n /usr/bin/python3 -I /opt/printlab/deploy/receive-deploy.py frontend
```

Sudoers разрешает только эти точные команды. Произвольный SSH shell, forwarding,
изменение Compose и доступ к root-ключу ноутбука CI не предоставляются.
Закрытая часть каждого ключа сохраняется как `DEPLOY_SSH_KEY` только в соответствующем
репозитории → environment `production`. Environment разрешает только ветку `main`.
GHCR образы остаются приватными с доступом соответствующего репозитория Actions.
Приёмник хранит credentials GHCR только во временном каталоге на время pull,
после чего удаляет их. Публичный SSH host key закреплён в `deploy/ssh_known_hosts`;
при смене VPS его нужно сверять и обновлять вручную.

Установка/изменение этих прав требует согласия владельца. Одного наличия workflow
в локальной ветке недостаточно: файлы должны попасть в `main`, environment и ключи
должны быть подключены, а первые реальные запуски Actions — завершиться успешно.

## Проверки и обслуживание

На VPS:

```sh
docker compose --env-file /etc/printlab/compose.env -f /etc/printlab/compose.yaml ps
docker compose --env-file /etc/printlab/compose.env -f /etc/printlab/compose.yaml logs --tail=100 backend
python3 /opt/printlab/deploy/smoke.py
cat /var/lib/printlab-deploy/backend.json
df -h /
```

Новый ключ/изменение Compose/обновление приёмника устанавливаются вручную после
проверки. Бэкапить volume отдельно от образов. Не выполнять `down -v`, удаление
volume или глобальный `docker system prune --volumes` на production.
Автоматическая очистка старых образов не включена; при обслуживании сохранять
текущие и предыдущие digest из файлов состояния, а также bootstrap-образы отката.

Для ручного отката заменить соответствующий `*_IMAGE` в `compose.env` на
`previous_image` из файла состояния, выполнить `up -d --no-deps --wait backend`
(или `frontend`) с указанными выше `--env-file`/`-f`, проверить сайт и расчёт.

Первоначальные данные `/var/lib/printlab` и systemd-служба сохранены отдельно
для отката первой миграции; после перехода новые данные пишутся только в volume.
Копия прежнего Nginx находится в
`/opt/printlab/backups/container-migration-20261025/nginx.conf`.
Для полного возврата сначала остановить Compose, затем вернуть прежний Nginx
и `systemctl enable --now printlab-backend`; учитывать различие копий данных.
