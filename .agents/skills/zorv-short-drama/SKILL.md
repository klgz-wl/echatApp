---
name: zorv-short-drama
description: Use when implementing or verifying Zorv short-drama discovery, history, preview gates, episode playback and navigation in echatApp.
---

# Zorv short-drama integration

- Product baseline is the two v1.1 requirements in `/Users/kuailegeziwl/android/docs/short-drama`; Figma file `MfqtmQVwTbHwxNJEc3IIjl`. User authorization to implement supersedes the documents' historical document-only scope statements.
- Preserve app/core boundaries. Add authenticated list/history/ads adapters to core; fetch full catalog with is_home=true and is_vip=true, page/page_size and hot/latest sort. History uses last_episode_id, not fabricated seconds or recent timestamps.
- Short discovery has Banner, Continue Watching, Top 10, All. The rank destination initially shows four then up to ten. History from Me and discovery uses the same screen. Banner ads provide copy/action but do not provide six-slot three-type images/configuration: document that gap rather than invent six promotions.
- Pass drama and target episode through navigation. History selects its actual last episode. Explicit player back returns to Short. Subscription/Top Up returns to the same episode and revalidates rights; it never buys automatically.
- Reuse retained playback/unlock controllers. On natural completion advance to the next actual available episode in server order through the same permission path, never auto-unlock. Stop at the final episode; completion UI cannot claim a saved rating without a real endpoint. Video does not loop. Pause/hidden cancels renewal; resume refreshes the existing grant, not a second play.
- v1.1 preview policy supersedes prior no-preview full-play fallback: Home uses real preview grants only until explicit Watch action, no automatic play requests for browsing/preloading. Clamp restricted preview to ten seconds of media position and show rights-derived gates; no-preview keeps poster. Current API cannot prove arbitrary episode-to-preview association or provide missing synopsis; do not bypass this by loading locked full media.
- Respect can_play/access_reason and exact server coin prices. VIP-free and unlocked episodes do not charge again. Ordinary paid content does not invent a VIP gate; coins versus displayed diamonds remains a backend confirmation gap. Preserve durable purchase identities and price-change confirmation.
- User text is Android resources, theme tokens reused, icons from supplied assets. Tests cover authenticated contract decoding, pagination/retry/owner cancellation, four-to-ten expansion, history episode routing, ended/next gates, preview time limits and nonlooping, and subscription/Top Up return ownership. Run unit tests, assembleDebug and lintDebug; run connected UI tests when a compatible device exists.

- New visible tabs keep legacy internal IDs Home=0, Short=4, Video=5, Image=1; saved billing return routes must validate 0..5, not the old 0..3. Balance-origin purchases preserve their original route; Short episode returns remain single-use navigation requests so subscription/Top Up cannot reset manual episode selection.

- App minSdk is 24 with no core-library desugaring. Reuse the strict `core.payment.paymentTime` parser for ISO grant expiry; do not call java.time. Diamond history formatting preserves the source offset, normalizes fractional precision through the parser and leaves invalid raw values unchanged. Read Compose stringResource before callbacks/semantics rather than LocalContext.getString.

- User authorizes simulated comments: show clearly labeled demo seed comments, accept trimmed nonblank local comments (max 500 characters), keep new comments/draft within the current player/drama UI session and across closing the sheet, and make no network or persistent account writes. This overrides the former unavailable-comments placeholder only; rating submission remains separately unavailable. Test blank submit prevention, local append, reopen retention and absence of playback/unlock side effects.

- User confirms Home uses the existing authorized preview and a client-side limit driven by actual player currentPosition, not wall-clock elapsed time. Keep native MediaItem clipping at 10,000ms, pause on the progress boundary and finish at most once per player. Paused/buffering/background time never advances the limit; previews shorter than ten seconds finish naturally. No new trimmed/re-associated asset is required for this scope; arbitrary episode targeting remains separate.

## Home preview startup and adjacent preloading

- Home owns an account/attribution/gateway scoped window containing current plus immediately adjacent Feed cards (at most three). Prefetch only short-drama detail and preview resolvers, concurrently, reuse on entry, cancel and evict outside the window, on hide/background and on owner change; discard late results. Expiry covers both grant and resolved URL and is rechecked before media use.
- Use bundled Media3 DefaultPreloadManager with matching builder-created players to preload up to 3 seconds of samples in RAM, with an explicit 6MiB preload budget and bounded active buffer. No disk cache, JWT or signed media URLs in persistent storage; no decoder/player for neighbors, formal play/unlock/refresh or ACK caused by prefetch.
- Preloaded and nonpreloaded playback share actual-position 10s clipping, permission gating, natural-end single completion and foreground visibility. Permission changes invalidate cached metadata and media sources. Retain current-frame ownership while visible and release on leaving; preload sources are independently removed/released.
- Normal preview startup uses a neutral video-loading surface rather than flashing the drama poster. Poster is reserved for missing preview/empty episodes, explicit failure/expiry and completion. A 503 remains a real retryable error, not fabricated playback.
- Test warm detail/resolver reuse, bounded cancellation, expiry and owner changes, no paid/play/ACK side effects, native preloaded source handoff and current-only decoder ownership. Verify frontend loading/ready/failure/completion and device first-frame handoff; do not promise zero cold-network latency or claim backend media failures fixed.

## Confirmed Home end-of-preview gates

- After restricted Home preview ends, a VIP episode without an effective subscription shows the 1338:2585 fuchsia/purple Subscribe to unlock full episodes CTA on a blurred in-card background. This opens the same template-entry subscription presentation as generation.
- Once subscribed, a still-locked coin-priced episode shows 1338:2684 Watch Full Series, and this CTA goes directly to Top Up (not the player or an automatic unlock). Non-VIP coin-locked content also uses Top Up; no subscription is invented. Fully entitled/free episodes are not clipped or charged again; unconfigured prices remain unavailable.
- Home remembers completed card keys across temporary billing navigation so returning does not replay the preview or lose the same-card gate. Completed state resets on actual Feed refresh. Formal episode purchase still requires explicit confirmed pricing in the player.
- Gate styling is a single rounded gradient CTA over blurred card content, leaving app header/navigation usable. API24 blur gracefully uses dimming fallback; no background content taps can bypass the gate. Test VIP/non-VIP, subscriber/non-subscriber, paid/free/unlocked states and exact CTA destinations.
