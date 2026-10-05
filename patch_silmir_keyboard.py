#!/usr/bin/env python3
from pathlib import Path
import re, shutil

ROOT = Path('.')
SRC = ROOT / 'srcs/juloo.keyboard2'

# 1. Bundle Sil'mir words into personal suggestions.
p = SRC / 'Config.java'
s = p.read_text(encoding='utf-8')
needle_import = 'import java.util.Map;\n'
imports = (
    'import java.io.BufferedReader;\n'
    'import java.io.IOException;\n'
    'import java.io.InputStreamReader;\n'
    'import java.nio.charset.StandardCharsets;\n'
)
if imports not in s:
    if needle_import not in s:
        raise SystemExit('Config.java import anchor not found')
    s = s.replace(needle_import, needle_import + imports, 1)
old = 'personal_dictionary = new PersonalDictionary(PersonalDictionaryPreference.get(_prefs));'
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
        raise SystemExit('Personal dictionary anchor not found')
    s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# 2. Sil'mir letters are distinct letters, not diacritic variants.
p = SRC / 'PersonalDictionary.java'
s = p.read_text(encoding='utf-8')
pattern = re.compile(
    r'static String normalized\(String w\)\s*\{.*?return b\.toString\(\);\s*\}',
    re.S,
)
replacement = '''static String normalized(String w)
  {
    return w.toLowerCase();
  }'''
s2, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit('PersonalDictionary.normalized() anchor not found')
p.write_text(s2, encoding='utf-8')

# 3. Add a dedicated translator event/key.
p = SRC / 'KeyValue.java'
s = p.read_text(encoding='utf-8')
if 'SILMIR_TRANSLATE' not in s:
    anchor = '    CHANGE_DICTIONARY,\n'
    if anchor not in s:
        raise SystemExit('KeyValue Event anchor not found')
    s = s.replace(anchor, anchor + '    SILMIR_TRANSLATE,\n', 1)

    anchor = '      case "change_dictionary": return eventKey(0xE01D, Event.CHANGE_DICTIONARY, 0);\n'
    if anchor not in s:
        raise SystemExit('KeyValue special-key anchor not found')
    s = s.replace(
        anchor,
        anchor + '      case "silmir_translate": return eventKey("⇄", Event.SILMIR_TRANSLATE, FLAG_SMALLER_FONT);\n',
        1,
    )
p.write_text(s, encoding='utf-8')

# 4. Hook the translator into the IME.
p = SRC / 'Keyboard2.java'
s = p.read_text(encoding='utf-8')
if 'import java.io.InputStream;' not in s:
    anchor = 'import java.util.AbstractMap.SimpleEntry;\n'
    if anchor not in s:
        raise SystemExit('Keyboard2 import anchor not found')
    s = s.replace(
        anchor,
        'import java.io.InputStream;\nimport java.io.IOException;\n' + anchor,
        1,
    )

if 'private SilmirTranslator _silmirTranslator' not in s:
    anchor = '  private Handler _handler;\n'
    if anchor not in s:
        raise SystemExit('Keyboard2 field anchor not found')
    s = s.replace(anchor, anchor + '  private SilmirTranslator _silmirTranslator = null;\n', 1)

methods = r'''
  private SilmirTranslator get_silmir_translator()
  {
    if (_silmirTranslator != null)
      return _silmirTranslator;
    try (InputStream lex = getAssets().open("silmir_translation_lexicon.tsv");
         InputStream forms = getAssets().open("silmir_ru_forms_compact.tsv"))
    {
      _silmirTranslator = new SilmirTranslator(lex, forms);
      return _silmirTranslator;
    }
    catch (IOException e)
    {
      Log.e("UnexpectedKeyboard", "Cannot load Sil'mir translator", e);
      return null;
    }
  }

  /** Translate selected text. If nothing is selected, translate the current
      line before the cursor. Direction is detected automatically. */
  private void translate_silmir_text()
  {
    InputConnection conn = getCurrentInputConnection();
    SilmirTranslator tr = get_silmir_translator();
    if (conn == null || tr == null)
      return;

    CharSequence selected = conn.getSelectedText(0);
    boolean hasSelection = selected != null && selected.length() > 0;
    String source;
    int removeBefore = 0;

    if (hasSelection)
      source = selected.toString();
    else
    {
      CharSequence before = conn.getTextBeforeCursor(1024, 0);
      if (before == null || before.length() == 0)
        return;
      String b = before.toString();
      int start = b.lastIndexOf('\n') + 1;
      while (start < b.length() && Character.isWhitespace(b.charAt(start)))
        start++;
      source = b.substring(start);
      removeBefore = source.length();
    }

    if (source.trim().isEmpty())
      return;

    String translated = tr.translate(source.trim());
    conn.beginBatchEdit();
    if (hasSelection)
      conn.commitText(translated, 1);
    else
    {
      conn.deleteSurroundingText(removeBefore, 0);
      conn.commitText(translated, 1);
    }
    conn.endBatchEdit();
  }
'''
if 'private void translate_silmir_text()' not in s:
    anchor = '  public void launch_dictionaries_activity()\n'
    if anchor not in s:
        raise SystemExit('Keyboard2 method anchor not found')
    s = s.replace(anchor, methods + '\n' + anchor, 1)

if 'case SILMIR_TRANSLATE:' not in s:
    anchor = '''        case CHANGE_DICTIONARY:
          new DictionarySwitcher(Keyboard2.this, _dictionaries, this).choose();
          break;
'''
    if anchor not in s:
        raise SystemExit('Keyboard2 event anchor not found')
    s = s.replace(
        anchor,
        anchor + '''
        case SILMIR_TRANSLATE:
          translate_silmir_text();
          break;
''',
        1,
    )
p.write_text(s, encoding='utf-8')

# 5. Add the Java translator engine to the app source tree.
source = Path('../silmir-kit/SilmirTranslator.java')
if not source.exists():
    raise SystemExit('SilmirTranslator.java not found in build kit')
shutil.copy2(source, SRC / 'SilmirTranslator.java')

print("Sil'mir suggestions + offline translator patched successfully")
