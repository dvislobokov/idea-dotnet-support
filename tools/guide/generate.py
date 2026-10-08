"""
Writes docs/guide.html, the documentation of the plugin (the second page of the Welcome tab of the IDE).

    uv run --no-project python tools/guide/generate.py

The names of the settings are not typed here: they are read from the texts of the settings pages
(src/main/resources/messages/DotNetBundle*.properties) and from the catalogue of the options of the language server
(RoslynOptions), so the page names an option the way the IDE does, in Russian and in English. The look (colors, the top bar)
is taken from docs/demo.html. GuidePageTest fails when an option is on a settings page and not on this one: run the script again.
"""
import html
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MESSAGES = ROOT / "src/main/resources/messages"
REVISION = "2026-09-29.1"


def properties(path):
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip()
    return result


EN = properties(MESSAGES / "DotNetBundle.properties")
RU = properties(MESSAGES / "DotNetBundle_ru.properties")


def roslyn_options():
    """(group, section, label, default, values) of RoslynOptions.ALL, in its order."""
    source = (ROOT / "src/main/kotlin/io/github/dotnetsupport/lsp/RoslynLanguageServerSettings.kt").read_text(encoding="utf-8")
    body = source[source.index("val ALL: List<RoslynOption> = listOf("):source.index("val GROUPS")]
    found = []
    pattern = re.compile(r'(toggle|RoslynOption)\("([^"]+)", "([^"]+)", "([^"]+)", (true|false|"[^"]*")(?:,\s*(SCOPES|listOf\(([^)]*)\)))?')
    for match in pattern.finditer(body):
        default = match.group(5).strip('"')
        values = None
        if match.group(6) == "SCOPES":
            values = ["openFiles", "fullSolution", "none"]
        elif match.group(7):
            values = re.findall(r'"([^"]+)"', match.group(7))
        found.append((match.group(2), match.group(3), match.group(4), default, values))
    assert len(found) >= 30, len(found)
    return found


def e(text):
    return html.escape(text, quote=False)


def name(key, english=None):
    """The name of a setting: Russian, and the English one the page shows when the language is English."""
    russian = RU[key].rstrip(":")
    original = (english if english is not None else EN[key]).rstrip(":")
    if russian == original:
        return "<b>%s</b>" % e(russian)
    return '<b>%s</b><small lang="en">%s</small>' % (e(russian), e(original))


def note(key):
    """A comment of a settings page is HTML already (<code>)."""
    text = RU.get(key, "")
    return re.sub(r"</?html>", "", text)


ON, OFF = "включено", "выключено"


def table(rows):
    lines = ['<div class="table"><table>', "<thead><tr><th>Параметр</th><th>По умолчанию</th><th>Что делает</th></tr></thead>", "<tbody>"]
    for label, default, description in rows:
        lines.append("<tr><td>%s</td><td>%s</td><td>%s</td></tr>" % (label, default, description))
    lines += ["</tbody>", "</table></div>"]
    return "\n".join(lines)


def option(key, default, description=None):
    return name(key), default, description if description is not None else note(key + ".comment")


def settings():
    out = []

    def page(anchor, path, key, lead):
        title = RU[key]
        english = EN[key]
        out.append('<h3 id="%s">%s%s</h3>' % (anchor, e(title), "" if title == english else ' <small lang="en">%s</small>' % e(english)))
        out.append('<p class="path">%s</p>' % e(path))
        out.append("<p>%s</p>" % lead)

    def group(key, english=None):
        title = RU[key]
        original = english if english is not None else EN.get(key, title)
        out.append("<h4>%s%s</h4>" % (e(title), "" if title == original else ' <small lang="en">%s</small>' % e(original)))

    page("settings-dotnet", "Settings | .NET", "page.dotnet",
         "Общие параметры плагина: где находится <code>dotnet</code>, какие инструменты установлены, чем форматировать код. "
         "Параметры этой страницы, кроме форматтера, действуют на все проекты на этой машине.")
    group("settings.cli.group")
    out.append(table([
        option("settings.cli.executable", "пусто"),
        (name("settings.cli.check"), "", "Проверяет указанный путь и показывает версию новейшего SDK."),
        (name("settings.cli.sdks"), "", "Список SDK, о которых сообщает <code>dotnet</code>, с папками установки."),
        (name("settings.cli.globalJson"), "", "Какой SDK требует <code>global.json</code> проекта и какой из установленных ему подошёл."),
    ]))
    group("settings.tools.group")
    out.append("<p>%s</p>" % note("settings.tools.comment"))
    tools = ["roslyn-language-server", "dotnet-debugger-dap", "csharpier", "dotnet-ef", "dotnet-counters", "dotnet-stack", "dotnet-gcdump", "dotnet-dump", "upgrade-assistant"]
    out.append('<div class="table"><table><thead><tr><th>Инструмент</th><th>Для чего нужен</th></tr></thead><tbody>')
    for tool in tools:
        out.append("<tr><td><code>%s</code></td><td>%s</td></tr>" % (tool, e(RU["tool.purpose." + tool])))
    out.append("</tbody></table></div>")
    group("settings.formatting.group")
    out.append(table([
        (name("settings.formatting.formatter"), e(RU["formatter.AUTO"].split(":")[0]),
         note("settings.formatting.comment") + " Варианты: «%s», «%s», «%s», «%s»." % tuple(e(RU["formatter." + v]) for v in ("AUTO", "CSHARPIER", "DOTNET_FORMAT", "NONE"))),
    ]))
    group("settings.behavior.group")
    out.append(table([
        option("settings.behavior.runConfigurations", ON, "Для каждого запускаемого проекта и каждого профиля из <code>launchSettings.json</code> создаётся конфигурация запуска."),
        option("settings.behavior.buildWindow", ON),
        option("settings.behavior.solutionView", ON, "Окно Project показывает решение так, как его видит сборка. Вернуться к обычному виду можно в заголовке окна."),
        option("settings.behavior.bracketColors", ON,
               note("settings.behavior.bracketColors.comment") + ". Как в VS Code: закрывающая скобка берёт ближайшую открывающую своего вида, лишние остаются "
               "нераскрашенными, неактивные ветки <code>#if</code> не раскрашиваются; <code>&lt;&gt;</code> — только списки типов, не сравнения."),
        option("settings.behavior.mapping", ON,
               note("settings.behavior.mapping.comment") + ". Имена сравниваются точно, без учёта регистра, по общему началу или концу слов "
               "(<code>UserId</code> ↔ <code>Id</code>) и по перекрытию camel-слов; тип должен приводиться неявно. Строки стоят первыми, когда контекст "
               "явно mapping (рядом уже есть присваивание из того же объекта), иначе после обычных; помечены серым <code>map</code>."),
        option("settings.behavior.importStats", ON,
               note("settings.behavior.importStats.comment") + ". Статистика снята с открытого корпуса C# (эксперимент e20 движка): для имени типа — "
               "частота каждого пространства имён и его связь с уже подключёнными using. Пространства, которых нет в индексе, никогда не предлагаются; "
               "сопутствующие using по статистике не добавляются."),
        option("settings.behavior.rememberChoices", ON,
               note("settings.behavior.rememberChoices.comment") + ". Вид места — после точки, начало оператора, аргумент, тип, справа от <code>=</code>. "
               "Без ML-ранкера выбранное поднимается внутри группы одного приоритета; с ранкером к его оценке прибавляется "
               "<code>0,3 × ln(1 + счётчик)</code>. Кнопка «%s» очищает память проекта. Подробности — <code>ML_ACCEPTANCE.md</code>." % e(RU["settings.behavior.resetChoices"])),
        option("settings.language", e(RU["language.AUTO"]),
               note("settings.language.comment") + " Русского языкового пакета для самой IDE нет, поэтому язык страниц плагина выбирается здесь."),
        option("settings.palette", e(RU["palette.default"]),
               note("settings.palette.comment") + " Палитры: Rider, Visual Studio, VS Code, Nord, Dracula, One Dark / One Light, Solarized, GitHub; "
               "«%s» — без палитры, C# берёт цвета Language Defaults схемы. Палитра записывается в текущую схему (её редактируемую копию, "
               "как при правке в Settings | Editor | Color Scheme) и переписывается при смене схемы или темы. Список с живым просмотром — "
               "меню .NET → C# Color Palette…, Esc возвращает прежнюю." % e(RU["palette.default"])),
        (name("settings.completion.exclude"), "пусто", note("settings.completion.exclude.comment") + ". Работает и для списка импортируемых типов, и для методов расширения из неподключённых пространств имён."),
    ]))

    page("settings-build", "Settings | .NET | Toolset and Build", "page.build",
         "Что добавляется к командам сборки. Параметры относятся к проекту, открытому в IDE, и хранятся в его рабочих файлах: в репозиторий они не попадают.")
    group("build.toolset")
    out.append(table([
        (name("build.cli"), "", note("build.cli.comment") + "."),
        option("build.msbuild", e(RU["build.msbuild.auto"]),
               note("build.msbuild.comment") + " Выбранная установка Visual Studio собирает всё, и проекты SDK тоже. Только в Windows."),
        option("build.properties", "пусто"),
    ]))
    group("build.group")
    out.append(table([
        option("build.afterLoad", OFF, "Сборка решения запускается сама, как только оно загружено."),
        option("build.restore", ON),
        ('<b>%s … %s</b><small lang="en">%s … %s</small>' % tuple(e(t) for t in (
            RU["build.parallel.before"], RU["build.parallel.after"], EN["build.parallel.before"], EN["build.parallel.after"])),
         e(RU["build.parallel.auto"].split(" (")[0]), "Сколько процессов MSBuild работает одновременно. Больше процессов, чем ядер, сборку не ускоряет."),
    ]))
    group("build.logging")
    out.append(table([
        option("build.verbosity.output", e(RU["verbosity.MINIMAL"]), "Насколько подробно MSBuild пишет в окно Build."),
        option("build.logToFile", OFF, note("build.logFolder.comment") + "."),
        option("build.verbosity.file", e(RU["verbosity.NORMAL"]), "Подробность журнала, который пишется в файл."),
        (name("build.logFolder.chooser"), "<code>~/idea-dotnet-logs/DotNetBuild</code>", "Куда складываются журналы сборки."),
    ]))

    page("settings-nuget", "Settings | .NET | NuGet", "page.nuget", "Поиск пакетов и восстановление. Действует на все проекты на этой машине.")
    group("nuget.search")
    out.append(table([option("nuget.prerelease", OFF)]))
    group("nuget.restore")
    out.append(table([
        option("nuget.automatic", ON),
        option("nuget.smart", ON),
        option("nuget.noCache", OFF),
        option("nuget.interactive", OFF),
    ]))
    group("nuget.credentials")
    out.append("<p>%s.</p>" % note("nuget.credentials.comment"))

    page("settings-coverage", "Settings | .NET | Coverage", "page.coverage", "Что делать с покрытием, собранным новым прогоном тестов.")
    group("coverage.onNew")
    out.append(table([
        option("coverage.onNew.ask", "выбрано", "Вопрос задаётся, только если какое-то покрытие уже показано."),
        option("coverage.onNew.doNotApply", "", "Новое покрытие собирается, но в редакторе остаётся прежнее."),
        option("coverage.onNew.replace", "", "Показывается только покрытие последнего прогона."),
        option("coverage.onNew.add", "", note("coverage.onNew.add.comment") + "."),
    ]))
    out.append(table([
        option("coverage.activate", ON, "Окно .NET Coverage открывается, когда покрытие собрано."),
        option("coverage.projectView", ON),
    ]))

    page("settings-debugger", "Settings | .NET | Debugger", "page.debugger",
         note("debugger.common") + " <b>Build, Execution, Deployment | Debugger</b>.")
    group("debugger.languages")
    out.append(table([option("debugger.external", OFF)]))
    group("debugger.values")
    out.append(table([option("debugger.implicit", ON)]))

    page("settings-analysis", "Settings | .NET | Analyzers and Generators", "page.codeAnalysis",
         "Генераторы исходного кода и анализаторы Roslyn без сервера языка: их запускает помощник CodeAnalysisHelper, которого плагин "
         "собирает из исходников установленным SDK (Roslyn берётся из SDK, сеть не нужна). Нужен SDK 8 или новее.")
    group("codeAnalysis.generators")
    out.append(table([option("codeAnalysis.runGenerators", ON, note("codeAnalysis.runGenerators.comment") + ".")]))
    group("codeAnalysis.analyzers")
    out.append(table([
        option("codeAnalysis.onSave", ON, note("codeAnalysis.onSave.comment") + "."),
        option("codeAnalysis.suggestions", OFF, note("codeAnalysis.suggestions.comment") + "."),
    ]))
    out.append(table([option("codeAnalysis.idle", "10", note("codeAnalysis.idle.comment") + ".")]))

    page("settings-server", "Settings | .NET | Language Server", "page.languageServer",
         note("server.about") + " Изменение параметров группы «%s» перезапускает сервер, остальные применяются на лету." % e(RU["server.group"]))
    out.append(table([option("server.enabled", ON, "Выключено: сервер не запускается, редактор работает на собственных эвристиках плагина — без ошибок компилятора и рефакторингов.")]))
    group("server.group")
    out.append(table([
        (name("server.executable"), "", note("server.executable.comment").replace("{0}", "roslyn-language-server") + "."),
        option("server.logLevel", "Information", "<code>--logLevel</code>. Для разбора неполадок — Debug или Trace."),
        option("server.logFolder", "<code>~/idea-dotnet-logs/roslyn-language-server</code>", "<code>--extensionLogDirectory</code>."),
        option("server.autoLoad", ON),
        option("server.autoLoad.limit", "0", "Сколько проектов сервер загружает сам. 0 — предел, который рекомендует сервер."),
        option("server.generators", "Automatic"),
        option("server.arguments", "пусто"),
    ]))
    options = roslyn_options()
    groups = []
    for item in options:
        if item[0] not in groups:
            groups.append(item[0])
    for title in groups:
        group("roslyn.group." + title.lower().replace(" ", "_"), title)
        rows = []
        for _, section, label, default, values in (o for o in options if o[0] == title):
            key = "roslyn.option." + section
            shown = {"true": ON, "false": OFF, "": "пусто"}.get(default, "<code>%s</code>" % e(default))
            description = note(key + ".comment")
            if values:
                description = (description + " " if description else "") + "Значения: " + ", ".join("<code>%s</code>" % e(v) for v in values) + "."
            rows.append((name(key, label), shown, description))
        out.append(table(rows))
    group("server.other")
    out.append("<p>%s.</p>" % note("server.other.comment"))

    out.append('<h3 id="settings-style">Стиль кода C# <small lang="en">Code Style | C#</small></h3>')
    out.append('<p class="path">Settings | Editor | Code Style | C#</p>')
    out.append("<p>Отступы, с которыми редактор набирает код: по умолчанию четыре пробела. Если в репозитории есть <code>.editorconfig</code>, "
               "значения <code>indent_style</code> и <code>indent_size</code> из него важнее. Остальное оформление кода задаёт форматтер: "
               "CSharpier или <code>dotnet format</code> по правилам <code>.editorconfig</code>.</p>")
    return "\n".join(out)


def rows(items):
    return "\n".join("<tr><td>%s</td><td>%s</td></tr>" % item for item in items)


def simple_table(first, second, items):
    return '<div class="table"><table><thead><tr><th>%s</th><th>%s</th></tr></thead><tbody>\n%s\n</tbody></table></div>' % (first, second, rows(items))


def content():
    tools = lambda *names: ", ".join("<code>%s</code>" % n for n in names)
    return '''
<section id="start">
<h2>Начало работы</h2>
<h3>Что нужно</h3>
<ul>
  <li>IDE на платформе IntelliJ версии 2026.1 или новее: IntelliJ IDEA (включая Community), GoLand, PyCharm, WebStorm.</li>
  <li>Пакет SDK .NET. Для подсказок по библиотекам и для измерения расхода памяти нужен SDK 8 или новее.</li>
  <li>Два инструмента .NET: %(required)s. Остальные ставятся по мере надобности.</li>
</ul>
<h3>Первые шаги</h3>
<ol>
  <li><b>Установите инструменты.</b> Откройте <b>Settings | .NET</b>, раздел «%(tools_group)s», и нажмите «%(install)s» у нужных инструментов.</li>
  <li><b>Откройте папку с решением.</b> Именно папку, а не файл решения. Окно Project переключится на вид Solution.</li>
  <li><b>Выберите решение, если их несколько.</b> IDE спросит, какое загрузить. Позже выбор меняется щелчком на значке сервера в статус-баре.</li>
  <li><b>Дождитесь загрузки.</b> Значок сервера C# в статус-баре показывает состояние. Подсветка, структура и серое продолжение строки работают сразу,
      ошибки компилятора и рефакторинги — после загрузки решения.</li>
</ol>
<h3>Значок сервера в статус-баре</h3>
<p>Виден, пока открыт проект. По щелчку показывает состояние сервера, загрузку процессора и занятую память, и даёт действия:
   Restart, Select Solution, Log, Timings, Settings.</p>
</section>

<section id="menu">
<h2>Меню .NET</h2>
<p>Находится в главной строке меню после Tools. Названия пунктов приведены так, как они написаны в интерфейсе.</p>
%(menu)s
<h3>Контекстное меню панели Solution</h3>
%(popup)s
</section>

<section id="windows">
<h2>Окна</h2>
%(windows)s
<p>Сборка, запуск и отладка используют стандартные окна IDE: Build, Run, Debug, Services и Problems.</p>
</section>

<section id="editor">
<h2>Редактор</h2>
%(editor)s
<h3>Форматирование</h3>
<p>Reformat Code (<kbd>Ctrl+Alt+L</kbd>) форматирует файл C# выбранным форматтером: CSharpier или <code>dotnet format</code>.
   Какой из них используется, задаётся на странице <a href="#settings-dotnet">.NET</a>. Правила берутся из <code>.editorconfig</code> репозитория.</p>
</section>

<section id="run">
<h2>Запуск и отладка</h2>
<ul>
  <li><b>Конфигурации запуска</b> типа «.NET Project» создаются сами для запускаемых проектов и профилей из <code>launchSettings.json</code>.</li>
  <li><b>Конфигурация и целевая платформа</b> (Debug или Release, <code>net10.0</code> и другие) выбираются в меню стрелки кнопки Build Solution в тулбаре (как в Rider) и действуют на сборку, запуск и тесты. Сам молоток собирает решение; в том же меню — Rebuild, Clean, NuGet Restore и Cancel Build.</li>
  <li><b>Отладка</b> работает в стандартном окне Debug: точки останова с условиями, остановка на исключениях, значения в коде, Evaluate, Watches.</li>
  <li><b>Set Next Statement</b> — в контекстном меню редактора во время отладки: делает строку под курсором следующей для выполнения.</li>
  <li><b>Attach</b> к уже работающему процессу и <b>отладка тестов</b> — из окна Unit Tests и значков у методов.</li>
</ul>
<p class="note">Отладчик работает с современным .NET: .NET Core и .NET 5 и новее. Hot Reload и отладка .NET Framework не поддерживаются.</p>
</section>

<section id="memory">
<h2>Расход памяти по строкам</h2>
<ol>
  <li>Соберите решение: значения привязываются к строкам той сборки, которая запущена.</li>
  <li>Включите <b>.NET | Show Allocations in Editor</b>. Тот же пункт есть в контекстном меню редактора файла C#.</li>
  <li>Запустите приложение через Run или Debug и откройте файл с кодом.</li>
</ol>
<p>У строк появятся байты и объекты в секунду, тип объекта и доля строки в общем расходе. У объявления метода, в котором память выделяют несколько строк,
   показывается их сумма. Полоса слева тем плотнее, чем больше доля строки.</p>
<p class="note">Значения получаются по выборке событий среды выполнения. Строка, которая выделяет мало, появляется не сразу и может пропадать.
   При первом включении плагин собирает вспомогательную программу: для этого нужны два пакета NuGet из кэша или из сети.</p>
</section>

<section id="settings">
<h2>Настройки</h2>
<p>Все страницы находятся в <b>Settings | .NET</b>. Названия параметров приведены по-русски, рядом серым — по-английски, как они выглядят,
   когда язык страниц английский. Язык выбирается на странице <a href="#settings-dotnet">.NET</a>, параметр «%(language)s».</p>
%(settings)s
</section>

<section id="files">
<h2>Где что хранится</h2>
%(files)s
</section>

<section id="trouble">
<h2>Если что-то не работает</h2>
%(trouble)s
<p>Если причина не нашлась, откройте <b>.NET | Plugin Logs</b>: это журнал плагина — что он запускал, что вернулось, что не удалось и почему,
по строке на событие (время, уровень, категория: <code>dotnet</code>, <code>roslyn</code>, <code>debugger</code>, <code>nuget</code>…), без записей самой IDE.
Кнопка «Warnings and Errors Only» оставляет только проблемы. Тот же журнал лежит в <code>~/idea-dotnet-logs/plugin</code>; <b>.NET | Open Logs Folder</b>
открывает папку, которую можно целиком приложить к сообщению об ошибке.</p>
</section>
''' % dict(
        required=tools("roslyn-language-server", "dotnet-debugger-dap"),
        tools_group=e(RU["settings.tools.group"]), install=e(RU["settings.tools.install"]), language=e(RU["settings.language"].rstrip(":")),
        settings=settings(),
        menu=simple_table("Пункт", "Что делает", [
            ("Build Solution, Rebuild Solution, Clean Solution", "Сборка, пересборка и очистка решения. Ошибки показываются деревом в окне Build."),
            ("Measure Build Performance", "Пересборка со сводкой MSBuild: самые медленные цели и задачи."),
            ("NuGet", "Подменю работы с пакетами: восстановление, управление пакетами решения, обновление всех пакетов, источники, журнал. "
                      "Быстрый список действий — <kbd>Alt+Shift+N</kbd>."),
            ("EF Core", "Подменю Entity Framework Core: миграции, обновление базы данных, SQL-скрипт, создание классов по существующей базе."),
            ("New .NET Project...", "Новый проект по шаблону <code>dotnet new</code> с параметрами шаблона."),
            ("Monitor .NET Process", "Окно .NET Monitor: процессор, память, сборка мусора, запросы и исключения работающего процесса."),
            ("Show Allocations in Editor", "Расход памяти по строкам кода, см. <a href=\"#memory\">раздел ниже</a>."),
            (".NET on This Machine...", "Установленные SDK и среды выполнения со статусом поддержки, <code>dotnet --info</code>, <code>global.json</code>."),
            ("C# Color Palette...", "Палитра C# в стиле Rider, Visual Studio, VS Code, Nord, Dracula, One Dark, Solarized или GitHub поверх текущей схемы: "
                                    "фон не меняется. Палитра применяется сразу при движении по списку, Esc возвращает прежнюю."),
            ("Format, Verify Formatting", "Форматирование проекта или решения и проверка, что всё отформатировано, как это делает сервер сборки."),
            ("Analyze Upgrade to Newer .NET...", "Анализ перехода на новую версию .NET: пакеты, свойства проекта и API, которые мешают переходу."),
            ("Insert New GUID", "Вставляет новый GUID в каждую позицию курсора."),
            ("Analyze .NET Stack Trace...", "Вставьте стек вызовов и переходите по его кадрам к коду."),
            ("Reload Solution, Reload Project", "Перечитывает решение или проект с диска. Нужно, когда файлы и папки изменены мимо IDE."),
            ("Welcome to C# Project Support", "Страница о возможностях плагина."),
            ("Plugin Documentation", "Эта страница."),
            ("Suggestion Statistics", "Как часто подсказки редактора показаны и приняты. Считается только на этой машине."),
            ("Plugin Logs", "Журнал плагина в окне: что запущено, что вернулось, что не удалось и почему. Только события плагина, без записей IDE."),
            ("Open Logs Folder, Show Debugger Logs", "Открывают папки журналов плагина и отладчика."),
            ("Trace Debugger Protocol", "Записывает обмен с отладчиком в следующих сеансах отладки. Нужно для сообщения об ошибке."),
        ]),
        popup=simple_table("Пункт", "Что делает", [
            ("Add", "Новый или существующий проект, папка решения, ссылка на проект, ссылка на сборку."),
            ("Rename Project...", "Переименовывает файл проекта и по желанию его папку; решение, ссылки других проектов и конфигурации запуска обновляются."),
            ("Properties...", "Целевые платформы, тип выходного файла, nullable, версия языка, предупреждения, сведения о пакете."),
            ("Edit Project File", "Открывает файл проекта или решения в редакторе."),
            ("Run Project, Debug Project", "Запуск и отладка выбранного проекта."),
            ("Build, Rebuild, Clean", "То же, что в меню .NET, но для выбранного проекта."),
            ("Manage NuGet Packages...", "Окно NuGet для выбранного проекта."),
            ("Run Tests with Coverage", "Тесты проекта со сбором покрытия."),
            ("Run MSBuild Target...", "Запуск произвольной цели MSBuild."),
            ("Convert to .slnx...", "Переводит решение в новый формат <code>.slnx</code>."),
            ("Remove from Solution...", "Убирает проект из решения, файлы остаются на диске."),
            ("Show All Files", "Показывает в дереве <code>bin</code>, <code>obj</code> и файлы проектов."),
        ]),
        windows=simple_table("Окно", "Что в нём", [
            ("Solution", "Вид окна Project: проекты решения, их зависимости по целевым платформам, файлы так, как их видит сборка."),
            ("Unit Tests", "Дерево тестов проекта и результаты прогонов: запуск, отладка, фильтр, повтор упавших."),
            (".NET Coverage", "Покрытие по файлам; в редакторе — полосы у строк, в дереве проекта — проценты."),
            ("NuGet", "Вкладки Packages, Sources, Folders и Log: пакеты решения или проекта, источники, папки кэша, журнал восстановления."),
            ("EF Core", "Миграции каждого <code>DbContext</code> со статусом в базе данных."),
            (".NET Monitor", "Графики процесса, дамп потоков, снимки кучи, дамп памяти."),
            ("Endpoints", "Маршруты minimal API и контроллеров с переходом к коду и запросом в один щелчок."),
        ]),
        editor=simple_table("Действие", "Как вызвать", [
            ("Принять серое продолжение строки", "<kbd>Tab</kbd>"),
            ("Список подсказок", "Появляется при наборе; <kbd>Ctrl+Space</kbd> вызывает его вручную. Регистр букв не важен."),
            ("Исправления и рефакторинги", "<kbd>Alt+Enter</kbd>; в списке есть Fix All для документа, проекта и решения."),
            ("Создание кода: конструктор, Equals, переопределения, тест", "<kbd>Alt+Insert</kbd>: список генераторов, как в Rider."),
            ("Refactor This: все рефакторинги у курсора одним списком", "<kbd>Ctrl+Alt+Shift+T</kbd>"),
            ("Navigate To: объявление, реализации, базовые символы, использования, тесты, IL", "<kbd>Ctrl+Shift+G</kbd>"),
            ("Переименование", "<kbd>Shift+F6</kbd>; файл переименовывается вместе с типом."),
            ("Переход к объявлению и реализациям", "<kbd>Ctrl+B</kbd>, <kbd>Ctrl+Alt+B</kbd>, щелчок с <kbd>Ctrl</kbd>."),
            ("Иерархия типов и вызовов", "<kbd>Ctrl+H</kbd>, <kbd>Ctrl+Alt+H</kbd>"),
            ("Сведения о параметрах и документация", "<kbd>Ctrl+P</kbd>, <kbd>Ctrl+Q</kbd>"),
            ("Быстрый список NuGet", "<kbd>Alt+Shift+N</kbd>"),
        ]) + '<p class="note">Сочетания клавиш приведены для раскладки Windows по умолчанию. Если в IDE выбрана другая раскладка, действуют её сочетания.</p>',
        files=simple_table("Что", "Где", [
            ("Настройки плагина на этой машине", "Файл <code>dotnet-support.xml</code> в папке настроек IDE."),
            ("Форматтер проекта", "<code>.idea/dotnet.xml</code>. Файл можно хранить в репозитории, чтобы команда форматировала одинаково."),
            ("Параметры сборки проекта", "Рабочие файлы проекта в <code>.idea</code>; в репозиторий не попадают."),
            ("Журналы", "<code>~/idea-dotnet-logs</code>: журнал плагина (<code>plugin</code>), команды <code>dotnet</code> с выводом (<code>commands</code>), журналы сборки, отладчика и сервера языка. Старше двух недель удаляются."),
            ("Кэши", "Папка <code>dotnet-support</code> в системной папке IDE: индекс библиотек, вспомогательные программы, кэш ответов сервера. "
                     "Удаление безопасно: всё создаётся заново."),
            ("Рядом с вашими проектами", "Плагин ничего не создаёт, кроме стандартной папки <code>.idea</code>."),
        ]),
        trouble=simple_table("Что видно", "Что сделать", [
            ("Нет ошибок компилятора и рефакторингов", "Проверьте значок сервера в статус-баре. Если сервер не найден, установите <code>roslyn-language-server</code> "
                                                        "на странице <a href=\"#settings-dotnet\">.NET</a>. Если он завис, выберите Restart."),
            ("Загружено не то решение", "Щёлкните значок сервера и выберите Select Solution."),
            ("Новый файл или папка не видны в дереве", "<b>.NET | Reload Solution</b> или Reload Project в контекстном меню проекта."),
            ("Кнопка Debug не запускает отладку", "Установите <code>dotnet-debugger-dap</code> на странице <a href=\"#settings-dotnet\">.NET</a>."),
            ("Сборка не находит SDK", "Откройте <b>.NET | .NET on This Machine...</b>: там видно, какой SDK требует <code>global.json</code> и установлен ли он."),
            ("Пакеты не восстанавливаются", "Откройте вкладку Log окна NuGet. Для закрытого источника включите «%s» на странице <a href=\"#settings-nuget\">NuGet</a>."
             % e(RU["nuget.interactive"])),
            ("Нет значений расхода памяти", "Проверьте, что пункт Show Allocations in Editor отмечен, решение собрано, а приложение запущено из IDE. "
                                           "Причина неудачи показывается уведомлением."),
        ]),
    )


TOC = [
    ("start", "Начало работы", []),
    ("menu", "Меню .NET", []),
    ("windows", "Окна", []),
    ("editor", "Редактор", []),
    ("run", "Запуск и отладка", []),
    ("memory", "Расход памяти", []),
    ("settings", "Настройки", [("settings-dotnet", ".NET"), ("settings-build", "Инструменты и сборка"), ("settings-nuget", "NuGet"),
                               ("settings-coverage", "Покрытие"), ("settings-debugger", "Отладчик"), ("settings-analysis", "Анализаторы и генераторы"),
                               ("settings-server", "Сервер языка"),
                               ("settings-style", "Стиль кода C#")]),
    ("files", "Где что хранится", []),
    ("trouble", "Если что-то не работает", []),
]


def toc():
    lines = ['<nav class="toc" aria-label="Содержание">', "<ul>"]
    for anchor, title, children in TOC:
        lines.append('<li><a href="#%s">%s</a>' % (anchor, e(title)))
        if children:
            lines.append("<ul>" + "".join('<li><a href="#%s">%s</a></li>' % (a, e(t)) for a, t in children) + "</ul>")
        lines.append("</li>")
    lines += ["</ul>", "</nav>"]
    return "\n".join(lines)


def shared_style():
    """The tokens, the base and the top bar of docs/demo.html: one look for both pages."""
    demo = (ROOT / "docs/demo.html").read_text(encoding="utf-8")
    start = demo.index("<style>") + len("<style>")
    end = demo.index("/* hero */")
    return demo[start:end].strip()


STYLE = '''
/* the documentation: the contents on the left, the text on the right */
.doc { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 56px; padding: 48px 0 96px; align-items: start; }
.toc { position: sticky; top: calc(var(--nav-h) + 24px); max-height: calc(100vh - var(--nav-h) - 48px); overflow-y: auto; font-size: .93rem; }
.toc ul { list-style: none; margin: 0; padding: 0; }
.toc ul ul { margin: 2px 0 8px 12px; border-left: 1px solid var(--line); }
.toc a { display: block; padding: 5px 12px; border-radius: 8px; color: var(--text-2); text-decoration: none; }
.toc ul ul a { font-size: .88rem; padding: 3px 12px; color: var(--text-3); }
.toc a:hover, .toc a.active { color: var(--text); background: color-mix(in srgb, var(--text) 9%, transparent); }
.doc main { min-width: 0; }
.doc p, .doc li, .doc td, .doc h3, .doc h4 { overflow-wrap: anywhere; }
.doc h1 { font-size: clamp(2rem, 4vw, 3rem); margin-bottom: 14px; }
.doc .lead { color: var(--text-2); font-size: 1.15rem; max-width: 46em; }
.doc section { padding-top: 56px; }
.doc h2 { font-size: clamp(1.5rem, 2.6vw, 2.1rem); padding-bottom: 14px; margin-bottom: 20px; border-bottom: 3px solid; border-image: var(--grad) 1; }
.doc h3 { font-size: 1.3rem; margin: 40px 0 8px; }
.doc h4 { font-size: 1rem; font-weight: 600; margin: 26px 0 0; color: var(--text); }
.doc h3 small, .doc h4 small, .doc td small { font-weight: 400; color: var(--text-3); }
.doc h3 small, .doc h4 small { font-size: .75em; margin-left: 8px; }
.doc td small { display: block; font-size: .85em; margin-top: 2px; }
.doc p, .doc li { color: var(--text-2); max-width: 52em; }
.doc p { margin: 10px 0 0; }
.doc ul, .doc ol { margin: 12px 0 0; padding-left: 22px; display: grid; gap: 8px; }
.doc b { color: var(--text); font-weight: 600; }
.doc a { color: var(--text); text-decoration-color: var(--violet); text-underline-offset: 3px; }
.doc .path { font-family: var(--mono); font-size: .85rem; color: var(--text-3); margin-top: 0; }
.doc .note { border-left: 3px solid var(--magenta); padding: 2px 0 2px 16px; color: var(--text-3); font-size: .95rem; margin-top: 18px; }
.table { margin-top: 14px; overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-size: .93rem; }
th, td { text-align: left; padding: 11px 18px 11px 0; border-bottom: 1px solid var(--line); vertical-align: top; color: var(--text-2); }
th { color: var(--text-3); font-weight: 500; font-size: .84rem; }
td:first-child { color: var(--text); width: 34%; min-width: 200px; }
td:first-child b { font-weight: 600; }
td:nth-child(2):not(:last-child) { width: 18%; min-width: 120px; }
footer { border-top: 1px solid var(--line); padding: 32px 0 48px; color: var(--text-3); font-size: .9rem; }
footer .wrap { display: flex; flex-wrap: wrap; gap: 12px 28px; align-items: center; }
footer a { color: var(--text-2); }
.nav li a.here { color: var(--text); background: color-mix(in srgb, var(--text) 10%, transparent); }
@media (max-width: 1000px) {
  .doc { grid-template-columns: minmax(0, 1fr); gap: 0; padding-top: 28px; }
  .toc { position: static; max-height: none; border: 1px solid var(--line); border-radius: 12px; padding: 10px; }
  .nav ul { display: flex; }
}
@media (max-width: 560px) {
  .nav li a.here { display: none; }
  td:first-child, td:nth-child(2):not(:last-child) { min-width: 0; }
  th, td { padding-right: 12px; }
}
@media (prefers-reduced-motion: reduce) { html { scroll-behavior: auto; } }
'''

SCRIPT = '''
(function () {
  var root = document.documentElement;
  var theme = document.getElementById('themeToggle');
  try { var saved = localStorage.getItem('demo-theme'); if (saved && !root.getAttribute('data-host')) root.setAttribute('data-theme', saved); } catch (err) {}
  theme.addEventListener('click', function () {
    var current = root.getAttribute('data-theme');
    var dark = current === 'dark' || (!current && !window.matchMedia('(prefers-color-scheme: light)').matches);
    var next = dark ? 'light' : 'dark';
    root.setAttribute('data-theme', next);
    try { localStorage.setItem('demo-theme', next); } catch (err) {}
  });

  // inside the IDE a link to a place of the page is scrolled to by hand
  document.addEventListener('click', function (e) {
    var link = e.target.closest ? e.target.closest('a[href^="#"]') : null;
    if (!link) return;
    var target = document.getElementById(link.getAttribute('href').slice(1));
    if (!target) return;
    e.preventDefault();
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  });
  // opened at a place of the page (from the settings): the place is on the screen
  if (location.hash) {
    var place = document.getElementById(location.hash.slice(1));
    if (place) setTimeout(function () { place.scrollIntoView({ block: 'start' }); }, 0);
  }

  if (!('IntersectionObserver' in window)) return;
  var links = Array.prototype.slice.call(document.querySelectorAll('.toc a'));
  var places = links.map(function (a) { return document.getElementById(a.getAttribute('href').slice(1)); }).filter(Boolean);
  var observer = new IntersectionObserver(function (entries) {
    entries.forEach(function (entry) {
      if (!entry.isIntersecting) return;
      links.forEach(function (a) { a.classList.toggle('active', a.getAttribute('href') === '#' + entry.target.id); });
    });
  }, { rootMargin: '-15% 0px -75% 0px' });
  places.forEach(function (place) { observer.observe(place); });
})();
'''

PAGE = '''<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>C# Project Support: документация</title>
<meta name="description" content="Документация плагина C# Project Support: начало работы, меню и окна, все страницы настроек, решение проблем.">
<meta name="color-scheme" content="dark light">
<!-- Generated by tools/guide/generate.py: edit the script, not this file. Self-contained, as docs/demo.html, whose look it takes:
     the same file is shown inside the IDE (welcome/guide.html of the plugin). Links into the repository carry the class "repo". -->
<style>
%(shared)s
%(style)s
</style>
</head>
<body>
<a class="skip" href="#main">К содержимому</a>

<header class="nav">
  <div class="wrap">
    <a class="brand" href="demo.html"><span class="mark" aria-hidden="true">C#</span>C# Project Support</a>
    <ul id="navLinks">
      <li><a href="demo.html">О плагине</a></li>
      <li><a class="here" href="#top" aria-current="page">Документация</a></li>
    </ul>
    <button class="icon-btn" id="themeToggle" aria-label="Переключить тему" title="Переключить тему">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>
    </button>
  </div>
</header>

<div class="wrap doc" id="top">
%(toc)s
<main id="main">
<h1>Документация</h1>
<p class="lead">Как работать с плагином: с чего начать, что находится в меню и окнах, что означает каждый параметр настроек и что делать, если что-то не работает.</p>
%(content)s
</main>
</div>

<footer>
  <div class="wrap">
    <span>C# Project Support, <code>io.github.dotnetsupport</code></span>
    <span>Редакция страницы %(revision)s</span>
    <a href="demo.html">О плагине</a>
    <a class="repo" href="../COMPARE.md">Полное сравнение с Rider</a>
    <a class="repo" href="../ROADMAP.md">Roadmap</a>
  </div>
</footer>

<script>%(script)s</script>
</body>
</html>
'''

if __name__ == "__main__":
    page = PAGE % dict(shared=shared_style(), style=STYLE.strip(), toc=toc(), content=content().strip(), revision=REVISION, script=SCRIPT)
    target = ROOT / "docs/guide.html"
    target.write_text(page, encoding="utf-8", newline="\n")
    print("written", target.relative_to(ROOT), len(page), "characters")
