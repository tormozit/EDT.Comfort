# Project rules

Единый файл всех правил репозитория для любого ИИ-агента (Claude Code, Cursor и т.п.). Это единственный источник — не дублировать в других местах.

## Базовое

- Язык пользователя: русский. Отвечать в чате по-русски (не только код-комментарии).
- Java 17: `C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64` (JAVA_HOME).
- Бандлы EDT: `C:\VC\EDT-plugin-WS\.metadata\.plugins`.
- **Maven запрещён.** Сборку делает пользователь, не агент.
- Временные файлы — только `.tmp/` (логи, снимки, пробы, артефакты задачи). **Исключение:** распаковка EDT/Eclipse JAR и `javap` по ним — только `.tmp/bundles/<bundle-id>/` (см. «EDT workspace»).
- При первой же неудаче — добавлять временное безусловное логирование в отдельный файл (см. ниже).

## Снимки исходников (обязательны перед каждой правкой)

Перед **каждой** правкой: **снимок → правка**, без исключений по «мелочи». В снимке — только файлы следующего шага (целиком); повторная правка того же файла — новый снимок.

Хранение:

```text
.tmp\chat-snapshots\<chat-id>\
  manifest.jsonl
  001-<метка>\plugin\src\...   (зеркало путей от корня)
```

`<chat-id>` — UUID транскрипта или `session-<YYYY-MM-DD-HHmm>`.

Область: снимать `plugin/src/**`, `plugin/plugin.xml`, `META-INF/**`, `launch/**` и т.п. Не снимать `plugin/bin/`.

Метки: `before-first-edit` | `before-edit` | `before-refactor` / `before-rollback` / `before-retry-N` | `golden`. `manifest.jsonl` — одна JSON-строка на снимок, нумерация `001`, `002`, …

Отключение и откат: снимки отключаются только по явной фразе в чате («без снимков» и т.п.). Откат — копировать из `.tmp/chat-snapshots/…`, не из патчей транскрипта.

Запрещено: пропускать снимок; лишние файлы в снимке; править до снимка; класть снимки в `plugin/` или коммитить; удалять чужие `<chat-id>`.

## Откат в контексте задачи

«Откати» / «верни как было» — только правки **этого чата**, если не сказано иное.

- Откатывать только файлы/фрагменты из **этой** беседы; не трогать параллельные задачи в тех же файлах.
- Точечное редактирование, не слепой `git restore` целого файла; при смешанных правках — только свои строки; если граница неясна — спросить.
- Широкий откат (`git checkout`, всё незакоммиченное) — только по явной просьбе.

Примеры: «откати инспектор» → только `DebugInspector*` из этого чата; «откати всё» → git с предупреждением.

## Согласование эвристик

Перед **негарантированными** путями (догадка, обход, неполное знание) — **согласовать с пользователем**.

**Эвристика:** рефлексия/хук без подтверждения в EDT/репо; подмена поведения «наугад»; недокументированные API; обход с неясным исходом; предположения о бандлах/private-полях; «может сработать, гарантии нет».

**Не эвристика:** принятый паттерн репо; API из кода/JAR в `.tmp/`; явная инструкция пользователя; стиль/CRLF/правила.

Порядок: кратко проблема → 1–3 варианта (риски, проверка) → ждать ответа → реализовать. Выбор в том же сообщении = согласование. Не цепочка A→B→C без обсуждения; не откладывать вопрос до большого диффа.

Диагностические эвристики (timing wraps, временное логирование, выбор порогов для инструментализации) — пре-одобрены, без запроса на каждый случай. Эвристики, меняющие runtime-поведение (не только наблюдение), — всегда спрашивать.

Перед повтором после неудачи — сначала смотреть логи предыдущей попытки.

## Подпись «(Комфорт)» в UI плагина

`Global.pluginSignForTooltip()` / `Global.withPluginWindowTitle()` — не дублировать « (Комфорт)» вручную.


| Где                                        | Заголовок окна | Тултип/description | label/name/setText |
| ------------------------------------------ | -------------- | ------------------ | ------------------ |
| Кастомный Shell/Dialog плагина             | с суффиксом    | без                | без                |
| Меню EDT, plugin.xml, хуки view/редакторов | —              | с суффиксом        | без                |
| Внутри окна коллекции/скелета              | —              | без                | без                |
| Eclipse View, Preferences, тосты, окна EDT | без            | по контексту       | без                |


Примеры: `shell.setText(Global.withPluginWindowTitle("Коллекция …"))`; `item.setToolTipText("…" + Global.pluginSignForTooltip())` вне окна; внутри — `setToolTipText("Видимость и порядок колонок")`.

## Ширина подсказок (тултипов) — через `TooltipText.wrap`

Любой текст подсказки, который плагин ставит сам (`setToolTipText` и аналоги), пропускать через `TooltipText.wrap(control, text)` — он разбивает строки по ширине `TooltipText.MAX_WIDTH_PX` (500 px) фактическим шрифтом контрола. Нативная подсказка Windows переносов не делает: подсказка в два-три предложения растягивается на весь экран.

**Исключение:** подсказки, показывающие фрагменты кода (модуль, запрос, стек, содержимое ячейки с кодом). Там перенос ломает форматирование — такие подсказки оставлять как есть.

## Фон изменений в тексте

В подсказке Quick Diff на полосе номеров фон текста не красить. В начале каждой добавленной/изменённой/удалённой строки — ячейка цветом маркера из **Параметры → … → Выделение изменений**. Добавление/изменение — как клетка на полосе номеров (`DiffPainter.getShadedColor`, масштаб 0.6). Удаление — слабее сырого маркера, но заметнее (смешение с `COLOR_LIST_BACKGROUND` ~0.75, не 0.96). Не подсвечивать фрагменты и не брать палитру окна сравнения.

## Один потребитель — вложенный класс

В `plugin/src/tormozit/` тип с **одним** потребителем (без `plugin.xml`) — `**private static` вложенный класс**, не отдельный `.java`. Импорты — во внешний класс. Цепочки: сначала «лист», потом родитель.

Отдельный файл: точка входа OSGi (`plugin.xml`); ≥2 потребителя; публичный API бандла; ~800+ строк вложенного кода (по согласованию). Второй потребитель появился — вынести в `.java`.

Эталоны: `InspectorRegistry` → `BslInspectSupport.InspectorRegistry`; `RecentPlacesDialog` → `RecentPlacesHandler.RecentPlacesDialog`; `DebugCollectionSplitTable` → `DebugCollectionWindow.DebugCollectionSplitTable`.

Запрещено: `FooHelper.java` только для `FooHook`; файлы «на вырост»; `*Support`/`*Util` без второго потребителя.

## Активный проект EDT

Разово: `Global.getActiveProject(part/page, showMessage)` — не дублировать обход навигатора/`IFile.getProject()`. `showMessage=true` только для тоста «Нет активного проекта». Эталон: `Global.java`.

Реактивный UI: `ActiveProjectTracker` — `bootstrapPage`, `addListener`/`removeListener`, `peek` / `resolveContextProject`. Не свои `IPartListener2` для проекта. `Global.isNavigatorPart` — проверка навигатора.

Запрещено: проект только из `getActiveEditor()` в обход Global/tracker; прямой обход навигатора где хватает Global; `getActiveProject(page)` при фокусе в навигаторе + открытом редакторе; дублировать window-level хуки.

Исключения: запись с известным `projectName`; явный `IProject`/`IFile`; `ApplicationsViewHook.applyApplicationsProject`.

## Термины в пользовательских текстах

Запрещено в пользовательских текстах (UI, `docs/`, release notes, тосты, подписи команд) использовать термины **«чекбокс»** (правильно **«пометка»** или **флажок**) и **«hover-подсказка»** (правильно **«подсказка при наведении»**).

Нельзя называть элементы интерфейса внутренними именами, которых пользователь на экране не видит — только надписями самой EDT. Например, вместо **staged / unstaged** (имена полей `stagedViewer`/`unstagedViewer` в коде) в документации писать **«Индексированные изменения»** и **«Неиндексированные изменения»** — так эти списки подписаны в панели «Индексирование Git».

## Документация — только про поведение плагина, не про его баги

В `docs/` **не описывать исправления багов, внесённых самим плагином** (регрессии, недоделки своих же механизмов). Пользователю нужно текущее поведение, а не история наших поломок. Пример-антипаттерн: раздел «Исправления» с «плагин больше не ломает создание остановки по ошибке».

Описывать: возможности и их текущее поведение; обход/исправление багов **самой EDT** — как свойство плагина.

## Кириллица в строках — без `\uXXXX`-эскейпов

В `plugin/src/` не использовать `А…` для русского текста (UTF-8, см. `build.properties`). Писать кириллицу напрямую; при правке заменять существующие `\u04..`.

Допустимо:  ``, `\t`, `\n`; escape по требованию внешнего API; генерируемый код. `"×"`, `"—"`, английские строки — норма.

## Разделители строк — CRLF (Windows)

Файлы на диске — **CRLF**. В patch/write-тулах — `\n` между строками (не `\r\n`, не `\n\n` между строками кода).

Поломка: «шахматка» пустых строк, `0D 0D 0A` / `\r\r\n` — не копировать в патч, сначала ремонт. Тот же скрипт приводит LF/mixed → CRLF (токены Read не растут).

Git hook: см. `.cursor/scripts/install-git-eol-hook.ps1`, проверка — `check-double-eol.ps1`.

**После завершения задачи** для тронутых файлов в `plugin/src/`**, `plugin/plugin.xml`, `META-INF/**` (а также `.cursor/**`, `launch/**`, `site/**`):

```powershell
powershell -NoProfile -File "C:\VC\EDT.Comfort\.cursor\scripts\repair-double-eol.ps1" -Path "C:\VC\EDT.Comfort\plugin\src\tormozit\Foo.java"
```

При `REPAIRED` (шахматка/spacing) — проверить плотность строк через `Read`. Сообщение `LF/mixed -> CRLF` — только нормализация EOL, плотность не менялась. `plugin/.gitattributes`: `text=auto`, локально CRLF.

## Логирование плагина Комфорт

Два режима — **не смешивать** без явной команды пользователя.

### 1. Постоянные логи (для других РМ)

Только журнал «Комфорт» (`Global.log` / `GlobalLog`) при `Global.isLogEnabled()` (Параметры → Комфорт → «Общее логирование»). В модулях — `*Debug` с `TAG`, проверка флажка; эталон: `DebugInspectorDebug`.

Запрещено: `GlobalLog.append` в обход флажка; `System.out/err` без договорённости; JVM `-Dtormozit.*`; файлы/NDJSON/Eclipse Error Log как постоянный канал; логи «всегда».

`problem(msg)` — префикс `[!]`; `step(phase, detail)` — через `Global.log`. Новый модуль: `FooDebug`, `isEnabled()` → `Global.isLogEnabled()`, запись через `Global.log(TAG, …)`.

### 2. Временные логи (отладочное РМ, текущая сессия)

Любой приёмник: `Global.tempLog`, `Global.log`, `debug-*.log`, NDJSON, `.tmp/`, `// #region agent log`. Не считается нарушением п. 1. **Не удалять** и не «приводить к comfort-logging» без команды. Снять временную инструментализацию — только после подтверждения фикса.

Перед повторной попыткой после неудачи — проанализировать логи предыдущей.

**Диагностическое/временное логирование должно быть БЕЗУСЛОВНЫМ** — никакого `Global.isLogEnabled()`, никакого порога по severity/времени, ничего, что может молча дать пустой лог-файл. Писать каждый вызов, всегда, в момент срабатывания инструментализации. Пороговое значение можно записывать как данные в строке лога (например, поле `"slow": true/false`), но оно никогда не решает, писать строку или нет.

`Global.tempLog(topic, text)` — стандартный приёмник временных логов: `Global.tempLog("тема", "текст")` — одна строка с меткой времени в файл `.tmp/temp-logs/<тема>.log`. Каждая тема — отдельный файл. Не зависит от флажка «Общее логирование» и не пишет в журнал «Комфорт» — независимый канал наравне с остальными приёмниками временных логов. Вся папка `.tmp/temp-logs/` очищается автоматически при каждом старте плагина.

## PDE launch и OSGi-профиль

Инцидент 06.2026: второй launch сломал `Eclipse Application` (`BundleException`, пропал `org.eclipse.jdt.core.compiler.batch`).

Причины: `clearConfig=true` / `generateProfile=true`; второй launch с тем же `configLocation`; shared `.launch` в `launch/`; restore без правки launch; `attrib +R` на рабочих копиях в `.metadata`.

Запрещено: менять основной `Eclipse Application.launch` без просьбы; второй launch с тем же `configLocation`; `clearConfig`/`generateProfile` для экспериментов; активные `.launch` в репо; `+R` на `.metadata` (только эталон в `launch/backup/`); restore только `bundles.info`.

## EDT workspace

### Бандлы EDT

Не искать в `Program Files\1C\…` без явной просьбы. Target platform:

- Workspace: `C:\VC\EDT-plugin-WS`
- JAR: `C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.pde.core\.bundle_pool\plugins\` (бандлы `21.0.0`)

EDT 2026.1 (бандлы `23.0.1`, API местами несовместим): JAR в `C:\Users\Сергей\.p2\pool\plugins\`, `<bundle-id>` кэша с суффиксом версии — `platform-services-core-23`.

### Распаковка и разбор

Два разных места в `.tmp/` — не смешивать:

| Что | Куда |
| --- | --- |
| Распаковка EDT/Eclipse JAR, классы/`META-INF` из бандла, `*.javap.txt` по ним | **только** `.tmp/bundles/<bundle-id>/` |
| Всё остальное временное (снимки, `temp-logs`, пробы кода плагина, заметки, выгрузки NLS и т.п.) | `.tmp/` как удобно (в т.ч. `.tmp/<задача>/`) |

**Не** распаковывать в корень репозитория / `plugin/`. Если `jar xf` положил `com/`/`org/` в корень — удалить.

`javap` только: `C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64\bin\javap.exe`

### Общий кэш распакованных бандлов

Только для кэша бандлов (строка таблицы выше). `.tmp/bundles/<bundle-id>/` — единый переиспользуемый кэш на бандл, общий для всех задач и агентов. `<bundle-id>` — короткое стабильное имя бандла (напр. `mdui`, `lwt`, `search-ui`, `jdt-ui`), **не** имя задачи / фичи / чата.

Перед каждым `jar xf` / `unzip` / первым `javap` по классу из бандла:

1. Определить `<bundle-id>` целевого бандла.
2. Проверить `.tmp/bundles/<bundle-id>/` — если уже есть нужное, использовать как есть (`javap` / `jar tf` / grep), **не** распаковывать заново и **не** дублировать под другим именем.
3. Если папки нет — найти JAR в `.bundle_pool` → `jar xf` с `working_directory` = `.tmp/bundles/<bundle-id>/`.
4. `*.javap.txt` и прочие продукты разбора **этого** бандла — в ту же папку.

**Запрещено** (именно для распаковки бандлов, не для прочих артефактов в `.tmp/`):

- класть `com/` / `org/` / `META-INF/` из EDT/Eclipse JAR в `.tmp/spelling-*`, `.tmp/search-*`, `.tmp/jdt-*`, `.tmp/bsl-hover-*`, `.tmp/<имя-задачи>/` и т.п.
- дублировать уже существующий `.tmp/bundles/<bundle-id>/` под другим именем

При находке такой распаковки вне `bundles/` — перенести нужное в `.tmp/bundles/<bundle-id>/` и удалить лишнее.

### Репозиторий и сборка

Исходники: `plugin\`. Запуск — PDE workspace `EDT-plugin-WS`. **Maven запрещён** — сборка в PDE; после правок сообщить о пересборке, не запускать `mvn`.

## Таблицы в формах (плагин Комфорт)

Для **новых** и дорабатываемых `Table` / многоколоночных `Tree` в кастомных окнах — единое поведение. Среда: Windows + SWT.

Обязательно:

1. **Клик и выбор:** ЛКМ в любой колонке → строка + `activeColumn` (`getItem(Point)` + `columnAt` по `getBounds`); `setSelection` + перерисовка.
2. **Копирование:** Ctrl+C / «Копировать» — только текст активной ячейки (`getCellDisplayText` / `getText(activeColumn)`). В `FormTableInteraction.install()` уже вызывается `CopyCommandSupport.wireCopyOverride` (Win32: Ctrl+C не доходит до KeyDown). **Не** дублировать ручной `wireCopyOverride` и **не** полагаться только на `SWT.KeyDown`. Для EDT-инспектора (Tree) — подмена Copy как в `DebugInspectorTreeEnhancement.hookGlobalCopyAction()`. **Любой новый `Table` / `List` / `Tree` в `plugin/src` — в том же месте создания `CopyCommandSupport.wireCopyOverride(control)`, не ждать просьбы.** Ни `List`, ни `Table` в редакторе EDT сами не копируют: Ctrl+C забирает global Copy части. `wireCopyOverride` подменяет его через `IActionBars`. Проверка: фокус на виджете → Ctrl+C → в буфере текст выделения.
3. **Подсветка строки/ячейки:** `EraseItem` — фон строки (`rowSelectionBackground`), активной ячейки темнее (`activeCellBackground`); `PaintItem` — рамка по `getBounds(activeColumn)`; при `FocusIn`/`FocusOut` — сброс кэша цветов.
4. **Заголовок колонки:** accent 2 px снизу активной колонки; линия 1 px под шапкой на ширину клиента; overlay `Canvas` на `tableStack` (`setLayout(null)`), таблица в `columnHost` + `TableColumnLayout` — см. `FormTableInteraction` (`headerSeparator` + `headerHighlight`). Accent над разделителем; scroll не сбрасывает `activeColumn`; при drag-resize колонки — accent на паузе (`SWT.Resize`).
5. **Порядок колонок:** `setMoveable(true)` в `FormTableInteraction`; opt-out коллекции — `setColumnReorderEnabled(false)`. После reorder `activeColumn` — ссылка на `TableColumn`; persist — `FormTableColumnOrder` + `IDialogSettings` (`"0,2,1,3"`). Load до `install()`, save в `close()`.

Эталоны:


| Контрол           | Класс                                                          |
| ----------------- | -------------------------------------------------------------- |
| Table (диалоги)   | `FormTableInteraction` + `tableStack`                          |
| Table (коллекция) | `DebugCollectionTableInteraction` + `DebugCollectionTableHost` |
| Tree (инспектор)  | `DebugInspectorTreeEnhancement`                                |


Окна: `DebugCollectionWindow`, `DebugCollectionSkeletonWindow`.

Исключения: штатная таблица EDT — не ломать UX; пикер одной сущности — достаточно подсветки строки; без заголовков (`setHeaderVisible(false)`) — п. 4 не применяется.

Запрещено: копировать строку/несколько колонок при выделенной одной ячейке; выбор только по первой колонке при `FULL_SELECTION`; штатный clipboard EDT без перехвата; дублировать логику в каждом окне; overlay в `GridLayout`/`SashForm` напрямую.

Фильтр коллекции: `DebugCollectionFilterEraseSupport` (`filterSkipItem`).

## Сетка (разделительные линии) — только через `ThemeAwareColors`

`setLinesVisible(true)` напрямую **не вызывать**. Нативные линии SWT рисует системными цветами Windows (`DrawEdge`/`BDR_SUNKENINNER`), от темы Eclipse они не зависят: в тёмной теме получается слишком контрастная сетка, которая к тому же исчезает после первой перерисовки нашим owner-draw — то есть живёт недолго и непредсказуемо.


| Виджет | Вызов |
| --- | --- |
| Таблица/дерево, которое создаёт сам плагин | `ThemeAwareColors.applyGridLines(control)` — в светлой теме включает сетку, в тёмной выключает |
| Штатный список EDT, который плагин только дополняет | `ThemeAwareColors.hideGridLinesInDarkTheme(control)` — гасит в тёмной, светлую не трогает |


Исключение: виджет намеренно повторяет вид соседнего штатного (`ComfortKeysPreferences.createLocalConflictViewer` — `referenceTable.getLinesVisible()`).

## Нативное выделение поверх нашей отрисовки строки

Если в `SWT.EraseItem` мы сами заливаем фон текущей строки/активной ячейки, а в списке есть подсветка вхождений фильтра — там же снимать `SWT.SELECTED` и `SWT.HOT` **в обеих темах**, не только в тёмной. С оставшимся `SELECTED` Windows дорисовывает своё выделение поверх (в колонке с текстом — инверсия фон/шрифт), а `StyledCellLabelProvider.useColors()` считает ячейку выделенной и вырезает цвета `StyleRange` — подсветка пропадает именно на текущей строке. Провайдер с подсветкой при этом всё равно создавать с `COLORS_ON_SELECTION` (см. раздел про `SelectionAwareStyledCellLabelProvider`).

Эталон: `GitStagingFilterHook.GitStagingTreeInteraction.onEraseItem` + `GitStagingLabelProvider`.

Где подсветки нет, `SELECTED` исторически снимается только в тёмной теме — в светлой нативное выделение даёт привычный голубой оттенок строки (`FormTreeInteraction`, `FormTableInteraction`). Менять это — отдельная задача, не побочно.

## Подключение фильтра (SearchBox/FilterInputBox) — история обязательна и персистентна

При любом подключении многословного фильтра ({@link SmartMatcher}) к штатному или новому `SearchBox` — история поиска **обязательна** и должна **переживать закрытие окна/диалога** (`ScopedPreferenceStore`, не память процесса). Голый `new InMemorySearchHistory()` — история живёт только пока открыт текущий диалог, теряется при закрытии.

Обязательно одно из:

- Новый `SearchBox` создаётся с нуля → `FilterInputBox.create(...)` / готовая фабрика `FilterInputBox.forXxx(...)`.
- Штатный `Text` заменяется на `SearchBox` → `FilterInputBox.replacePatternText(oldText, scope, onSearch)`.
- Штатный `SearchBox` уже есть, меняется только слушатель/фильтрация (наш случай чаще всего) → `FilterInputBox.attachHistory(searchBox, scope)` — **не** `searchBox.setHistory(new InMemorySearchHistory())` и не оставлять штатную историю, если она привязана к объекту, который патч выводит из игры.

**Ширина поля фильтра — compact, всегда.** Не растягивать `SearchBox` / `FilterInputBox` на всю строку (`grab(true)` / `FILL` / `GridDataFactory.fillDefaults().grab(true, false)`). Максимум {@link FilterInputBox#MAX_WIDTH} (300 px), `grab=false`, выравнивание в начале строки. Новый `SearchBox` — `FilterInputBox.create` / `compactLayoutData`. Штатный `SearchBox` — `attachHistory` сам вызывает `applyCompactLayout` (если родитель `GridLayout`). Соседи справа (флажок, кнопки) — сразу у поля, не через пустоту на ширину секции. Исключение — только по явной просьбе в чате. Сейчас: страница «Валидация» параметров проекта — `attachHistoryKeepLayout` (штатный `SearchBox` тянется в строке с тулбаром).

Для нового места — завести новую константу в `FilterInputBox.Scope` (ключи `comfort.<место>.filter.history.count` / `comfort.<место>.filter.history.`), при необходимости — `case XXX -> throw new IllegalStateException(...)` в `createForScope`, если `SearchBox` штатный и через `create()` не создаётся (см. `RIGHTS_DIALOG`/`FILTER_BY_SUBSYSTEMS`/`INFOBASES`/`VALIDATION_CHECKS`).

Проверка перед сдачей: закрыть окно/диалог с фильтром → открыть заново → набрать первую букву запроса → всплывает история прошлых запросов (Ctrl+↓ или клик по стрелке). Если история пуста после переоткрытия — фильтр подключён неправильно.

При добавлении фильтра по подстроке — окрашивать его вхождения (`SmartMatcher.getHighlightRanges` + `SmartMatchHighlight.styler`, как в `ObjectSetsView.NameLabelProvider`). **Для колонки с такой подсветкой — только `SelectionAwareStyledCellLabelProvider`, НИКОГДА штатный `DelegatingStyledCellLabelProvider`** (см. класс-javadoc `SelectionAwareStyledCellLabelProvider`): у штатного нет способа передать `StyledCellLabelProvider.COLORS_ON_SELECTION`, и без этого флага JFace намеренно игнорирует цвета `StyleRange` на ВЫДЕЛЕННЫХ строках — подсветка вхождений пропадает именно на активной строке, независимо от `setOwnerDrawColumns` (тот нужен отдельно, для перерисовки самим `FormTableInteraction`, но саму проблему цвета не решает). Проверка перед сдачей: набрать фильтр → стрелками пройтись по всем видимым строкам → подсветка должна быть видна и на активной (выделенной) строке тоже, не только на невыделенных.

## SWT: Ctrl+<буква> не долетает до KeyDown (нативный акселератор Win32)

Повторяется (`KeyBindingToastHook`/Ctrl+Shift+F, `PreferenceSearchFilterAugmenter.wireTreeCopy`/Ctrl+C): `SWT.KeyDown`, даже `Display.addFilter(SWT.KeyDown, …)`, не видит букву при зажатом Ctrl (только «чистые» модификаторы) — если сочетание совпадает с нативным Win32-акселератором меню (в т.ч. штатный Edit → Copy), Windows транслирует его в `WM_COMMAND` до создания SWT-события. Это системное ограничение, не баг конкретного хука — доп. диагностика через SWT-хуки бесполезна.

**Решение:** перехватывать команду, не клавишу. Для копирования из наших `Table`/`List`/`Tree` — только `CopyCommandSupport.wireCopyOverride` (сразу при создании виджета). В диалоге без своего Copy хватает слушателя `org.eclipse.ui.edit.copy`; **внутри редактора/View этого мало** — штатный обработчик Copy выполняется и перезаписывает буфер, поэтому `wireCopyOverride` дополнительно подменяет global Copy через `IActionBars` (как `CompareConfigMenuHook.installCopyActionOverride`). Не писать свой `setGlobalActionHandler` для обычного текста выделения. Не рассчитывать, что Win32-`List` «сам скопирует» — в редакторе EDT не копирует. Для инспектора с чужим `globalActions` — `DebugInspectorTreeEnhancement.hookGlobalCopyAction()`. Для прочих Ctrl+буква (не Copy) — `ICommandService.addExecutionListener`, эталон `KeyBindingToastHook`.

## SWT: клавиши в StyledText — только `addVerifyKeyListener`

Свою обработку клавиш `StyledText` делает во внутреннем слушателе `SWT.KeyDown`, добавленном при создании виджета — раньше нашего. К нашему `SWT.KeyDown` виджет уже отработал (на Tab выделение заменено табуляцией), `doit = false` бесполезен. Перехват — `addVerifyKeyListener` (`ST.VerifyKey`, до обработки); обход по Tab гасится отдельно на `SWT.Traverse`. Эталон: `TextMergeEditorHook.MergeResultEditorKeys`.

Диагностика: «состояние уже изменено» в позднем слушателе — про порядок слушателей, а не про бездействие пользователя. Для `Ctrl+<буква>` этого мало, см. раздел выше.

## Каретка редактора: виджет ≠ модель (folding)

В BSL-редакторе EDT две системы координат. Без свёрток они совпадают — баг молчит. Со свёртками выше каретки виджетный офсет меньше модельного на сумму скрытого текста (типично тысячи символов: `2718` vs `14526`).

| Координаты | Откуда | Для чего |
| ---------- | ------ | -------- |
| **Виджет** (видимый текст) | `StyledText.getCaretOffset()`, `getLocationAtOffset`, `getLineAtOffset` | Только API самого `StyledText` (экран, линия виджета, `setCaretOffset`) |
| **Модель** (весь документ) | `ITextViewer.getSelectedRange()`, `DocumentEvent.getOffset()`, `ContentAssistant` `invocationOffset`/`filterOffset`, `IDocument.get/getChar` | Документ, AST, префикс, LinkedMode, «Родитель:», сравнение каретки с offset события |

`StyledText.getCaretOffset()` **нельзя** передавать в `IDocument`, Xtext/AST, `findMemberAccessDot`, сравнение с `DocumentEvent.getOffset()` / `desiredCaret` LinkedMode, чтение префикса строки.

Перевод: `ITextViewerExtension5.widgetOffset2ModelOffset`. Эталоны: `SmartContentAssistProcessor.resolveWidgetCaret` / `widgetToModelOffset`, `ContentAssistSessionReloader.modelCaretOffset()`. Новый код каретки для документа — только через них, не копировать `getTextWidget().getCaretOffset()`.

Инциденты: issue 278 (автооткрытие), подсказка параметров после вставки (виджет `2718` vs модель `14526`), футер «Родитель:» (виджет попадал в чужой идентификатор).

## OpenHelper.openEditor(EObject, EStructuralFeature) — feature не «активировать свойство»

`feature` в `OpenHelper.openEditor(...)` — не «какое свойство выделить в Свойствах» и не `eContainingFeature()`. Декомпиляция `getFile()`: `feature` используется только как резерв для поиска BSL-модуля — `object.eGet(feature)`, и если результат `instanceof Module`, открывается его файл (для команд/общих модулей). Иначе — `IllegalArgumentException: The feature 'X' is not a valid feature`.

**Для активации произвольного EObject — 1-arg `openEditor(EObject)`, без feature.** Эталон: `CompareConfigOpenObjectHandler.openInEditor()`.

## Активация поля AEF (фокус ввода) — только `AefFieldFocus`

Фокус в поле AEF (панель «Свойства», редакторы МД и т.п.) — `AefFieldFocus.focusComponent(scene, component)`, не дублировать обход `viewModelToView`/`getNativeControl`. Детали механики и неработающие пути (`ClientFocusEvent`, `setSelection`) — в javadoc класса.

## Комфорт-подменю (сортировка)

Использовать `ComfortSubmenuHelper.createSortedMenuItem` везде, где элементы добавляются в подменю «Комфорт»; несколько хуков могут разделять один и тот же экземпляр подменю.
