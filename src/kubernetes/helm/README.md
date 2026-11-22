# CinemaAbyss Helm chart

Chart разворачивает Proxy и Events на Spring Boot / Java 21, Go-монолит и Movies, PostgreSQL, Kafka и ZooKeeper. Ingress направляет весь бизнес-API в Proxy, включая `/api/events/*`.

Один release устанавливается в один namespace: имена Service фиксированы, а namespace берётся из `helm --namespace`. В другом namespace можно установить независимый экземпляр. Chart не создаёт и не удаляет Namespace.

## Подготовка

Нужны Helm 3, Kubernetes с `networking.k8s.io/v1`, динамический provisioner PVC и ingress-nginx. Для локального TLS используется OpenSSL. Все четыре образа приложения по умолчанию берутся из публичного `ghcr.io/aleksabramovweb/architecture-cinemaabyss`, tag `cinema`; registry credentials не требуются. Для воспроизводимой версии можно указать опубликованный `sha-<полный SHA>` в `*.image.tag`.

Пример из корня репозитория:

```bash
# Выберите свой учебный Kubernetes-контекст до применения команд.
kubectl create namespace cinemaabyss --dry-run=client -o yaml | kubectl apply -f -
bash scripts/kubernetes/create-local-tls.sh --namespace cinemaabyss
helm lint src/kubernetes/helm --strict
helm template cinemaabyss src/kubernetes/helm --namespace cinemaabyss > /tmp/cinemaabyss-rendered.yaml
kubectl apply --dry-run=server -f /tmp/cinemaabyss-rendered.yaml
helm install cinemaabyss ./src/kubernetes/helm \
  --namespace cinemaabyss --create-namespace --wait --atomic --timeout 10m
helm status cinemaabyss --namespace cinemaabyss
kubectl --namespace cinemaabyss get pods,services,ingress
```

Если namespace уже содержит ручную установку задания 3, устанавливайте проверочный release в отдельный namespace с другим `global.domain` и сертификатом для этого имени либо предварительно выполните описанный в задании сброс учебного стенда. Helm не присваивает себе существующие чужие ресурсы. Для отдельного namespace передайте тот же namespace TLS-скрипту и Helm.

Ingress использует существующий Secret `cinemaabyss-tls`; локальный скрипт создаёт CA и сертификат с SAN `cinemaabyss.example.com`. Ключи хранятся только в игнорируемом `.local/`. В другой среде укажите собственный TLS Secret через values.

## Проверка API

Настройте маршрут от локальной машины к ingress и имя `cinemaabyss.example.com` (для minikube Docker на macOS варианты приведены в задании 3 `Project_template.md`).

```bash
curl --cacert .local/cinemaabyss/tls/ca.crt \
  https://cinemaabyss.example.com/api/movies
NODE_EXTRA_CA_CERTS="$PWD/.local/cinemaabyss/tls/ca.crt" \
  npm --prefix tests/postman run test:kubernetes
kubectl --namespace cinemaabyss logs deployment/events-service
```

Для curl без системной записи hosts добавьте `--resolve cinemaabyss.example.com:443:127.0.0.1`, если ingress проброшен на localhost:443. В Postman передавайте CA или доверяйте ему штатным способом; проверка TLS остаётся включённой.

## Настройки

Все значения находятся в [values.yaml](values.yaml), типы и допустимые диапазоны — в [values.schema.json](values.schema.json).

| Параметр | Назначение / значение по умолчанию |
| --- | --- |
| `global.domain` | Host ingress, `cinemaabyss.example.com`. |
| `*.image.repository`, `*.image.tag`, `*.image.pullPolicy` | Образы; у приложений `cinema`, `IfNotPresent`. |
| `proxyService.replicas` | Два экземпляра Proxy. |
| `*.resources`, `*.service.port`, `*.service.targetPort` | Ресурсы и порты; upstream URL и ingress следуют Service-портам. |
| `proxyService.javaOpts`, `eventsService.javaOpts` | JVM options для Java 21 с ограничением heap относительно памяти контейнера. |
| `config.gradualMigration` | Boolean `true`: процентное переключение Movies. |
| `config.moviesMigrationPercent` | Integer `0..100`, по умолчанию `100`. |
| `config.kafkaBrokers` | Пусто — внутренний `kafka:9092`; задаётся явно для внешнего брокера. |
| `config.kafkaGroupId` | Consumer group Events, `cinemaabyss-events-service`. |
| `database.password` | Обычная строка, не base64; учебное значение `postgres_password`. |
| `database.existingSecret` | Имя внешнего Secret с ключом `DB_PASSWORD`; при задании chart не создаёт свой DB Secret. |
| `imagePullSecrets.existingSecret` | Внешний pull Secret в namespace release. |
| `imagePullSecrets.dockerconfigjson` | Альтернатива: base64 Docker config JSON с `auths`. По умолчанию пусто; реальные credentials не сохраняйте в Git. |
| `ingress.enabled`, `ingress.className` | Ingress включён, class `nginx`. |
| `ingress.tls.enabled`, `ingress.tls.secretName` | HTTPS включён, Secret `cinemaabyss-tls`. |
| `database.persistence.*`, `kafka.persistence.*`, `zookeeper.persistence.*` | PVC: enabled, size, accessMode, необязательный storageClass. `enabled=false` даёт `emptyDir`. |
| `kafka.heapOpts`, `zookeeper.heapOpts` | Ограничения heap инфраструктурных JVM. |

Пример registry Secret, созданного отдельно: `helm install ... --set imagePullSecrets.existingSecret=ghcr-read`. Inline JSON допускается через локальный values-файл. Внешний DB Secret используется всеми подключающимися к PostgreSQL сервисами; обновление его содержимого требует согласованного обновления пароля в самой БД и перезапуска клиентов.

Kafka/ZooKeeper рассчитаны на один экземпляр. Топики `movie-events`, `user-events`, `payment-events` создаёт Kafka Admin сервиса Events. Broker probes — TCP, без запуска дополнительных JVM; `KAFKA_CREATE_TOPICS` не используется. Это сохраняет настройки, проверенные в задании 3.

При `kafka.enabled=false` и включённом Events укажите `config.kafkaBrokers`; встроенный Kafka требует `zookeeper.enabled=true`. При выключенном Proxy выключите и ingress. Некорректный процент и несовместимые настройки отклоняются до установки.

## Обновление и rollback

```bash
helm upgrade cinemaabyss ./src/kubernetes/helm --namespace cinemaabyss \
  --set config.moviesMigrationPercent=0 --wait --atomic --timeout 10m
helm history cinemaabyss --namespace cinemaabyss
# Номер ревизии берётся из history; пример возврата к первой установке:
helm rollback cinemaabyss 1 --namespace cinemaabyss --wait --timeout 10m
```

Checksum-аннотации автоматически обновляют Pod при изменении нужной ему конфигурации. Переключение процента перезапускает только Proxy, с RollingUpdate `maxUnavailable: 0`; PostgreSQL, Kafka и consumers продолжают работу. Для image tag предпочтительны новые SHA-теги, чтобы Kubernetes гарантированно выполнил rollout новой версии.

## Данные и очистка

```bash
helm uninstall cinemaabyss --namespace cinemaabyss --wait
```

По умолчанию PostgreSQL PVC сохраняется при удалении StatefulSet; отдельные Kafka/ZooKeeper PVC помечены `helm.sh/resource-policy: keep`. Повторная установка с тем же release/namespace использует эти тома. Kafka и ZooKeeper нужно сохранять либо сбрасывать согласованной парой, иначе возможен Cluster ID mismatch. Инициализация PostgreSQL выполняется только для пустого каталога данных; изменение database name/user/password или SQL init не мигрирует существующую БД. Изменение размера/класса PVC зависит от provisioner и не выполняется изменением immutable StatefulSet-полей.

Для полного удаления **только одноразового проверочного namespace**, включая его данные:

```bash
kubectl delete namespace cinemaabyss
```

После локальной проверки остановите/удалите также созданный для неё minikube-профиль и вспомогательные port-forward/Docker-пробросы. Эти ресурсы не управляются Helm.
