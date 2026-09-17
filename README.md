# Speedo

A GPS speedometer for Android: shows how fast you are travelling in a train or car, in km/h or mph.

- Speed is read from the GPS chip's Doppler measurement (`Location.getSpeed()`), which stays accurate at train speeds.
- Tap the dial or the toggle to switch between **km/h** and **mph**; the choice is remembered.
- Trip figures: maximum, moving average, distance, moving time. "Reset trip" clears them.
- Layout adapts to a foldable: stacked on the cover screen, side by side when unfolded or in landscape.
- GPS runs only while the app is on screen (no background service), and the screen stays awake while it is.
- No network permission: nothing is stored or sent anywhere.

In a train, GPS reception is best next to a window. The status line shows accuracy and the number of satellites in use.

## Building

Builds run on GitHub Actions (`.github/workflows/android.yml`). Every push produces an APK artifact; pushing a
`vX.Y.Z` tag attaches a signed AAB + APK to a GitHub Release (needs the four signing secrets described in the workflow).
