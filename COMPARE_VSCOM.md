# Плагин, Rider и Visual Studio Community: сравнение функционала

Составлено 2026-09-30 по знанию продуктов (Visual Studio 2022 Community 17.x, Rider 2025.x) и по состоянию плагина из `ROADMAP.md`.
Не сверялось с живыми установками VS — перед решениями по конкретному пункту проверить в актуальной версии. Razor и Blazor не
рассматриваются (решение 2026-09-30, `ROADMAP.md` → «Вне рамок»). Дизайнеры и всё, что живёт только на .NET Framework под Windows, отмечены
отдельно: это не «языковые» возможности, но именно они составляют главную часть того, чего у Rider нет.

Обозначения: ✅ есть · △ частично · ❌ нет · (Ent) — только в Visual Studio Enterprise, в Community нет.

## 1. Что такое три продукта

| | Плагин C# Project Support | Rider | Visual Studio Community |
|---|---|---|---|
| Основа понимания C# | `roslyn-language-server` (тот же Roslyn, что в VS Code C# Dev Kit) + свои эвристики по токенам, без парсера | ReSharper-бэкенд (своя семантическая модель) + Roslyn-анализаторы | Roslyn целиком: IntelliSense, рефакторинги, анализаторы, Code Style |
| Платформы | Любая IDE JetBrains 2026.1+ на Windows / macOS / Linux (LSP-модуль нужен в IDE) | Windows / macOS / Linux | Только Windows (VS for Mac закрыт в 2024) |
| Цена | Бесплатно, open source | Бесплатно для некоммерческого использования, иначе подписка | Бесплатно для физлиц, open source, учёбы и компаний до 5 разработчиков |
| Языки | C# (F#, VB — только как файлы) | C#, F#, VB (просмотр), C++ (Unreal/CMake), TS/JS, SQL | C#, F#, VB.NET, C++ (native, C++/CLI), Python, Node.js, TS/JS |

## 2. Языковые возможности C#

| Возможность | Плагин | Rider | VS Community |
|---|---|---|---|
| Completion по типам, импорт `using` | ✅ (сервер + свой индекс сборок) | ✅ | ✅ |
| Ошибки компилятора в редакторе | ✅ (pull-диагностика, по solution — Project Errors) | ✅ | ✅ |
| Инспекции сверх компилятора | △ анализаторы решения через сервер | ✅ 2500+ инспекций ReSharper | △ анализаторы Roslyn, Code Style (`.editorconfig`), IDExxxx |
| Quick-fix / code actions | ✅ действия Roslyn, Fix All | ✅ | ✅ |
| Rename с обновлением ссылок | ✅ (сервер), файл вместе с типом | ✅ | ✅ |
| Extract Method / Local function, Introduce variable/constant/parameter, Inline | △ как code action без диалога и предпросмотра | ✅ с диалогами и предпросмотром | ✅ с inline-вводом имени и предпросмотром |
| Extract Interface / Base class | △ code action сервера по умолчанию: все public-члены в новый файл `I<Тип>.cs`, без выбора членов и имени | ✅ диалог | ✅ диалог |
| Change Signature, Move to namespace с вводом имени | ❌ (в LSP-варианте Roslyn нет) | ✅ | ✅ с диалогами |
| Find Usages с группировкой | △ references сервера, плоский список | ✅ | ✅ |
| Type / Call Hierarchy, Go to Base / Implementation | ✅ | ✅ | ✅ |
| Structure / Outline тел методов, structural search | ❌ (нет парсера) | ✅ | △ (Outline есть, structural search нет) |
| Форматирование | △ CSharpier / `dotnet format whitespace` | ✅ форматтер ReSharper | ✅ Roslyn formatter + Code Cleanup profiles |
| Генерация кода (конструктор, Equals, overrides, члены интерфейса) | ✅ (Generate сервера) | ✅ | ✅ |
| Live templates / snippets, postfix | ✅ | ✅ | ✅ snippets, postfix — нет |
| Code Lens (ссылки, тесты) | ✅ references | ✅ Code Vision (ссылки, авторы VCS, тесты) | ✅ CodeLens (ссылки, тесты, история Git) |
| Inlay hints | ✅ (сервер) | ✅ | ✅ |
| Semantic-раскраска | ✅ | ✅ | ✅ |
| Source generators, декомпиляция в навигации | △ MetadataAsSource сервера; ilspycmd в плане | ✅ встроенный декомпилятор, IL Viewer | △ декомпиляция (ILSpy внутри) по опции |
| C# Interactive / REPL | ❌ (в плане: `csharprepl`) | ❌ | ✅ окно C# Interactive |
| Code Metrics (maintainability, cyclomatic) | ❌ | ❌ (плагин) | ✅ Calculate Code Metrics |
| Class Designer (UML по коду) | ❌ | ❌ | ✅ (компонент установщика) |
| Unit-тесты: генерация тестов из кода | △ Create test (intention) | ✅ | ✅ Create Unit Tests (базовый), IntelliTest (Ent) |
| VB.NET | ❌ | ❌ (только чтение) | ✅ полная поддержка |
| F# | ❌ | ✅ | ✅ |
| C++ / C++/CLI, смешанный код | ❌ | △ C++ только для Unreal / CMake, без C++/CLI | ✅ |

## 3. Отладка

| Возможность | Плагин | Rider | VS Community |
|---|---|---|---|
| Точки останова с условием, hit count, лог | ✅ | ✅ | ✅ (плюс Tracepoints, зависимые точки, временные) |
| Исключения (first-chance, фильтры) | ✅ | ✅ | ✅ Exception Settings |
| Evaluate, Watch, Set Value, потоки, кадры | ✅ | ✅ | ✅ |
| Hot Reload / Edit & Continue | ❌ (Hot Reload в плане) | ✅ | ✅ (в т.ч. XAML Hot Reload) |
| Smart Step Into, Return values | ❌ | ✅ | ✅ Step Into Specific, Autos с возвращаемыми значениями |
| Отладка дампов (`.dmp`) как сессии | △ просмотр Memory Dump (dotnet-dump + SOS) | △ dotMemory для памяти | ✅ управляемый и native дамп, Diagnostic Analysis |
| Native / mixed-mode отладка | ❌ | ❌ | ✅ |
| Диагностика во время отладки (CPU / память, события) | △ Monitor, аллокации по строкам | ✅ Dynamic Program Analysis, dotTrace / dotMemory (Windows) | ✅ Diagnostic Tools (CPU, память, снимки кучи, события) |
| Пользовательские визуализаторы (`DebuggerVisualizer`), JSON/XML/HTML visualizer | ❌ | △ просмотрщики JSON/XML | ✅ |
| Parallel Stacks / Parallel Watch / Tasks | ❌ | ✅ Parallel Stacks | ✅ |
| Удалённая, WSL, Docker-отладка | ❌ | ✅ | ✅ (Remote Debugger, WSL, контейнеры) |
| Snapshot / Time Travel / IntelliTrace | ❌ | ❌ | (Ent) |
| Предиктивный отладчик | ❌ | ✅ | ❌ |

## 4. Тесты, покрытие, профилирование

| Возможность | Плагин | Rider | VS Community |
|---|---|---|---|
| Test Explorer, запуск / отладка тестов | ✅ (результаты после завершения) | ✅ | ✅ |
| Continuous testing | ❌ (в плане) | ✅ | (Ent) Live Unit Testing |
| Покрытие кода | ✅ (coverlet) | ✅ dotCover встроен | ❌ (Ent) — в Community покрытия нет |
| Профилировщик CPU | △ Monitor; `dotnet-trace` в плане | ✅ dotTrace (Windows) | ✅ Performance Profiler: CPU Usage, .NET Object Allocation, Events, DB, File I/O |
| Профилировщик памяти | △ Memory Dump, аллокации по строкам | ✅ dotMemory (Windows) | ✅ Memory Usage, .NET Object Allocation |
| BenchmarkDotNet | ❌ (в плане) | △ плагин | ❌ |

## 5. Solution, проекты, пакеты, публикация

| Возможность | Плагин | Rider | VS Community |
|---|---|---|---|
| Solution / проекты, nesting, Show All Files | ✅ | ✅ | ✅ |
| Unload / reload проекта, drag-and-drop | ❌ (в плане) | ✅ | ✅ |
| Project Properties (страницы) | △ правка `.csproj` со схемой и completion | ✅ | ✅ богатые страницы (сборка, отладка, подпись, ресурсы, ClickOnce) |
| NuGet: поиск, установка, фиды | ✅ | ✅ | ✅ |
| NuGet: outdated / vulnerable / deprecated, Update All, консолидация | ❌ (в плане) | ✅ | ✅ (вкладки Updates / Consolidate, значки уязвимостей) |
| Central Package Management | △ чтение версий | ✅ | ✅ |
| Публикация: папка, Docker, Azure | △ `dotnet publish`; мастер в плане | ✅ папка / Docker / Azure (плагин) | ✅ мастер Publish: папка, IIS / Web Deploy, Azure App Service / Functions / Containers, ClickOnce, MSIX |
| Контейнеры: Dockerfile, `PublishContainer`, docker-compose проект (`.dcproj`) | △ | △ Dockerfile, compose-запуск | ✅ включая `.dcproj` и оркестрацию |
| Aspire | ❌ (в плане) | ✅ | ✅ |
| Connected Services (OpenAPI/gRPC/WCF-клиенты, Azure-зависимости, user secrets) | ❌ (OpenAPI в плане) | △ плагин OpenAPI | ✅ |
| Templates `dotnet new`, item templates | ✅ | ✅ | ✅ |
| Upgrade Assistant | △ анализ | ✅ плагин | ✅ расширение .NET Upgrade Assistant |
| EF Core (миграции, scaffold) | ✅ | ✅ | △ через Package Manager Console / расширения; EDMX-дизайнер EF6 |
| HTTP-клиент (`.http`) | ✅ (платформа) | ✅ | ✅ (Endpoints Explorer) |
| Git, PR-ы | ✅ (платформа) | ✅ | ✅ |

## 6. Дизайнеры и Windows-специфичное

| Возможность | Плагин | Rider | VS Community |
|---|---|---|---|
| WinForms-дизайнер | ❌ | ✅ (Windows) | ✅ |
| WPF / WinUI 3 / UWP визуальный дизайнер, Live Visual Tree, Live Property Explorer | ❌ | ❌ (только XAML-код, preview для Avalonia плагином) | ✅ |
| Web Forms (ASP.NET .NET Framework) дизайнер | ❌ | ❌ | ✅ |
| `.settings`, `app.manifest`, редактор ресурсов `.resx` | ❌ (`.resx` в плане) | △ `.resx` есть | ✅ |
| T4-шаблоны | ❌ | ✅ | ✅ |
| Windows Workflow Foundation дизайнер | ❌ | ❌ | ✅ (.NET Framework) |
| MSIX / Windows App SDK packaging, App Installer | ❌ | ❌ | ✅ |
| Setup / Installer проекты | ❌ | ❌ | ✅ (расширение Microsoft) |
| SQL Server Data Tools (`.sqlproj`, Schema Compare), Server Explorer | ❌ | ❌ (DataGrip-функции есть, SSDT нет) | ✅ |
| .NET MAUI: эмуляторы, Hot Restart iOS с Windows, Mac pairing | ❌ | △ MAUI есть, без Hot Restart | ✅ |
| Полный MSBuild с .NET Framework targeting packs из установщика | ❌ (нужны Build Tools / SDK) | ❌ (нужны Build Tools) | ✅ |

## 7. Есть в Visual Studio Community, нет в Rider (без Razor/Blazor)

Собрано из таблиц выше; (Ent)-возможности исключены — их в Community тоже нет.

Язык и редактор:
1. C# Interactive (REPL Roslyn) и окно Immediate с выполнением операторов.
2. Code Metrics (Calculate Code Metrics: maintainability index, cyclomatic complexity, coupling, LOC).
3. Class Designer — UML-диаграммы классов по коду и обратно.
4. VB.NET — полная поддержка (в Rider только чтение).
5. C++ native и C++/CLI, смешанные решения C#/C++ с mixed-mode отладкой.
6. Code Cleanup profiles Roslyn (IDExxxx-стиль в один клик) — у Rider своё, но именно профили Roslyn нет.

Отладка:
7. Native / mixed-mode отладка, отладка native-дампов, Diagnostic Analysis дампов.
8. Пользовательские Debugger Visualizers (`DebuggerVisualizerAttribute`), встроенные JSON / XML / HTML visualizers (у Rider только просмотрщики).
9. Diagnostic Tools при отладке: снимки кучи и сравнение, события, CPU — в одном окне сессии.
10. Dependent breakpoints, Tracepoints с действиями, Temporary breakpoints — у Rider частично.
11. XAML Hot Reload с Live Visual Tree / Live Property Explorer.

Тесты и профилирование:
12. Performance Profiler без отладчика: .NET Object Allocation, Events Viewer, Database, File I/O, GPU — у Rider dotTrace/dotMemory только на Windows и без части профилей.
13. Create Unit Tests (генерация тестового проекта и заглушек) встроенный — у Rider через плагины/шаблоны.

Проекты и публикация:
14. Мастер Publish: IIS / Web Deploy (MSDeploy), ClickOnce, MSIX, Azure App Service / Functions прямо из IDE.
15. Connected Services: WCF-клиент, OpenAPI/gRPC-клиенты, Azure Storage / Key Vault / SQL зависимости, автонастройка secrets.
16. docker-compose проект (`.dcproj`) с оркестрацией нескольких сервисов и отладкой.
17. Project Properties UI: подпись сборки, ClickOnce, ресурсы, `.settings`, `app.manifest`.
18. EF6 EDMX-дизайнер, Package Manager Console (PowerShell-хост NuGet).

Дизайнеры и Windows:
19. WPF / WinUI 3 / UWP визуальный дизайнер.
20. Web Forms дизайнер и Web Site-проекты (.NET Framework).
21. Windows Workflow Foundation дизайнер.
22. MSIX / Windows App SDK packaging, App Installer, Setup-проекты.
23. SQL Server Data Tools (`.sqlproj`, Schema/Data Compare), Server Explorer, Report Designer (RDLC, расширение).
24. .NET MAUI: iOS Hot Restart с Windows, Mac pairing, встроенные Android-эмуляторы.
25. Установщик с .NET Framework targeting packs и полным MSBuild — сборка старых Framework-решений без отдельных Build Tools.

Чего, наоборот, нет в Community, но есть в Rider: покрытие кода (dotCover), Live Unit Testing-аналог (continuous testing), кроссплатформенность,
инспекции ReSharper, встроенный декомпилятор с IL Viewer, Parallel Stacks, предиктивный отладчик, Unity/Unreal/Godot.

## 8. Где плагин выигрывает у обоих

- Работает внутри уже открытой IDE JetBrains другого языка (GoLand / PyCharm / WebStorm / IDEA): полиглот-репозиторий без второй IDE.
- Всё прозрачно через `dotnet` CLI: любое действие плагина повторяется в терминале, ничего не зависит от закрытого бэкенда.
- Аллокации по строкам в редакторе на живом процессе (EventPipe) — у VS это отдельный профиль, у Rider — dotMemory-сессия.
- Схема MSBuild с completion по SDK и пакетам сообщества, генерация документации по настройкам на двух языках.

## 9. Вывод

По языковым возможностям порядок такой: Visual Studio Community ≈ Rider (разные акценты: у VS — VB/C++/дизайнеры/публикация, у Rider —
инспекции/декомпилятор/кроссплатформенность/покрытие) ≫ плагин. Плагин закрывает повседневный цикл «писать — собирать — запускать — отлаживать —
тестировать — пакеты» на уровне Roslyn-сервера, но не имеет своей семантики: рефакторинги с диалогами, инспекции сверх анализаторов, structural
search и всё, что строится на PSI, ему недоступны без парсера C#. Из списка §7 в рамках плагина реалистичны: REPL (`csharprepl`), генерация тестов,
мастер Publish, `.resx`-редактор, Connected Services в виде генерации OpenAPI/gRPC-клиентов, docker-compose запуск — всё это уже в `ROADMAP.md`.
Дизайнеры, VB.NET, C++/CLI, native-отладка, SSDT, MSIX — вне рамок.
