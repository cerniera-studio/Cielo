# Cielo

The weather for the Galaxy Z Fold 8 Ultra, rewritten from Brolly (the October web app) as a native
Android app under Bellezza Necessaria. Ferguson Consultancy Designs.

## What it shows

- **The glance:** the temperature in Italiana, then the one thing worth knowing about the next
  twelve hours ("Rain likely from 16:00", "Dry for the next twelve hours").
- **The day:** one continuous temperature curve. Slide a finger along it and the screen travels to
  that hour; the line yields under the finger. Rain is a bead per hour, as full as its chance.
  The axis is lit between sunrise and sunset.
- **The week:** seven days on one scale. Tap a day and the curve flows into that day's hours.
- **Now:** wind, sun and feels-like always; rain, UV, air quality and visibility only on days
  they matter.

## The light

The screen has one light, and the sky sets every property of it: it sits where the sun is between
sunrise and sunset, grows with the sun's height, dims and spreads under cloud, cools in rain, and
is a faint ember at night.

## The fold

Unfolding, the page re-forms in two columns that come out of the crease, nearest first; folding,
it comes in from the cover's hinge edge. The day's line draws through and the sun seats at now:
the one climax. A fast fold (read from the hinge sensor) plays a shorter entrance with less
ornament. A rotation is not a fold and plays nothing.

## Gestures

| Gesture | Does |
|---|---|
| Slide on the curve | Travel through the hours |
| Tap a day | That day's curve; tap again or *Back to today* to return |
| Swipe sideways | The next place; the page stretches and gathers like a membrane |
| Pull down | Refresh: the hairline at the top fills, a click when release will refresh |
| Tap the place name | Places: search, use my location, remove |

## Building

GitHub Actions builds a debug APK on every push (Actions → Build APK → Cielo-apk) and runs the
launch test on an emulator, with screenshots on the `screens` branch. One fixed debug key, so each
build installs over the last.

Weather data by [Open-Meteo.com](https://open-meteo.com/), CC BY 4.0. Fonts: Jost and Italiana
(SIL Open Font License, in `app/src/main/assets/fonts`).

Brolly, the web app, now lives on the `gh-pages` branch, still served at the same address.
