SIL'MIR KEYBOARD v4 ULTRA
===================

Внутри:
- QWERTY-раскладка Sil'mir
- a↑æ, e↑ə, o↑ø, s↑ś, z↑ź
- возвращены обычные символы: ~ ! @ # $ % ^ & * ( ) ` - _ = + { } [ ] | \\ < > . , ? / : ; " '
- 11 550 слов для подсказок
- отдельный переводческий словарь из 11 550 корней
- отдельный индекс русских словоформ
- полный набор правил грамматики в assets/silmir_rules.json
- офлайн переводчик RU ↔ Sil'mir

КНОПКА ПЕРЕВОДА
На клавише P: свайп вниз-влево (SW), значок ⇄.

Если текст выделен, ⇄ заменяет выделение переводом.
Если ничего не выделено, ⇄ переводит текущую строку перед курсором.
Направление определяется автоматически: кириллица = RU→SIL, Sil'mir/латиница = SIL→RU.

СБОРКА
Загрузи содержимое этой папки в корень GitHub-репозитория.
Затем: Actions → Build Silmir Keyboard v3 → Run workflow.
После зеленой сборки скачай Artifact: Silmir-Keyboard-v3-debug-apk.

РАСКЛАДКА
После установки APK:
Settings → Add an alternate layout → Custom layout
и вставь Silmir_Unexpected_Keyboard_v3.xml.

ПРИМЕЧАНИЕ
Переводчик правиловый и офлайн. Sil'mir-морфология разбирается по правилам, а не
по списку всех готовых форм. Русский синтаксический разбор в v1 эвристический,
поэтому длинные/неоднозначные предложения иногда потребуют ручной правки.


НЕИЗВЕСТНЫЕ РУССКИЕ СЛОВА
Если слова нет в переводческом словаре, оно не остается кириллицей.
RU→SIL автоматически делает фонетическую запись в алфавите Sil'mir.
Примеры: Жуковский → źukovskij, чайник → tśajnik, Москва → moskva.
Это fallback для имен, новых терминов, опечаток и свежих заимствований.


V4 ULTRA SEMANTIC ENGINE
------------------------
Canonical Sil'mir concepts: 10,350
Dictionary synonym roots collapsed: 1,200
Russian semantic families: 317
Russian semantic alias rows: 12,913
Existing Russian morphology rows: 49,658

Russian synonyms map to one canonical Sil'mir concept. Generated aliases are rejected if they collide with another explicit Sil'mir meaning. The engine also accepts common Russian Latin transliteration and inflected/stem variants, and matches multi-word aliases before word-by-word translation.
