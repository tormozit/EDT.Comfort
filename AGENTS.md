# Project rules

Единый файл всех правил репозитория для любого ИИ-агента (Claude Code, Cursor и т.п.). Это единственный источник — не дублировать в других местах.

## Базовое

- Язык пользователя: русский. Отвечать в чате по-русски (не только код-комментарии).
- Java 17: `C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64` (JAVA_HOME).
- Бандлы EDT: `C:\VC\EDT-plugin-WS\.metadata\.plugins`.
- **Maven запрещён.** Сборку делает пользователь, не агент.
- Временные файлы — только `.tmp/`.
- При первой же неудаче — добавлять временное безусловное логирование в отдельный файл (см. ниже).

## Снимки исходников (обязательны перед каждой правкой)

Перед **каждой** правкой: **снимок → правка**, без исключений по «мелочи». В снимке — только файлы следующего шага (целиком); повторная правка того же файла — новый снимок.

Хранение:

```text
C:\VC\EDT.Comfort\.tmp\chat-snapshots\<chat-id>\
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

## Метка времени в ответах чата

В **начале финального** текстового ответа — `DD-HH:MM:SS` + пробел + текст на **одной строке** (день 01–31, время 24ч). Только в сообщениях пользователю, не в вызовах инструментов.

Время — **локальное**, получать через shell перед ответом (`date +%d-%H:%M:%S` / `powershell -NoProfile -Command "Get-Date -Format 'dd-HH:mm:ss'"`), можно в конец другой команды в том же turn. Не угадывать, не брать из системного контекста дат. Не упоминать получение времени в ответе. Метку не повторять; списки — со второй строки.

## Подпись «(Комфорт)» в UI плагина

`Global.pluginSignForTooltip()` / `Global.withPluginWindowTitle()` — не дублировать « (Комфорт)» вручную.


| Где                                        | Заголовок окна | Тултип/description | label/name/setText |
| ------------------------------------------ | -------------- | ------------------ | ------------------ |
| Кастомный Shell/Dialog плагина             | с суффиксом    | без                | без                |
| Меню EDT, plugin.xml, хуки view/редакторов | —              | с суффиксом        | без                |
| Внутри окна коллекции/скелета              | —              | без                | без                |
| Eclipse View, Preferences, тосты, окна EDT | без            | по контексту       | без                |


Примеры: `shell.setText(Global.withPluginWindowTitle("Коллекция …"))`; `item.setToolTipText("…" + Global.pluginSignForTooltip())` вне окна; внутри — `setToolTipText("Видимость и порядок колонок")`.

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

## Кириллица в строках — без `\uXXXX`-эскейпов

В `plugin/src/` не использовать `А…` для русского текста (UTF-8, см. `build.properties`). Писать кириллицу напрямую; при правке заменять существующие `\u04..`.

Допустимо:  ``, `\t`, `\n`; escape по требованию внешнего API; генерируемый код. `"×"`, `"—"`, английские строки — норма.

## Разделители строк — CRLF (Windows)

Файлы на диске — **CRLF**. В patch/write-тулах — `\n` между строками (не `\r\n`, не `\n\n` между строками кода).

Поломка: «шахматка» пустых строк, `0D 0D 0A` / `\r\n\r\n` — не копировать в патч, сначала ремонт.

Git hook: см. `.cursor/scripts/install-git-eol-hook.ps1`, проверка — `check-double-eol.ps1`.

**После завершения задачи** для тронутых файлов в `plugin/src/`**, `plugin/plugin.xml`, `META-INF/**` (а также `.cursor/**`, `launch/**`, `site/**`):

```powershell
powershell -NoProfile -File "C:\VC\EDT.Comfort\.cursor\scripts\repair-double-eol.ps1" -Path "C:\VC\EDT.Comfort\plugin\src\tormozit\Foo.java"
```

При `REPAIRED` — проверить плотность строк через `Read`. `plugin/.gitattributes`: `text=auto`, локально CRLF.

## Логирование плагина Комфорт

Два режима — **не смешивать** без явной команды пользователя.

### 1. Постоянные логи (для других РМ)

Только журнал «Комфорт» (`Global.log` / `GlobalLog`) при `Global.isLogEnabled()` (Параметры → Комфорт → «Общее логирование»). В модулях — `*Debug` с `TAG`, проверка флажка; эталон: `PropertySheetDebug`, `DebugInspectorDebug`.

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
- JAR: `C:\VC\EDT-plugin-WS\.metadata\.plugins\org.eclipse.pde.core\.bundle_pool\plugins\`

### Распаковка и разбор

Временные файлы — только `.tmp/` (напр. `.tmp\mdprops\`). **Не** распаковывать в корень/`plugin/`. `jar xf`/`unzip` — `working_directory` в `.tmp\<имя>`; иначе `com/`, `org/` в корне — удалить.

`javap` только: `C:\Program Files\1C\1CE\components\axiom-jdk-full-17.0.16+12-x86_64\bin\javap.exe`

### Общий кэш распакованных бандлов

`.tmp/bundles/<bundle-id>/` — единый переиспользуемый кэш исходников/классов на бандл, общий для всех задач и агентов (включая Cursor). `<bundle-id>` — короткое стабильное имя бандла (напр. `mdui`, `lwt`, `search-ui`, `spelling`), не имя текущей задачи.

Разбор класса:

1. Определить `<bundle-id>` целевого бандла.
2. Проверить `.tmp/bundles/<bundle-id>/` — если бандл уже распакован, использовать как есть (`javap` / `jar tf` / grep), не распаковывать заново.
3. Если папки нет — найти JAR в `.bundle_pool` → `jar xf` в `.tmp/bundles/<bundle-id>/` → работать с ним.
4. Результаты `javap` (`*.javap.txt`) и прочие продукты разбора класть рядом, в `.tmp/bundles/<bundle-id>/`, а не в задаче-специфичную папку.

Старые задаче-специфичные папки (`.tmp/spelling-*`, `.tmp/search-*` и т.п.) — новые по этой схеме не создавать; при следующем обращении к тем же бандлам переносить/дублировать нужное в `.tmp/bundles/<bundle-id>/`.

### Репозиторий и сборка

Исходники: `C:\VC\EDT.Comfort\plugin\`. Запуск — PDE workspace `EDT-plugin-WS`. **Maven запрещён** — сборка в PDE; после правок сообщить о пересборке, не запускать `mvn`.

## Таблицы в формах (плагин Комфорт)

Для **новых** и дорабатываемых `Table` / многоколоночных `Tree` в кастомных окнах — единое поведение. Среда: Windows + SWT.

Обязательно:

1. **Клик и выбор:** ЛКМ в любой колонке → строка + `activeColumn` (`getItem(Point)` + `columnAt` по `getBounds`); `setSelection` + перерисовка.
2. **Копирование:** Ctrl+C / «Копировать» — только текст активной ячейки (`getCellDisplayText` / `getText(activeColumn)`); для EDT-инспектора — подмена Copy как в `DebugInspectorTreeEnhancement.hookGlobalCopyAction()`.
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

## Комфорт-подменю (сортировка)

Использовать `ComfortSubmenuHelper.createSortedMenuItem` везде, где элементы добавляются в подменю «Комфорт»; несколько хуков могут разделять один и тот же экземпляр подменю.