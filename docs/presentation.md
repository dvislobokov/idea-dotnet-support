---
marp: true
title: C# Project Support
description: C# и .NET в IntelliJ IDEA, GoLand, PyCharm и WebStorm
lang: ru
paginate: true
size: 16:9
style: |
  :root {
    --bg: #19191c;
    --panel: #24252a;
    --line: #3a3c41;
    --text: #ffffff;
    --text-2: #c3c5cc;
    --text-3: #8b8e97;
    --violet: #7b61ff;
    --magenta: #ff318c;
    --orange: #ff8a1f;
    --ok: #57c78a;
    --warn: #f2c037;
    --no: #ff6b6b;
    --grad: linear-gradient(100deg, #7b61ff, #ff318c 52%, #ff8a1f);
  }
  section {
    background: var(--bg);
    color: var(--text-2);
    font-family: "Inter", "Segoe UI Variable Display", "Segoe UI", system-ui, sans-serif;
    font-size: 27px;
    line-height: 1.45;
    padding: 56px 72px 64px;
    display: flex;
    flex-direction: column;
    place-content: start stretch !important;
    justify-content: flex-start !important;
  }
  section::after { color: var(--text-3); font-size: 15px; right: 72px; bottom: 28px; }
  h1, h2, h3 { color: var(--text); letter-spacing: -0.03em; line-height: 1.08; margin: 0; }
  h1 { font-size: 76px; font-weight: 700; }
  h2 { font-size: 46px; font-weight: 700; padding-bottom: 18px; margin-bottom: 26px; border-bottom: 3px solid; border-image: var(--grad) 1; }
  h3 { font-size: 27px; font-weight: 600; letter-spacing: -0.01em; margin: 18px 0 6px; }
  p { margin: 0 0 14px; }
  strong { color: var(--text); font-weight: 600; }
  a { color: var(--text); }
  ul, ol { margin: 0; padding-left: 28px; }
  li { margin: 0 0 10px; }
  li::marker { color: var(--magenta); }
  code {
    font-family: "JetBrains Mono", "Cascadia Code", Consolas, monospace;
    font-size: 0.86em;
    color: var(--text);
    background: rgba(255, 255, 255, 0.09);
    border-radius: 5px;
    padding: 0.08em 0.36em;
  }
  pre {
    background: #1e1f22;
    border: 1px solid var(--line);
    border-radius: 12px;
    padding: 20px 24px;
    font-size: 21px;
    line-height: 1.6;
    margin: 0 0 18px;
  }
  pre code { background: none; padding: 0; font-size: 1em; color: #c3c5cc; }
  pre code .hljs-keyword, pre code .hljs-built_in { color: #cf8e6d; }
  pre code .hljs-title, pre code .hljs-type { color: #56a8f5; }
  pre code .hljs-string { color: #6aab73; }
  pre code .hljs-number, pre code .hljs-literal { color: #2aacb8; }
  pre code .hljs-comment { color: #7a7e85; font-style: italic; }
  section table { display: table; border-collapse: collapse; width: 100%; font-size: 23px; margin: 0 0 24px; }
  section table th, section table td {
    background: none !important;
    border: 0 !important;
    border-bottom: 1px solid var(--line) !important;
    padding: 11px 22px 11px 0;
    text-align: left;
    vertical-align: top;
    color: var(--text-2);
  }
  section table th { color: var(--text-3); font-weight: 500; font-size: 19px; }
  section table td:first-child { color: var(--text); font-weight: 600; white-space: nowrap; }
  section table tr { background: none !important; border: 0 !important; }
  blockquote { border-left: 3px solid var(--magenta); margin: 18px 0 0; padding: 2px 0 2px 20px; color: var(--text-3); font-size: 22px; }
  section.lead {
    justify-content: center !important;
    padding: 72px 96px;
    background:
      radial-gradient(760px 520px at 88% 8%, rgba(255, 49, 140, 0.42), transparent 62%),
      radial-gradient(620px 460px at 100% 62%, rgba(255, 138, 31, 0.30), transparent 62%),
      radial-gradient(700px 520px at 62% 0%, rgba(123, 97, 255, 0.46), transparent 62%),
      var(--bg);
  }
  section.lead h1 { max-width: 15em; }
  section.lead p { font-size: 32px; color: var(--text-2); margin-top: 28px; max-width: 26em; }
  section.lead p + p { font-size: 21px; color: var(--text-3); margin-top: 40px; }
  section.part { justify-content: center !important; padding: 72px 96px; }
  section.part h1 { font-size: 64px; }
  section.part p { font-size: 28px; margin-top: 22px; max-width: 28em; }
  section.cols ul { columns: 2; column-gap: 56px; }
  section.cols li { break-inside: avoid; }
  section.small { font-size: 23px; }
  section.small table { font-size: 21px; }
  section.small table th, section.small table td { padding-top: 8px; padding-bottom: 8px; }
---

<!-- _class: lead -->
<!-- _paginate: false -->

# C# и .NET в той IDE, которая у вас уже открыта

Редактор на Roslyn, отладчик, тесты, NuGet и EF Core в IntelliJ IDEA, GoLand, PyCharm и WebStorm

Плагин C# Project Support, версия 0.1.0. Платформа IntelliJ 2026.1 и новее

---

## Зачем это нужно

- В команде несколько языков, а IDE хочется одну. Сервис на Go, рядом API на C#, и оба открыты в GoLand.
- Rider решает задачу целиком, но это отдельный продукт и отдельное окно.
- Плагин даёт основной рабочий цикл на C# внутри любой IDE платформы IntelliJ.

### Рабочий цикл

Открыть solution, написать код, собрать, запустить, отладить, прогнать тесты, поставить пакет.

> Работает и в бесплатной IntelliJ IDEA Community.

---

## Как устроено

| Что | Кто делает |
|---|---|
| Семантика C# | `roslyn-language-server`, тот же компилятор, что в Visual Studio |
| Отладка | свой клиент протокола DAP и адаптер `dotnet-debugger-dap` |
| Сборка, тесты, пакеты | `dotnet` CLI |
| Диагностика процесса | `dotnet-counters`, `dotnet-stack`, `dotnet-gcdump`, `dotnet-dump` |
| Подсветка, структура, отступы | лексер и сканер объявлений плагина |

Собственного парсера C# в плагине нет. Пока сервер грузится, работают эвристики по токенам. Когда сервер готов, они ему уступают.

---

<!-- _class: part -->

# Редактор

Семантику даёт Roslyn. Скорость набора даёт слой, которому сервер не нужен.

---

## Редактор понимает код

<!-- _class: cols -->

- **Ошибки** компилятора и анализаторов в редакторе
- **Problems** по всем файлам solution, а не только по открытым
- **Completion** с автоматическим `using`
- **Parameter Info** со всеми перегрузками
- **Quick Doc**, inlay hints, семантическая раскраска
- **Alt+Enter**: исправления и рефакторинги Roslyn
- **Fix All** по документу, проекту и solution
- **Rename** с переименованием файла
- **Generate** по Alt+Insert: конструктор, Equals, overrides
- Опции C# в `.editorconfig` с подсказками

---

## Серый текст по Tab

Продолжение предлагается только там, где оно одно.

```csharp
public string Customer          // { get; set; }
private readonly List<OrderLine> _lines =      // new();
public Order(                   // string customer, Guid id)
{
                                // _customer = customer;
}
repository.Save(                // order, cancellationToken
services.AddSingleton<IClock>(  // serviceProvider =>
```

Ещё четыре случая: namespace по папке, имя типа по имени файла, `ILogger<Класс>`, `catch (Exception e)`.

---

## Где серый текст молчит

| Набрано | Почему нет подсказки |
|---|---|
| `public Order FindLatest` | глагол в имени, это метод |
| `public Task<Order> Latest` | тип `Task`, это метод |
| `private readonly Order _order = ` | справа с той же вероятностью вызов |
| `Save(` без переменной с именем параметра | нечего подставить |
| открыт список completion | Tab принадлежит списку |

Семь подсказок из девяти считаются по токенам. Они появляются мгновенно и работают, пока solution ещё грузится.

---

## Набор без ожидания

<!-- _class: cols -->

- **Лямбда первой в списке** там, где ожидается делегат
- **Переменные выше ключевых слов** в completion
- **Скобки после** `typeof`, `nameof`, `new HttpClient`
- **Парная** `>` у generic: `AddSingleton<|>()`
- Пара снимается сама на сравнении `Count<5`
- **21 postfix-шаблон**: `expr.notnull`, `list.foreach`
- **33 live templates**: `ctor`, `prop`, `foreach`
- **Surround With** для выражений и строк
- **Отступы** по правилам Allman и K&R
- Действия файла: namespace по папке, тип в свой файл

---

## Навигация

- **Объявление и реализации**, в том числе по Ctrl и наведению
- **Декомпилированный код** фреймворка и пакетов, только для чтения
- **Type Hierarchy** и **Call Hierarchy** в стандартном окне Hierarchy
- **Go to Base** и производные типы
- **Usages** по клику на code lens над объявлением
- **Go to Class** и Structure по индексу плагина, без сервера

### Перенос файла

Перетащили `.cs` в другую папку: плагин предложит сменить namespace вместе со всеми использованиями.

---

<!-- _class: part -->

# Отладка, запуск, тесты

Стандартные окна IDE: Debug, Run, Services, Build.

---

## Отладчик

<!-- _class: cols -->

- **Точки останова**: условие, счётчик попаданий, сообщение в лог
- **Остановка на исключениях** с фильтром по типам
- Шаги, Run to Cursor, **Set Next Statement**
- **Асинхронный стек**, кадры фреймворка серым
- Variables постранично, **изменение значений**
- Evaluate и Watches с completion
- **Значения в редакторе** рядом с кодом
- **Attach** к процессу
- **Отладка тестов**
- Ввод в консоль отлаживаемого приложения

> Только .NET Core и .NET 5 и новее. Hot Reload и Smart Step Into нет.

---

## Solution и проект

- **Форматы**: `.sln`, `.slnx`, фильтры `.slnf`, несколько solution в папке
- **Dependencies** по каждой целевой платформе: пакеты с транзитивными, проекты, сборки, анализаторы
- **Честное содержимое**: скрыты файлы, убранные через `Compile Remove`, показаны связанные файлы
- **New Project** с опциями шаблона из `dotnet new`
- **Properties**: три вкладки, правки не ломают форматирование `.csproj`
- **Rename Project**: файл, папка, solution и все ссылки одной отменяемой командой
- **Completion в** `.csproj` по схеме MSBuild, включая id и версии пакетов

---

## Сборка и запуск

- **Build, Rebuild, Clean, Restore** с деревом ошибок в окне Build
- Переключатель **Debug и Release** и целевой платформы в тулбаре
- **Run configurations** создаются сами по `launchSettings.json`
- Режимы `run`, `watch` и `test`, профиль запуска, окружение
- Окно **Services**: статус, консоли, ссылка на адрес приложения
- В консоли кликабельные стектрейсы и раскраска уровней логов
- **Measure Build Performance**: самые медленные targets и tasks

---

## Тесты и покрытие

- **Фреймворки**: xUnit, NUnit, MSTest, TUnit
- **Раннеры**: VSTest и Microsoft.Testing.Platform
- Окно **Unit Tests**: дерево проекта и сессии результатов
- Запуск от метода и класса, фильтр, **повтор упавших**
- **Run with Coverage**: полосы в редакторе, окно по файлам, проценты в дереве проекта
- **Create test** для выбранного класса из Alt+Enter

> Дерево результатов строится после завершения прогона. Консоль идёт вживую.

---

<!-- _class: part -->

# Вокруг кода

Пакеты, база данных, диагностика процесса, веб.

---

## NuGet и EF Core

### NuGet

- Окно пакетов по solution или проекту, карточка с версиями и зависимостями
- Приватные фиды с учётными данными, обновление всех пакетов разом
- Авто-restore при правке `.csproj`

### EF Core

- Add и Remove Migration, Update Database с откатом, SQL-скрипт, scaffold, bundle
- Окно миграций со статусом applied и pending
- Startup-проект и `DbContext` подбираются сами, команда видна до запуска

---

## Диагностика и веб

### .NET Monitor

- Графики процесса: CPU, память, GC, запросы, исключения
- Дамп потоков со свёрнутыми одинаковыми стеками
- Снимки кучи с разницей между ними
- Memory dump: типы, объекты и **кто держит объект**

### Endpoints

- Маршруты minimal API и контроллеров по проектам
- HTTP-запрос в `.http` в один клик, переход к коду, открытие в браузере

---

## Сравнение с Rider

<!-- _class: small -->

| Область | Rider | C# Project Support |
|---|---|---|
| Модель кода | ReSharper, свой парсер | Roslyn language server и эвристики |
| Ошибки | компилятор и 2 500 инспекций | компилятор и анализаторы Roslyn |
| Рефакторинги | больше 60 | Rename и рефакторинги Roslyn |
| Навигация | полная | объявления, реализации, иерархии |
| Отладчик | CoreCLR, .NET Framework, Mono | CoreCLR |
| Тесты | результаты вживую | результаты после прогона |
| NuGet, EF Core | есть | есть, NuGet только V3 |
| Razor и Blazor | полный язык | пока нет |
| Где работает | только Rider | IDEA, GoLand, PyCharm, WebStorm |

---

## Чего в плагине нет

- **Razor, Blazor, XAML, F#, VB.NET.** Только значки, шаблоны файлов и группировка в дереве.
- **Publish и контейнеры.** Нет публикации в папку, IIS, Azure и Docker.
- **Hot Reload.** `dotnet watch` доступен как режим запуска, при отладке изменений на лету нет.
- **Отладка .NET Framework и Mono**, удалённая отладка.
- **Инспекции и Code Cleanup ReSharper.**
- **Профилирование CPU.**

> Это следствие подхода: семантика живёт во внешнем сервере, и плагин умеет то, что умеет сервер.

---

## Что дальше

| Направление | Состояние |
|---|---|
| Razor и Blazor | сервер отвечает на запросы для `.razor` и `.cshtml`, трафик снят, клиент не подключён |
| Значки в списке Alt+Enter | спроектировано: вид действия, Incoming и Outgoing Calls |
| Серый текст, третья партия | пара для DI-регистрации, заполнение инициализатора объекта |
| Надёжность сервера | большие solution, перезапуски |

> Всё, что сделано в последние дни, покрыто тестами, но большая часть ещё не проверена вживую в IDE.

---

## С чего начать

1. **Поставьте инструменты.** Settings, Tools, .NET, .NET Tools. Нужны `roslyn-language-server` и `dotnet-debugger-dap`.
2. **Откройте папку с solution.** Именно папку, не файл. Панель проекта переключится на Solution.
3. **Дождитесь загрузки.** Виджет внизу покажет «Roslyn: имя solution».

### Под рукой

- Страница о плагине: меню .NET, пункт Welcome to C# Project Support
- Логи плагина: `~/idea-dotnet-logs`

---

## Сценарий демо на 10 минут

<!-- _class: small -->

| Минута | Что показать |
|---|---|
| 0:00 | Открыть папку. Панель Solution, Dependencies по платформам |
| 1:00 | Services: запуск веб-проекта, ссылка на адрес, окно Endpoints |
| 2:30 | Набор кода: свойство и конструктор серым текстом, лямбда в `AddSingleton` |
| 4:00 | Alt+Enter и Fix All in Solution, Rename, Type Hierarchy |
| 5:30 | Отладка: остановка на исключении, Evaluate, Set Next Statement |
| 7:00 | Unit Tests: Run with Coverage, повтор упавших |
| 8:00 | NuGet: поиск и установка пакета |
| 9:00 | EF Core: Add Migration, статус миграций |
| 9:30 | .NET Monitor: снимки кучи, memory dump, кто держит объект |

---

<!-- _class: lead -->
<!-- _paginate: false -->

# Спасибо

C# Project Support, `io.github.dotnetsupport`

Подробное сравнение с Rider лежит в `COMPARE.md`, планы в `ROADMAP.md`
