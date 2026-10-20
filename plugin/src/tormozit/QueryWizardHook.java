package tormozit;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.layout.TreeColumnLayout;
import org.eclipse.jface.viewers.ColumnPixelData;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.TreeViewerColumn;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.xtext.ui.editor.XtextEditor;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.common.ui.controls.search.SearchBox;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.dbview.DbViewElement;
import com._1c.g5.v8.dt.metadata.dbview.DbViewFieldDef;
import com._1c.g5.v8.dt.metadata.dbview.Table;
import com._1c.g5.v8.dt.ql.model.QuerySchemaExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchemaOrderExpression;
import com._1c.g5.v8.dt.ql.model.QuerySchemaTotalControlPoint;
import com._1c.g5.v8.dt.qw.ui.utils.QueryWizardServiceUtils;
import com._1c.g5.v8.dt.qw.ui.utils.QueryWizardSource;
import com._1c.g5.v8.dt.ui.util.OpenHelper;

/**
 * Штатный конструктор запроса EDT ({@code QueryWizard}): многословный фильтр списка доступных таблиц
 * (#81), колонка «Тип» во всех списках полей ({@link TypeColumns}, #839), число элементов в заголовках
 * вкладок ({@link TabCounts}), запрос пакета под кареткой ({@link BatchQueryAtCaret}) и предупреждение
 * о потере комментариев ({@link #warnLostComments}).
 */
public final class QueryWizardHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.queryConstructorFilterPatched"; //$NON-NLS-1$
    private static final String TYPE_COLUMNS_KEY = "tormozit.queryWizardTypeColumns"; //$NON-NLS-1$
    private static final String COMMENTS_WARNED_KEY = "tormozit.queryWizardCommentsWarned"; //$NON-NLS-1$
    /** Сколько символов текстов комментариев показывать в уведомлении о потере. */
    private static final int COMMENTS_PREVIEW_LENGTH = 100;
    private static final String QUERY_WIZARD_CLASS = "QueryWizard"; //$NON-NLS-1$

    private static final Set<Shell> patchedShells =
        Collections.newSetFromMap(new WeakHashMap<>());

    /** Персистентно (переживает и переоткрытие окна, и перезапуск EDT) — через уже принятое
     * в проекте {@link FilterHistoryStore} (см. {@code PreferenceSearchFilterAugmenter},
     * {@code ComfortKeysPreferences}), а не штатный {@code InMemorySearchHistory}. */
    private static final SearchHistoryAdapter searchHistory = new SearchHistoryAdapter();

    private static final class SearchHistoryAdapter
        implements com._1c.g5.v8.dt.common.ui.controls.search.ISearchHistory
    {
        private static final String SCOPE_ID = "queryConstructorTables"; //$NON-NLS-1$

        @Override
        public void savePattern(String pattern)
        {
            FilterHistoryStore.remember(SCOPE_ID, pattern);
        }

        @Override
        public void replacePattern(String pattern)
        {
            savePattern(pattern);
        }

        @Override
        public String getActivePattern()
        {
            // Всегда "" — SearchBox.setHistory() сам подставляет непустой activePattern в поле
            // при каждом открытии окна, а это не нужно (см. просьбу пользователя).
            return ""; //$NON-NLS-1$
        }

        @Override
        public java.util.List<String> getRecentPatterns(int max)
        {
            java.util.List<String> items = FilterHistoryStore.load(SCOPE_ID);
            return max >= 0 && items.size() > max ? items.subList(0, max) : items;
        }
    }

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.Show, QueryWizardHook::handleShow);
        BatchQueryAtCaret.installListener();
    }

    private static final String ALIASES_TAB_CLASS = "com._1c.g5.v8.dt.qw.ui.tabs.AliasesTab"; //$NON-NLS-1$
    /** Метка колонки «Тип» плагина — её не считает переплетённый {@code AliasesTab.updateOperatorColumn}. */
    private static final String TYPE_COLUMN_KEY = "tormozit.queryWizardTypeColumn"; //$NON-NLS-1$
    private static final java.util.concurrent.atomic.AtomicBoolean weavingHookInstalled =
        new java.util.concurrent.atomic.AtomicBoolean();
    /** Переплетение применилось: только тогда колонка «Тип» добавляется в список псевдонимов. */
    private static volatile boolean aliasesTabWoven;

    /**
     * Регистрируется из {@link Activator#start} до загрузки {@code AliasesTab}. Штатный
     * {@code AliasesTab.updateOperatorColumn} считает колонки списка псевдонимов как «имя поля + по колонке
     * на каждый запрос объединения» ({@code tree.getColumnCount() - 1}) и по этому числу добавляет,
     * удаляет и подписывает колонки запросов: лишняя колонка «Тип» удалила бы колонку запроса или
     * уронила бы подпись ({@code operators.get}). Внутри этого метода {@code Tree.getColumnCount()}
     * заменяется на {@link #aliasColumnCount}, который нашу колонку не считает; новые колонки запросов
     * штатный код и так вставляет на позиции {@code 1..n}, перед ней.
     */
    static void installWeavingHook()
    {
        if (!weavingHookInstalled.compareAndSet(false, true))
            return;
        org.osgi.framework.Bundle bundle = org.osgi.framework.FrameworkUtil.getBundle(QueryWizardHook.class);
        org.osgi.framework.BundleContext context = bundle != null ? bundle.getBundleContext() : null;
        if (context != null)
            context.registerService(org.osgi.framework.hooks.weaving.WeavingHook.class, wovenClass ->
            {
                if (wovenClass.getState() != org.osgi.framework.hooks.weaving.WovenClass.TRANSFORMING
                    || !ALIASES_TAB_CLASS.equals(wovenClass.getClassName()))
                    return;
                try
                {
                    byte[] transformed = transformAliasesTab(wovenClass.getBytes());
                    if (transformed == null)
                        return;
                    wovenClass.getDynamicImports().add("tormozit"); //$NON-NLS-1$
                    wovenClass.setBytes(transformed);
                    aliasesTabWoven = true;
                }
                catch (Throwable e)
                {
                    Global.logError(Debug.TAG, "transformAliasesTab", e); //$NON-NLS-1$
                }
            }, null);
    }

    private static byte[] transformAliasesTab(byte[] source)
    {
        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(source);
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(reader, org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        java.util.concurrent.atomic.AtomicBoolean touched = new java.util.concurrent.atomic.AtomicBoolean();
        reader.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer)
        {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions)
            {
                org.objectweb.asm.MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"updateOperatorColumn".equals(name) || !"()V".equals(descriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                    return mv;
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, mv)
                {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String methodDescriptor,
                        boolean isInterface)
                    {
                        if (opcode == org.objectweb.asm.Opcodes.INVOKEVIRTUAL && "org/eclipse/swt/widgets/Tree".equals(owner) //$NON-NLS-1$
                            && "getColumnCount".equals(method) && "()I".equals(methodDescriptor)) //$NON-NLS-1$ //$NON-NLS-2$
                        {
                            super.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "tormozit/QueryWizardHook", //$NON-NLS-1$
                                "aliasColumnCount", "(Lorg/eclipse/swt/widgets/Tree;)I", false); //$NON-NLS-1$ //$NON-NLS-2$
                            touched.set(true);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, method, methodDescriptor, isInterface);
                    }
                };
            }
        }, 0);
        return touched.get() ? writer.toByteArray() : null;
    }

    /** Колонки списка псевдонимов без колонки «Тип» плагина — для переплетённого {@code updateOperatorColumn}. */
    public static int aliasColumnCount(Tree tree)
    {
        int count = tree.getColumnCount();
        for (TreeColumn column : tree.getColumns())
        {
            if (column.getData(TYPE_COLUMN_KEY) != null)
                count--;
        }
        return count;
    }

    /** Обе доработки окна уже подключены (или фильтр выключен настройкой). */
    private static boolean isPatched(Shell shell)
    {
        return shell.getData(PATCHED_KEY) != null && shell.getData(TYPE_COLUMNS_KEY) != null;
    }

    private static void handleShow(Event event)
    {
        if (!(event.widget instanceof Shell shell))
            return;
        if (shell.isDisposed())
            return;
        if (suppressShowKeysPopupIfRecentCtrlF(shell))
            return;
        if (isPatched(shell))
            return;
        if (patchedShells.contains(shell))
            return;
        if (!isQueryWizardShell(shell))
            return;
        scheduleTryPatch(shell, 0);
    }

    /** Момент последнего перехваченного Ctrl+F в нашем дереве (см. {@link #wireCtrlF}) — используется
     * ниже, чтобы отличить попап "подсказка по клавишам" Eclipse, вызванный именно этим нажатием. */
    private static volatile long lastCtrlFTimeMs;

    /**
     * Сервис биндингов Eclipse обрабатывает Ctrl+F раньше нас (см. {@link #wireCtrlF} —
     * {@code KeyDown}-фильтр опаздывает выставить {@code doit=false}) и сам решает показать
     * {@code ShowKeysUI$ShowKeysPopup} ("подсказка по клавишам") — предотвратить его появление
     * нельзя, но можно сразу скрыть, пока пользователь не успел его заметить (в пределах короткого
     * окна после нашего Ctrl+F — не трогаем этот попап, если он появился по другой причине).
     */
    private static boolean suppressShowKeysPopupIfRecentCtrlF(Shell shell)
    {
        Object data = shell.getData();
        if (data == null || !data.getClass().getName().contains("ShowKeysUI")) //$NON-NLS-1$
            return false;
        if (System.currentTimeMillis() - lastCtrlFTimeMs > 500)
            return false;
        shell.setVisible(false);
        return true;
    }

    private static boolean isQueryWizardShell(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            return false;
        String name = data.getClass().getName();
        return name.contains(QUERY_WIZARD_CLASS) && !name.contains("Control"); //$NON-NLS-1$
    }

    private static void scheduleTryPatch(Shell shell, int attempt)
    {
        if (shell.isDisposed() || isPatched(shell))
            return;
        int delay = attempt == 0 ? 0 : 60;
        shell.getDisplay().timerExec(delay, () ->
        {
            if (shell.isDisposed() || isPatched(shell))
                return;
            if (tryPatch(shell))
                return;
            if (attempt < 40)
                scheduleTryPatch(shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        Object dialog = shell.getData();
        if (dialog == null)
        {
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            shell.setData(TYPE_COLUMNS_KEY, Boolean.TRUE);
            return true;
        }

        Object qwc = getField(dialog, "queryWizardControl"); //$NON-NLS-1$
        if (qwc == null)
            return false;

        // Доработки независимы: сбой одной не должен выключать остальные.
        if (shell.getData(COMMENTS_WARNED_KEY) == null)
        {
            shell.setData(COMMENTS_WARNED_KEY, Boolean.TRUE);
            runIsolated("comments", () -> warnLostComments(shell, dialog)); //$NON-NLS-1$
        }
        runIsolated("batchQuery", () -> BatchQueryAtCaret.apply(qwc)); //$NON-NLS-1$
        if (shell.getData(TYPE_COLUMNS_KEY) == null)
        {
            boolean[] installed = { false };
            runIsolated("typeColumns", () -> installed[0] = TypeColumns.install(qwc)); //$NON-NLS-1$
            if (installed[0])
            {
                shell.setData(TYPE_COLUMNS_KEY, Boolean.TRUE);
                runIsolated("tabCounts", () -> TabCounts.install(qwc)); //$NON-NLS-1$
            }
        }
        if (shell.getData(PATCHED_KEY) == null)
        {
            if (!ComfortSettings.isReplaceListFiltersEnabled())
                shell.setData(PATCHED_KEY, Boolean.TRUE);
            else
                runIsolated("filter", () -> tryPatchFilter(shell, qwc)); //$NON-NLS-1$
        }
        return isPatched(shell);
    }

    /**
     * Конструктор собирает текст запроса заново из схемы, и комментарии исходного текста в результат
     * не попадают. Предупреждаем сразу при открытии — до правок, а не после «ОК». Окно только для
     * просмотра текст не возвращает, там потери нет.
     */
    private static void warnLostComments(Shell shell, Object dialog)
    {
        if (Boolean.FALSE.equals(getField(dialog, "editable")) //$NON-NLS-1$
            || !(getField(dialog, "initQueryText") instanceof String text)) //$NON-NLS-1$
            return;
        List<String> comments = queryComments(text);
        if (comments.isEmpty())
            return;
        String joined = String.join("; ", comments); //$NON-NLS-1$
        String preview = joined.length() > COMMENTS_PREVIEW_LENGTH
            ? joined.substring(0, COMMENTS_PREVIEW_LENGTH) + "…" : joined; //$NON-NLS-1$
        // Конструктор ИР — только для литерала модуля: из редактора запроса EDT передаёт сюда лишь
        // выделенный фрагмент, а конструктор ИР работал бы со всем текстом редактора.
        BslXtextEditor editor = BatchQueryAtCaret.sourceEditor();
        Runnable openIr = editor != null && IrQueryConstructorHandler.isApplicable(editor)
            ? () -> openIrInstead(shell, dialog, editor) : null;
        ToastNotification.show("Конструктор запроса", //$NON-NLS-1$
            "При разборе текста потеряны комментарии: " + comments.size() //$NON-NLS-1$
                + ". А конструктор ИР их сохраняет.\n" + preview, //$NON-NLS-1$
            8000, openIr, openIr != null ? "Открыть конструктор ИР" : null, shell); //$NON-NLS-1$
    }

    /** Штатное окно закрывается без вопроса о сохранении, каретка в литерале модуля остаётся на месте. */
    private static void openIrInstead(Shell shell, Object dialog, BslXtextEditor editor)
    {
        if (!shell.isDisposed())
            Global.invoke(dialog, "pressCancel"); //$NON-NLS-1$
        // После выхода из модального цикла штатного окна (его закрывающее действие снимает свои слушатели).
        Display.getDefault().asyncExec(() ->
        {
            if (editor.getSite() != null && editor.getSite().getPage() != null)
                IrQueryConstructorHandler.openQueryConstructor(editor);
        });
    }

    /** Тексты ручных комментариев {@code //} вне строк запроса ({@code ""} внутри строки — одна кавычка). */
    private static List<String> queryComments(String text)
    {
        List<String> comments = new ArrayList<>();
        boolean inString = false;
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (inString)
            {
                if (c == '"' && next == '"')
                    i++;
                else if (c == '"')
                    inString = false;
            }
            else if (c == '"')
                inString = true;
            else if (c == '/' && next == '/')
            {
                int start = i + 2;
                while (i < text.length() && text.charAt(i) != '\n')
                    i++;
                comments.add(text.substring(start, i).replaceAll("^/+", "").strip()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        // Ручной комментарий — с буквами; без букв это разделители вроде «////////» между запросами пакета.
        comments.removeIf(comment -> comment.chars().noneMatch(Character::isLetter));
        return comments;
    }

    private static void runIsolated(String part, Runnable action)
    {
        try
        {
            action.run();
        }
        catch (RuntimeException e)
        {
            Global.logError(Debug.TAG, part, e);
        }
    }

    private static boolean tryPatchFilter(Shell shell, Object qwc)
    {
        Object tabsObj = getField(qwc, "tablesAndFieldsTab"); //$NON-NLS-1$
        if (tabsObj == null)
            return false;

        TreeViewer tree = getFieldAs(tabsObj, "availableTablesTree", TreeViewer.class); //$NON-NLS-1$
        if (tree == null)
            return false;

        Composite parent = safeCast(tree.getControl().getParent(), Composite.class);
        if (parent == null)
            return false;

        Composite qwcComp = safeCast(qwc, Composite.class);
        ToolBar searchToolBar = qwcComp != null ? findSearchToolBar(qwcComp) : null;

        SearchBox searchBox = findSearchBox(parent);
        if (searchBox != null)
        {
            stripFocusListeners(searchBox);
        }
        else
        {
            if (searchToolBar == null)
                return false;
            searchBox = createSearchBoxViaAction(searchToolBar);
            if (searchBox == null)
                return false;
            stripFocusListeners(searchBox);
        }

        shell.setData(PATCHED_KEY, Boolean.TRUE);
        patchedShells.add(shell);

        try
        {
            installFilter(tree, searchBox, searchToolBar);
            Debug.log("PATCH OK tree=" + tree.getClass().getSimpleName() //$NON-NLS-1$
                + " searchBox=" + searchBox.getClass().getSimpleName()); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Debug.problem("tryPatch exception: " + e); //$NON-NLS-1$
            Global.logError(Debug.TAG, "tryPatch", e); //$NON-NLS-1$
        }

        shell.addDisposeListener(e -> patchedShells.remove(shell));
        return true;
    }

    private static Object getField(Object obj, String name)
    {
        if (obj == null)
            return null;
        try
        {
            Field f = findField(obj.getClass(), name);
            if (f == null)
                return null;
            f.setAccessible(true);
            return f.get(obj);
        }
        catch (Exception e)
        {
            Debug.problem("getField " + name + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getFieldAs(Object obj, String name, Class<T> type)
    {
        Object val = getField(obj, name);
        return type.isInstance(val) ? (T) val : null;
    }

    private static Field findField(Class<?> clazz, String name)
    {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass())
        {
            try
            {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static SearchBox findSearchBox(Control root)
    {
        if (root == null || root.isDisposed())
            return null;
        if (root instanceof SearchBox sb)
            return sb;
        if (root instanceof Composite comp)
        {
            for (Control child : comp.getChildren())
            {
                if (child.isDisposed())
                    continue;
                if (child instanceof SearchBox sb)
                    return sb;
                SearchBox found = findSearchBox(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T safeCast(Object obj, Class<T> type)
    {
        return type.isInstance(obj) ? (T) obj : null;
    }

    private static SearchBox createSearchBoxViaAction(ToolBar toolBar)
    {
        ToolItem searchItem = findSearchToolItem(toolBar);
        if (searchItem == null)
            return null;
        Object action = searchItem.getData();
        if (action == null)
            return null;
        if (action instanceof org.eclipse.jface.action.ActionContributionItem aci)
        {
            action = aci.getAction();
        }
        if (action == null)
            return null;

        Composite parent = safeCast(toolBar.getParent(), Composite.class);
        try
        {
            if (action instanceof org.eclipse.jface.action.IAction ia)
                ia.run();
            else
            {
                java.lang.reflect.Method runMethod = action.getClass().getMethod("run"); //$NON-NLS-1$
                runMethod.invoke(action);
            }
        }
        catch (Exception e)
        {
            Debug.problem("createSearchBoxViaAction invoke failed: " + e); //$NON-NLS-1$
            return null;
        }

        SearchBox sb = parent != null ? findSearchBox(parent) : null;

        // TablesAndFieldsTab$6.run() (эта action) не идемпотентен — безусловно создаёт НОВЫЙ
        // SearchBox через addSearchBox() при каждом вызове, не проверяя, есть ли уже один. Отключение
        // самой action (setEnabled(false)) визуально красит её недоступной, но НЕ убирает исходный
        // ToolItem "Поиск" из тулбара — он остаётся, просто "сжатым" разовым layout-хаком нативного
        // кода при создании SearchBox; любой следующий relayout (потеря/восстановление фокуса,
        // Ctrl+F) может снова показать его (уже недоступным, но занимающим место — отсюда прыжок).
        // Реально убираем — dispose() самого ToolItem, а не только его action.
        if (sb != null)
        {
            if (action instanceof org.eclipse.jface.action.IAction ia)
                ia.setEnabled(false);
            if (!searchItem.isDisposed())
                searchItem.dispose();
        }
        return sb;
    }

    private static ToolBar findToolBar(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof ToolBar tb)
                return tb;
        }
        return null;
    }

    private static ToolBar findSearchToolBar(Composite root)
    {
        for (Control child : root.getChildren())
        {
            if (child instanceof ToolBar tb && findSearchToolItem(tb) != null)
                return tb;
            if (child instanceof Composite comp)
            {
                ToolBar found = findSearchToolBar(comp);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static ToolItem findSearchToolItem(ToolBar toolBar)
    {
        for (ToolItem item : toolBar.getItems())
        {
            String tip = item.getToolTipText();
            if (tip != null && (tip.contains("Поиск") || tip.toLowerCase().contains("search"))) //$NON-NLS-1$ //$NON-NLS-2$
                return item;
        }
        return null;
    }

    /**
     * Снимаем только чужой {@code TablesAndFieldsTab$7} (его {@code focusLost} делает
     * {@code searchBox.dispose()} — схлопывает наш поиск обратно в кнопку при потере фокуса).
     * Внутренний {@code SearchBox$5} (штатная подсказка при пустом поле — display/hideMessage)
     * не трогаем, иначе {@code setMessage} перестаёт что-либо показывать.
     */
    private static void stripFocusListeners(SearchBox searchBox)
    {
        try
        {
            Listener[] lost = searchBox.getListeners(SWT.FocusOut);
            for (Listener l : lost)
            {
                Object real = Global.unwrapTypedListener(l);
                String className = real.getClass().getName();
                if (className.startsWith("com._1c.g5.v8.dt.common.ui.controls.search.SearchBox$")) //$NON-NLS-1$
                    continue;
                searchBox.removeListener(SWT.FocusOut, l);
            }
        }
        catch (Exception e)
        {
            Debug.problem("stripFocusListeners: " + e); //$NON-NLS-1$
        }
    }

    private static void installFilter(TreeViewer tree, SearchBox searchBox, ToolBar searchToolBar)
    {
        org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider baseStyled =
            resolveStyledLabelProvider(tree);
        if (baseStyled == null)
        {
            Debug.problem("installFilter: baseStyled=null"); //$NON-NLS-1$
            return;
        }

        // skipHighlight = null: категория тоже может законно подсветиться в иерархическом режиме
        // ("справ.таблица" — фрагмент "справ" матчится в самой категории), не только "Таблица".
        SmartOutlineLabelProvider highlighter = new SmartOutlineLabelProvider(baseStyled, null, null, baseStyled);
        tree.setLabelProvider(new org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider(highlighter));

        AvailableTableSearchFilter filter = new AvailableTableSearchFilter(baseStyled);
        tree.addFilter(filter);

        searchBox.setMessage("Поиск по таблицам"); //$NON-NLS-1$
        searchBox.setMinimumSearchTextLength(3);
        searchBox.setHistory(searchHistory);
        installPopupOutsideClickCloser(searchBox);

        setSearchListener(searchBox,
            (SearchBox.ISearchListener) (text, monitor) -> onSearch(text, tree, filter, highlighter));

        installChangeTablesResetHook(tree, searchToolBar, filter);

        if (tree.getControl() instanceof Tree swtTree)
        {
            wireTreeCopy(swtTree);
            wireCtrlF(searchBox, swtTree);
        }

        Debug.log("installFilter filters=" + tree.getFilters().length); //$NON-NLS-1$
    }

    /** Текущие {@code SearchBox}/дерево, для которых нужно перехватывать Ctrl+F (см. {@link #wireCtrlF}). */
    private static volatile SearchBox ctrlFTargetSearchBox;
    private static volatile Tree ctrlFTargetTree;
    private static boolean ctrlFExecutionListenerInstalled;

    /**
     * Ctrl+F должен фокусировать наш {@code SearchBox} (штатная кнопка так и подписана —
     * "Поиск (Ctrl+f)"). Тот же архитектурный потолок, что и у Ctrl+C (см. {@link #wireTreeCopy}):
     * обычный {@code SWT.KeyDown} не долетает в модальном диалоге. Перехват — через
     * {@code ICommandService} на {@code org.eclipse.ui.edit.findReplace} (штатный ID команды
     * "Найти"), один слушатель на всё время работы плагина (не на каждое открытие окна).
     */
    private static void wireCtrlF(SearchBox searchBox, Tree tree)
    {
        ctrlFTargetSearchBox = searchBox;
        ctrlFTargetTree = tree;
        searchBox.addDisposeListener(e ->
        {
            if (ctrlFTargetSearchBox == searchBox)
            {
                ctrlFTargetSearchBox = null;
                ctrlFTargetTree = null;
            }
        });
        if (ctrlFExecutionListenerInstalled || PlatformUI.getWorkbench() == null)
            return;
        ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
        if (commandService == null)
            return;
        commandService.addExecutionListener(new IExecutionListener()
        {
            @Override
            public void preExecute(String commandId, ExecutionEvent event)
            {
                handlePossibleCtrlF(commandId);
            }

            @Override
            public void postExecuteSuccess(String commandId, Object returnValue)
            {
            }

            @Override
            public void notHandled(String commandId, NotHandledException exception)
            {
                handlePossibleCtrlF(commandId);
            }

            @Override
            public void postExecuteFailure(String commandId, ExecutionException exception)
            {
            }
        });
        ctrlFExecutionListenerInstalled = true;

        // Дополнительно — сырой SWT.KeyDown фильтр раньше сервиса биндингов Eclipse: тот на
        // Ctrl+F показывает свой попап "подсказка по клавишам" (ShowKeysUI$ShowKeysPopup) —
        // не всегда действенно (порядок регистрации фильтров не гарантирован), но Ctrl+C оказался
        // съеден нативным акселератором раньше SWT, а Ctrl+F может и не быть.
        searchBox.getDisplay().addFilter(SWT.KeyDown, event ->
        {
            if ((event.stateMask & SWT.CTRL) == 0 || Character.toLowerCase((char) event.keyCode) != 'f') //$NON-NLS-1$
                return;
            SearchBox sb = ctrlFTargetSearchBox;
            Tree t = ctrlFTargetTree;
            if (sb == null || sb.isDisposed() || t == null || t.isDisposed())
                return;
            Control focus = sb.getDisplay().getFocusControl();
            if (focus != t && focus != sb)
                return;
            lastCtrlFTimeMs = System.currentTimeMillis();
            event.doit = false;
            if (focus != sb)
                sb.forceFocus();
        });
    }

    private static void handlePossibleCtrlF(String commandId)
    {
        SearchBox searchBox = ctrlFTargetSearchBox;
        Tree tree = ctrlFTargetTree;
        if (searchBox == null || searchBox.isDisposed() || tree == null || tree.isDisposed())
            return;
        if (!"org.eclipse.ui.edit.findReplace".equals(commandId)) //$NON-NLS-1$
            return;
        Control focus = searchBox.getDisplay().getFocusControl();
        // Фокус уже там — не дёргаем forceFocus() повторно: preExecute и notHandled оба
        // срабатывают на одно нажатие (см. wireTreeCopy), второй вызов на уже сфокусированном
        // поле вызывал видимый "прыжок" (SearchBox что-то пересчитывает в layout при повторном
        // получении фокуса).
        if (focus == searchBox)
            return;
        if (focus != tree)
            return;
        lastCtrlFTimeMs = System.currentTimeMillis();
        searchBox.forceFocus();
    }

    /**
     * Ctrl+C при фокусе на дереве копирует текст выделенной строки. Тот же архитектурный потолок,
     * что и в {@code PreferenceSearchFilterAugmenter.wireTreeCopy}/{@code KeyBindingToastHook}:
     * буква C при зажатом Ctrl не порождает {@code SWT.KeyDown} в модальном диалоге — нативная
     * Win32-трансляция акселератора съедает её раньше. Перехват — через общий
     * {@link CopyCommandSupport}.
     */
    private static void wireTreeCopy(Tree tree)
    {
        CopyCommandSupport.wireCopyOverride(tree, () -> copyTreeSelection(tree));
    }

    /** Текст активной ячейки ({@link FormTreeInteraction}, если к дереву добавлена колонка «Тип»). */
    private static void copyTreeSelection(Tree tree)
    {
        if (tree.isDisposed())
            return;
        TreeItem[] selection = tree.getSelection();
        if (selection.length == 0)
            return;
        FormTreeInteraction interaction = FormTreeInteraction.of(tree);
        int column = interaction != null && interaction.activeColumn() < tree.getColumnCount()
            ? Math.max(0, interaction.activeColumn()) : 0;
        StringBuilder builder = new StringBuilder();
        for (TreeItem item : selection)
        {
            String cell = item.getText(column);
            if (cell == null || cell.isBlank())
                continue;
            if (builder.length() > 0)
                builder.append('\n');
            builder.append(cell);
        }
        String text = builder.toString();
        if (text.isEmpty())
            return;
        Clipboard clipboard = new Clipboard(tree.getDisplay());
        try
        {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        }
        finally
        {
            clipboard.dispose();
        }
    }

    /**
     * «Отображать таблицы изменений» (тумблер) сам делает только {@code tree.refresh()} (см.
     * декомпиль {@code TablesAndFieldsTab$4.runWithEvent}) — новые узлы {@code *.Изменения}
     * добавляются в модель, но ни разу не проходят через наш filter/label provider (тот же кэш
     * JFace, что уже чинили для непустого поиска). После клика по кнопке досбрасываем input сами.
     */
    private static void installChangeTablesResetHook(
        TreeViewer tree, ToolBar searchToolBar, AvailableTableSearchFilter filter)
    {
        if (searchToolBar == null)
            return;
        ToolItem changeTablesItem = findToolItemByTip(searchToolBar, "Отображать таблицы изменений"); //$NON-NLS-1$
        if (changeTablesItem == null)
            return;
        changeTablesItem.addListener(SWT.Selection, e ->
        {
            Object input = tree.getInput();
            tree.setInput(null);
            tree.setInput(input);
            if (filter.isFiltering())
            {
                tree.expandToLevel(2);
                for (org.eclipse.jface.viewers.TreePath target : filter.drainExpandTargets())
                    tree.setExpandedState(target, true);
            }
        });
    }

    private static ToolItem findToolItemByTip(ToolBar toolBar, String tipFragment)
    {
        for (ToolItem item : toolBar.getItems())
        {
            String tip = item.getToolTipText();
            if (tip != null && tip.contains(tipFragment))
                return item;
        }
        return null;
    }

    /**
     * {@code SearchBox} сам регистрирует {@code Display.addFilter(SWT.MouseDown, ...)} для
     * закрытия попапа истории по клику вне поля/попапа (см. {@code displayPopup}/{@code SearchBox$2}
     * в декомпиле) — но на практике попап не закрывается. Дублируем ту же логику своим
     * независимым фильтром через рефлексию (не мешает штатному, если он всё же сработает —
     * {@code hidePopup()} у него идемпотентен, второй вызов — no-op).
     */
    private static void installPopupOutsideClickCloser(SearchBox searchBox)
    {
        Display display = searchBox.getDisplay();
        if (display == null || display.isDisposed())
            return;
        Listener closer = event ->
        {
            if (searchBox.isDisposed())
                return;
            try
            {
                Field displayingPopupField = SearchBox.class.getDeclaredField("displayingPopup"); //$NON-NLS-1$
                displayingPopupField.setAccessible(true);
                if (!Boolean.TRUE.equals(displayingPopupField.get(searchBox)))
                    return;
                java.lang.reflect.Method getPopup = SearchBox.class.getDeclaredMethod("getPopup"); //$NON-NLS-1$
                getPopup.setAccessible(true);
                Object popup = getPopup.invoke(searchBox);
                if (event.widget == popup || event.widget == searchBox)
                    return;
                java.lang.reflect.Method hidePopup = SearchBox.class.getDeclaredMethod("hidePopup"); //$NON-NLS-1$
                hidePopup.setAccessible(true);
                hidePopup.invoke(searchBox);
            }
            catch (Exception e)
            {
                Debug.problem("installPopupOutsideClickCloser: " + e); //$NON-NLS-1$
            }
        };
        display.addFilter(SWT.MouseDown, closer);
        searchBox.addDisposeListener(e -> display.removeFilter(SWT.MouseDown, closer));
    }

    /**
     * "Таблица" (в т.ч. табличная часть на 3-м уровне) — это НЕ конкретный класс
     * ({@code DbViewTableDefImpl}/{@code DbViewSelectDefImpl} — таблицы 2-го уровня,
     * {@code DbViewFieldTableDefImpl} — табличная часть 3-го уровня), а маркерный интерфейс
     * {@code com._1c.g5.v8.dt.metadata.dbview.Table} ({@code getFields()}): через него проходят
     * {@code DbViewDef} (→ {@code DbViewTableDef}/{@code DbViewSelectDef}) и напрямую
     * {@code DbViewFieldTableDef} (табличная часть — "поле, которое само таблица"). Обычные поля
     * ({@code DbViewFieldFieldDef}/{@code DbViewVirtualFieldDef}) этот интерфейс не реализуют —
     * отсекаются автоматически, на любой глубине, без привязки к уровню/родителю.
     */
    private static boolean isTableLike(Object element)
    {
        return element instanceof com._1c.g5.v8.dt.metadata.dbview.Table;
    }

    /**
     * Только штатный обход дерева (JFace сам вызывает {@code select}/{@code getStyledText} для уже
     * раскрытых узлов) — без своего рекурсивного прохода по поддереву и без автораскрытия путей к
     * совпадениям: узлы "Таблица" на выбор пользователя разворачиваются штатно, а получение их
     * полей (дорогой BM/DB-запрос) не форсируется фильтром сверх того, что уже делает
     * {@code expandToLevel(2)} (тот, как выяснилось, и так материализует и таблицы, и их поля).
     */
    private static final class AvailableTableSearchFilter extends ViewerFilter
    {
        private final org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider styled;
        private SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
        /** Таблицы, показанные ТОЛЬКО из-за совпавшей табличной части внутри (не по своему имени)
         * — их и только их нужно точечно развернуть, чтобы совпадение было видно. Остальные
         * видимые таблицы разворачивать не нужно — иначе разворачивается вообще всё подряд.
         * Полный {@code TreePath} (не голый элемент) — {@code AvailableTablesContentProvider} не
         * реализует {@code getParent()}, поэтому {@code setExpandedState(Object,boolean)} не может
         * сам построить цепочку родителей для ещё не связанного с виджетом элемента; путь строим
         * явно из уже известного {@code parentElement}. */
        private final java.util.Set<org.eclipse.jface.viewers.TreePath> expandTargets =
            new java.util.LinkedHashSet<>();
        /** Узлы, совпавшие САМИ ПО СЕБЕ (не через проверку потомков) — все их потомки видимы без
         * дальнейшей фильтрации (иначе внутри найденной табличной части отрезались бы её же
         * несовпавшие поля — то, что и должно быть видно целиком, раз сама часть — и есть матч).
         * Заполняется по ходу {@link #select}, родитель проверяется раньше ребёнка (JFace всегда
         * так обходит), так что к моменту проверки ребёнка родитель уже отмечен. */
        private final java.util.Set<Object> fullyMatchedContainers =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        AvailableTableSearchFilter(
            org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider styled)
        {
            this.styled = styled;
        }

        void setPattern(String pattern)
        {
            matcher = new SmartMatcher(pattern != null ? pattern : ""); //$NON-NLS-1$
            expandTargets.clear();
            fullyMatchedContainers.clear();
        }

        boolean isFiltering()
        {
            return !matcher.isEmpty;
        }

        /** Забрать и сбросить накопленный за последний проход {@code select()} список путей,
         * которые нужно точечно развернуть (см. {@link #expandTargets}). */
        java.util.Set<org.eclipse.jface.viewers.TreePath> drainExpandTargets()
        {
            java.util.Set<org.eclipse.jface.viewers.TreePath> result = new java.util.LinkedHashSet<>(expandTargets);
            expandTargets.clear();
            return result;
        }

        @Override
        public boolean select(org.eclipse.jface.viewers.Viewer viewer, Object parentElement, Object element)
        {
            if (matcher.isEmpty)
                return true;
            if (!(viewer instanceof TreeViewer treeViewer))
                return true;
            Object cp = treeViewer.getContentProvider();
            if (!(cp instanceof org.eclipse.jface.viewers.ITreeContentProvider tcp))
                return true;

            String elemText = text(element);
            // Текст родителя заходит в матчинг ТОЛЬКО через иерархический режим
            // SmartMatcher.matchesTree ("родитель.имя" по секциям — откат к обычному matches()
            // при однословном фильтре без точки), а не отдельной независимой проверкой родителя.
            String parentText = parentElement != null ? text(parentElement) : ""; //$NON-NLS-1$
            String fullName = parentText.isEmpty() ? elemText : parentText + "." + elemText; //$NON-NLS-1$

            // Родитель уже совпал сам по себе — этот узел (и весь его поддерево) виден целиком,
            // без дальнейшей фильтрации по имени.
            if (parentElement != null && fullyMatchedContainers.contains(parentElement))
            {
                if (isTableLike(element))
                    fullyMatchedContainers.add(element);
                return true;
            }

            if (isTableLike(element))
            {
                if (matcher.matchesTree(fullName))
                {
                    fullyMatchedContainers.add(element);
                    return true;
                }
                // Собственное имя не совпало — таблица всё равно видима, если внутри неё есть
                // совпавшая табличная часть (3-й уровень); тогда саму таблицу нужно развернуть
                // точечно (markContainerOnMatch=true), чтобы совпадение было видно.
                return hasMatchingDescendant(tcp, element, elemText, 1, true, parentElement);
            }
            // Категория верхнего уровня (не Table, см. isTableLike) — скрываем, если внутри нет ни
            // одной совпавшей таблицы ИЛИ табличной части глубже (глубина 2: категория→таблица→
            // табличная часть) — иначе прячем всю ветку, даже если совпадение есть, просто на
            // 3-м уровне, а мы проверяли только 1-й (это и была причина «не находит ветку»).
            // Саму категорию разворачивать не нужно (markContainerOnMatch=false) — она и так
            // видна как верхний уровень.
            if (element instanceof com._1c.g5.v8.dt.qw.ui.utils.AvailableTable)
                return hasMatchingDescendant(tcp, element, elemText, 2, false, parentElement);
            // Обычное поле (не "Таблица", не категория) — видимо только при собственном совпадении.
            return matcher.matchesTree(fullName);
        }

        /**
         * Есть ли совпадение среди потомков (только "Таблица"/табличные части — обычные поля не
         * интересуют, у них своя видимость через {@link #select}), вглубь максимум
         * {@code remainingDepth} уровней. При найденном совпадении — если {@code markContainerOnMatch}
         * и {@code container} сам "Таблица" (не категория) — кладёт {@code TreePath(containerParent,
         * container)} в {@link #expandTargets}, чтобы потом развернуть точечно именно этот
         * контейнер, а не всё дерево.
         */
        private boolean hasMatchingDescendant(org.eclipse.jface.viewers.ITreeContentProvider tcp,
            Object container, String containerText, int remainingDepth, boolean markContainerOnMatch,
            Object containerParent)
        {
            if (remainingDepth <= 0)
                return false;
            boolean found = false;
            for (Object child : tcp.getChildren(container))
            {
                if (!isTableLike(child) && !(child instanceof com._1c.g5.v8.dt.qw.ui.utils.AvailableTable))
                    continue;
                String childText = text(child);
                String fullName = containerText.isEmpty() ? childText : containerText + "." + childText; //$NON-NLS-1$
                if (matcher.matchesTree(fullName) || (remainingDepth > 1
                    && hasMatchingDescendant(tcp, child, fullName, remainingDepth - 1, true, container)))
                {
                    found = true;
                    break;
                }
            }
            if (found && markContainerOnMatch && isTableLike(container))
            {
                Object[] segments = containerParent != null
                    ? new Object[] { containerParent, container } : new Object[] { container };
                expandTargets.add(new org.eclipse.jface.viewers.TreePath(segments));
            }
            return found;
        }

        private String text(Object element)
        {
            org.eclipse.jface.viewers.StyledString text = styled.getStyledText(element);
            return text != null ? text.getString() : ""; //$NON-NLS-1$
        }
    }

    private static org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider
        resolveStyledLabelProvider(TreeViewer tree)
    {
        Object lp = tree.getLabelProvider();
        if (lp instanceof org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider delegating)
            return delegating.getStyledStringProvider();
        return null;
    }

    private static void setSearchListener(SearchBox searchBox, Object listener)
    {
        try
        {
            Field f = SearchBox.class.getDeclaredField("searchListener"); //$NON-NLS-1$
            f.setAccessible(true);
            f.set(searchBox, listener);
        }
        catch (Exception e)
        {
            Debug.problem("setSearchListener: " + e); //$NON-NLS-1$
        }
    }

    /**
     * Непустой фильтр — полный сброс input (см. ниже, почему) + {@code expandToLevel(2)}
     * (категории → таблицы; {@code (1)} на этом дереве оказался no-op) + точечное раскрытие
     * КОНКРЕТНЫХ таблиц, у которых совпадение только в табличной части (см.
     * {@link AvailableTableSearchFilter#drainExpandTargets}) — не блочный
     * {@code expandToLevel(3)}, который разворачивал вообще все видимые таблицы без разбора.
     * Очистка — сворачиваем всё ({@code collapseAll}), но восстанавливаем текущую строку:
     * запоминаем выделение ДО сворачивания и ставим обратно через {@code setSelection(..., true)}
     * — {@code reveal=true} штатно разворачивает только путь к ней, а не всё дерево.
     */
    private static void onSearch(String text, TreeViewer tree, AvailableTableSearchFilter filter,
        SmartOutlineLabelProvider highlighter)
    {
        filter.setPattern(text);
        highlighter.setHighlightPattern(text);
        if (text == null || text.isEmpty())
        {
            org.eclipse.jface.viewers.ISelection selection = tree.getSelection();
            tree.collapseAll();
            tree.refresh();
            if (!selection.isEmpty())
                tree.setSelection(selection, true);
            return;
        }
        // collapseAll()+expandToLevel()+refresh() не заставляли JFace переспросить select() для
        // уже материализованных узлов "Таблица" — их select() не вызывался вообще, в отличие от их
        // полей. Полный сброс input — гарантированно сбрасывает все ассоциации на всех уровнях
        // дерева, без гадания о конкретном месте кэширования в JFace.
        Object input = tree.getInput();
        tree.setInput(null);
        tree.setInput(input);
        tree.expandToLevel(2);
        for (org.eclipse.jface.viewers.TreePath target : filter.drainExpandTargets())
            tree.expandToLevel(target, 1);
    }

    /**
     * Колонка «Тип» во всех списках полей конструктора (#839) и флажок её показа внизу окна, слева от
     * кнопок отмены/повтора. Колонка добавляется последней: штатный код обращается к своим колонкам по
     * индексу. Списки — по классу поставщика содержимого ({@link #TYPED_CONTENT}); список псевдонимов,
     * колонки которого EDT пересчитывает по числу запросов объединения, — только при переплетённом
     * подсчёте ({@link QueryWizardHook#installWeavingHook}).
     *
     * <p>Вкладки ({@code AbstractLazyTab}) строят свои деревья при первой отрисовке, поэтому деревья
     * ищутся заново при каждой отрисовке вкладки. Штатный {@code setLabelProvider} у списка (заново —
     * при каждой смене текста запроса, {@code QueryWizardControl.setTreesProviders}) переставляет
     * поставщик подписей ВСЕХ колонок, в том числе нашей, а вкладка «Итоги» ещё и вешает на каждую
     * колонку свою правку ячейки — обе подмены снимаются при ближайшей отрисовке дерева.
     *
     * <p>Тип поля — как в самом конструкторе ({@code SpreadsheetDocumentTemplateBuilder.getDataFormat}):
     * {@code computeDbView} выражения и {@code getFieldType} его представления. Представление и
     * объект метаданных поля дают в контекстном меню «Открыть в редакторе» и «Показать в навигаторе».
     */
    private static final class TypeColumns
    {
        private static final String SETTINGS_SECTION = "QueryWizardHook"; //$NON-NLS-1$
        private static final String KEY_VISIBLE = "typeColumnVisible"; //$NON-NLS-1$
        private static final String KEY_WIDTH_PREFIX = "typeColumnWidth."; //$NON-NLS-1$
        private static final int DEFAULT_WIDTH = 70;
        private static final int MIN_WIDTH = 20;
        private static final String MENU_KEY = "tormozit.queryWizardFieldMenu"; //$NON-NLS-1$
        private static final String SCAN_AFTER_INIT_KEY = "tormozit.queryWizardScanAfterInit"; //$NON-NLS-1$
        private static final String MENU_ITEM_KEY = "tormozit.queryWizardFieldMenuItem"; //$NON-NLS-1$

        /** Списки полей и выражений — по простому имени класса штатного поставщика содержимого. */
        private static final Set<String> TYPED_CONTENT = Set.of(
            "AvailableTablesContentProvider", "SourcesContentProvider", "FieldsContentProvider", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "AvailableFieldsContentProvider", "AvailableAliasesContentProvider", "GroupingsContentProvider", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "AggregatesContentProvider", "OrderContentProvider", "IndexContentProvider", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "TotalsControlsPointsContentProvider", "TotalExpressionsContentProvider", //$NON-NLS-1$ //$NON-NLS-2$
            "DcsFieldsContentProvider", "DcsConditionsContentProvider"); //$NON-NLS-1$ //$NON-NLS-2$

        /** Список псевдонимов — только при переплетённом подсчёте колонок (см. {@link QueryWizardHook#installWeavingHook}). */
        private static final String ALIASES_CONTENT = "AliasesContentProvider"; //$NON-NLS-1$

        private final Object control;
        private final Display display;
        /** Все найденные деревья вкладок; {@code null} — дерево не со списком полей. */
        private final Map<TreeViewer, TreeState> trees = new IdentityHashMap<>();
        /** Представление поля на время одного цикла событий: подписи колонки спрашивают его по нескольку раз. */
        private final Map<Object, Optional<DbViewElement>> dbViews = new IdentityHashMap<>();
        private boolean dbViewsClearScheduled;
        private boolean visible;

        private TypeColumns(Object control, Display display)
        {
            this.control = control;
            this.display = display;
        }

        static boolean install(Object queryWizardControl)
        {
            if (!(getField(queryWizardControl, "undoButton") instanceof Button undo) || undo.isDisposed()) //$NON-NLS-1$
                return false;
            TypeColumns columns = new TypeColumns(queryWizardControl, undo.getDisplay());
            IDialogSettings settings = settings();
            columns.visible = settings.get(KEY_VISIBLE) != null && settings.getBoolean(KEY_VISIBLE);
            columns.createToggle(undo);
            // Вкладки EDT пересоздаёт при смене вида запроса (QueryWizardControl.updateTabsContains),
            // поэтому вкладки, появившиеся позже, подхватываются при отрисовке полосы вкладок.
            if (getField(queryWizardControl, "tabFolder") instanceof CTabFolder folder && !folder.isDisposed()) //$NON-NLS-1$
                folder.addListener(SWT.Paint, e -> columns.scanTabs());
            // Флажок уже создан: повторная попытка подключения добавила бы второй.
            runIsolated("scanTabs", columns::scanTabs); //$NON-NLS-1$
            return true;
        }

        private void scanTabs()
        {
            for (Object tab : tabs())
            {
                scan(tab);
                // У скрытой сейчас вкладки в tabItem остаётся уже уничтоженный CTabItem.
                if (getField(tab, "tabItem") instanceof CTabItem item && !item.isDisposed() //$NON-NLS-1$
                    && item.getControl() != null && !item.getControl().isDisposed()
                    && item.getControl().getData(SCAN_AFTER_INIT_KEY) == null
                    && !Boolean.TRUE.equals(Global.invoke(tab, "isInit"))) //$NON-NLS-1$
                {
                    item.getControl().setData(SCAN_AFTER_INIT_KEY, Boolean.TRUE);
                    scanAfterInit(tab, item.getControl());
                }
            }
        }

        /**
         * Штатная ленивая инициализация вкладки — её же слушатель отрисовки, зарегистрированный раньше
         * нашего. Деревья ищем после этой отрисовки (не внутри неё: добавление колонки обновляет
         * дерево) и один раз — после инициализации вкладки состав её деревьев не меняется.
         */
        private void scanAfterInit(Object tab, Control tabControl)
        {
            Listener[] listener = new Listener[1];
            listener[0] = e ->
            {
                tabControl.removeListener(SWT.Paint, listener[0]);
                display.asyncExec(() ->
                {
                    if (!tabControl.isDisposed())
                        scan(tab);
                });
            };
            tabControl.addListener(SWT.Paint, listener[0]);
        }

        private void createToggle(Button undo)
        {
            Composite bar = undo.getParent();
            if (!(bar.getLayout() instanceof GridLayout layout))
                return;
            Button toggle = new Button(bar, SWT.CHECK);
            toggle.setText("Типы"); //$NON-NLS-1$
            toggle.setToolTipText(TooltipText.wrap(toggle,
                "Показывать колонку «Тип» во всех списках полей конструктора" + Global.pluginSignForTooltip())); //$NON-NLS-1$
            toggle.setSelection(visible);
            toggle.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, true));
            toggle.moveAbove(undo);
            layout.numColumns++;
            toggle.addListener(SWT.Selection, e -> setVisible(toggle.getSelection()));
            bar.requestLayout();
        }

        private void setVisible(boolean show)
        {
            visible = show;
            settings().put(KEY_VISIBLE, show);
            for (TreeState state : trees.values())
            {
                if (state != null)
                    state.apply(show);
            }
        }

        /** Вкладки конструктора — поля {@code QueryWizardControl} с собственной вкладкой {@code tabItem}. */
        private List<Object> tabs()
        {
            List<Object> result = new ArrayList<>();
            for (Object value : fieldValues(control, Object.class))
            {
                if (getField(value, "tabItem") instanceof CTabItem) //$NON-NLS-1$
                    result.add(value);
            }
            return result;
        }

        private void scan(Object tab)
        {
            for (Map.Entry<String, Object> entry : namedFieldValues(tab, TreeViewer.class).entrySet())
            {
                TreeViewer viewer = (TreeViewer)entry.getValue();
                if (trees.containsKey(viewer) || viewer.getControl() == null || viewer.getControl().isDisposed()
                    || viewer.getContentProvider() == null)
                    continue;
                String content = viewer.getContentProvider().getClass().getSimpleName();
                if (!TYPED_CONTENT.contains(content) && !(aliasesTabWoven && ALIASES_CONTENT.equals(content)))
                {
                    trees.put(viewer, null);
                    continue;
                }
                TreeState state = new TreeState(viewer, tab.getClass().getSimpleName() + "." + entry.getKey()); //$NON-NLS-1$
                trees.put(viewer, state);
                state.apply(visible);
            }
        }

        /** Поля классов EDT (без унаследованных полей SWT/JFace — у {@code QueryWizardControl} это Composite). */
        private static Map<String, Object> namedFieldValues(Object owner, Class<?> type)
        {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            for (Class<?> c = owner.getClass(); c != null && c.getName().startsWith("com._1c."); c = c.getSuperclass()) //$NON-NLS-1$
            {
                for (Field field : c.getDeclaredFields())
                {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())
                        continue;
                    Object value = getField(owner, field.getName());
                    if (type.isInstance(value))
                        result.putIfAbsent(field.getName(), value);
                }
            }
            return result;
        }

        private static java.util.Collection<Object> fieldValues(Object owner, Class<?> type)
        {
            return namedFieldValues(owner, type).values();
        }

        private TypeDescription typeOf(Object element)
        {
            DbViewElement view = dbView(element);
            if (!(view instanceof DbViewFieldDef field) || view instanceof Table)
                return null;
            TypeDescription type = field.getType();
            if (type != null && !type.getTypes().isEmpty())
                return type;
            if (!(Global.invoke(control, "getQuerySchemaSource") instanceof QueryWizardSource source)) //$NON-NLS-1$
                return null;
            try
            {
                return source.getDerivedTypeDbViewComputer().getFieldType(view).getFirst();
            }
            catch (RuntimeException e)
            {
                Debug.problem("typeOf: " + e); //$NON-NLS-1$
                return null;
            }
        }

        /**
         * Владелец описания типов для меню колонки ({@code SelectTypeDialogHook.openReadOnly} берёт из
         * него доступные типы и проект): реквизит метаданных поля, если у него ровно одно описание типов,
         * иначе само представление поля.
         */
        private EObject contextOf(Object element)
        {
            DbViewElement view = dbView(element);
            EObject md = view != null ? view.getMdObject() : null;
            if (md != null)
            {
                long typeFeatures = md.eClass().getEAllReferences().stream()
                    .filter(reference -> reference.getEReferenceType() == McorePackage.Literals.TYPE_DESCRIPTION)
                    .count();
                if (typeFeatures == 1)
                    return md;
            }
            return view != null ? view : element instanceof EObject object ? object : null;
        }

        /** Объект метаданных поля для «Открыть в редакторе» и «Показать в навигаторе». */
        private EObject mdObjectOf(Object element)
        {
            return dbView(element) instanceof DbViewFieldDef field ? field.getMdObject() : null;
        }

        private DbViewElement dbView(Object element)
        {
            if (element == null)
                return null;
            if (!dbViewsClearScheduled)
            {
                dbViewsClearScheduled = true;
                display.asyncExec(() ->
                {
                    dbViews.clear();
                    dbViewsClearScheduled = false;
                });
            }
            return dbViews.computeIfAbsent(element, key -> Optional.ofNullable(computeDbView(key))).orElse(null);
        }

        private static DbViewElement computeDbView(Object element)
        {
            Object item = element instanceof QuerySchemaOrderExpression order ? order.getItem()
                : element instanceof QuerySchemaTotalControlPoint point ? point.getExpression() : element;
            if (item instanceof DbViewElement view)
                return view;
            if (!(item instanceof QuerySchemaExpression expression) || expression.getExpression() == null)
                return null;
            try
            {
                return QueryWizardServiceUtils.computeDbView(expression.getExpression());
            }
            catch (RuntimeException e)
            {
                Debug.problem("computeDbView: " + e); //$NON-NLS-1$
                return null;
            }
        }

        private static IDialogSettings settings()
        {
            IDialogSettings top = Activator.getDefault().getDialogSettings();
            IDialogSettings section = top.getSection(SETTINGS_SECTION);
            return section != null ? section : top.addNewSection(SETTINGS_SECTION);
        }

        /** Колонка «Тип» одного списка и меню его полей. */
        private final class TreeState
        {
            private final TreeViewer viewer;
            private final Tree tree;
            private final String widthKey;
            private TreeViewerColumn column;
            private ValueTypeColumnLabelProvider labels;
            private boolean restoreScheduled;

            TreeState(TreeViewer viewer, String key)
            {
                this.viewer = viewer;
                this.tree = viewer.getTree();
                this.widthKey = KEY_WIDTH_PREFIX + key;
                tree.addListener(SWT.Paint, e -> restoreColumnIfReplaced());
                tree.addListener(SWT.Dispose, e -> rememberWidth());
                tree.addListener(SWT.MenuDetect, e -> hookMenu());
                tree.addListener(SWT.MouseMove, e -> releaseNativeToolTip(e.x, e.y));
                tree.addListener(SWT.MouseHover, e ->
                {
                    int x = e.x;
                    int y = e.y;
                    display.asyncExec(() -> releaseNativeToolTip(x, y));
                });
            }

            /**
             * Штатная подсказка списка ({@code QueryWizardTreeViewer.createToolTip}, JFace
             * {@code DefaultToolTip}) при каждом наведении ставит дереву {@code setToolTipText("")}, а
             * пустой текст глушит системную подсказку обрезанной ячейки. Строку под указателем она находит
             * штатным попаданием дерева без {@code SWT.FULL_SELECTION} — в колонке «Тип» только у
             * выделенной строки. Где ячейку она не нашла ({@code getCell == null}), возвращаем {@code null}:
             * обрезанный текст покажет сама Windows; в выделенной строке остаётся одна штатная подсказка.
             * На {@code MouseMove} — до запроса системной подсказки, после наведения — вслед штатной.
             */
            private void releaseNativeToolTip(int x, int y)
            {
                if (column == null || tree.isDisposed() || tree.getToolTipText() == null)
                    return;
                if (viewer.getCell(new org.eclipse.swt.graphics.Point(x, y)) == null)
                    tree.setToolTipText(null);
            }

            void apply(boolean show)
            {
                if (show && column == null)
                    createColumn();
                else if (!show && column != null)
                    disposeColumn();
            }

            private void createColumn()
            {
                if (!(tree.getParent().getLayout() instanceof TreeColumnLayout layout))
                    return;
                // Заполнение ширины держит штатный TreeColumnLayout весом основной колонки, но любое
                // изменение ширины колонки вне раскладки (перетаскивание границы) навсегда заменяет
                // её вес фиксированной шириной — с двумя колонками поле перестаёт заполняться. Ширины
                // ведёт общий ColumnAutoFit (как у «Выбор реквизита»): «Тип» фиксирована, остаток —
                // основной колонке; его setWidth раскладка лишь запоминает фиксированными ширинами.
                ColumnAutoFit.install(tree, null, this::isTypeColumn);
                column = new TreeViewerColumn(viewer, SWT.NONE);
                TreeColumn swtColumn = column.getColumn();
                // Как штатный QueryWizardTreeViewer.createTreeColumn: вкладка «Итоги» берёт
                // TreeViewerColumn из getData() каждой колонки своего списка.
                swtColumn.setData(column);
                swtColumn.setData(TYPE_COLUMN_KEY, Boolean.TRUE);
                swtColumn.setText("Тип"); //$NON-NLS-1$
                swtColumn.setToolTipText(TooltipText.wrap(tree, "Тип значения поля" + Global.pluginSignForTooltip())); //$NON-NLS-1$
                // Сначала нулевая ширина: колонки по-прежнему ровно заполняют поле, и setColumnWidth
                // ниже отдаёт место «Типу» за счёт основной колонки, а не уводит в прокрутку.
                layout.setColumnData(swtColumn, new ColumnPixelData(0, true, false));
                labels = new ValueTypeColumnLabelProvider(tree, TypeColumns.this::typeOf, TypeColumns.this::contextOf, null)
                {
                    // Ячейка без своего шрифта рисуется шрифтом предыдущей ячейки строки: в списке
                    // псевдонимов это жирное имя поля (AliasesLabelProvider.getFont задаёт шрифт явно
                    // каждой колонке). Задаём явно и мы.
                    @Override
                    public org.eclipse.swt.graphics.Font getFont(Object element)
                    {
                        return tree.getFont();
                    }
                };
                column.setLabelProvider(labels);
                // Штатное дерево создано без SWT.FULL_SELECTION: клик в колонке «Тип» строку не выбирает.
                FormTreeInteraction.install(tree, viewer);
                CopyCommandSupport.wireCopyOverride(tree, () -> copyTreeSelection(tree));
                tree.getParent().layout(true);
                ColumnAutoFit.setColumnWidth(swtColumn,
                    FormTableColumnState.readWidth(settings(), widthKey, DEFAULT_WIDTH, MIN_WIDTH));
                viewer.refresh();
            }

            private boolean isTypeColumn(int index)
            {
                return column != null && !column.getColumn().isDisposed()
                    && tree.indexOf(column.getColumn()) == index;
            }

            private void disposeColumn()
            {
                rememberWidth();
                column.getColumn().dispose();
                column = null;
                labels = null;
                tree.getParent().layout(true);
                // Освободившееся место — основной колонке (подгонка ColumnAutoFit по программной ширине).
                if (tree.getColumnCount() > 0)
                    ColumnAutoFit.setColumnWidth(tree.getColumn(0), tree.getColumn(0).getWidth());
            }

            private void rememberWidth()
            {
                if (column == null || column.getColumn().isDisposed())
                    return;
                int width = column.getColumn().getWidth();
                if (width >= MIN_WIDTH)
                    settings().put(widthKey, width);
            }

            /** Штатный {@code setLabelProvider} списка переставил подписи и нашей колонке — возвращаем свои. */
            private void restoreColumnIfReplaced()
            {
                if (column == null || restoreScheduled || column.getColumn().isDisposed())
                    return;
                int index = tree.indexOf(column.getColumn());
                if (index < 0 || viewer.getLabelProvider(index) == labels)
                    return;
                restoreScheduled = true;
                display.asyncExec(() ->
                {
                    restoreScheduled = false;
                    if (column == null || tree.isDisposed() || column.getColumn().isDisposed())
                        return;
                    column.setLabelProvider(labels);
                    column.setEditingSupport(null);
                    viewer.refresh();
                });
            }

            private void hookMenu()
            {
                Menu menu = tree.getMenu();
                if (menu == null || menu.isDisposed())
                {
                    menu = new Menu(tree);
                    tree.setMenu(menu);
                }
                if (menu.getData(MENU_KEY) != null)
                    return;
                menu.setData(MENU_KEY, Boolean.TRUE);
                menu.addListener(SWT.Show, e -> fillMenu((Menu)e.widget));
            }

            private void fillMenu(Menu menu)
            {
                for (MenuItem item : menu.getItems())
                {
                    if (item.getData(MENU_ITEM_KEY) != null)
                        item.dispose();
                }
                TreeItem[] selection = tree.getSelection();
                EObject md = selection.length == 1 ? mdObjectOf(selection[0].getData()) : null;
                if (md == null)
                    return;
                if (menu.getItemCount() > 0)
                    new MenuItem(menu, SWT.SEPARATOR).setData(MENU_ITEM_KEY, Boolean.TRUE);
                MenuItem open = new MenuItem(menu, SWT.PUSH);
                open.setData(MENU_ITEM_KEY, Boolean.TRUE);
                open.setText("Открыть в редакторе"); //$NON-NLS-1$
                ComfortSubmenuHelper.setMenuItemTooltip(open, "Открыть редактор объекта метаданных поля"); //$NON-NLS-1$
                open.addListener(SWT.Selection, e -> runFieldCommand(md, false));
                MenuItem reveal = new MenuItem(menu, SWT.PUSH);
                reveal.setData(MENU_ITEM_KEY, Boolean.TRUE);
                reveal.setText("Показать в навигаторе"); //$NON-NLS-1$
                ComfortSubmenuHelper.setMenuItemTooltip(reveal, "Показать объект метаданных поля в навигаторе"); //$NON-NLS-1$
                reveal.addListener(SWT.Selection, e -> runFieldCommand(md, true));
            }

            private void runFieldCommand(EObject md, boolean reveal)
            {
                display.asyncExec(() ->
                {
                    try
                    {
                        if (reveal)
                            NavigatorReveal.revealAndActivateIfHidden(md);
                        else
                            new OpenHelper().openEditor(md);
                    }
                    catch (RuntimeException e)
                    {
                        String title = reveal ? "Показать в навигаторе" : "Открыть в редакторе"; //$NON-NLS-1$ //$NON-NLS-2$
                        Global.logError(Debug.TAG, title, e);
                        ToastNotification.show(title, "Не удалось выполнить команду: " + e.getMessage(), 5000); //$NON-NLS-1$
                    }
                });
            }
        }
    }

    /**
     * Число элементов собственных списков вкладки в её заголовке — «Группировки (0)»; у вкладки с
     * несколькими списками — их сумма. Каталоги доступных таблиц и полей не считаются.
     *
     * <p>Вкладки ленивые: их списки создаются при первом показе, а число нужно и до него. Поэтому
     * считаются не строки виджетов, а верхние элементы тех же штатных поставщиков содержимого
     * ({@link #LISTS}) — все они строятся от {@code QueryWizardSource}, получают его же входом и
     * ничего не регистрируют. Базовый заголовок берётся оттуда же, откуда его берёт EDT
     * ({@code QueryWizardControl.addTab}: {@code getData()} контрола вкладки) — вкладки
     * пересоздаются при смене вида запроса.
     *
     * <p>Пересчёт — по уведомлениям {@code QueryWizardSource} ({@link java.util.Observable}); источник
     * заменяется целиком при смене текста запроса и отмене, это замечается при отрисовке полосы
     * вкладок и списков.
     */
    @SuppressWarnings("deprecation")
    private static final class TabCounts implements java.util.Observer
    {
        private static final String PACKAGE = "com._1c.g5.v8.dt.qw.ui.contentproviders."; //$NON-NLS-1$
        private static final String HOOKED_KEY = "tormozit.queryWizardTabCounts"; //$NON-NLS-1$

        /** Собственные списки вкладки — классы поставщиков содержимого в порядке на экране. */
        private static final Map<String, List<String>> LISTS = Map.ofEntries(
            Map.entry("QueryBatchTab", List.of("QueryBatchContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("TablesAndFieldsTab", List.of("SourcesContentProvider", "FieldsContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Map.entry("JoinsTab", List.of("JoinsContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("GroupingsTab", List.of("GroupingsContentProvider", "AggregatesContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Map.entry("ConditionsTab", List.of("ConditionsContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("AdditionallyTab", List.of("TablesForUpdateContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("AliasesTab", List.of("OperatorsContentProvider", "AliasesContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Map.entry("OrderTab", List.of("OrderContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("IndexTab", List.of("IndexSetsContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("TotalsTab", List.of("TotalsControlsPointsContentProvider", "TotalExpressionsContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Map.entry("DataCompositionTab", //$NON-NLS-1$
                List.of("JoinsContentProvider", "DcsFieldsContentProvider", "DcsConditionsContentProvider")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Map.entry("CharacteristicsTab", List.of("DcsCharacteristicsContentProvider"))); //$NON-NLS-1$ //$NON-NLS-2$

        private final Object control;
        private final Display display;
        private final Map<String, IStructuredContentProvider> providers = new java.util.HashMap<>();
        /** Суффикс « (N)» по классу вкладки. */
        private final Map<String, String> suffixes = new java.util.HashMap<>();
        private QueryWizardSource source;
        private boolean recountScheduled;

        private TabCounts(Object control, Display display)
        {
            this.control = control;
            this.display = display;
        }

        static void install(Object queryWizardControl)
        {
            if (!(getField(queryWizardControl, "tabFolder") instanceof CTabFolder folder) || folder.isDisposed()) //$NON-NLS-1$
                return;
            TabCounts counts = new TabCounts(queryWizardControl, folder.getDisplay());
            folder.addListener(SWT.Paint, e -> counts.onPaint());
            folder.addListener(SWT.Dispose, e -> counts.setSource(null));
            counts.onPaint();
        }

        @Override
        public void update(java.util.Observable observable, Object argument)
        {
            scheduleRecount();
        }

        /** Полоса вкладок: заодно подключаемся к спискам вкладок, созданным с прошлого раза. */
        private void onPaint()
        {
            checkSource();
            for (Object tab : tabs())
            {
                for (Object viewer : TypeColumns.namedFieldValues(tab, TreeViewer.class).values())
                {
                    Control list = ((TreeViewer)viewer).getControl();
                    if (list != null && !list.isDisposed() && list.getData(HOOKED_KEY) == null)
                    {
                        list.setData(HOOKED_KEY, Boolean.TRUE);
                        list.addListener(SWT.Paint, e -> checkSource());
                    }
                }
            }
            applyTitles();
        }

        /** Источник заменён целиком (новый текст запроса, отмена) — подписаться на новый и пересчитать. */
        private void checkSource()
        {
            Object current = Global.invoke(control, "getQuerySchemaSource"); //$NON-NLS-1$
            if (current != source)
                setSource(current instanceof QueryWizardSource wizardSource ? wizardSource : null);
        }

        private void setSource(QueryWizardSource next)
        {
            if (source != null)
                source.deleteObserver(this);
            source = next;
            providers.clear();
            if (source != null)
            {
                source.addObserver(this);
                scheduleRecount();
            }
        }

        private void scheduleRecount()
        {
            if (recountScheduled)
                return;
            recountScheduled = true;
            display.asyncExec(() ->
            {
                recountScheduled = false;
                recount();
                applyTitles();
            });
        }

        private void recount()
        {
            suffixes.clear();
            if (source == null)
                return;
            for (Object tab : tabs())
            {
                String tabClass = tab.getClass().getSimpleName();
                List<String> names = LISTS.get(tabClass);
                if (names == null)
                    continue;
                Integer total = 0;
                for (String name : names)
                {
                    Integer count = count(tab, name);
                    total = count != null && total != null ? total + count : null;
                }
                suffixes.put(tabClass, " (" + (total != null ? total.toString() : "?") + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }

        private Integer count(Object tab, String providerName)
        {
            try
            {
                String key = tab.getClass().getSimpleName() + "." + providerName; //$NON-NLS-1$
                IStructuredContentProvider provider = providers.get(key);
                if (provider == null)
                {
                    provider = createProvider(tab, providerName);
                    if (provider == null)
                        return null;
                    providers.put(key, provider);
                }
                Object[] elements = provider.getElements(source);
                return elements != null ? elements.length : 0;
            }
            catch (RuntimeException | ReflectiveOperationException | LinkageError e)
            {
                Debug.problem("TabCounts " + providerName + ": " + e); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            }
        }

        /** Как во {@code setTreeProviders} вкладок: от источника; «Связи» компоновки — в режиме СКД. */
        private IStructuredContentProvider createProvider(Object tab, String providerName)
            throws ReflectiveOperationException
        {
            Class<?> type = tab.getClass().getClassLoader().loadClass(PACKAGE + providerName);
            Object provider;
            try
            {
                provider = type.getConstructor(QueryWizardSource.class).newInstance(source);
            }
            catch (NoSuchMethodException e)
            {
                try
                {
                    provider = type.getConstructor(QueryWizardSource.class, boolean.class).newInstance(source,
                        "DataCompositionTab".equals(tab.getClass().getSimpleName())); //$NON-NLS-1$
                }
                catch (NoSuchMethodException noSourceArgument)
                {
                    provider = type.getConstructor().newInstance();
                }
            }
            return provider instanceof IStructuredContentProvider structured ? structured : null;
        }

        private void applyTitles()
        {
            for (Object tab : tabs())
            {
                if (!(getField(tab, "tabItem") instanceof CTabItem item) || item.isDisposed() //$NON-NLS-1$
                    || item.getControl() == null || item.getControl().getData() == null)
                    continue;
                String suffix = suffixes.get(tab.getClass().getSimpleName());
                String text = item.getControl().getData().toString() + (suffix != null ? suffix : ""); //$NON-NLS-1$
                if (!text.equals(item.getText()))
                    item.setText(text);
            }
        }

        /** Все вкладки конструктора ({@code QueryWizardControl.tabs}), в том числе скрытые сейчас. */
        private java.util.Collection<?> tabs()
        {
            return getField(control, "tabs") instanceof Map<?, ?> tabs ? tabs.values() : List.of(); //$NON-NLS-1$
        }
    }

    /**
     * Конструктор, открытый из модуля (команды {@code com._1c.g5.v8.dt.bsl.qw}), получает весь литерал —
     * весь пакет — и открывается на первом запросе. Номер запроса пакета под кареткой считается перед
     * выполнением команды и выбирается в «Редактируемый запрос» штатным обработчиком выбора списка.
     * Окно модальное, поэтому номер живёт только пока выполняется команда.
     */
    private static final class BatchQueryAtCaret
    {
        private static final Set<String> COMMANDS = Set.of(
            "com._1c.g5.v8.dt.bsl.qw.BslQueryWizardHandler", //$NON-NLS-1$
            "com._1c.g5.v8.dt.bsl.qw.BslQueryWizardWithResultHandler"); //$NON-NLS-1$

        private static int pendingIndex = -1;
        /** Модуль, из которого вызвана команда, — для «Открыть конструктор ИР» в уведомлении о комментариях. */
        private static java.lang.ref.WeakReference<BslXtextEditor> pendingEditor;
        private static boolean listenerInstalled;

        static BslXtextEditor sourceEditor()
        {
            return pendingEditor != null ? pendingEditor.get() : null;
        }

        static void installListener()
        {
            if (listenerInstalled || PlatformUI.getWorkbench() == null)
                return;
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService == null)
                return;
            listenerInstalled = true;
            commandService.addExecutionListener(new IExecutionListener()
            {
                @Override
                public void preExecute(String commandId, ExecutionEvent event)
                {
                    if (!COMMANDS.contains(commandId))
                        return;
                    pendingIndex = caretQueryIndex(event);
                    BslXtextEditor editor = GetRef.getActiveBslEditor(HandlerUtil.getActivePart(event));
                    pendingEditor = editor != null ? new java.lang.ref.WeakReference<>(editor) : null;
                }

                @Override
                public void postExecuteSuccess(String commandId, Object returnValue)
                {
                    clearIfOwn(commandId);
                }

                @Override
                public void postExecuteFailure(String commandId, ExecutionException exception)
                {
                    clearIfOwn(commandId);
                }

                @Override
                public void notHandled(String commandId, NotHandledException exception)
                {
                    clearIfOwn(commandId);
                }

                private void clearIfOwn(String commandId)
                {
                    if (!COMMANDS.contains(commandId))
                        return;
                    pendingIndex = -1;
                    pendingEditor = null;
                }
            });
        }

        /** Первое окно конструктора, открытое командой, переключается на запрос под кареткой. */
        static void apply(Object queryWizardControl)
        {
            int index = pendingIndex;
            pendingIndex = -1;
            if (index <= 0 || !(getField(queryWizardControl, "queriesCombo") instanceof Combo combo) //$NON-NLS-1$
                || combo.isDisposed())
                return;
            // Каретка после последнего «;» — в хвосте пакета: остаётся последний запрос.
            int target = Math.min(index, combo.getItemCount() - 1);
            if (target <= 0 || combo.getSelectionIndex() == target)
                return;
            combo.select(target);
            combo.notifyListeners(SWT.Selection, new Event());
        }

        /** Как штатный {@code BslQueryWizardUtils.openQueryWizardOrEditor}: активная часть и её выделение. */
        private static int caretQueryIndex(ExecutionEvent event)
        {
            try
            {
                IWorkbenchPart part = HandlerUtil.getActivePart(event);
                XtextEditor editor = part != null ? part.getAdapter(XtextEditor.class) : null;
                if (editor == null && part instanceof XtextEditor xtextEditor)
                    editor = xtextEditor;
                if (editor == null || part.getSite() == null || part.getSite().getSelectionProvider() == null
                    || !(part.getSite().getSelectionProvider().getSelection() instanceof ITextSelection selection))
                    return -1;
                IDocument doc = editor.getDocument();
                String before = BslQueryLiteralSupport.literalTextBefore(doc, textOffset(doc, selection.getOffset()));
                return before != null ? separatorsBefore(before) : -1;
            }
            catch (RuntimeException | org.eclipse.jface.text.BadLocationException e)
            {
                Debug.problem("caretQueryIndex: " + e); //$NON-NLS-1$
                return -1;
            }
        }

        /** Каретка в отступе перед {@code |} строки продолжения — начало текста этой строки. */
        private static int textOffset(IDocument doc, int offset) throws org.eclipse.jface.text.BadLocationException
        {
            org.eclipse.jface.text.IRegion line = doc.getLineInformationOfOffset(offset);
            String text = doc.get(line.getOffset(), line.getLength());
            int column = offset - line.getOffset();
            int bar = text.indexOf('|');
            return bar >= column && text.substring(0, bar).isBlank() ? line.getOffset() + bar + 1 : offset;
        }

        /** Разделители «;» пакета вне строк и комментариев запроса. */
        private static int separatorsBefore(String text)
        {
            int count = 0;
            boolean inString = false;
            for (int i = 0; i < text.length(); i++)
            {
                char c = text.charAt(i);
                char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
                if (inString)
                {
                    if (c == '"' && next == '"')
                        i++;
                    else if (c == '"')
                        inString = false;
                }
                else if (c == '"')
                    inString = true;
                else if (c == '/' && next == '/')
                {
                    while (i < text.length() && text.charAt(i) != '\n')
                        i++;
                }
                else if (c == ';')
                    count++;
            }
            return count;
        }
    }

    private static final class Debug
    {
        static final String TAG = "QConstructorFilter"; //$NON-NLS-1$

        private Debug()
        {
        }

        static boolean isEnabled()
        {
            return Global.isLogEnabled();
        }

        static void log(String msg)
        {
            if (isEnabled())
                Global.log(TAG, msg);
        }

        static void problem(String msg)
        {
            if (isEnabled())
                Global.log(TAG, "[!] " + msg); //$NON-NLS-1$
        }
    }
}
