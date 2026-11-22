# Proxy Service

Strangler Fig proxy на Spring Boot 3.5.16 и Java 21. Сервис не использует БД.

```bash
mvn test package
java -jar target/proxy-service.jar
```

Для сборки и запуска необходим JDK 21. Dockerfile выполняет Maven-сборку и запускает JAR в JRE 21 от непривилегированного пользователя.

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `PORT` | `8000` | HTTP-порт Proxy |
| `MONOLITH_URL` | `http://localhost:8080` | Адрес монолита |
| `MOVIES_SERVICE_URL` | `http://localhost:8081` | Адрес Movies |
| `EVENTS_SERVICE_URL` | `http://localhost:8082` | Адрес Events |
| `GRADUAL_MIGRATION` | `true` | Включает процентную маршрутизацию Movies |
| `MOVIES_MIGRATION_PERCENT` | `50` | Доля запросов к Movies, от `0` до `100` |
| `PROXY_CONNECT_TIMEOUT` | `2s` | Таймаут установки соединения с backend |
| `PROXY_REQUEST_TIMEOUT` | `10s` | Таймаут ответа backend |

При `GRADUAL_MIGRATION=true` решение принимается независимо для каждого запроса к `/api/movies` и его дочерним путям. `0` направляет их в монолит, `100` — в Movies. Промежуточное значение задаёт вероятность, поэтому короткая выборка не обязана точно совпадать с процентом. При `GRADUAL_MIGRATION=false` весь этот трафик идёт в Movies независимо от процента. Изменение переменных применяется при пересоздании контейнера.

`/api/movies/health` всегда проверяет Movies. `/api/events` и его дочерние пути направляются в Events. Остальные пути идут в монолит. Собственный `GET /health` возвращает `Strangler Fig Proxy is healthy` и показывает работоспособность Proxy без проверки backend.

Proxy сохраняет метод, необработанный query string, байты тела, HTTP-статус и end-to-end заголовки. Перенаправления возвращаются клиенту. Автоматического переключения на другой backend после ошибки и прикладных повторов записи нет: иначе POST мог бы создать дубликат. Hop-by-hop заголовки, включая перечисленные в `Connection`, удаляются. Ошибка соединения возвращает `502`, таймаут — `504`. Выбранный backend виден в заголовке `X-Cinemaabyss-Upstream` и в логах; заголовок не влияет на клиентский API.

```bash
curl -i http://localhost:8000/api/movies
curl http://localhost:8000/api/events/health
```

Тесты используют локальные HTTP-заглушки: проверяют сохранение тела и query string, маршруты и граничные значения флага, статусы и заголовки, ошибки, таймауты и отсутствие перенаправления записи в другой сервис.
