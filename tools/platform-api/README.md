# Анализ платформенных API LSP / DAP

К сборке плагина не относится. Отсюда получены `docs/platform-lsp-dap.html` и эталон диагностики `src/main/resources/platformProbe/expected.json`.

| Файл | Что делает |
|---|---|
| `api.json` | Разобранный дамп IntelliJ IDEA 2026.1.4 (IU-261.26222.65): классы и члены API обоих модулей с пометками `@Experimental` / `@Deprecated` / …, типы lsp4j, которыми пользуется реализация, дескрипторы модулей |
| `extract.py` | `javap`-дампы → `api.json` |
| `gen_expected.py` | `api.json` → `expected.json` (член = `имя/число параметров`) |
| `gen_html.py` | `api.json` + написанные руками таблицы возможностей и недостач → HTML |

Запуск (системный Python, выводить в UTF-8): `PYTHONIOENCODING=utf-8 python gen_expected.py`, то же для `gen_html.py`.

## Обновить под другую версию IDE
`extract.py` ждёт рядом с собой (в репозиторий не положено — 4 МБ, воспроизводится):
- распакованные jar-ы: `lsp/` ← `lib/intellij.platform.lsp.jar`, `lsp.impl/` ← `lib/intellij.platform.lsp.impl.jar`, `dap/` ← `lib/intellij.platform.dap.jar`;
- `lsp.api.javap.txt`, `dap.api.javap.txt` — вывод `javap -v -protected -cp <папка> <классы API>` (LSP: пакет `com.intellij.platform.lsp.api` и ниже;
  DAP: `com.intellij.platform.dap` и ниже, без `impl`). `javap` — из JDK в `~/.jdks/jdk-25*` (у JBR его нет).

Потом поправить путь `IDE_LIB` и `source` в скриптах, перегенерировать оба файла и прогнать `PlatformApiProbeTest`.
Python-скрипты с обратными слэшами не передавать через heredoc — только файлами.
