# Fix: key bindings в HistoryView

## Проблема
В HistoryView контекст `tormozit.gitChangedFile.context` деактивируется через несколько секунд после активации из-за того, что внутри HistoryView активируется другой part (non-git). Лог:

```
10:23:26  GitChangedFileMenu: context activated for org.eclipse.team.ui.GenericHistoryView
10:23:32  GitChangedFileMenu: context deactivated (non-git part=???)
```

## Шаг 1: Диагностика
Добавить ID part'а в лог при деактивации — понять, какой part сбрасывает контекст.

**Файл**: `plugin\src\tormozit\GitChangedFileMenuHook.java`
**Строка**: 146 (текущая, может отличаться)
**Изменение**: `"context deactivated (non-git part)"` → `"context deactivated (non-git part=" + part.getSite().getId() + ")"`

## Шаг 2: Логи в handler'ах
Вернуть по 1 строке лога в `execute()` каждого handler'а, чтобы видеть, вызываются ли они вообще.

**Файл**: `plugin\src\tormozit\GitOpenObjectHandler.java`
**После строки 24** (проверки `isGitView`): добавить `Global.log("GitOpenObject: handler called for " + part.getSite().getId());`

**Файл**: `plugin\src\tormozit\GitShowInNavigatorHandler.java`
**После строки 24** (проверки `isGitView`): добавить `Global.log("GitShowInNavigator: handler called for " + part.getSite().getId());`

## Шаг 3: Сбор данных
- Запустить EDT
- Переключиться в HistoryView
- Нажать F2
- Показать лог

## Возможные причины (по результатам диагностики)

### A. Handler не вызывается
Key binding конфликтует с другой командой на F2 в GenericHistoryView. Проверить через Preferences > Keys > type "F2".

### B. Handler вызывается, selection null
`selectionFromFocusControl()` не находит данные в Table/Tree под фокусом. Возможно, HistoryView использует другой тип виджета (StyledText, Canvas и т.д.) или getData() возвращает null.

### C. Handler вызывается, selection есть, file/eObject null
Проблема resolve — элемент не распознаётся.
