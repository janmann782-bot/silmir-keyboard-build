Sil'mir + Unexpected Keyboard custom build kit
================================================

Extracted headwords: 11550

What this does
--------------
Stable Unexpected Keyboard currently has no bulk personal-dictionary importer.

This kit builds from the open personal-dictionary development branch and
bundles all 11550 Sil'mir headwords into the suggestion system.

Files
-----
assets/silmir_words.txt
    One Sil'mir word per line.

Silmir_Unexpected_Keyboard.xml
    The custom Sil'mir QWERTY layout.

inject_silmir.py
    Adds the bundled word list to the branch's PersonalDictionary loader.

.github/workflows/build.yml
    GitHub Actions workflow that builds a DEBUG APK and uploads it as an artifact.

Important
---------
The resulting debug APK uses the project's .debug application ID, so it can
normally be installed alongside the stable Unexpected Keyboard.

No usage frequencies were present in the source dictionary, so none were invented.


Sil'mir alphabet handling
-------------------------
The upstream personal-dictionary PR normally folds diacritics for matching.
That is wrong for Sil'mir because ś, ź, æ, ø and ə are real letters. The patch
therefore changes PERSONAL-dictionary matching to case-insensitive exact
Unicode matching, so e.g. s and ś, z and ź remain distinct.
