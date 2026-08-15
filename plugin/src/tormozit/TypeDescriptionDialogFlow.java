package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorPart;
import org.osgi.framework.Bundle;

/**
 * Открытие диалога «Редактирование типа данных» для поля типа AEF ({@code TypeDescriptionComponent})
 * и активация в нём строки нужного типа — общий механизм для всех мест плагина:
 * <ul>
 * <li>{@link EventHandlersOpenHandlerHook} — команда «Открыть связь» в дереве подписок (поле
 * «Источник» редактора подписки);</li>
 * <li>{@code ConfigSearchResultsHook.PropertyFieldFocus} — открытие результата поиска ссылок,
 * найденного в составном типе реквизита («Реквизиты.Касса.Тип.Типы»), где поле «Тип» панели
 * «Свойства» показывает только слово «Составной тип» и сам по себе ничего не сообщает.</li>
 * </ul>
 *
 * <p>Путь повторяет штатный: диалог открывает сам {@code TypeDescriptionComponent} по клику своей
 * кнопки «…» — мы лишь отправляем этой кнопке тот же {@code ClickEvent}, что шлёт SWT-рендерер,
 * поэтому дальше работает штатная цепочка (создание модели диалога, {@code IEngine.showDialog} и
 * возврат выбранного типа в поле). Пакеты AEF плагином не импортируются — работа рефлексией.
 *
 * <p>Слушателя кнопок ({@code buttonListener}) {@code TypeDescriptionComponent} вешает на свой
 * ДОЧЕРНИЙ компонент панели действий ({@code TypeDescriptionComponent$6} —
 * {@code AbstractDtListActionBarComponent} для составного типа, {@code $3} — combo для
 * одиночного), а компонент сам является {@code IEventChannel}; у самой {@code ButtonItemViewModel}
 * кнопки {@code eventChannel} равен {@code null} — ждать непустой канал у view model бесполезно.
 *
 * <p>Кнопку «…» создаёт не сам компонент, а его внутренний combo-компонент в {@code createButtons()},
 * то есть только при отрисовке панели действий поля: до этого поле {@code selectButton} пустое.
 * Поэтому {@link #open} возвращает {@code false}, пока кнопки нет, — вызывающий повторяет попытку.
 *
 * <p>Каждый шаг пишется во временный лог вызывающего безусловно: шагов много и все они зависят от
 * вёрстки чужого редактора/диалога.
 */
final class TypeDescriptionDialogFlow
{
    /** Компонент поля типа (панель «Свойства», страницы редакторов МД). */
    static final String COMPONENT_CLASS =
        "com._1c.g5.v8.dt.md.ui.aef.components.type.TypeDescriptionComponent"; //$NON-NLS-1$

    private static final String AEF_STANDARD_BUNDLE = "com._1c.g5.aef2.standard"; //$NON-NLS-1$

    private static final String CLICK_EVENT_CLASS =
        "com._1c.g5.aef2.standard.events.ClickEvent"; //$NON-NLS-1$

    private static final int STEP_MS = 150;

    private static final int STEP_ATTEMPTS = 40;

    private static final int MAX_TREE_DEPTH = 5;

    /** Моменты проверки выделенной строки диалога, мс от действия. */
    private static final int[] SELECTION_RECHECK_DELAYS = { 150, 400, 800, 1500 };
    private TypeDescriptionDialogFlow() {}

    /**
     * Что сделать с найденным полем типа помимо открытия диалога: активировать его до открытия
     * диалога и повторно — после закрытия (модальный диалог забирает ввод себе).
     */
    interface FieldHandler
    {
        void activateField(Object page, Object component);
    }

    /**
     * Ждёт поле типа на страницах редактора МД-объекта и открывает по нему диалог
     * «Редактирование типа данных» со строкой искомого объекта. Свойство «Тип» самого
     * МД-объекта (определяемый тип, «Источник» подписки на события и т.п.) правится именно в
     * редакторе объекта, а не в панели «Свойства».
     *
     * @param onFieldReady действие над найденным полем ({@code null} — просто активировать поле)
     */
    static void openInEditor(IEditorPart part, List<String> targets, FieldHandler onFieldReady)
    {
        FieldHandler handler = onFieldReady != null ? onFieldReady : TypeDescriptionDialogFlow::focusField;
        scheduleOpenInEditor(part, targets, handler, 0);
    }

    private static void scheduleOpenInEditor(IEditorPart part, List<String> targets,
        FieldHandler handler, int attempt)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed() || attempt >= STEP_ATTEMPTS)
        {
            return;
        }

        display.timerExec(attempt == 0 ? 0 : STEP_MS, () ->
        {
            Object[] pageAndComponent = findFieldInEditor(part);
            if (pageAndComponent == null)
            {
                scheduleOpenInEditor(part, targets, handler, attempt + 1);
                return;
            }

            Object page = pageAndComponent[0];
            Object component = pageAndComponent[1];
            handler.activateField(page, component);
            // Модальный диалог открывается сразу после запроса фокуса и забирает ввод себе —
            // повторяем активацию поля, когда диалог закрыт.
            open(component, targets, () -> handler.activateField(page, component));
        });
    }

    /**
     * Активация поля по умолчанию — тем же механизмом, что и поля панели «Свойства». Страница
     * редактора доводит свою инициализацию асинхронно и переводит ввод обратно на первое поле уже
     * после нашего вызова, поэтому активация подтверждается ограниченным числом проверок.
     */
    private static void focusField(Object page, Object component)
    {
        Object scene = Global.invoke(component, "getScene"); //$NON-NLS-1$
        if (scene == null && page != null)
            scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        AefFieldFocus.focusComponent(scene, component);
        // Повторных проверок фокуса здесь НЕ делать. Проверялось: цикл «фокус ушёл — вернуть»
        // дерётся с собственной инициализацией страницы и с самим диалогом — ввод в течение
        // ~минуты прыгает по полям. Одна попытка активации, дальше поле оставляем в покое.
    }

    /**
     * Страница редактора и компонент поля типа с уже созданной кнопкой «…».
     *
     * @return {@code [страница, компонент]} или {@code null}, если кнопки ещё нет
     */
    private static Object[] findFieldInEditor(IEditorPart part)
    {
        List<Object> pages = new ArrayList<>();
        Object active = Global.invoke(part, "getActivePageInstance"); //$NON-NLS-1$
        if (active != null)
            pages.add(active);
        Object all = Global.getField(part, "pages"); //$NON-NLS-1$
        if (all instanceof Iterable<?> iterable)
            for (Object page : iterable)
                if (page != null && !pages.contains(page))
                    pages.add(page);

        int componentsFound = 0;
        for (Object page : pages)
        {
            Object root = Global.getField(page, "pageComponent"); //$NON-NLS-1$
            componentsFound += collectComponents(root).size();
            Object component = findComponentWithSelectButton(root);
            if (component != null)
            {
                return new Object[] { page, component };
            }
        }
        return null;
    }

    /**
     * Кликает кнопку «…» поля типа и активирует в открывшемся диалоге строку одного из
     * {@code targets}.
     *
     * @param component компонент поля типа ({@link #COMPONENT_CLASS})
     * @param targets имена искомого типа (полное «СправочникСсылка.Кассы» и/или короткое);
     *        пустой список — просто открыть диалог, ничего не выделяя
     * @param onDialogClosed действие после закрытия диалога ({@code null} — ничего)
     * @return {@code false}, если кнопка «…» ещё не создана (поле не отрисовано) — попытку нужно
     *         повторить позже
     */
    static boolean open(Object component, List<String> targets, Runnable onDialogClosed)
    {
        if (component == null)
            return false;
        Object selectButton = Global.getField(component, "selectButton"); //$NON-NLS-1$
        if (selectButton == null)
            return false;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return false;

        List<Shell> before = List.of(display.getShells());
        Object channel = findButtonChannel(component);
        if (!fireSelectButtonClick(selectButton, channel))
            return false;
        scheduleSelectTypeRow(display, targets, before, onDialogClosed, 0);
        return true;
    }

    /** Компоненты поля типа в поддереве (включая сам корень), сверху вниз. */
    static List<Object> collectComponents(Object root)
    {
        List<Object> result = new ArrayList<>();
        collectComponents(root, result, 0);
        return result;
    }

    /**
     * Компонент поля типа в поддереве, у которого кнопка «…» уже создана.
     *
     * @return {@code null}, если такого нет (поля типа нет вовсе либо оно ещё не отрисовано)
     */
    static Object findComponentWithSelectButton(Object root)
    {
        for (Object component : collectComponents(root))
            if (Global.getField(component, "selectButton") != null) //$NON-NLS-1$
                return component;
        return null;
    }

    private static void collectComponents(Object component, List<Object> result, int depth)
    {
        if (component == null || depth > 20)
            return;
        if (COMPONENT_CLASS.equals(component.getClass().getName()))
            result.add(component);
        for (Object child : AefFieldFocus.childComponents(component))
            collectComponents(child, result, depth + 1);
    }

    /**
     * Компонент панели действий, которому {@code TypeDescriptionComponent} отдал
     * {@code buttonListener}, — его дочерний анонимный компонент ({@code TypeDescriptionComponent$N}).
     * Если такого нет, шлём событие самому {@code TypeDescriptionComponent} (тоже
     * {@code IEventChannel}).
     */
    private static Object findButtonChannel(Object typeDescriptionComponent)
    {
        Object children = Global.invoke(typeDescriptionComponent, "getComponents"); //$NON-NLS-1$
        if (children instanceof Iterable<?> iterable)
        {
            for (Object child : iterable)
            {
                if (child != null && child.getClass().getName().startsWith(COMPONENT_CLASS + '$'))
                    return child;
            }
        }
        return typeDescriptionComponent;
    }

    /** Тот же {@code ClickEvent} по кнопке «…», что отправляет SWT-рендерер AEF. */
    private static boolean fireSelectButtonClick(Object selectButton, Object channel)
    {
        try
        {
            Bundle bundle = Platform.getBundle(AEF_STANDARD_BUNDLE);
            if (bundle == null)
            {
                return false;
            }
            Class<?> clickEventClass = bundle.loadClass(CLICK_EVENT_CLASS);
            Object event = clickEventClass.getConstructors()[0].newInstance(selectButton);
            Global.invoke(channel, "queueEvent", event); //$NON-NLS-1$
            return true;
        }
        catch (Exception | LinkageError e)
        {
            return false;
        }
    }

    private static void scheduleSelectTypeRow(Display display, List<String> targets, List<Shell> before,
        Runnable onDialogClosed, int attempt)
    {
        if (display.isDisposed() || attempt >= STEP_ATTEMPTS)
        {
            return;
        }

        display.timerExec(STEP_MS, () ->
        {
            Shell dialog = findNewShell(display, before);
            if (dialog == null || !selectTypeRow(dialog, targets))
            {
                scheduleSelectTypeRow(display, targets, before, onDialogClosed, attempt + 1);
                return;
            }
            if (onDialogClosed != null)
                dialog.addDisposeListener(e -> display.asyncExec(onDialogClosed));
        });
    }

    private static Shell findNewShell(Display display, List<Shell> before)
    {
        for (Shell shell : display.getShells())
        {
            if (shell == null || shell.isDisposed() || !shell.isVisible() || before.contains(shell))
                continue;
            return shell;
        }
        return null;
    }

    private static boolean selectTypeRow(Shell dialog, List<String> targets)
    {
        if (targets.isEmpty())
            return true; // диалог открыт, выделять нечего
        Tree tree = findTree(dialog);
        if (tree == null || tree.isDisposed())
            return false;

        TreeViewer viewer = findTreeViewer(tree, dialog);
        if (viewer == null || !(viewer.getContentProvider() instanceof ITreeContentProvider tcp))
        {
            return false;
        }

        Object[] roots = tcp.getElements(viewer.getInput());
        if (roots == null || roots.length == 0)
            return false;
        Object element = findTypeRow(tcp, roots, targets);
        if (element == null)
            return false;

        viewer.setSelection(new StructuredSelection(element), true);
        tree.setFocus();
        // Фильтр «Только помеченные» диалога применяется асинхронно и не только прокручивает дерево
        // после нашего выбора, но и ПЕРЕСОБИРАЕТ его содержимое — выделение при этом теряется
        // целиком (по отчёту: диалог открыт, строка не подсвечена, хотя в логе «строка типа
        // активирована»). Поэтому проверки не просто прокручивают, а восстанавливают выделение,
        // причём элемент ищется заново: после пересборки это уже другие экземпляры узлов.
        for (int delay : SELECTION_RECHECK_DELAYS)
            tree.getDisplay().timerExec(delay, () -> keepTypeRowSelected(tree, viewer, tcp, targets));
        return true;
    }

    /** См. {@link #selectTypeRow} — восстановление выделения после пересборки дерева диалога. */
    private static void keepTypeRowSelected(Tree tree, TreeViewer viewer, ITreeContentProvider tcp,
        List<String> targets)
    {
        if (tree.isDisposed() || viewer.getControl() == null || viewer.getControl().isDisposed())
            return;
        if (tree.getSelectionCount() > 0 && isTargetSelected(viewer, tcp, targets))
        {
            tree.showSelection();
            return;
        }
        Object[] roots = tcp.getElements(viewer.getInput());
        if (roots == null || roots.length == 0)
            return;
        Object element = findTypeRow(tcp, roots, targets);
        if (element == null)
            return;
        viewer.setSelection(new StructuredSelection(element), true);
        tree.showSelection();
    }

    private static boolean isTargetSelected(TreeViewer viewer, ITreeContentProvider tcp,
        List<String> targets)
    {
        Object selected = viewer.getStructuredSelection().getFirstElement();
        if (selected == null)
            return false;
        List<String> names = candidateNames(selected);
        List<String> parentNames = candidateNames(tcp.getParent(selected));
        return matchesTarget(names, targets, true, parentNames)
            || matchesTarget(names, targets, false, parentNames);
    }

    /**
     * Строка искомого типа в дереве диалога. Два прохода: сначала полное имя типа
     * («СправочникОбъект._ДемоКассы»), и только если такого узла нет — короткое («_ДемоКассы»), но
     * уже с проверкой ГРУППЫ, в которой узел лежит. Без проверки группы короткое имя неоднозначно:
     * один и тот же объект присутствует в дереве и как «СправочникСсылка», и как
     * «СправочникОбъект», и выделялся всегда первый по порядку.
     */
    private static Object findTypeRow(ITreeContentProvider tcp, Object[] roots, List<String> targets)
    {
        Object element = findElement(tcp, roots, targets, true, List.of(), 0);
        return element != null ? element : findElement(tcp, roots, targets, false, List.of(), 0);
    }

    private static Object findElement(ITreeContentProvider tcp, Object[] elements, List<String> targets,
        boolean fullNameOnly, List<String> parentNames, int depth)
    {
        if (elements == null || depth > MAX_TREE_DEPTH)
            return null;
        for (Object element : elements)
        {
            if (element == null)
                continue;
            List<String> names = candidateNames(element);
            if (matchesTarget(names, targets, fullNameOnly, parentNames))
                return element;
            Object found =
                findElement(tcp, tcp.getChildren(element), targets, fullNameOnly, names, depth + 1);
            if (found != null)
                return found;
        }
        return null;
    }

    /**
     * @param parentNames имена узла-владельца (группы вида типов) — по ним проверяется префикс
     *        имени типа при совпадении по короткому имени
     */
    private static boolean matchesTarget(List<String> candidateNames, List<String> targets,
        boolean fullNameOnly, List<String> parentNames)
    {
        for (String candidate : candidateNames)
        {
            for (String target : targets)
            {
                if (candidate.equalsIgnoreCase(target))
                    return true;
                if (fullNameOnly || !candidate.equalsIgnoreCase(lastSegment(target)))
                    continue;
                String prefix = typePrefix(target);
                if (prefix == null || containsIgnoreCase(parentNames, prefix))
                    return true;
            }
        }
        return false;
    }

    /** «СправочникОбъект» из «СправочникОбъект._ДемоКассы»; {@code null} — имя без точки. */
    private static String typePrefix(String name)
    {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : null;
    }

    private static boolean containsIgnoreCase(List<String> names, String value)
    {
        for (String name : names)
            if (name.equalsIgnoreCase(value))
                return true;
        return false;
    }

    /** Имена узла дерева типов: сам узел и вложенный в него {@code TypeItem}, если есть. */
    private static List<String> candidateNames(Object element)
    {
        List<String> result = new ArrayList<>(4);
        if (element == null)
            return result;
        addNames(element, result);
        for (String getter : new String[] { "getTypeItem", "getType", "getValue" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            addNames(Global.invoke(element, getter), result);
        return result;
    }

    private static void addNames(Object object, List<String> result)
    {
        if (object == null)
            return;
        for (String getter : new String[] { "getName", "getNameRu", "getPresentation", "getText" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Object value = Global.invoke(object, getter);
            if (value instanceof String text && !text.isBlank() && !result.contains(text))
                result.add(text);
        }
    }

    /**
     * Первое дерево в чужой вёрстке. Package-visible: второй потребитель —
     * {@code ConfigSearchResultsHook} (строка состава плана обмена в редакторе).
     */
    static Tree findTree(Control control)
    {
        if (control instanceof Tree tree)
            return tree;
        if (control instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
            {
                Tree found = findTree(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /**
     * Как {@code SmartOutlineHook.findTreeViewer}: сам виджет, предки, затем слушатели дерева.
     * Package-visible — см. {@link #findTree}.
     *
     * @param boundary предок, выше которого не подниматься ({@code null} — до самого верха)
     */
    static TreeViewer findTreeViewer(Tree tree, Composite boundary)
    {
        for (Composite parent = tree.getParent(); parent != null; parent = parent.getParent())
        {
            for (String getter : new String[] { "getTreeViewer", "getViewer" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Object viewer = Global.invoke(parent, getter);
                if (viewer instanceof TreeViewer treeViewer)
                    return treeViewer;
            }
            for (String field : new String[] { "treeViewer", "viewer" }) //$NON-NLS-1$ //$NON-NLS-2$
            {
                Object viewer = Global.getField(parent, field);
                if (viewer instanceof TreeViewer treeViewer)
                    return treeViewer;
            }
            if (parent == boundary)
                break;
        }
        for (int eventType : new int[] { SWT.Selection, SWT.Expand, SWT.Collapse })
        {
            for (Listener listener : tree.getListeners(eventType))
            {
                for (String field : new String[] { "this$0", "viewer", "treeViewer" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                {
                    Object viewer = Global.getField(listener, field);
                    if (viewer instanceof TreeViewer treeViewer)
                        return treeViewer;
                }
            }
        }
        return null;
    }

    private static String lastSegment(String name)
    {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : name;
    }
}
