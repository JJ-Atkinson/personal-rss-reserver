# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Personal RSS Reserver - A self-hosted RSS feed generator for podcast websites that don't provide RSS feeds. Currently scrapes Lotus Eaters content, downloads episodes, and serves them via a custom RSS feed with S3-backed storage.

## Development Commands

### Starting Development Environment

```bash
# Start REPL with dev dependencies (using Nix flake)
nix develop

# Inside Nix shell, start REPL
bin/launchpad

# In REPL, start the system
(user/start)
(user/restart)  ; reload and restart
(user/stop)
```

### Formatting

```bash
# Format all Clojure files
bin/format
```

### Building

```bash
# Build uberjar (via Nix)
nix build

# Production build creates:
# - Compiled uberjar with all dependencies
# - Packaged Playwright CLI for browser automation
# - Wrapped launcher script with environment setup
```

## Architecture

### System Initialization (Integrant)

The application uses Integrant for component lifecycle management. Configuration lives in `resources/config/config.edn` and uses a custom configuration language (similar to Aero) with `#n/ref` reader tags for environment/secret resolution.

**Configuration resolution flow:**
1. `personal-rss-feed.config/resolve-config!` reads config files
2. Custom readers:
   - `#n/ref` - Nested config references (keyword or vector path)
   - `#n/reader-file-str` - Read and trim file contents as string
   - `#n/env` - Read environment variable
3. Integrant initializes components in dependency order

**Key Integrant components:**
- `:personal-rss-feed.feed.db/conn` - Datalevin database
- `:personal-rss-feed.queue/queue` - Custom filesystem queue system
- `:personal-rss-feed.ingest.lotus-eaters/lotus-eaters-ingest` - Scraping orchestrator
- `:personal-rss-feed.server.main/server` - Jetty web server

### Queue System Architecture

**Custom filesystem-backed queue** (`dev.freeformsoftware.simple-queue.core`):
- Each task stored as EDN file in `persistence-dir/queued-items/{uuid}.edn`
- Queue state persisted to `persistence-dir/{queue-name}.edn`
- Features:
  - Priority queue with configurable rate limiting
  - Composable rate limits: `comp-or_lockout_rate-limit`, `comp-and_lockout_rate-limit`
  - Automatic retry with failure count tracking
  - Timeout watchdog that auto-fails long-running tasks
  - Permanent failure retention for debugging
  
**Rate limiting patterns:**
- `rate-limit-number-active` - Max concurrent tasks
- "x per day" limits (custom semaphore composition)
- High-priority task allowances

### Ingest Pipeline Flow

**Daily scraping cycle** (`personal-rss-feed.ingest.lotus-eaters`):
1. **Scrape** - Playwright-based web scraping (`lotus-eaters/fetch-metadata`)
2. **Parse** - RSS feed parsing (`lotus-eaters/rss-feed-parser`)
3. **Download** - Queue item for file download (`lotus-eaters/download-file`)
4. **Convert** - If video, extract audio with FFMPEG (`lotus-eaters/extract-audio`)
5. **Upload** - Store to S3 with UUID-based naming (`feed/s3`)
6. **Serve** - Add to self-hosted RSS feed (`feed/feed-host`)

**Queue-driven workflow:**
- Metadata fetch → queues download task
- Download completion → queues audio extraction (if video)
- Extraction completion → updates RSS feed

### Web Server Architecture

**Ring/Jetty stack** (`personal-rss-feed.server.main`):
- `clj-simple-router` for routing
- Two route namespaces merged:
  - `feed.routes` - Public RSS feed serving
  - `admin.routes` - Admin panel with JWT auth
- Middleware stack (`wrap-admin`):
  - `wrap-logged-in` - JWT authentication (Buddy)
  - `wrap-content-type`
  - `wrap-defaults` with CSRF disabled

**Auth flow:**
- Login form → JWT token generation (24hr expiry)
- Protected routes check JWT claims
- Safe prefixes (favicon, public resources) bypass auth

### Database Schema (Datalevin)

**Episode attributes:**
- `:episode/url` (unique) - Source URL
- `:episode/id` - Incremental string ID (e.g., "aaaa", "aaab")
- `:episode/uuid` - S3 object naming
- `:episode/audio-content-length` / `video-content-length` - Presence indicates successful download

**Podcast attributes:**
- `:podcast/feed-uri` (unique)
- `:podcast/id` - Incremental ID
- `:podcast/generated-icon-relative-uri` - S3 path

**ID generation:**
- Singleton entity `:singleton/current-id` tracks next ID
- `inc-str` function: "aaa" → "aab" → ... → "aba"

### Playwright Integration

**Nix-managed browser automation:**
- `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` - Uses Nix-provided Chromium
- `CHROME_LOCATION` points to Nix store Chromium binary
- `PLAYWRIGHT_CLI_LOCATION` - Packaged CLI directory
- Wally library wrapper (`io.github.pfeodrippe/wally`) for Clojure integration

**Playwright CLI packaging quirk:**
- Dev: `playwright-cli-dir--bin/` with symlinks to Nix store
- Prod: Separate derivation in `flake.nix` packages `cli.js` and `node` binary

### S3 Storage Pattern

**Cognitect AWS API usage:**
- Special JVM flags required: `--add-opens java.base/java.nio=ALL-UNNAMED`
- Issue with default `host` header breaking signature (see deps.edn comment)
- UUID-based object naming for "passwordless" CDN access
- Public S3 prefix configured via `#n/ref :s3/public-s3-prefix`

## Project Structure

```
src/main/
  personal_rss_feed/
    ingest/           # Web scraping and content ingestion
      lotus_eaters/   # Site-specific scrapers
    feed/             # RSS feed generation and serving
    admin/            # Admin panel with auth
    server/           # Web server setup
    playwright/       # Browser automation utilities
    queue.clj         # Queue system integrant component
    config.clj        # Custom config language
  dev/freeformsoftware/simple_queue/  # Custom queue implementation

resources/config/config.edn  # Integrant configuration
```

## Important Patterns

### Configuration References

Use `#n/ref` for nested lookups in config.edn:
```clojure
:some-key #n/ref :path/to/value
:some-key #n/ref [:nested :path]
```

### Queue Operations

```clojure
;; Submit queue item
(simple-queue/qsubmit! system queue-name item-data priority)

;; Complete task
(simple-queue/qcomplete! system queue-item-id result-data)

;; Handle error
(simple-queue/qerror! system queue-item-id error-data)

;; Resubmit failed item
(simple-queue/qresubmit-item! system queue-name queue-item-id)
```

### Integrant Lifecycle

Always use `ig/ref` for component dependencies:
```clojure
{:my-component/foo
 {:dependency #ig/ref :other-component/bar}}
```

## Development Notes

- **REPL workflow**: Use `user/start`, `user/stop`, `user/restart` for component lifecycle
- **Nix flake**: All dependencies managed via flake.nix, including Chromium, FFMPEG, AWS CLI
- **Portal**: Available for REPL data visualization (`user/start-portal!`)
- **No ClojureScript**: Recently removed Electric Clojure; pure Clojure backend only
- **Secrets**: Use SOPS (not in repo); config.edn has placeholder values

## Code Style

**DO NOT reformat existing Clojure files unless explicitly asked.** This project uses zprint in strict mode (`bin/format`), and unnecessary formatting changes create noisy diffs. When editing files:
- Use the `Edit` tool to make surgical changes to specific forms
- Preserve existing indentation and whitespace exactly as-is
- Only reformat if the user explicitly requests formatting changes
