package tormozit;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.workbench.IPresentationEngine;
import org.eclipse.e4.ui.workbench.renderers.swt.StackRenderer;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.DefaultToolTip;
import org.eclipse.jface.window.ToolTip;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPartConstants;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ShowInContext;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.hooks.weaving.WeavingHook;
import org.osgi.framework.hooks.weaving.WovenClass;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.metadata.mdclass.BasicCommand;
import com._1c.g5.v8.dt.metadata.mdclass.CommonCommand;

/** Заголовки вкладок (728), подсказки списка (729), постоянная кнопка списка (730) и сопоставление восстановленных редакторов (726). */
public final class WorkbenchTabsHook implements IStartup
{
    private static final String TOPIC = "workbench-tabs"; //$NON-NLS-1$

    private final Set<BslXtextEditor> titleEditors = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        PartListButton.installPatch();
        Display.getDefault().asyncExec(() -> {
            Display display = Display.getDefault();
            if (display.isDisposed())
                return;
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                hookWindow(window);
            PlatformUI.getWorkbench().addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow window) { hookWindow(window); }
                @Override public void windowActivated(IWorkbenchWindow window) {}
                @Override public void windowDeactivated(IWorkbenchWindow window) {}
                @Override public void windowClosed(IWorkbenchWindow window) {}
            });
            display.addFilter(SWT.Paint, event -> {
                if (event.widget instanceof CTabFolder folder)
                    PartListButton.syncMark(folder);
            });
            display.addFilter(SWT.Show, event -> {
                if (!(event.widget instanceof Shell shell))
                    return;
                try
                {
                    installPartListToolTips(shell);
                }
                catch (RuntimeException ex)
                {
                    Global.tempLogException(TOPIC, "tooltip install", ex); //$NON-NLS-1$
                }
            });
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        PartListButton.scan(window.getShell());
        for (var page : window.getPages())
            for (IEditorReference ref : page.getEditorReferences())
                hookEditor(ref);
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)
            {
                hookEditor(ref);
            }
            @Override public void partActivated(IWorkbenchPartReference ref)
            {
                hookEditor(ref);
            }
            @Override public void partInputChanged(IWorkbenchPartReference ref)
            {
                hookEditor(ref);
            }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref) {}
            @Override public void partDeactivated(IWorkbenchPartReference ref) {}
            @Override public void partHidden(IWorkbenchPartReference ref) {}
            @Override public void partVisible(IWorkbenchPartReference ref) {}
        });
    }

    private void hookEditor(IWorkbenchPartReference ref)
    {
        if (!(ref instanceof IEditorReference) || !(ref.getPart(false) instanceof BslXtextEditor editor))
            return;
        if (titleEditors.add(editor))
            editor.addPropertyListener((part, property) -> {
                if (property == IWorkbenchPartConstants.PROP_TITLE)
                    Display.getDefault().asyncExec(() -> shortenCommandTitle(editor));
            });
        shortenCommandTitle(editor);
    }

    /** Штатный BSL ShowInContext возвращает владельца модуля, как и расчёт заголовка EDT. */
    private static void shortenCommandTitle(BslXtextEditor editor)
    {
        if (PlatformUI.getWorkbench().isClosing() || editor.getSite() == null
            || editor.getInternalSourceViewer() == null
            || editor.getInternalSourceViewer().getTextWidget() == null
            || editor.getInternalSourceViewer().getTextWidget().isDisposed())
            return;
        try
        {
            ShowInContext context = editor.getShowInContext();
            if (context == null || !(context.getSelection() instanceof IStructuredSelection selection)
                || !(selection.getFirstElement() instanceof BasicCommand command)
                || command instanceof CommonCommand)
                return;
            String name = command.getName();
            if (name != null && !name.isBlank() && !name.equals(editor.getPartName()))
            {
                if (!Global.invokeVoid(editor, "setPartName", name)) //$NON-NLS-1$
                    Global.tempLog("command-tab-title", "setPartName failed: " + name); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (RuntimeException ex)
        {
            Global.tempLogException("command-tab-title", "shorten title", ex); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void installPartListToolTips(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child instanceof Table table)
            {
                TableItem[] rows = table.getItems();
                boolean partTable = false;
                for (TableItem row : rows)
                    partTable |= row.getData() instanceof MPart;
                if (!partTable)
                    continue;
                PartListToolTip.install(table);
            }
            else if (child instanceof Composite composite)
                installPartListToolTips(composite);
        }
    }

    /** BasicPartList хранит MPart в строках; источник текста совпадает с SWTPartRenderer.getToolTip. */
    private static final class PartListToolTip extends DefaultToolTip
    {
        private static final String KEY = "tormozit.workbench.partListToolTip"; //$NON-NLS-1$

        private final Table table;

        static void install(Table table)
        {
            if (table.getData(KEY) != null)
                return;
            table.setData(KEY, new PartListToolTip(table));
        }

        private PartListToolTip(Table table)
        {
            super(table, ToolTip.NO_RECREATE, false);
            this.table = table;
            setShift(new Point(10, 20));
            table.addListener(SWT.Hide, event -> hide());
            table.getShell().addListener(SWT.Hide, event -> hide());
        }

        @Override
        protected Object getToolTipArea(Event event)
        {
            return table.getItem(new Point(event.x, event.y));
        }

        @Override
        protected boolean shouldCreateToolTip(Event event)
        {
            // Штатный ColumnViewerToolTipSupport перед этим событием включает native tooltip.
            // JFace-подсказка использует Shell с NO_FOCUS и не забирает фокус у списка вкладок.
            table.setToolTipText(""); //$NON-NLS-1$
            TableItem row = (TableItem)getToolTipArea(event);
            String text = null;
            if (row != null && row.getData() instanceof MPart part)
            {
                text = partToolTip(part);
                if (text == null || text.isBlank())
                    text = part.getLocalizedLabel();
            }
            if (text == null || text.isBlank())
                return false;
            setText(TooltipText.wrap(table, text + Global.pluginSignForTooltip()));
            return super.shouldCreateToolTip(event);
        }
    }

    /** Текст подсказки вкладки — тот же источник, что у {@code SWTPartRenderer.getToolTip}. */
    private static String partToolTip(MPart part)
    {
        Object override = part.getTransientData().get(IPresentationEngine.OVERRIDE_TITLE_TOOL_TIP_KEY);
        return override instanceof String tip ? tip : part.getLocalizedTooltip();
    }

    /** Регистрация вплетения в подписи списка вкладок; как можно раньше, из {@code Activator.start}. */
    public static void installWeavingHook()
    {
        EditorModelMatching.install();
        PartListLabels.install();
    }

    /** Issue 726: DtEditorMatchingStrategy сравнивает proxy и загруженную модель как разные объекты. */
    private static final class EditorModelMatching
    {
        /** Совпадает с PROP_EDITOR_MODEL_EQUALS раннего бандла. */
        private static final String PROPERTY = "tormozit.editor.modelEquals"; //$NON-NLS-1$

        static void install()
        {
            System.getProperties().put(PROPERTY,
                (BiFunction<Object, Object, Boolean>) EditorModelMatching::matches);
        }

        private static Boolean matches(Object requested, Object existing)
        {
            if (java.util.Objects.equals(requested, existing))
                return Boolean.TRUE;
            if (!(requested instanceof org.eclipse.emf.ecore.EObject requestedModel)
                || !(existing instanceof org.eclipse.emf.ecore.EObject existingModel)
                || !requestedModel.eIsProxy() && !existingModel.eIsProxy())
                return Boolean.FALSE;
            try
            {
                var requestedUri = modelUri(requestedModel);
                var existingUri = modelUri(existingModel);
                boolean match = requestedUri != null && "bm".equals(requestedUri.scheme()) //$NON-NLS-1$
                    && requestedUri.equals(existingUri);
                return match;
            }
            catch (RuntimeException ex)
            {
                return Boolean.FALSE;
            }
        }

        /** Тот же выбор URI, что у DtEditorInputElementFactory.saveElement; proxy не разрешаем. */
        private static org.eclipse.emf.common.util.URI modelUri(org.eclipse.emf.ecore.EObject model)
        {
            return model instanceof com._1c.g5.v8.bm.core.IBmObject bmObject
                ? bmObject.bmGetUri() : org.eclipse.emf.ecore.util.EcoreUtil.getURI(model);
        }
    }

    /**
     * Подписи строк списка вкладок: когда открыто больше одного проекта — «вкладка (проект)».
     *
     * <p>Текст строки отдаёт {@code BasicStackListLabelProvider.getText} штатного списка; по нему же
     * считается ширина списка. В каждый возврат метода вплетён вызов функции из
     * {@code System.getProperties()} — без ветвлений, кадры стека остаются исходными.
     *
     * <p>Строка поиска списка берёт текст у того же поставщика
     * ({@code NamePatternFilter.select} и {@code findElement} в {@code AbstractTableInformationControl}).
     * Искать по имени проекта она не должна, поэтому результат {@code ILabelProvider.getText} в этих
     * двух методах проходит через {@link #stockText} — он возвращает подпись без проекта. В
     * {@code setMatcherString} текст запроса проходит через {@link #substringPattern} — поиск по
     * подстроке.
     *
     * <p>Поставщик подписей и {@code NamePatternFilter} грузятся при первом открытии списка — их
     * вплетает этот класс. Сам {@code AbstractTableInformationControl} загружается вместе с
     * воркбенчем, раньше активации основного бандла (проверено по временному логу 07.10.2026),
     * поэтому {@code findElement} и {@code setMatcherString} вплетает ранний бандл (его
     * {@code Activator}, см. README бандла); функции он берёт из тех же свойств.
     *
     * <p>Проект берётся из подсказки вкладки: EDT начинает её с имени проекта
     * («Проект→Раздел→Объект»). У вкладок без проекта (панели, прочие редакторы) подпись штатная.
     */
    private static final class PartListLabels implements WeavingHook
    {
        private static final String PACKAGE = "org.eclipse.e4.ui.internal.workbench.renderers.swt."; //$NON-NLS-1$
        private static final String LABEL_PROVIDER = PACKAGE + "BasicPartList$BasicStackListLabelProvider"; //$NON-NLS-1$
        private static final String NAME_FILTER = PACKAGE + "AbstractTableInformationControl$NamePatternFilter"; //$NON-NLS-1$
        private static final String PROP_TEXT = "tormozit.partList.text"; //$NON-NLS-1$
        /** Должен совпадать с {@code PROP_PART_LIST_STOCK_TEXT} в {@code Activator} раннего бандла. */
        private static final String PROP_STOCK_TEXT = "tormozit.partList.stockText"; //$NON-NLS-1$
        /** Должен совпадать с {@code PROP_PART_LIST_PATTERN} в {@code Activator} раннего бандла. */
        private static final String PROP_PATTERN = "tormozit.partList.pattern"; //$NON-NLS-1$
        private static final String PATH_SEPARATOR = "→"; //$NON-NLS-1$
        private static final int STOCK_TEXTS_LIMIT = 2000;
        private static final AtomicBoolean installed = new AtomicBoolean();
        /** Подпись с проектом → штатная подпись, из которой она получена. Только UI-поток. */
        private static final Map<String, String> stockTexts = new HashMap<>();

        static void install()
        {
            if (!installed.compareAndSet(false, true))
                return;
            // Свойства ставятся до хука: вплетённый код вызывает их без проверки на null.
            System.getProperties().put(PROP_TEXT, (BiFunction<Object, Object, Object>) PartListLabels::text);
            System.getProperties().put(PROP_STOCK_TEXT, (Function<Object, Object>) PartListLabels::stockText);
            System.getProperties().put(PROP_PATTERN, (Function<Object, Object>) PartListLabels::substringPattern);
            Bundle bundle = FrameworkUtil.getBundle(WorkbenchTabsHook.class);
            BundleContext context = bundle != null ? bundle.getBundleContext() : null;
            if (context != null)
                context.registerService(WeavingHook.class, new PartListLabels(), null);
        }

        @Override
        public void weave(WovenClass wovenClass)
        {
            String name = wovenClass.getClassName();
            boolean provider = LABEL_PROVIDER.equals(name);
            if (!provider && !NAME_FILTER.equals(name) || wovenClass.getState() != WovenClass.TRANSFORMING)
                return;
            try
            {
                byte[] transformed = transform(wovenClass.getBytes(), provider);
                if (transformed != null)
                    wovenClass.setBytes(transformed);
            }
            catch (Throwable t)
            {
                // Класс остаётся штатным: подписи и поиск списка работают как без плагина.
            }
        }

        /**
         * Поставщик подписей: стек перед каждым {@code areturn} в {@code getText}
         * {@code [text]} → {@code [(String) text.apply(element, text)]}.
         *
         * <p>{@code NamePatternFilter.select}: стек после {@code ILabelProvider.getText}
         * {@code [text]} → {@code [(String) stockText.apply(text)]}.
         */
        static byte[] transform(byte[] bytes, boolean provider)
        {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] touched = new int[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer)
            {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions)
                {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (mv == null)
                        return null;
                    if (!provider)
                    {
                        if (!"select".equals(name)) //$NON-NLS-1$
                            return mv;
                        return new MethodVisitor(Opcodes.ASM9, mv)
                        {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                boolean isInterface)
                            {
                                super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                                if (opcode != Opcodes.INVOKEINTERFACE
                                    || !"org/eclipse/jface/viewers/ILabelProvider".equals(owner) //$NON-NLS-1$
                                    || !"getText".equals(mname)) //$NON-NLS-1$
                                    return;
                                emitGetProperty(mv, PROP_STOCK_TEXT, "java/util/function/Function"); //$NON-NLS-1$
                                mv.visitInsn(Opcodes.SWAP);
                                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", //$NON-NLS-1$
                                    "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String"); //$NON-NLS-1$
                                touched[0]++;
                            }
                        };
                    }
                    if (!"getText".equals(name) //$NON-NLS-1$
                        || !"(Ljava/lang/Object;)Ljava/lang/String;".equals(descriptor)) //$NON-NLS-1$
                        return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv)
                    {
                        @Override
                        public void visitInsn(int opcode)
                        {
                            if (opcode == Opcodes.ARETURN)
                            {
                                emitGetProperty(mv, PROP_TEXT, "java/util/function/BiFunction"); //$NON-NLS-1$
                                mv.visitInsn(Opcodes.SWAP);
                                mv.visitVarInsn(Opcodes.ALOAD, 1);
                                mv.visitInsn(Opcodes.SWAP);
                                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/BiFunction", //$NON-NLS-1$
                                    "apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true); //$NON-NLS-1$ //$NON-NLS-2$
                                mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String"); //$NON-NLS-1$
                                touched[0]++;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            return touched[0] > 0 ? writer.toByteArray() : null;
        }

        /** Стек {@code []} → {@code [(castTo) System.getProperties().get(prop)]}. */
        private static void emitGetProperty(MethodVisitor mv, String prop, String castTo)
        {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", //$NON-NLS-1$ //$NON-NLS-2$
                "()Ljava/util/Properties;", false); //$NON-NLS-1$
            mv.visitLdcInsn(prop);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", //$NON-NLS-1$ //$NON-NLS-2$
                "(Ljava/lang/Object;)Ljava/lang/Object;", false); //$NON-NLS-1$
            mv.visitTypeInsn(Opcodes.CHECKCAST, castTo);
        }

        /**
         * Вызов из вплетённого {@code setMatcherString}: строка поиска ищет по подстроке без ввода
         * ведущей «*». Штатный {@code SearchPattern} по тексту без «*» ищет только с начала имени, а
         * «*текст» сам дополняет до «*текст*».
         */
        static Object substringPattern(Object pattern)
        {
            return pattern instanceof String text && !text.isEmpty() && !text.startsWith("*") //$NON-NLS-1$
                ? "*" + text : pattern; //$NON-NLS-1$
        }

        /**
         * Вызов из вплетённого кода сопоставления со строкой поиска: подпись без имени проекта.
         * Подпись, которую построил {@link #text}, опознаётся по {@link #stockTexts}; прочие
         * возвращаются как есть.
         */
        static Object stockText(Object text)
        {
            String stock = text instanceof String ? stockTexts.get(text) : null;
            return stock != null ? stock : text;
        }

        /** Вызов из вплетённого кода: элемент строки и её штатный текст. */
        static Object text(Object element, Object text)
        {
            if (!(element instanceof MPart part) || !(text instanceof String label))
                return text;
            try
            {
                String tip = partToolTip(part);
                int cut = tip == null ? -1 : tip.indexOf(PATH_SEPARATOR);
                if (cut <= 0)
                    return text;
                String projectName = tip.substring(0, cut).trim();
                int open = 0;
                boolean known = false;
                for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects())
                {
                    if (!project.isOpen())
                        continue;
                    open++;
                    known |= project.getName().equals(projectName);
                }
                if (open <= 1 || !known)
                    return text;
                String result = label + " (" + projectName + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                if (stockTexts.size() >= STOCK_TEXTS_LIMIT)
                    stockTexts.clear();
                stockTexts.put(result, label);
                return result;
            }
            catch (RuntimeException ex)
            {
                Global.tempLogException(TOPIC, "part list label", ex); //$NON-NLS-1$
                return text;
            }
        }
    }

    /**
     * Сохраняем штатный chevron и расчёт вкладок; меняется только условие его видимости.
     * Само условие вплетает в {@code CTabFolder.setItemSize} ранний бандл (его {@code Activator},
     * см. README бандла): отсюда вплетение не успевает — SWT уже загружен, а агент в EDT
     * недоступен. Здесь папки только помечаются ключом {@link #KEY} — пока в папке не меньше
     * {@link #MIN_TABS} вкладок.
     */
    private static final class PartListButton
    {
        /** Должен совпадать с {@code TAB_FOLDER_MARK} в {@code Activator} раннего бандла. */
        private static final String KEY = "tormozit.workbench.partListButton"; //$NON-NLS-1$
        /** Должен совпадать с {@code PROP_TAB_FOLDER_CHEVRON} в {@code Activator} раннего бандла. */
        private static final String PROP_PATCH = "tormozit.tabFolder.chevronPatch"; //$NON-NLS-1$
        /** Меньше вкладок — кнопка ведёт себя штатно: видна, только когда не все вкладки помещаются. */
        private static final int MIN_TABS = 5;
        private static boolean patchRegistered;

        static synchronized void installPatch()
        {
            if (patchRegistered)
                return;
            String state = System.getProperty(PROP_PATCH);
            patchRegistered = state != null && state.startsWith("woven"); //$NON-NLS-1$
        }

        static void scan(Composite parent)
        {
            if (parent == null || parent.isDisposed())
                return;
            if (parent instanceof CTabFolder folder)
                syncMark(folder);
            for (Control child : parent.getChildren())
                if (child instanceof Composite composite)
                    scan(composite);
        }

        /**
         * Пометка папки по числу её вкладок. Зовётся на каждой отрисовке папки (событий добавления
         * и удаления вкладок у {@code CTabFolder} нет), поэтому без изменений выходит сразу.
         */
        static void syncMark(CTabFolder folder)
        {
            if (!patchRegistered || folder.isDisposed())
                return;
            boolean marked = folder.getData(KEY) != null;
            if (marked == (folder.getItemCount() >= MIN_TABS))
                return;
            if (!marked && (!(folder.getData("modelElement") instanceof MPartStack stack) //$NON-NLS-1$
                || !(stack.getRenderer() instanceof StackRenderer)))
                return;
            folder.setData(KEY, marked ? null : Boolean.TRUE);
            folder.getDisplay().asyncExec(() -> {
                if (folder.isDisposed())
                    return;
                // При нуле скрытых вкладок SWT считает пустой кэш уже актуальным.
                // Создать штатное изображение до расчёта ширины его ToolBar.
                Global.invoke(folder, "getChevron"); //$NON-NLS-1$
                Global.setField(folder, "chevronCount", -1); //$NON-NLS-1$
                Global.invoke(folder, "updateChevronImage", false); //$NON-NLS-1$
                Global.invoke(folder, "updateItems"); //$NON-NLS-1$
                folder.redraw();
            });
        }
    }

}
