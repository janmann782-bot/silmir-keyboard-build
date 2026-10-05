#!/usr/bin/env python3
from pathlib import Path
import re

# 1) Bundle the Sil'mir word list into Config's personal dictionary.
p = Path("srcs/juloo.keyboard2/Config.java")
s = p.read_text(encoding="utf-8")

needle_import = "import java.util.Map;\n"
imports = (
    "import java.io.BufferedReader;\n"
    "import java.io.IOException;\n"
    "import java.io.InputStreamReader;\n"
    "import java.nio.charset.StandardCharsets;\n"
)

if imports not in s:
    if needle_import not in s:
        raise SystemExit("Config.java import anchor not found")
    s = s.replace(needle_import, needle_import + imports, 1)

old = "personal_dictionary = new PersonalDictionary(PersonalDictionaryPreference.get(_prefs));"
new = '''List<PersonalDictionary.Entry> silmir_entries =
        PersonalDictionaryPreference.get(_prefs);
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
        res.getAssets().open("silmir_words.txt"), StandardCharsets.UTF_8)))
    {
      String line;
      while ((line = reader.readLine()) != null)
      {
        line = line.trim();
        if (!line.isEmpty())
          silmir_entries.add(new PersonalDictionary.Entry(line, ""));
      }
    }
    catch (IOException ignored) {}
    personal_dictionary = new PersonalDictionary(silmir_entries);'''

if new not in s:
    if old not in s:
        raise SystemExit(
            "Personal-dictionary anchor not found. "
            "The upstream branch may have changed."
        )
    s = s.replace(old, new, 1)

p.write_text(s, encoding="utf-8")

# 2) IMPORTANT FOR SIL'MIR:
# The upstream personal-dictionary PR intentionally folds diacritics so café
# can match cafe. In Sil'mir, ś/ź/æ/ø/ə are real distinct letters, not accents.
# Make personal-dictionary matching case-insensitive ONLY, preserving the alphabet.
pd = Path("srcs/juloo.keyboard2/PersonalDictionary.java")
t = pd.read_text(encoding="utf-8")

pattern = re.compile(
    r"static String normalized\(String w\)\s*\{"
    r".*?"
    r"return b\.toString\(\);\s*\}",
    re.S,
)
replacement = '''static String normalized(String w)
  {
    return w.toLowerCase();
  }'''

t2, n = pattern.subn(replacement, t, count=1)
if n != 1:
    raise SystemExit(
        "PersonalDictionary.normalized() anchor not found. "
        "The upstream branch may have changed."
    )

pd.write_text(t2, encoding="utf-8")
print("Injected Sil'mir vocabulary and preserved æ ə ø ś ź as distinct letters.")
