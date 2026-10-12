# План следующей сессии (с 2026-10-04)

Рабочая папка — `idea-dotnet-support`. Перед работой прочитать `CLAUDE.md`, `CSHARP_PSI_MIGRATION.md` (карта шагов,
шаг 7, чек-лист) и в `../csharp-psi`: `CLAUDE.md`, `docs/PLAN.md`, `docs/GRAMMAR.md` (разделы PSI, Reparseable bodies,
Language version, Doc comments), `docs/TESTING.md`.

## Где мы

- **Плагин**, `origin/master` = `6dbe5d2`, 0.1.44:
  - 0.1.41–0.1.42 — .NET Framework: MSBuild из Visual Studio, запуск legacy-проектов;
  - 0.1.43 — фасад `CSharpSyntaxModel` (19 потребителей объявлений, снимки `CSharpSyntaxSnapshotTest`), модель компиляции
    `msbuild/CompilationModel` (символы `#if`, `LangVersion` по TFM, global usings, `Compile`), сценарий замеров
    `tools/ui-robot/baseline.py`;
  - 0.1.44 — цвета до готовности сервера и тёплый кэш токенов, без мигания, `AlreadyDisposedException` при закрытии,
    Go to Symbol / Class с типом-контейнером, порядок completion (ключевые слова выше типов, неимпортированное ниже),
    серый `{ get; set; }` не перекрывается списком имён.
- **csharp-psi**, `origin/main` = `0b60b06`: лексер с `#if`, весь `LanguageParser`, ленивые тела, версии языка до C# 7.3,
  doc-комментарии, типизированный PSI из `Syntax.xml` (`roslyndump gen-psi`). Гейты на 0: деревья (Roslyn src, runtime,
  aspnetcore, playground, netfx C# 7.3), лексер, doc-комментарии, аксессоры PSI, фазз; гейт мутаций — 1 известный мутант;
  parsing tests — 0, кроме `ParseBigExpression`.

## 0. Хвосты (в начале сессии)

1. Карта шагов в `CSHARP_PSI_MIGRATION.md`: отметить шаги 3–6 сделанными (даты, ссылки на `CHANGELOG.md` csharp-psi),
   чек-лист в конце — синхронно с `docs/PLAN.md` csharp-psi.
2. Шаг 0: пользователь подтверждает цифры «Исходных замеров» вживую. Таблица снята до 0.1.44 (первые цвета были 5,5 с,
   стали 1–1,5 с): перемерить `baseline.py --runs 3` на свободной машине и записать обе строки — «путь ROSLYN до 0.1.44» и
   «после». Закрыть пункт в обоих планах.
3. Живая проверка фасада, не пройденная в 0.1.43 (sandbox `runIde`, `debug-playground`): Structure с Autoscroll from
   Source и breadcrumbs (`Console/Types.cs`), folding, Ctrl+Alt+B и Ctrl+U (`Console/Editor/GoToBase.cs`), ▶ тестов
   (`Tests/PricingTests.cs`), IL Viewer (`Console/Editor/IlViewer.cs`).
4. Мелкое: в completion для `str` в поле TYPE шаблона `prop` есть `abstract` и дважды `struct` — разобрать.
5. Ветка `origin/main` (3 коммита пользователя от 2026-09-22/23, без общей истории с `master`) — спросить пользователя.
6. Удалить остатки: `../idea-dotnet-support-wt/` (каталоги `baseline`, `bugs`, патчи), заблокированные каталоги в
   `../csharp-psi/.claude/worktrees/`.

## 1. Шаг 7 — подмена парсера (основная работа, 1–2 сессии)

Цель: файл `.cs` в плагине разбирается парсером csharp-psi; всё, что читает объявления, получает их из PSI через
`CSharpSyntaxModel` при `CSharpFeatures.SYNTAX_TREE = NATIVE`; ROSLYN-путь и эвристики остаются рабочими.

1. Перенос кода: `../csharp-psi/csharp-psi-core/src/{main,test}` → `csharp-psi-core/` плагина, пакеты
   `io.github.dotnetsupport.csharp.*` без переименования; `testData/`, гейты и `tools/roslyndump` — решить, переезжают ли
   сразу или csharp-psi остаётся источником, а плагин берёт код синхронизацией (записать решение в план). Заголовки MIT
   и `NOTICE.md` переезжают вместе с кодом.
2. Регистрация: `CSharpParserDefinition` и файловый тип — на язык `C#` хоста (`CSharpLanguage` плагина), без своих
   иконок, цветов, настроек и текстов. Лексер подсветки — в ключах `CSharpSyntaxHighlighter` хоста. Эвристический лексер
   плагина остаётся для ROSLYN-пути, пока фичи не переведены.
3. NATIVE-реализация `CSharpSyntaxModel` поверх сгенерированного PSI (`CSharpTypeDeclaration`, `CSharpMethodDeclaration`,
   …; диапазоны и имена — как в фасаде). Выбор реализации — по `CSharpFeatures`.
4. Снимки: `CSharpSyntaxSnapshotTest` гоняется на обеих реализациях; каждое отличие NATIVE разбирается. Семь известных
   ошибок эвристик (описание шага 7 в плане) должны исчезнуть — это ожидаемые отличия, записать их.
5. Символы и версия языка на файл из `CompilationModel`: `CSharpPreprocessorSymbols.KEY`, `CSharpLanguageLevel.KEY`;
   по `CompilationModel.CHANGED` — перепарсить открытые файлы. Тест на multi-target (`#if NET48` меняет дерево при смене
   TFM в тулбаре).
6. Гейты: в плагине `test buildPlugin`; корпусные гейты csharp-psi — там, где живёт код (п. 1). Бенчмарки — перемерить
   на свободной машине (пороги разбора 101–108 мс/МБ, под нагрузкой было 110–123).
7. Робот: `baseline.py` на NATIVE против ROSLYN по тем же метрикам (первая подсветка, память IDE, время до Structure).
   Это вход в веху S.

## 2. Записать в план миграции

- Таблица пунктов страницы настроек Roslyn (Автодополнение, Навигация, Code Lens, Подсказки, Редактирование): что даёт
  синтаксис (шаги 7–9), индексы (8), разрешение имён (11b), типы (11c); что уже есть в плагине (`AssemblyIndex`,
  `ImportCompletion`, `TestDiscovery`, `CSharpEditing`, `CSharpTemplatesAndDocs`).
- Декомпиляция и Source Link без сервера: `ICSharpCode.Decompiler` и чтение portable PDB уже в `helpers/dotnethelper`
  (IL Viewer); переход по символу — после 11b, по имени типа — раньше.
- Go to Symbol по `AssemblyIndex` — можно сразу, без PSI.

## 3. Отложенное («допиши потом»)

- `.claude/agents/csharp-psi-porter.md` и `csharp-psi-reviewer.md`: стандартная преамбула брифа (правила CLAUDE.md, не
  коммитить, свой worktree, главная проверка, без иконок / цветов / настроек / текстов), отчёт ≤ 30 строк.
- `tools/merge-agent.sh`: diff worktree с новыми файлами (`git add -A -N`, `git diff --binary HEAD -- . ':!build'`) →
  `git apply --3way` в основное дерево → удаление worktree и ветки.
- Раздел в `CLAUDE.md` про работу с агентами (worktree на агента, слияние, проверка оркестратором, ревью).
- csharp-psi: структурные узлы директив; находка ревью — 2^depth у вложенных namespace с переносом членов
  (`ParseNamespaceBody`), мемоизировать.
