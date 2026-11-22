## Изучите [README-правка.md](README-правка.md) файл и структуру проекта.

# Задание 1

## Контейнерная диаграмма C4 — To-Be

- [Посмотреть диаграмму в SVG](docs/architecture/to-be.svg).
- [Исходник PlantUML](docs/architecture/to-be.puml).

![Кинобездна: целевая контейнерная архитектура C4](docs/architecture/to-be.svg)

Диаграмма показывает целевую систему после завершения декомпозиции. Внутри границы «Кинобездна» находятся клиентские и серверные приложения, хранилища и Kafka; снаружи — зритель и системы партнёров. У контейнеров указаны назначение и технология, у связей — направление и способ взаимодействия. Сплошные связи обозначают синхронные запросы, пунктирные охристые — события и фоновый обмен. Видеопоток отделён от управляющего API.

Исходник использует встроенную библиотеку C4-PlantUML (`!include <C4/C4_Container>`) и Java-движок Smetana. Для воспроизведения SVG не нужны сетевые include или установленный Graphviz:

```bash
java -jar /path/to/plantuml.jar -charset UTF-8 -tsvg docs/architecture/to-be.puml
```

Полная To-Be — ориентир для дальнейшего развития. Результат ближайших двух месяцев: перенос существующей системы в Kubernetes, выделенный `movies`, `proxy`, Kafka MVP `events`, Helm и CI/CD. Выделение остальных доменов не включается в этот срок.

## Отправная точка

По условию система представляет собой Go-монолит с одной PostgreSQL, синхронными вызовами и внешней асинхронной рекомендательной системой. Она обслуживает пользователей, фильмы и видео, платежи, подписки, скидки, личные оценки и избранное.

В стартовом коде [монолит](src/monolith/main.go) уже сосуществует с [movies](src/microservices/movies/main.go). Согласно [Compose](docker-compose.yml), они подключаются к одной базе, причём оба читают и изменяют таблицы `movies` и `movie_genres`. В [SQL-схеме](src/database/init.sql) также есть пользователи, платежи, подписки, просмотры и личные оценки. Аутентификация, папки избранного, актёры и полноценное воспроизведение предусмотрены требованиями, но соответствующие бизнес-сценарии в стартовом API не реализованы. Ниже описывается проектное решение, а не утверждение об их готовности.

## Домены и владение данными

| Домен | Контейнер | Ответственность и собственные данные |
| --- | --- | --- |
| Пользователи и доступ | **Users Service**, Go + Users DB, PostgreSQL | Аккаунты, аутентификация, профили, выпуск токенов. Владелец идентификатора пользователя. |
| Каталог и метаданные | **Movies Service**, Go + Movies DB, PostgreSQL | Фильмы, описания, жанры, актёры, длительность, постеры. Хранит проекцию общего рейтинга; личные оценки принадлежат Engagement. |
| Пользовательская активность | **Engagement Service**, Go + Engagement DB, PostgreSQL | Личные оценки, папки и избранное, история просмотров. Пользовательские действия порождают события для рейтинга и рекомендаций. |
| Рекомендации | **Recommendations Service**, Go + Recommendations DB, PostgreSQL | Адаптер сторонней рекомендательной системы, фоновые задания и готовые персональные подборки. Алгоритм рекомендаций остаётся внешним. |
| Монетизация | **Commerce Service**, Go + Commerce DB, PostgreSQL | Модули платежей, подписок и скидок; тарифы и право просмотра по подписке. Интеграции с платёжными провайдерами, лояльностью и маркетплейсами. |
| Просмотр контента | **Playback Service**, Go + Playback DB, PostgreSQL | Видео, соответствие фильма источникам, права поставщиков, сессии просмотра и выдача разрешённого URL потока. Сами видеоданные поступают от поставщиков/CDN. |

Платежи, подписки и скидки объединены в один сервис монетизации: их правила тесно связаны, а команду из пяти Go-разработчиков не стоит сразу нагружать отдельным развёртыванием каждой сущности. Внутренние модули отделяют платёжную логику от управления подпиской и акциями. При необходимости их можно выделить позже. Выбранный Go соответствует компетенциям команды; новых языков в серверную часть не добавляется.

Каждый сервис — единственный владелец своих данных и миграций. В To-Be у него отдельная логическая БД и роль PostgreSQL; доступ к таблицам других доменов запрещён. На старте эти БД могут находиться в одном PostgreSQL-кластере, но это сохраняет общую точку отказа и предел масштабирования. Нагруженные домены можно переносить на отдельные кластеры независимо. Междоменные ссылки передаются как идентификаторы; внешних ключей между базами и межсервисных SQL JOIN нет.

## Единая точка входа и клиентские приложения

**API Gateway / Proxy** — общий публичный адрес REST API для Web, Mobile и Smart TV. Kubernetes Ingress завершает TLS и направляет бизнес-API в Gateway; внутренние сервисы доступны через ClusterIP. Gateway проверяет токены, ограничивает нагрузку и маршрутизирует запросы, а доменные сервисы проверяют права на конкретные ресурсы. Выпуск токенов выполняет Users Service; проверка подписанного токена не требует синхронного запроса к Users для каждого обращения.

В целевой версии Gateway содержит модуль BFF с представлениями Web/Mobile/TV: он собирает экран из нескольких API и отдаёт нужные поля, размеры изображений и объём списка. Доменные модели от типа устройства не зависят. Существующие маршруты `/api/movies`, `/api/users`, `/api/payments`, `/api/subscriptions` сохраняются. На этапе задания 2 Proxy выполняет маршрутизацию по существующим контрактам; BFF и полноценная аутентификация относятся к последующим этапам.

Единая точка входа относится к бизнес-API. После получения разрешённого URL клиент скачивает поток напрямую у поставщика/CDN, чтобы Gateway не становился узким местом для видео. Уведомления платёжного провайдера также проходят через Gateway в Commerce; Commerce проверяет подпись и защищается от повторной обработки.

## Интеграционное взаимодействие

**Синхронный REST/JSON** используется, когда результат необходим для ответа пользователю:

1. Клиент → Gateway → Movies: список фильмов и карточка с метаданными.
2. Клиент → Gateway → Users / Engagement / Commerce: работа с профилем, избранным, оценками и подпиской.
3. Клиент → Gateway → Playback → Commerce: проверка действующей подписки; Playback дополнительно проверяет права на источник и выдаёт URL просмотра. При недоступной проверке новый доступ не выдаётся; поток уже разрешённой сессии не проходит через эти сервисы.
4. Commerce → платёжный провайдер: инициирование платежа с ключом идемпотентности. Ответ о начале платежа ещё не означает успешную оплату. Подтверждённый статус провайдера обрабатывается в Commerce; платёж, активация подписки и outbox-событие фиксируются локальной транзакцией.

На внешних HTTP-вызовах задаются таймауты и ограничение параллелизма; повторяются только безопасные либо идемпотентные операции. Отказ рекомендаций не должен блокировать каталог и воспроизведение.

**Асинхронные события через Kafka** распространяют уже совершившиеся изменения:

| Издатель | Топик и примеры событий | Потребитель и результат |
| --- | --- | --- |
| Users | `user-events`: `UserChanged` | Recommendations актуализирует данные для подбора. |
| Movies | `movie-events`: `MovieChanged` | Recommendations обновляет сведения о каталоге. |
| Engagement | `activity-events`: `RatingSubmitted`, `WatchRecorded` | Movies обновляет проекцию общего рейтинга; Recommendations учитывает активность. |
| Playback | `activity-events`: `PlaybackStarted` | Engagement добавляет запись истории; Recommendations учитывает начало просмотра. Реальный прогресс просмотра поступает через API Engagement. |
| Commerce | `payment-events`: `PaymentConfirmed` | Recommendations может использовать факт покупки; событие также доступно будущим интеграциям. Подписку активирует сам Commerce, а не внешний потребитель. |

Имена `user-events`, `movie-events`, `payment-events` соответствуют топикам стартового Compose. `activity-events` и перечисленные доменные типы — целевое расширение после MVP. Movies обрабатывает только события оценок, даже если в том же топике есть другие виды активности. Каждый сервис-подписчик использует собственную consumer group; внутри группы экземпляры делят партиции.

Recommendations читает события и в фоне взаимодействует с API внешней рекомендательной системы. Точный протокол получения результатов адаптируется к возможностям поставщика; поддержка Kafka внешней системой не предполагается. Готовые подборки сохраняются локально и выдаются по REST. При задержке расчёта доступны последняя подборка или обычный каталог.

Бизнес-сервисы публикуют события через **transactional outbox**: изменение состояния и запись события выполняются одной транзакцией в собственной БД, затем фоновый издатель доставляет событие в Kafka. Конверт содержит `event_id`, тип, версию, время, идентификатор сущности и `correlation_id`. Доставка — как минимум однократная: потребители обрабатывают дубли идемпотентно и фиксируют offset после успешного сохранения результата. Ключ партиции задаётся по сущности, для оценок — по пользователю и фильму; версия защищает проекции от устаревших обновлений. Повторные попытки ограничены, необрабатываемые события отправляются в DLQ с оповещением. Проекции рейтингов и рекомендаций согласуются в конечном счёте, финансовое состояние и право доступа остаются в транзакционных данных Commerce.

**Events Service из задания 2 — инфраструктурный MVP**, который принимает `/api/events/{user,payment,movie}`, публикует события и сам читает их с записью в лог. Он проверяет работу Kafka, но не является владельцем бизнес-данных или обязательным посредником между всеми сервисами. Тестовый запрос `PaymentEvent` не подтверждает реальную оплату и не активирует подписку. В To-Be доменные события создают владельцы данных; поэтому Events MVP не показан отдельным бизнес-доменом на целевой диаграмме.

## Переход без простоя

1. **Фасад перед монолитом.** Сначала Gateway направляет существующий API в монолит. Совместимость URL, методов, статусов и JSON проверяется контрактными тестами; переход клиентам не требует обновления API.
2. **Strangler Fig для movies.** При `GRADUAL_MIGRATION=true` доля `MOVIES_MIGRATION_PERCENT=0` оставляет запросы к фильмам в монолите, `1–99` распределяет их между монолитом и Movies, `100` полностью переводит в Movies. Остальной бизнес-API остаётся в монолите. Долю увеличивают после сравнения ответов, ошибок и задержек. На этом этапе оба обработчика используют существующие общие таблицы, поэтому переключение чтения не теряет новые фильмы; записи также должны соблюдать один совместимый контракт и ограничения БД. При регрессии значение возвращают к `0` с применением конфигурации.
3. **Kubernetes, Helm, CI/CD и Kafka MVP.** За ближайшие два месяца разворачиваются монолит, Movies, Proxy и Events MVP; добавляются автоматическая сборка образов и API-тесты. Переключение трафика начинается после готовности нового окружения, старое сохраняется на период проверки и отката. В учебных заданиях проверяется локальный кластер, фактический промышленный переезд ими не подтверждается.
4. **Отделение данных Movies.** Сначала переключается единственный путь записи в Movies, включая обращения старого кода через его API. Затем выполняются фоновое копирование согласованного снимка в новую БД и доставка изменений через CDC с соответствующей позиции журнала. После устранения отставания один писатель дожидается завершения операций старой БД, выполняются финальная сверка и синхронизация sequence идентификаторов, затем переключается подключение. Новые запросы на запись кратко ожидают переключения в пределах HTTP-таймаута; ответ `201` возвращается только после фактического commit. Длительность переключения и сохранение контракта предварительно проверяются на стенде под нагрузкой. Процентное распределение записей между независимыми БД без синхронизации не допускается. После разделения БД простой возврат процента к нулю небезопасен: откат требует актуального старого хранилища и согласованного переноса владельца записи.
5. **Остальные домены.** Аналогичным образом выделяются Users, Engagement, Commerce, Playback и Recommendations с собственными БД и событиями. После удаления последних маршрутов в монолит и проверки отсутствия обращений он выводится из эксплуатации; временный доступ к общей БД закрывается.

Изменения схемы выполняются по expand/contract: сначала добавляются совместимые поля, старые удаляются после обновления всех потребителей. Для обновления приложений планируются readiness/startup/liveness-пробы, graceful shutdown, минимум два экземпляра критичных stateless-сервисов, RollingUpdate с `maxUnavailable: 0`, ресурсы для дополнительного экземпляра и распределение по узлам. HPA масштабирует приложения по нагрузке; для consumers учитывается lag и число партиций Kafka. Kubernetes сам по себе не делает одиночную PostgreSQL или Kafka отказоустойчивой: для промышленной среды нужны репликация по независимым узлам, устойчивые тома, резервные копии и проверка восстановления. Учебные одноузловые конфигурации этого не гарантируют.

Приёмку каждого этапа определяют API-тесты, доступность пользовательских сценариев, частота ошибок, задержки, сверка данных и lag событий. Метрики Gateway различают монолит и Movies; `correlation_id` связывает HTTP-запросы, логи и события. Это позволяет оценивать миграцию и принимать решение об откате по наблюдаемым результатам.

# Задание 2

### 1. Proxy
Команда КиноБездны уже выделила сервис метаданных о фильмах movies и вам необходимо реализовать бесшовный переход с применением паттерна Strangler Fig в части реализации прокси-сервиса (API Gateway), с помощью которого можно будет постепенно переключать траффик, используя фиче-флаг.


Реализуйте сервис на любом языке программирования в ./src/microservices/proxy.
Конфигурация для запуска сервиса через docker-compose уже добавлена
```yaml
  proxy-service:
    build:
      context: ./src/microservices/proxy
      dockerfile: Dockerfile
    container_name: cinemaabyss-proxy-service
    depends_on:
      - monolith
      - movies-service
      - events-service
    ports:
      - "8000:8000"
    environment:
      PORT: 8000
      MONOLITH_URL: http://monolith:8080
      #монолит
      MOVIES_SERVICE_URL: http://movies-service:8081 #сервис movies
      EVENTS_SERVICE_URL: http://events-service:8082 
      GRADUAL_MIGRATION: "true" # вкл/выкл простого фиче-флага
      MOVIES_MIGRATION_PERCENT: "50" # процент миграции
    networks:
      - cinemaabyss-network
```

- После реализации запустите postman тесты - они все должны быть зеленые (кроме events).
- Отправьте запросы к API Gateway:
   ```bash
   curl http://localhost:8000/api/movies
   ```
- Протестируйте постепенный переход, изменив переменную окружения MOVIES_MIGRATION_PERCENT в файле docker-compose.yml.


### 2. Kafka
 Вам как архитектуру нужно также проверить гипотезу насколько просто реализовать применение Kafka в данной архитектуре.

Для этого нужно сделать MVP сервис events, который будет при вызове API создавать и сам же читать сообщения в топике Kafka.

    - Разработайте сервис на любом языке программирования с consumer'ами и producer'ами.
    - Реализуйте простой API, при вызове которого будут создаваться события User/Payment/Movie и обрабатываться внутри сервиса с записью в лог
    - Добавьте в docker-compose новый сервис, kafka там уже есть

Необходимые тесты для проверки этого API вызываются при запуске npm run test:local из папки tests/postman 
Приложите скриншот тестов и скриншот состояния топиков Kafka из UI http://localhost:8090 

# Задание 3

Команда начала переезд в Kubernetes для лучшего масштабирования и повышения надежности. 
Вам, как архитектору осталось самое сложное:
 - реализовать CI/CD для сборки прокси сервиса
 - реализовать необходимые конфигурационные файлы для переключения трафика.


### CI/CD

 В папке .github/worflows доработайте деплой новых сервисов proxy и events в docker-build-push.yml , чтобы api-tests при сборке отрабатывали корректно при отправке коммита в ваш репозиторий.

Нужно доработать 
```yaml
on:
  push:
    branches: [ main ]
    paths:
      - 'src/**'
      - '.github/workflows/docker-build-push.yml'
  release:
    types: [published]
```
и добавить необходимые шаги в блок
```yaml
jobs:
  build-and-push:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
      - name: Checkout repository
        uses: actions/checkout@v3

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v2

      - name: Log in to the Container registry
        uses: docker/login-action@v2
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

```
Как только сборка отработает и в github registry появятся ваши образы, можно переходить к блоку настройки Kubernetes
Успешным результатом данного шага является "зеленая" сборка и "зеленые" тесты


### Proxy в Kubernetes

#### Шаг 1
Для деплоя в kubernetes необходимо залогиниться в docker registry Github'а.
1. Создайте Personal Access Token (PAT) https://github.com/settings/tokens . Создавайте class с правом read:packages
2. В src/kubernetes/*.yaml (event-service, monolith, movies-service и proxy-service)  отредактируйте путь до ваших образов 
```bash
 spec:
      containers:
      - name: events-service
        image: ghcr.io/ваш логин/имя репозитория/events-service:latest
```
3. Добавьте в секрет src/kubernetes/dockerconfigsecret.yaml в поле
```bash
 .dockerconfigjson: значение в base64 файла ~/.docker/config.json
```

4. Если в ~/.docker/config.json нет значения для аутентификации
```json
{
        "auths": {
                "ghcr.io": {
                       тут пусто
                }
        }
}
```
то выполните 

и добавьте

```json 
 "auth": "имя пользователя:токен в base64"
```

Чтобы получить значение в base64 можно выполнить команду
```bash
 echo -n ваш_логин:ваш_токен | base64
```

После заполнения config.json, также прогоните содержимое через base64

```bash
cat .docker/config.json | base64
```

и полученное значение добавляем в

```bash
 .dockerconfigjson: значение в base64 файла ~/.docker/config.json
```

#### Шаг 2

  Доработайте src/kubernetes/event-service.yaml и src/kubernetes/proxy-service.yaml

  - Необходимо создать Deployment и Service 
  - Доработайте ingress.yaml, чтобы можно было с помощью тестов проверить создание событий
  - Выполните дальшейшие шаги для поднятия кластера:

  1. Создайте namespace:
  ```bash
  kubectl apply -f src/kubernetes/namespace.yaml
  ```
  2. Создайте секреты и переменные
  ```bash
  kubectl apply -f src/kubernetes/configmap.yaml
  kubectl apply -f src/kubernetes/secret.yaml
  kubectl apply -f src/kubernetes/dockerconfigsecret.yaml
  kubectl apply -f src/kubernetes/postgres-init-configmap.yaml
  ```

  3. Разверните базу данных:
  ```bash
  kubectl apply -f src/kubernetes/postgres.yaml
  ```

  На этом этапе если вызвать команду
  ```bash
  kubectl -n cinemaabyss get pod
  ```
  Вы увидите

  NAME         READY   STATUS    
  postgres-0   1/1     Running   

  4. Разверните Kafka:
  ```bash
  kubectl apply -f src/kubernetes/kafka/kafka.yaml
  ```

  Проверьте, теперь должно быть запущено 3 пода, если что-то не так, то посмотрите логи
  ```bash
  kubectl -n cinemaabyss logs имя_пода (например - kafka-0)
  ```

  5. Разверните монолит:
  ```bash
  kubectl apply -f src/kubernetes/monolith.yaml
  ```
  6. Разверните микросервисы:
  ```bash
  kubectl apply -f src/kubernetes/movies-service.yaml
  kubectl apply -f src/kubernetes/events-service.yaml
  ```
  7. Разверните прокси-сервис:
  ```bash
  kubectl apply -f src/kubernetes/proxy-service.yaml
  ```

  После запуска и поднятия подов вывод команды 
  ```bash
  kubectl -n cinemaabyss get pod
  ```

  Будет наподобие такого

```bash
  NAME                              READY   STATUS    

  events-service-7587c6dfd5-6whzx   1/1     Running  

  kafka-0                           1/1     Running   

  monolith-8476598495-wmtmw         1/1     Running  

  movies-service-6d5697c584-4qfqs   1/1     Running  

  postgres-0                        1/1     Running  

  proxy-service-577d6c549b-6qfcv    1/1     Running  

  zookeeper-0                       1/1     Running 
```

  8. Добавим ingress

  - добавьте аддон
  ```bash
  minikube addons enable ingress
  ```
  ```bash
  kubectl apply -f src/kubernetes/ingress.yaml
  ```
  9. Добавьте в /etc/hosts
  127.0.0.1 cinemaabyss.example.com

  10. Вызовите
  ```bash
  minikube tunnel
  ```
  11. Вызовите https://cinemaabyss.example.com/api/movies
  Вы должны увидеть вывод списка фильмов
  Можно поэкспериментировать со значением   MOVIES_MIGRATION_PERCENT в src/kubernetes/configmap.yaml и убедится, что вызовы movies уходят полностью в новый сервис

  12. Запустите тесты из папки tests/postman
  ```bash
   npm run test:kubernetes
  ```
  Часть тестов с health-чек упадет, но создание событий отработает.
  Откройте логи event-service и сделайте скриншот обработки событий

#### Шаг 3
Добавьте сюда скриншота вывода при вызове https://cinemaabyss.example.com/api/movies и  скриншот вывода event-service после вызова тестов.


# Задание 4
Для простоты дальнейшего обновления и развертывания вам как архитектуру необходимо так же реализовать helm-чарты для прокси-сервиса и проверить работу 

Для этого:
1. Перейдите в директорию helm и отредактируйте файл values.yaml

```yaml
# Proxy service configuration
proxyService:
  enabled: true
  image:
    repository: ghcr.io/db-exp/cinemaabysstest/proxy-service
    tag: latest
    pullPolicy: Always
  replicas: 1
  resources:
    limits:
      cpu: 300m
      memory: 256Mi
    requests:
      cpu: 100m
      memory: 128Mi
  service:
    port: 80
    targetPort: 8000
    type: ClusterIP
```

- Вместо ghcr.io/db-exp/cinemaabysstest/proxy-service напишите свой путь до образа для всех сервисов
- для imagePullSecret проставьте свое значение (скопируйте из конфигурации kubernetes)
  ```yaml
  imagePullSecrets:
      dockerconfigjson: ewoJImF1dGhzIjogewoJCSJnaGNyLmlvIjogewoJCQkiYXV0aCI6ICJaR0l0Wlhod09tZG9jRjl2UTJocVZIa3dhMWhKVDIxWmFVZHJOV2hRUW10aFVXbFZSbTVaTjJRMFNYUjRZMWM9IgoJCX0KCX0sCgkiY3JlZHNTdG9yZSI6ICJkZXNrdG9wIiwKCSJjdXJyZW50Q29udGV4dCI6ICJkZXNrdG9wLWxpbnV4IiwKCSJwbHVnaW5zIjogewoJCSIteC1jbGktaGludHMiOiB7CgkJCSJlbmFibGVkIjogInRydWUiCgkJfQoJfSwKCSJmZWF0dXJlcyI6IHsKCQkiaG9va3MiOiAidHJ1ZSIKCX0KfQ==
  ```

2. В папке ./templates/services заполните шаблоны для proxy-service.yaml и events-service.yaml (опирайтесь на свою kubernetes конфигурацию - смысл helm'а сделать шаблоны для быстрого обновления и установки)

```yaml
template:
    metadata:
      labels:
        app: proxy-service
    spec:
      containers:
       Тут ваша конфигурация
```

3. Проверьте установку
Сначала удалим установку руками

```bash
kubectl delete all --all -n cinemaabyss
kubectl delete  namespace cinemaabyss
```
Запустите 
```bash
helm install cinemaabyss .\src\kubernetes\helm --namespace cinemaabyss --create-namespace
```
Если в процессе будет ошибка
```code
[2025-04-08 21:43:38,780] ERROR Fatal error during KafkaServer startup. Prepare to shutdown (kafka.server.KafkaServer)
kafka.common.InconsistentClusterIdException: The Cluster ID OkOjGPrdRimp8nkFohYkCw doesn't match stored clusterId Some(sbkcoiSiQV2h_mQpwy05zQ) in meta.properties. The broker is trying to join the wrong cluster. Configured zookeeper.connect may be wrong.
```

Проверьте развертывание:
```bash
kubectl get pods -n cinemaabyss
minikube tunnel
```

Потом вызовите 
https://cinemaabyss.example.com/api/movies
и приложите скриншот развертывания helm и вывода https://cinemaabyss.example.com/api/movies

## Удаляем все

```bash
kubectl delete all --all -n cinemaabyss
kubectl delete namespace cinemaabyss
```
