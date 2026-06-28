# Shipeasy · Java Entity Guide (example)

A tiny, **self-contained** Spring Boot 3 (Java 17+) web app that renders a single
page — a "big guide document" with one styled card per Shipeasy entity:

1. **Feature flag** — a boolean on/off switch with targeting + percentage rollout
2. **Dynamic config** — a typed JSON blob you change without deploying
3. **A/B experiment** — splits users into variants and measures a metric
4. **Kill switch** — an operational off-switch shipped alongside flags
5. **Event / metric** — fire-and-forget events that power metrics + dashboards
6. **I18n label** — server-managed copy you translate + publish (Java i18n is a follow-up)
7. **Error reporting (`see()`)** — structured reports of the product *consequence*

Each card shows the entity key, its (placeholder) value, and the **exact Java SDK
call** that produces it.

## ⚠ SDK not wired yet

This example does **not** depend on `ai.shipeasy:shipeasy`. It has zero external
dependencies beyond `spring-boot-starter-web` + `spring-boot-starter-thymeleaf`,
makes **no network calls**, and runs fully offline.

Because of that, **every value on the page is a hardcoded placeholder** added to
the Spring `Model` in
[`GuideController`](src/main/java/ai/shipeasy/examples/guide/GuideController.java).
For each entity the controller also carries a `// TODO: once ai.shipeasy:shipeasy
is installed` block showing the real SDK call, and that same call is rendered as a
code block on the page.

## Run it

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080>.

(`mvnw` is the Maven wrapper — no local Maven install required. If you have Maven
on your `PATH` you can also run `mvn spring-boot:run`.)

## Next step: make it live

1. Add the SDK to [`pom.xml`](pom.xml):

   ```xml
   <dependency>
       <groupId>ai.shipeasy</groupId>
       <artifactId>shipeasy</artifactId>
       <version>LATEST</version>
   </dependency>
   ```

2. Construct one client and reuse it, e.g.:

   ```java
   ShipeasyClient c = Shipeasy.init(System.getenv("SHIPEASY_SERVER_KEY"));
   ```

3. Replace each `// TODO` in
   [`GuideController`](src/main/java/ai/shipeasy/examples/guide/GuideController.java)
   with the live call shown right next to it (and remove the corresponding
   placeholder string).

Docs: <https://docs.shipeasy.ai>
