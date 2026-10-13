# Бандл раннего старта (`tormozit.comfort.early`)

## Зачем отдельный бандл

Часть доработок Комфорта — вставки в байткод чужих классов (EDT, SWT) через OSGi `WeavingHook`. `WeavingHook` видит только классы, загруженные **после** его регистрации, а основной бандл Комфорта активируется лениво и поздно: парсер BSL к этому времени загружен при открытии проектов, SWT — при создании окна. Хук, зарегистрированный из `tormozit.Activator`, в такие классы не вплетается (проверено по временному логу 26.09.2026), а агент с перезагрузкой классов в EDT недоступен.

Поэтому вплетения в рано загружаемые классы живут в отдельном маленьком бандле, который **стартует вместе с фреймворком**, до приложения:

- в установленном плагине — `META-INF/p2.inf` (`setStartLevel(4)` + `markStarted(true)`);
- в PDE-запуске — запись `tormozit.comfort.early,…,4,true` в `bundles.info` профиля. Её ставит `site/scripts/comfort-osgi-version.ps1` (`sync-version.ps1`, `restore-main.ps1`, `assert-comfort-osgi.ps1 -Repair`).

До 07.10.2026 бандл назывался `tormozit.comfort.bslparser` (каталог `plugin.bslparser`, пакет `tormozit.bslparser`).

## Что вплетено

Все вставки делает `Activator` (вложенный `EarlyWeaving`); функции, которые зовёт вплетённый код, лежат в `System.getProperties()`.

### Парсер BSL (issue #561)

Хук исправляет разбор модулей, где директива `#Если` (а также `#Вставка`/`#Удаление`) разрывает оператор (описание — в javadoc `BslParserHook`). Вплетается в классы парсера EDT (`BslParser`, `CustomBslParser`, `BslPartialParsingHelper`).

Скрытые директивы — листья `SL_COMMENT` без `//`. Штатный `BslSuppressionProvider.extractSuppressions` отрезает `//` от любого такого листа и падал на односимвольном (скобка в условии `#Если Не (…) Тогда`), обрывая валидацию модуля. Поэтому в него тоже вплетена подмена: текст не на `//` он видит как пустой комментарий `//`.

### Свёртки BSL (issue #527)

Провайдер свёрток `BslFoldingRegionProvider` («Автоматически сворачиваемые области»): в начало `isInitiallyCollapsed(EObject)` вставлен вызов функции основного бандла (`tormozit.bslFolding.initiallyCollapsed`, регистрирует `tormozit.BslEditorFoldingHook`). Класс провайдера грузится раньше активации основного бандла (проверено по временному логу 01.10.2026).

### Область вкладок воркбенча (issue #730)

`org.eclipse.swt.custom.CTabFolder` — штатная кнопка списка вкладок видна всегда: в `setItemSize(GC)` к условию видимости кнопки добавлено «папка помечена ключом `tormozit.workbench.partListButton`». Пометку ставит `tormozit.WorkbenchTabsHook`, непомеченные папки ведут себя штатно. Итог вплетения бандл кладёт в системное свойство `tormozit.tabFolder.chevronPatch` (`woven 3/3/1` — применено; `skipped …` — байткод SWT не тот, на который рассчитана вставка, класс оставлен штатным); основной бандл без `woven` папки не помечает.

### Список вкладок воркбенча

`AbstractTableInformationControl` (список, который открывает кнопка в области вкладок) загружается вместе с воркбенчем, раньше активации основного бандла (проверено по временному логу 07.10.2026). В `setMatcherString` текст запроса перед `SearchPattern.setPattern` проходит через функцию `tormozit.partList.pattern` (поиск по подстроке без ведущей `*`), в `findElement` результат `ILabelProvider.getText` — через `tormozit.partList.stockText` (подпись без имени проекта, которое основной бандл добавляет к строкам). До активации основного бандла обе функции тождественные; свои ставит `tormozit.WorkbenchTabsHook.PartListLabels`. Итог вплетения — в системном свойстве `tormozit.partList.controlPatch` (`woven 1/1`).

### Сопоставление восстановленного редактора (issue #726)

В `DtEditorMatchingStrategy.matches` единственный вызов `Objects.equals` двух моделей заменён функцией `tormozit.editor.modelEquals`. Проверки типа входа и прочая логика EDT сохранены. До активации основного бандла функция выполняет штатный `Objects.equals`; затем `WorkbenchTabsHook.EditorModelMatching` дополнительно сопоставляет proxy и реальный объект по полному BM URI, как при сохранении входа в `DtEditorInputElementFactory`. Совпадение по имени или подсказке не используется. При изменении структуры метода вплетение не применяется.

В `DtGranularEditor.init(IEditorSite, IDtEditorInput)` сразу после `FormEditor.init` редактору передаётся название из `IWorkbenchPartReference.getTitle()`. При восстановлении сеанса ссылка Eclipse ещё хранит сохранённое название вкладки. Оно остаётся на вкладке во время асинхронной загрузки; штатный `makeInitialized` затем задаёт окончательное название. Вставка не добавляет ветвлений и применяется только при одном ожидаемом вызове `FormEditor.init`.

### Очередь обновления панели «Свойства» (issue #783)

В `MdPropertySheetPage` начало `cancelPreviousSelectionProcess` и `dispose` инвалидирует токен обновления через `tormozit.propertySheet.invalidate`. Созданный в `submitSelectionOperation` Runnable проходит через `tormozit.propertySheet.operation`; все пять вызовов `Display.asyncExec` в его лямбде — через `tormozit.propertySheet.result`. Имя лямбды определяется по bootstrap-ссылке. При несовпадении полного набора точек байткод остаётся штатным; статус — `tormozit.propertySheet.patch` (`ok [2, 1, 5]`).

Колбэки устанавливает `PropertySheetUiContext` при старте основного бандла. Устаревшие операции не получают контекст модели, а устаревшие результаты не заполняют палитру. Токен переносится через `ThreadLocal` из фоновой операции в отложенный UI-вызов; смена выделения и закрытие страницы делают его неактуальным. До старта основного бандла колбэки тождественные.

Отдельно вплетён `MdPropertySheetPage$Monitor`: `asyncExec` в `beginTask` и `done` проходят через `tormozit.propertySheet.progress` / `progressDone`. Перед выполнением проверяется штатный `IProgressMonitor.isCanceled()`, чтобы отменённые задания не показывали и не скрывали индикатор с повторным `layout()` панели. Если нового монитора нет (например, переход к пустому выделению), прежний `done` всё ещё убирает начатый индикатор. Поля `Monitor.this$0` и `MdPropertySheetPage.progressMonitor` подтверждены javap. Статус — `tormozit.propertySheet.monitorPatch` (`ok [1, 1]`).

## Правила

- **Не зависеть от основного бандла.** Обращение к его классам активировало бы весь Комфорт на старте фреймворка, до Workbench. Связь с основным бандлом — только через `System.getProperties()`.
- **`Activator` не касается типов Xtext и SWT** — иначе ранний старт активирует их бандлы. Классы Xtext грузит `BslParserHook`, при первом разборе; вставка в `CTabFolder` работает только с байткодом.
- Нет `Bundle-ActivationPolicy: lazy`: ленивый бандл даже с флагом «запущен» активируется только при первой загрузке класса.
- Версия — с `.qualifier`: проверок EDT здесь нет, поэтому квалификатор сборки перепроверку проекта не вызывает (в отличие от `plugin.checks`).
- **Имя бандла и пакета не менять без нужды.** Бандл проверок требует его по имени (`Require-Bundle`), а версия проверок фиксированная: переименование требует поднять `Bundle-Version` в `plugin.checks`, то есть полной перепроверки проектов у пользователей.

## Источник окна «Строки на разных языках» (#788)
Вызов createMultiLanguageDialog в AbstractMultilanguageComponent и AbstractSingleLanguageComponent передаёт созданный диалог и исходный компонент через tormozit.localStringDialog.source. LocalStringDialogHook получает имя из getModel().getObject() и добавляет кнопку «Из имени». Вставка применяется только при единственном подтверждённом вызове; до старта основного бандла колбэк возвращает диалог без изменений.

Для СКД getCellEditor(Object) в DataSetsFieldsEditingSupport и ParametersEditingSupport связывает возвращаемый редактор с исходным полем/параметром. LocalStringEditor.createDialog передаёт диалог и редактор строки; имя извлекается по его cellEditor. Точки и число возвратов (по два в каждом методе) подтверждены javap; общий контекст схемы и копия LocalString для имени не используются.
