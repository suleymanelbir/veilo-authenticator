# Veilo Enterprise — Play store graphics

- `icon-512.png` (512×512): the launcher mark from `app/src/main/res/drawable/ic_launcher_foreground.xml`
  on `ic_launcher_background` (#14324A), so the store icon matches the installed icon.
- `feature-graphic.png` (1024×500): gold accent (#F2B84B) to tell it apart from the public app's green.

Re-render from the HTML sources with headless Chromium (snap Chromium can only read under $HOME):

    chromium --headless --hide-scrollbars --force-device-scale-factor=1 --window-size=512,512 --screenshot=icon-512.png icon-512.html
    chromium --headless --hide-scrollbars --force-device-scale-factor=1 --window-size=1024,500 --screenshot=feature-graphic.png feature-graphic.html

## Screenshots

The app sets FLAG_SECURE, so the phone cannot screenshot it. `screenshots/` is rendered from the
real composables with sample data by `app/src/testEnterprise/.../store/StoreScreenshots.kt`
(Robolectric, phone 411×800 dp, 7" 600×960 dp, 10" 800×1280 dp):

    STORE_SHOTS_DIR=$HOME/store-shots ./gradlew :app:testEnterpriseDebugUnitTest --tests '*StoreScreenshots*'

The sign-in dialog is drawn in its own window; the published "giris-onayi" images put it over the
list with a 32 % dim (phone: scaled to 20 dp side margins, as on a device).
