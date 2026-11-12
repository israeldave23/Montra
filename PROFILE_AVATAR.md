# Profile photo — what it is, how it works, what changed

## Purpose

The avatar is the one personal thing on the dashboard: a face (or initials) that says *this is your
ledger*. Until now it could only ever be initials, so the app looked anonymous no matter who was using
it.

The profile photo lets you pick an image from the gallery once, and then shows it — as a circle — on the
home screen, on the profile screen, and anywhere the avatar appears.

## What it does

* **Pick from the gallery** — tap the avatar on the profile screen, or the "Add photo" / "Change photo"
  action under it. The system document picker opens filtered to images.
* **Shown as a circle** — the chosen image replaces the initials circle on the home header (48dp) and on
  the profile screen (92dp).
* **Remove** — "Remove photo" asks once, then deletes the stored image and the initials come back.
* **Initials stay the fallback** — with no photo, the avatar is the same initials circle as before, so the
  feature is additive and never blocks the rest of the screen.

## How the image is handled

A gallery photo is often 4000×3000 or larger; decoding it whole for a 48dp circle would allocate tens of
megabytes. The pipeline is:

1. **Read the bounds only** (`inJustDecodeBounds`) to learn the size without allocating pixels.
2. **Subsample at decode time** — `ImageScaling.sampleSizeFor(width, height, 512)` picks the largest
   power-of-two factor that still leaves at least 512 px on the long edge.
3. **Exact resize** to 512 px on the long edge, preserving the aspect ratio and never upscaling.
4. **Apply the EXIF orientation** so camera photos are not sideways after the circle crop.
5. **Re-encode as JPEG (quality 88)** into app storage: `filesDir/profile_avatar.jpg`.
6. **Decode at display time** with a sample size suited to the view, so the home screen never holds more
   than a small bitmap.

The photo is **copied into app storage** rather than remembering the gallery URI. A persisted URI grant
would keep a handle on a file the user may move or delete, and would break silently when they did.

No storage permission is required: `ACTION_OPEN_DOCUMENT` is a document-picker flow that grants
per-document access.

## Data and privacy

* The image lives in the app's private `filesDir` — no other app can read it.
* Nothing is uploaded anywhere; the app has no network code for this.
* `allowBackup` is `false`, so the photo does not leave the device through Android backup.
* Removing the photo deletes the file; there is no second copy retained.

## Files

**New**
- `ProfileAvatar.kt` — picker intent, import/downscale/EXIF/encode, load, circular binding, remove
- `domain/ImageScaling.kt` — pure `sampleSizeFor`, `fitsWithin`, `scaledDimension`
- `src/test/.../domain/ImageScalingTest.kt`

**Changed**
- `MainActivity.kt` — avatar container click, and `refreshProfileHeader()` binds the photo over the initials
- `ProfileActivity.kt` — pick/import (off the main thread), remove with confirmation, photo actions in `render()`
- `res/layout/activity_main.xml` — 48dp avatar container with an initials view and an image view
- `res/layout/activity_profile.xml` — 92dp avatar container plus "Add/Change photo" and "Remove photo"
- `res/values/strings.xml` — profile photo copy

## Verification

No JDK/Gradle in this environment, so it is checked statically:

- every `R.*` reference resolves, including the new `profile_photo_*` strings and the avatar views
- `getString` argument counts match format specifiers
- layout ids used by `MainActivity` and `ProfileActivity` exist in their layouts
- brace/parenthesis balance clean in every touched file
- `tools/check_ui_tokens.py` passes (only token-scaled sizes used)
- `ImageScalingTest` covers subsampling boundaries (`512 → 1`, `1024 → 2`, `4000 → 4`, `4096 → 8`), the
  "never over-shrink" rule (`700` and `1000` stay at 1, `1025` becomes 2), invalid dimensions, fit
  checks, and aspect-ratio-preserving resize for portrait, landscape and extreme images

## Still out of scope

- **No camera capture** — gallery only, so no camera permission is needed.
- **No cropping UI** — the image is centre-cropped into a circle.
- **No photo sync** — the app is local-only by design.
- **Account avatars** — this change is the *profile* avatar; per-account icons remain initials-based.
