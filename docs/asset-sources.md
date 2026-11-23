# Asset Sources

## `hero_portrait.webp`

- Location: `app/src/main/res/drawable-nodpi/hero_portrait.webp`
- Source: [Facee AI portrait](https://facee.ru/look/zhenschina-derzhit-pered-soboy-buket-roz-raznyh-ottenkov-rozovogo-ee-glaza-siyayut-ot-radosti-pozadi-legkie-pastelnye-to-se4cl/)
- Purpose: Figma prototype placeholder.
- Release status: The redistribution and model-release rights have not been verified. Replace this file with an owned or explicitly licensed production asset before any public or commercial release.

- Subscription page: subscription_*.png are original assets exported from user-provided Figma JKsmuWA408do7TUxEGQN6D, nodes 1160:1490 and 1160:1515. Original SVG exports were rasterized for Android; usage follows the project design ownership.

- Template VIP label: Figma JKsmuWA408do7TUxEGQN6D, node 1206:1853 (within 1206:1828); native Compose text/shape styling, no new third-party bitmap assets.

- Template-entry subscription overlay: Figma JKsmuWA408do7TUxEGQN6D node 1206:1954. Close glyph exported from 1206:2077 to docs/figma-assets/template_subscription_close.svg and rasterized losslessly for Android; existing subscription crown/feature/check/arrow exports reused. Actual product/Play prices replace design sample prices and savings.
- Template subscription crown: exact PNG export including effect bounds from Figma 1206:2085, replacing the older crown only in the template-entry overlay.

- Zorv bottom navigation: Home/Short/Video/Image vector paths exported from Figma AB 两面, node 1224:3380 (footer 1331:160), used as Android vector drawables ic_nav_*. These are supplied design assets; release rights follow the source design.

- Settings gear: Google Material Icons settings (https://fonts.gstatic.com/s/i/materialicons/settings/v6/24px.svg), Apache-2.0, converted exact SVG path into ic_settings.xml.

- Me module icons and detail back/play/video icons: supplied Figma AB 两面 nodes 1337:125, 1337:695, 1337:1105; downloaded exact SVG paths and converted to ic_me_* / ic_history_video Android vectors. Release rights follow the source design.

- Home Use Template waveform: supplied Figma AB 两面 node 1403:139 (home_use_wave.png). Portraits/videos are supplied by the authenticated featured-template API; no design sample media is used as live content.

- Short-drama Home Watch Full Drama icon: supplied Figma AB 两面 node 1403:40, exact exported SVG converted to ic_home_watch_drama.xml. Drama covers and previews are live API resources; sample title, SPICY and synopsis are not embedded.

- Full drama player icons (Back, Speed, Audio, Pause, Episodes): exported SVG paths from supplied Figma AB 两面 node 1226:4910, converted mechanically to ic_drama_*.xml. All titles/tags/descriptions and video content use API data rather than design sample text/media.

- Debug-only drama playback fixture: `app/src/debug/res/raw/drama_test_video.mp4` is original procedural artwork generated locally with AVFoundation (H.264, 180×320, 12 fps, 2 seconds; moving colored geometry), solely for Android instrumentation playback verification; no third-party media, excluded from release/main resources.

- Short-drama comments: exact SVG export from Figma `MfqtmQVwTbHwxNJEc3IIjl`, node `1226:5454`, mechanically converted to `ic_drama_comments.xml` with original path/stroke. Catalog/history posters and recommendation copy use live API content; no design sample titles or promotions are embedded.

- Completion rating star: original Figma `MfqtmQVwTbHwxNJEc3IIjl` node `1229:5688` SVG path, converted to `ic_drama_rating_star.xml`. Close control reuses the existing matching exported template-subscription close glyph. Rating inputs are local UI only; Send remains disabled until a real submission contract exists.

- Short drama transport play glyph: Figma file `MfqtmQVwTbHwxNJEc3IIjl`, paused frame `1226:5106`, vector `1226:5013`. Exported SVG path converted without geometry changes into `ic_drama_play.xml`; used at original 14.6667 × 18.6667dp with Figma inset. Existing `ic_drama_pause.xml` matches the supplied pause glyph and is rendered at 16 × 18.6667dp.

- Home CTA arrow: Figma `MfqtmQVwTbHwxNJEc3IIjl`, Home short-drama node `1403:47`; exported original SVG path converted without geometry changes to `ic_home_cta_chevron.xml` (5 × 8.75dp). Shared 24dp cyan circular treatment follows `1403:46` for Use Template and Watch Full Drama.

- Episode selector close glyph: Figma `MfqtmQVwTbHwxNJEc3IIjl`, node `1226:5486`, original SVG stroke path preserved in `ic_drama_episode_close.xml`. Existing Home wave and diamond assets are reused in episode tiles.
- Orbitron Bold for episode selector typography: official Google Fonts CSS API `https://fonts.googleapis.com/css?family=Orbitron:400,600,700`, static TTF from `fonts.gstatic.com/s/orbitron/v35/yMJMMIlzdpvBhQQL_SC3X9yhF25-T1ny_CmBoWg2.ttf`. Copyright 2018 The Orbitron Project Authors; SIL OFL 1.1 license from `https://raw.githubusercontent.com/google/fonts/main/ofl/orbitron/OFL.txt` retained in docs/licenses and packaged assets/licenses.
- Muted audio glyph derives the existing Figma speaker outline from `ic_drama_audio.xml`, removes sound-wave paths and adds the user-requested diagonal slash. Normal speaker geometry is unchanged.

- Paid episode gate: Figma `MfqtmQVwTbHwxNJEc3IIjl` node `1226:5340`; padlock exported from `1226:5374` into `ic_drama_paid_lock.xml` with original geometry. Runtime card/background uses the real drama poster rather than mockup-specific episode scenes; existing close/diamond exports reused.

- Short discovery homepage Figma `MfqtmQVwTbHwxNJEc3IIjl` node `1226:4528`: info glyph `1226:4601` and ranking flame `1338:3140` exported as SVG and converted with original paths to `ic_short_banner_info.xml` / `ic_short_rank_fire.xml`. Banner illustration is supplied entirely by the runtime backend poster; client-built gift/ribbon/bow geometry was removed per user correction; crown reuses the existing subscription asset. No mockup benefit text, promotional entitlement or placeholder drama cover is hardcoded.
