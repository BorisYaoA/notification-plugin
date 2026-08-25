# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The Jenkins Notification Plugin (`com.tikalk.hudson.plugins:notification`). It sends HTTP/TCP/UDP
notifications about job phase/status changes (queued, started, completed, finalized) to external
endpoints, as JSON or XML. Endpoints are configured per-job (`HudsonNotificationProperty`) or
triggered manually from a pipeline via the `notifyEndpoints` step (`NotifyStep`).

## Build & test commands

This is a standard Jenkins plugin built with Maven, inheriting build behavior from the
`org.jenkins-ci.plugins:plugin` parent POM.

```bash
# Full build (compile, test, package into .hpi)
mvn -s settings.xml clean package

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=ProtocolTest

# Run a single test method
mvn test -Dtest=ProtocolTest#testHttpPostWithRedirects

# Format code (Spotless, uses palantir-java-format via the parent POM)
mvn spotless:apply

# Check formatting without modifying files
mvn spotless:check

# Run a local Jenkins instance with the plugin installed, for manual testing
mvn hpi:run
```

Notes:
- `spotless.check.skip` and `ban-commons-lang-2.skip` are explicit properties in `pom.xml` (both
  `false`) — the build enforces formatting and bans `commons-lang` (v2); use `commons-lang3` or
  JDK equivalents instead.
- CI runs via Jenkins Infra's `buildPlugin` pipeline step (see `Jenkinsfile`) against JDK 21 on
  Linux and JDK 17 on Windows. `circle.yml` is legacy/unused (targets `oraclejdk7`).

## Architecture

### Notification flow

1. **`JobListener`** (a `RunListener<Run>` `@Extension`) hooks Jenkins build lifecycle events —
   `onStarted`, `onCompleted`, `onFinalized` — and dispatches to the corresponding `Phase.handle(...)`.
2. **`Phase`** (enum: `QUEUED`, `STARTED`, `COMPLETED`, `FINALIZED`, `NONE`) is the central dispatcher.
   `Phase.handle(...)`:
   - Reads the job's `HudsonNotificationProperty` to get the configured `Endpoint` list (returns
     immediately if none configured).
   - For each `Endpoint`, decides whether to fire based on the endpoint's `event` filter
     (`all` / `failed` / `failedAndFirstSuccess` / `manual` / a specific phase name) and branch
     filter (regex matched against `BRANCH_NAME` env var).
   - Builds a `JobState` snapshot of the build (SCM info, test results, changed files, culprits,
     parameters, log tail, artifacts) via `buildJobState(...)`.
   - Serializes it (`Format.serialize`) and sends it (`Protocol.send`), retrying up to
     `Endpoint.getRetries()` times on failure.
3. **`NotifyStep`** is the `notifyEndpoints` pipeline step — an alternate manual entry point into
   the same `Phase.handle(...)` logic (via `Phase.NONE`, `manual=true`), letting a Jenkinsfile fire
   a notification for an arbitrary phase/notes/loglines outside the normal lifecycle hooks.

### Key model/config classes

- **`Endpoint`**: one configured notification target — protocol, format, URL (or credential
  reference via `UrlInfo`/`UrlType`), event filter, branch filter, timeout, retries, log lines,
  build notes. Has a deprecated legacy constructor (`url` field) kept for backward XML
  deserialization compatibility (`readResolve()` upgrades old configs into `UrlInfo`).
- **`UrlInfo`** / **`UrlType`**: a target URL is either `PUBLIC` (literal URL, may contain env var
  expansions) or `SECRET` (a Jenkins Credentials ID resolved via `Utils.getSecretUrl`, using
  `StringCredentials`).
- **`Protocol`** (enum: `HTTP`, `TCP`, `UDP`): each variant implements its own `send(...)`. `HTTP`
  handles proxying (`http_proxy` env var or Jenkins' configured proxy), basic auth (from URL
  userinfo), and manually follows `307` redirects (streaming mode prevents
  `HttpURLConnection`'s built-in redirect handling from working).
- **`Format`** (enum: `JSON`, `XML`): serializes `JobState` via Gson (snake_case field naming) or
  XStream respectively.
- **`model/` package** (`JobState`, `BuildState`, `ScmState`, `TestState`): the plain data objects
  that make up the wire payload sent to endpoints.
- **`HudsonNotificationPropertyDescriptor`**: Stapler/UI glue — form validation
  (`doCheckPublicUrl`/`doCheckSecretUrl`), credential dropdown population, and JSON→`Endpoint`
  conversion from job config form submissions.

### UI resources

Jelly views live under `src/main/resources/com/tikal/hudson/plugins/notification/...` (job config
form) and `src/main/resources/lib/notification/` (reusable form-layout tag library:
`blockWrapper`, `cellWrapper`, `rowWrapper`, etc.). Help text is in `help-*.html` files alongside
the Jelly views and in `src/main/webapp/`.
