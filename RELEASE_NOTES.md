## What's new

### Replay
- Total redesign: cinematic, Apple Music Replay style stories with per-card themes and smooth transitions between cards.
- New fluid shader backgrounds on the story cards, with a gradient fallback.
- Redesigned intro story with an atmospheric edge-to-edge layout.
- Redesigned minutes story with a purple atmosphere and editorial stats.
- New habits story, plus richer stats across songs, minutes and the recap.
- New share poster that matches the story exactly, with fixes for spacing, overlap and share capture.
- Browse your statistics by past month and year.
- Fading backsound for the stories: it starts without waiting for the full download, loads sooner, and is cached and cleaned up automatically.
- Listening done on a remote speaker now counts toward your stats.
- Replay header logo now lines up with the Zenith wordmark.

### Home
- New Speed Dial: a grid built from your most played songs, pinned to the top of Home.
- Clean iOS style layout with labels under the covers, round artist photos, a glass badge and page dots.
- A shuffle card with a four-dot, fluid searching animation closes each page, and the dots idle gently while the app is in the foreground.
- Tiles that open a playlist, album or artist show a small chevron.
- The local Speed Dial picks a random size between 19 and 26 songs and keeps its own title so Quick picks stays visible.
- The pinned shelf no longer shows on Home; on-device playlists are pinned in Library instead.
- Shelf rows keep stable keys for smoother scrolling.
- Pull-to-refresh now blocks touches on the page while it runs.
- The sign-in banner is now a quiet, neutral card.

### New tab
- Explore is now New, merging new releases, explore shelves and charts in one place.
- Global charts added, and cards that already appear on Home are hidden.
- New Latest songs list, built from new singles and shown as a Recents list.
- Skeleton placeholders while chart shelves load.
- Faster loading: Explore requests overlap, single lookups are capped and charts are prefetched.

### Search
- Genre cards moved from Explore to the Search tab.
- Frosted glass header so content scrolls behind it, using the app backdrop blur, with rounded bottom corners.
- Fluid glass lift effect on the standalone Search tab, and a gentler bounce when the nav pill animates in from Search.

### Library
- Customize the Library layout from a new Home/Library segmented control.
- Choose whether Artists show as a grid or a sideways row, right from the shelf header.
- New Apple Music style stack icon for the Library tab, with a beamed double note cutout.
- Pinned Spotify playlists now show in the Library pinned grid.
- Tracks you add manually to the download folder now appear in Library downloads.
- Local music and download folder pickers now behave the same way.
- Source rows now show the full lossless output warning.

### Spotify playlists
- Edit imported Spotify playlists: add or remove songs, change the cover and pin them to Home.

### Album pages
- Full-bleed album cover header, tinted with the cover colour down through the hero foot.
- Apple Music style layout: genre and year line, inline MORE on the description and cleaner track rows.
- Footer with release date, song count and runtime, plus the full release date and copyright line from iTunes.
- The release action row is narrower, so Play is shorter and the side buttons clear the screen edge.
- Writers, producers and vocals credits in Song Info.
- Album top bar actions collapse into a single ellipsis button that slides into place, with a bouncing close button.
- Cover flight now returns to the original card, including after visiting an artist page and when the sleeve scrolls out and back in.
- Fixed a zoom jump when entering a page.

### Mix and artist pages
- Full-bleed Made for You Mix page tinted with its mix colour, with no text overlay on the hero.
- Hero photos now zoom on overscroll instead of being dragged down.
- Larger star icon on the artist page action row.
- Top bar avatar shrinks smoothly when the actions pill gains an action, and the profile photo fills the glass circle.

### Player
- Smoother, straighter cover flight between the mini player and the player, with a gentle ease-in at the start of the drag.
- Cover now centers early and the panel takes its colour from the cover while docking.
- Non-full-bleed cover is slightly larger and stays at its resting spot while docking.
- Cover and title row sit lower to match the Apple Music layout, and the now playing slot stays centered when full-bleed artwork overhangs.
- Removed the drag handle above the artwork and restored the thinner handle.
- Option to hide the lyric line under the title and artist; the title drops and the blur extends when it is hidden.
- Larger like and more icons with a thinner star stroke and no circle background.
- Thinner volume slider with more breathing room around it.
- Landscape player: full-bleed cover with a right-edge fade into the blur, a restored cover transition and fluid shader, and the clip frame held on skip.
- Fluid cover background shares one clock, so it stays continuous across transitions and resumes from the last frame after a pause.
- Music-only playback and square video covers are on by default, and square covers turn on automatically with music-only playback.
- Per-track spatial audio toggle with a smooth 300 ms crossfade.
- Loop mix decks can now read a part-cached incoming track instead of always falling back.
- Liked songs hero uses a static cover gradient instead of a shader for better performance.
- Mini player: press and kick animation on next, and a morph with press animation on play/pause.

### Queue and repeat
- New Apple Music style queue mode badge that fades in on the queue button.
- The queue glyph is masked behind the badge like Apple Music.
- The badge shows one mode at a time (repeat one > shuffle > repeat all).
- New Apple Music style repeat icons, with the repeat-one digit separated from the arrow head.

### Audio output
- Audio output now opens as a floating popup that morphs from where you touch.
- The volume slider is merged into the active output pill, with an animated speaker glyph.
- AirPlay icon redrawn with three rings and the triangle spacing fixed.
- Fluid press feedback on the audio output button.
- Smaller volume pill and text.

### Lyrics
- Lyrics offset is now saved per song instead of globally.
- Redesigned lyrics share card in Apple Music style, with the brand lockup reading Music.
- Share buttons and the lyrics pick bar now use the app capsule look and the unified Apple Music style share icon.
- Toggle to turn off the LiquidFlow lyric sparkle, and lyric glow strength restored.
- Fixed the offset sheet exit animation and reset the saved lyrics screen when the track changes.

### Liquid Glass
- New dedicated Liquid Glass settings page with per-effect sliders, including indicator controls.
- New Liquid Glass and Kyant glass bottom bar looks, with a deeper refraction through the selection pill.
- Continuous glass clarity slider with a gentler clear side, and tappable clarity labels as presets.
- The current glass settings are now the Zenith default, with separate values for light and dark theme.
- The selection pill lens refracts the tab bar rim and tab icons, with softer chromatic aberration and a dimmer rim and halo inside the lens.
- The tab bar swells fluidly while you drag the selection pill, and the flat pill no longer clips on release.
- Glass bars stay visible in the light theme and inactive navbar icons are more solid.
- The nav bar stays expanded on the Liquid Glass settings page, and the first tab tap expands the inline nav bar before switching tabs.

### Popups and dialogs
- macOS inspired popup motion with origin-aware open and close, applied to anchored menus and dialogs.
- Jelly pop-in and pop-out with rubber-band drag, a finger-following light and lean, and no more clipped sides while dragging.
- Touch glow and one frosted glass look shared by all alert dialogs and the audio output popup.
- Player drawers and popups now blur live instead of from a still image.
- Popup height changes smoothly when async rows finish loading.
- Fixed menu taps being swallowed.

### Performance
- Removed redundant blur and pill lens refraction from the search header.
- Canvas still is copied only when a new video frame arrives.
- Replay backsound starts sooner.

### Under the hood
- Removed dead code, unused imports, orphan strings and leftover upstream files.
- Dev builds are uploaded as zip files and stale dev draft releases are deleted automatically.

### Languages
- Indonesian and Korean translations completed for the new features.

### Fixes and performance
- Smoother list scrolling with less work per row.
- Many small fixes to glass, swipe and layout behaviour.
