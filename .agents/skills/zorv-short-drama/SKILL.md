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
- Respect can_play/access_reason and exact server coin prices. VIP-free and unlocked episodes do not charge again. Ordinary paid versus mandatory subscription and coins versus displayed diamonds remain product/backend confirmation gaps. Preserve durable purchase identities and price-change confirmation.
- User text is Android resources, theme tokens reused, icons from supplied assets. Tests cover authenticated contract decoding, pagination/retry/owner cancellation, four-to-ten expansion, history episode routing, ended/next gates, preview time limits and nonlooping, and subscription/Top Up return ownership. Run unit tests, assembleDebug and lintDebug; run connected UI tests when a compatible device exists.

- New visible tabs keep legacy internal IDs Home=0, Short=4, Video=5, Image=1; saved billing return routes must validate 0..5, not the old 0..3. Balance-origin purchases preserve their original route; Short episode returns remain single-use navigation requests so subscription/Top Up cannot reset manual episode selection.

- App minSdk is 24 with no core-library desugaring. Reuse the strict `core.payment.paymentTime` parser for ISO grant expiry; do not call java.time. Diamond history formatting preserves the source offset, normalizes fractional precision through the parser and leaves invalid raw values unchanged. Read Compose stringResource before callbacks/semantics rather than LocalContext.getString.

- User authorizes simulated comments: show clearly labeled demo seed comments, accept trimmed nonblank local comments (max 500 characters), keep new comments/draft within the current player/drama UI session and across closing the sheet, and make no network or persistent account writes. This overrides the former unavailable-comments placeholder only; rating submission remains separately unavailable. Test blank submit prevention, local append, reopen retention and absence of playback/unlock side effects.
