# personal-rss-reserver

This is a self hosted re-server for a podcast website I listen to that doesn't provide an RSS feed. Currently WIP but functional and in use. 

Flow details:

- Every day, at a few times in the morning, scrape the website for any new episodes
- If some episode is found, add a queue item to fetch metadata
- Once metadata is fetched, schedule a download.
- Once the download is done, it's either directly added to the self hosted RSS feed, or, if the file was a video, an FFMPEG conversion is scheduled to extract audio then add to the RSS feed.

Technical details:

- Uses a filesystem based queue system of my own design, with the goals of
  1. Easy persistance and backup
  2. Permanent data retention, even failures are retained so they can be examined and retried in the future
  3. Easy item prioritization
  4. Explicit timeouts that auto-fail and auto-cancel long running items
  5. A custom semaphore that can be composed with and/or from "x per day" limits, "x at once limits", "x items per day but only high priority ones"
- Electric clojure based admin dashboard (WIP)
- Nixos based build system for repeatable builds and ease of development
- SOPS for secret management
- Custom light weight configuration language, somewhat similar to Aero
