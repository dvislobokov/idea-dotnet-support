---
marp: true
title: C# Project Support
description: Разработка на C# и .NET в IntelliJ IDEA, GoLand, PyCharm и WebStorm
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
  li { margin: 0 0 14px; }
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
    margin: 0 0 24px;
  }
  pre code { background: none; padding: 0; font-size: 1em; color: #c3c5cc; }
  pre code .hljs-keyword, pre code .hljs-built_in { color: #cf8e6d; }
  pre code .hljs-title, pre code .hljs-type { color: #56a8f5; }
  pre code .hljs-string { color: #6aab73; }
  pre code .hljs-subst { color: #c3c5cc; }
  pre code .hljs-number, pre code .hljs-literal { color: #2aacb8; }
  pre code .hljs-comment { color: #f08a3c; font-style: italic; }
  section table { display: table; border-collapse: collapse; width: 100%; font-size: 23px; margin: 0 0 24px; }
  section table th, section table td {
    background: none !important;
    border: 0 !important;
    border-bottom: 1px solid var(--line) !important;
    padding: 12px 22px 12px 0;
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
  section.small { font-size: 23px; }
  section.small table { font-size: 21px; }
  section.small table th, section.small table td { padding-top: 8px; padding-bottom: 8px; }
  /* a list as cards: the statement in bold, its explanation under it */
  section.cards ul { list-style: none; padding: 0; margin: 10px 0 0; display: grid; grid-template-columns: repeat(3, 1fr); gap: 24px; }
  section.cards li { margin: 0; padding: 26px 28px 28px; background: var(--panel); border: 1px solid var(--line); border-radius: 16px; font-size: 23px; line-height: 1.4; }
  section.cards li strong { display: block; font-size: 28px; line-height: 1.15; letter-spacing: -0.02em; margin-bottom: 12px; }
  section.cards > p { font-size: 29px; max-width: 34em; margin-bottom: 22px; }
  section.cards.six li { padding: 20px 24px 22px; font-size: 21px; }
  section.cards.six li strong { font-size: 25px; margin-bottom: 8px; }
  /* the steps: cards with their numbers */
  section.steps ol { list-style: none; padding: 0; margin: 10px 0 0; display: grid; grid-template-columns: repeat(3, 1fr); gap: 24px; counter-reset: step; }
  section.steps li { counter-increment: step; margin: 0; padding: 22px 28px 28px; border-top: 3px solid var(--line); font-size: 23px; line-height: 1.4; }
  section.steps li::before { content: counter(step); display: block; font-size: 64px; font-weight: 700; line-height: 1; margin-bottom: 14px; background: var(--grad); -webkit-background-clip: text; background-clip: text; color: transparent; }
  section.steps li strong { display: block; font-size: 28px; line-height: 1.15; margin-bottom: 10px; }
  section.steps > p { margin-top: 30px; color: var(--text-3); font-size: 23px; }
  /* a feature: what it gives, in three statements */
  section.feature > p { font-size: 29px; max-width: 34em; margin-bottom: 24px; }
  section.feature li { font-size: 26px; }
---

<!-- _class: lead -->
<!-- _paginate: false -->

# C# и .NET в той IDE, которая у вас уже открыта

Полный цикл разработки на .NET в IntelliJ IDEA, GoLand, PyCharm и WebStorm

Плагин C# Project Support, версия 0.1.0

---

<!-- _class: cards -->

## .NET в команде с несколькими языками

Сервисы на Go, Python и TypeScript живут в одной IDE. Сервисы на .NET требуют другой.

- **Два рабочих места** Каждому разработчику нужны две IDE, два набора настроек и два набора привычек.
- **Потери на переключении** Внимание уходит на инструмент, а не на задачу: другие окна, другие клавиши.
- **Долгий ввод в проект** Новый сотрудник настраивает и осваивает два окружения вместо одного.

---

<!-- _class: cards -->

## .NET становится частью вашей IDE

Плагин добавляет полный рабочий цикл: код, сборка, запуск, отладка, тесты и пакеты.

- **Одна IDE на весь стек** Сервисы на .NET рядом с остальным кодом команды. Одни настройки и одно окно.
- **Работает в бесплатной IDEA** Все возможности, включая отладчик, доступны в IntelliJ IDEA Community.
- **Компилятор Microsoft внутри** Ошибки и исправления даёт Roslyn: тот же компилятор, который собирает ваш код.

---

## Кому это нужно

| Кто | Что получает |
|---|---|
| Команды с несколькими языками | Единое рабочее место для всего стека |
| Разработчики на Go, Python, TypeScript | Работа с сервисами .NET без смены IDE |
| Команды на IntelliJ IDEA Community | Отладка и тесты .NET без дополнительных продуктов |
| Руководители разработки | Один набор инструментов и быстрый ввод новых сотрудников |

---

<!-- _class: part -->

# Что получает разработчик

Пять возможностей, которые заметны в первый же день работы.

---

<!-- _class: feature -->

## Редактор, который дописывает за вас

Меньше набора: редактор предлагает готовые строки и находит ошибки до сборки.

```csharp
public string Customer          // { get; set; }
repository.Save(                // order, cancellationToken
WriteLi                         // Console.WriteLine(); и using System;
```

- **Продолжение строки** появляется серым текстом, остаётся нажать Tab
- **Готовые вызовы** подставляются целиком, нужное пространство имён подключается само
- **Ошибки видны во всём решении**, Alt+Enter исправляет их в файле, проекте или везде сразу

---

<!-- _class: feature -->

## Отладчик, к которому не нужно привыкать

Стандартное окно Debug со всеми его возможностями. Причина ошибки находится быстрее, когда значения видны прямо в коде.

- **Остановка там, где нужно:** условия, счётчики попаданий, исключения выбранного типа
- **Состояние перед глазами:** значения рядом с кодом, вычисление выражений, стек асинхронных вызовов
- **Любой сценарий:** приложение, отдельный тест или уже работающий процесс

> Поддерживается современный .NET: .NET Core и .NET 5 и новее.

---

<!-- _class: feature -->

## Видно, какая строка расходует память

Запустите приложение, и рядом с кодом появятся измеренные значения.

```csharp
return new byte[1024];          // 19.3 MB/s, 18.4K obj/s, byte[]  95%
names[i] = $"order-{i}";        // 884 KB/s, 18.8K obj/s, string  4.3%
```

- **Измерение, а не предположение:** данные снимаются с работающей программы
- **Понятно, с чего начать:** доля строки показывает, что оптимизировать первым
- **Без подготовки:** обычный запуск из IDE, без профилировщика и специальной сборки

---

<!-- _class: feature -->

## Решение целиком, а не папка с файлами

Проекты, зависимости и конфигурации показаны так, как их видит сборка.

- **Любой формат:** `.sln`, `.slnx` и фильтры `.slnf`, несколько решений в одной папке
- **Зависимости под контролем:** пакеты, проекты и анализаторы по каждой целевой платформе
- **Сборка и запуск в один клик:** ошибки со ссылками на код, профили запуска, окно Services
- **Новый проект за минуту:** шаблоны `dotnet new` с параметрами в диалоге IDE

---

<!-- _class: feature -->

## Тесты и покрытие в одном окне

xUnit, NUnit, MSTest и TUnit: запуск, отладка и покрытие без командной строки.

- **Запуск от места в коде:** один тест, класс или только упавшие
- **Покрытие на виду:** в редакторе, по файлам и в дереве проекта
- **Быстрый старт:** тестовый класс для выбранного типа создаётся из Alt+Enter

---

<!-- _class: cards six -->

## Всё остальное тоже на месте

- **NuGet** Поиск, установка и обновление пакетов по всему решению, включая приватные источники.
- **EF Core** Миграции, обновление базы данных и SQL-скрипты из меню.
- **Мониторинг** Процессор, память и сборка мусора работающего приложения, снимки кучи и дампы.
- **Endpoints** Все маршруты API одним списком, запрос к любому из них в один клик.
- **Единый стиль кода** CSharpier или `dotnet format` по привычной комбинации клавиш.
- **Шаблоны** Заготовки файлов и типовых конструкций вместо однотипного кода вручную.

---

<!-- _class: part -->

# Почему этому можно доверять

Плагин не изобретает своё: он соединяет IDE со стандартными инструментами .NET.

---

## Построено на стандартных инструментах

| Задача | На чём работает |
|---|---|
| Анализ кода | Roslyn, компилятор Microsoft |
| Сборка, тесты, пакеты | `dotnet` CLI, тот же, что на сервере сборки |
| Отладка | Стандартное окно Debug платформы IntelliJ |
| Интерфейс | Привычные окна IDE: Run, Services, Build, Problems |

Результат в IDE совпадает с результатом на сервере сборки: проект собирается и проверяется одними и теми же средствами.

> Каждую сборку плагина проверяют 447 автоматических тестов.

---

<!-- _class: small -->

## Плагин и Rider: что выбрать

| Область | C# Project Support | Rider |
|---|---|---|
| Где работает | IDEA, GoLand, PyCharm, WebStorm | Отдельная IDE |
| Анализ кода | Компилятор и анализаторы Roslyn | Собственный движок, 2 500 инспекций |
| Рефакторинги | Переименование и рефакторинги Roslyn | Более 60 |
| Отладчик | Современный .NET | .NET, .NET Framework, Mono |
| Расход памяти по строкам | Измеренные значения | Пометки без измерения |
| Тесты | Результаты после прогона | Результаты по ходу прогона |
| Razor и Blazor | В разработке | Полная поддержка |

**Плагин** подходит командам, где .NET является частью стека. **Rider** подходит командам, где .NET является основной платформой и нужны глубокие инспекции.

---

## Что пока не поддерживается

- **Razor, Blazor, XAML, F# и VB.NET**
- **Публикация:** в папку, IIS, Azure и контейнеры
- **Hot Reload** при отладке
- **Отладка .NET Framework и Mono**, удалённая отладка
- **Инспекции ReSharper и профилирование процессора**

> Расход памяти измеряется по выборке событий среды выполнения: строка, которая выделяет мало, появляется не сразу.

---

## План развития

| Направление | Что это даст |
|---|---|
| Razor и Blazor | Веб-интерфейсы на .NET в том же редакторе |
| Расход памяти | Сравнение двух запусков и причина выделения рядом со значением |
| Подсказки по библиотекам | Типы и методы пакетов, которые ещё не подключены к проекту |
| Большие решения | Устойчивая работа и быстрая загрузка на крупных проектах |

> Версия 0.1.0 находится в раннем доступе: часть возможностей проходит проверку на реальных проектах.

---

<!-- _class: steps -->

## Три шага до первой строки кода

1. **Установите инструменты** Settings, Tools, .NET, .NET Tools. Нужны сервер языка и отладчик.
2. **Откройте папку с решением** Панель проекта переключится на Solution.
3. **Работайте** Подсветка и подсказки доступны сразу, полный анализ включается после загрузки.

Проект менять не нужно: плагин читает те же файлы решения и проектов, что и `dotnet` CLI.

---

<!-- _class: small -->

## Сценарий демонстрации на 12 минут

| Минута | Что показать |
|---|---|
| 0:00 | Открыть папку с решением: панель Solution, зависимости по платформам |
| 1:00 | Запустить веб-проект из Services: ссылка на адрес, окно Endpoints |
| 2:30 | Набрать код: свойство и конструктор серым текстом, `WriteLi` и Tab |
| 4:00 | Alt+Enter и Fix All in Solution, переименование класса |
| 5:30 | Отладка: остановка на исключении, Evaluate, значения в коде |
| 7:00 | Расход памяти: Show Allocations in Editor, значения у строк |
| 8:30 | Тесты: Run with Coverage, повтор упавших |
| 10:00 | NuGet и EF Core: установка пакета, новая миграция |
| 11:00 | Мониторинг: графики, снимки кучи |

---

<!-- _class: lead -->
<!-- _paginate: false -->

# Попробуйте на своём проекте

Установите плагин и откройте папку с решением. До первой строки кода три шага.

C# Project Support, `io.github.dotnetsupport`
