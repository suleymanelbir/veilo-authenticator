# Veilo Enterprise — Play store graphics

- `icon-512.png` (512×512): the launcher mark from `app/src/main/res/drawable/ic_launcher_foreground.xml`
  on `ic_launcher_background` (#14324A), so the store icon matches the installed icon.
- `feature-graphic.png` (1024×500): gold accent (#F2B84B) to tell it apart from the public app's green.

Re-render from the HTML sources with headless Chromium (snap Chromium can only read under $HOME):

    chromium --headless --hide-scrollbars --force-device-scale-factor=1 --window-size=512,512 --screenshot=icon-512.png icon-512.html
    chromium --headless --hide-scrollbars --force-device-scale-factor=1 --window-size=1024,500 --screenshot=feature-graphic.png feature-graphic.html
