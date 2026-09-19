# Roadmap

Поддержка .NET в IDE на платформе IntelliJ **без LSP, Roslyn и отладчика**: всё держится на `dotnet` CLI,
файлах проекта и лексере. Отмечается по мере готовности.

## Готово
- [x] Панель Solution: `.sln`/`.slnx`, solution folders, solution items, проекты, Dependencies (пакеты, проекты, сборки, central package management)
- [x] C#: лексер, подсветка, комментирование, скобки, кавычки
- [x] Типы файлов (MSBuild, slnx, xaml, resx, config → XML) и иконки .NET-файлов

## Заход 1 — «можно писать и запускать код» (код готов, покрыт тестами; UI в живой IDE ещё не проверен)
### Сборка и запуск
- [x] Run configuration «.NET Project»: `dotnet run` / `watch` / `test`, аргументы, окружение, профили `launchSettings.json`
- [x] Создание конфигурации из контекста (проект в дереве, файл в редакторе), gutter ▶ у `Main`
- [x] Build / Rebuild / Clean / Restore для solution и проекта, вывод в Build tool window с разбором ошибок MSBuild
- [x] Кликабельные `file(line,col)` в консоли запуска
- [x] Автосоздание run configurations для запускаемых проектов solution (по одной на профиль `launchSettings.json`, как в Rider)
### Создание проектов и файлов
- [x] New Project (DirectoryProjectGenerator — GoLand, PyCharm, WebStorm...): шаблоны из `dotnet new list`, язык, framework, solution
- [x] Add → New Project в существующий solution (в т.ч. в solution folder)
- [x] New → C# Class / Interface / Record / Struct / Enum с вычисленным namespace (RootNamespace + путь, file-scoped по `.editorconfig`)
### Действия над solution
- [x] Add Existing Project, Remove from Solution
- [x] New Solution Folder (правка `.sln`/`.slnx`)
- [x] Add Project Reference (диалог с галочками)
- [x] Автовыбор панели Solution при первом открытии папки с solution

## Заход 2 — навигация и редактор без парсера
- [ ] Сканер объявлений поверх лексера (namespace / типы / члены по балансу скобок)
- [ ] Structure view, breadcrumbs, folding (блоки, `#region`, `using`, комментарии)
- [ ] Go to Class / Go to Symbol (индекс по файлам), переход между partial-частями, Related file (`.xaml` ↔ `.xaml.cs`, `.razor` ↔ `.razor.cs`, тест ↔ класс)
- [ ] Reformat Code через `dotnet format` (+ при сохранении)
- [ ] Ошибки последней сборки как аннотации в редакторе (ExternalAnnotator)
- [ ] Live templates (`ctor`, `prop`, `cw`, `foreach`, `svm`, `fact`...), Enter в `///` и `/* */`, заготовка `/// <summary>`
- [ ] TODO-индекс, WordsScanner (текстовый Find Usages), spellchecker, Color Settings page
- [ ] Неактивные ветки `#if` по `DefineConstants`
- [ ] Переименование файла вместе с типом

## Заход 3 — NuGet и тесты
- [ ] Транзитивные зависимости и разбивка по TFM из `obj/project.assets.json`
- [ ] Окно NuGet: поиск (API v3, источники из `nuget.config`), установка / обновление / удаление, CPM
- [ ] Устаревшие и уязвимые пакеты (`dotnet list package --outdated --vulnerable --format json`)
- [ ] csproj: completion имён и версий пакетов, inlay «доступна новая версия», quick-fix обновления
- [ ] Авто-`dotnet restore` при изменении csproj
- [ ] Тесты: `dotnet test --logger trx` → дерево результатов (SMTRunner), перезапуск упавших, gutter у `[Fact]`/`[Test]`/`[TestMethod]`

## Заход 4 — проект и окружение
- [ ] Страница настроек: путь к `dotnet`, выбор SDK, учёт `global.json`, уведомление об отсутствующем SDK
- [ ] New Project в IntelliJ IDEA (`GeneratorNewProjectWizard`)
- [ ] Item-шаблоны `dotnet new` в меню New (Razor component, контроллер, editorconfig, gitignore, nuget.config...)
- [ ] Project Properties (TargetFramework, OutputType, Nullable, LangVersion, RootNamespace)
- [ ] Честное содержимое проекта: `Compile Remove`, linked files, `DependentUpon`-вложение
- [ ] Переименование / перемещение проекта, unload / reload, solution filters (`.slnf`), drag-and-drop в дереве
- [ ] Publish, `dotnet tool restore`, user secrets, EF Core migrations

## Заход 5 — файлы проекта и конфигурации
- [ ] MSBuild: XSD, completion свойств и значений, навигация по `Import` / `ProjectReference` / `$(Property)`, битые пути
- [ ] JSON Schema для `appsettings.json`, `launchSettings.json`, `global.json`
- [ ] Редактор `.resx` (таблица, несколько культур)
- [ ] `.sln`: подсветка и сворачивание секций

## Вне рамок (нужна семантика языка)
Парсер и PSI, разрешение ссылок, типизация, инспекции, completion по типам, рефакторинги, собственный форматтер.
