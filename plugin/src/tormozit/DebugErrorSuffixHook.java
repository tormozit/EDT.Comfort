package tormozit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.debug.ui.AbstractDebugView;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.handlers.IHandlerService;

/**
 * Панель «Отладка»: признак невидимых строк описания ошибки в строке потока (issue 276).
 * <p>
 * Строку потока рисует EDT: {@code RuntimeDebugTargetThread.getName()} = презентация типа предмета
 * отладки + {@code [пользователь]} + {@code (суффикс)}. Суффикс EDT заполняет один раз, при
 * остановке по ошибке ({@code RuntimeDebugClientTarget.handleSuspensionByException}), причём только
 * верхней частью описания ({@code RuntimeException.getMessage()}); продолжение («по причине: …»)
 * лежит в цепочке {@code GenericException.getInner()/getDescr()} и в модели потока не сохраняется —
 * поэтому текст берётся в момент обработки события остановки, а не из модели.
 * <p>
 * Перехват: поток событий отладчика идёт через поле {@code eventProcessor} диспетчера
 * ({@code RuntimeEventDispatchJob}), объявленное интерфейсом
 * {@code IRuntimeEventProcessor} — подменяем его на {@link Proxy} с тем же интерфейсом, который всё
 * делегирует как есть, а после обработки остановки по ошибке дописывает потоку суффикс
 * «первая строка{@value #HIDDEN_LINES_MARK}», если ниже первой строки есть ещё текст. Однострочное
 * описание не трогаем — тогда ничего не скрыто.
 * <p>
 * Полное описание в модели EDT хранить негде, поэтому держим его сами (по потоку) и отдаём в панели
 * «Отладка»: подсказка при наведении на строку потока, копирование в буфер по Ctrl+C /
 * «Копировать» и окно с полным описанием по двойному клику. Для остальных строк дерева
 * копирование остаётся штатным.
 */
public final class DebugErrorSuffixHook implements IStartup
{
    /** Признак невидимых строк описания ошибки (как в панели «Трассировки стеков»). */
    static final String HIDDEN_LINES_MARK = " ..."; //$NON-NLS-1$
    private static final String LOG_TOPIC = "debug-error-suffix"; //$NON-NLS-1$
    private static final String PROCESSOR_IFACE =
            "com._1c.g5.v8.dt.debug.core.model.IRuntimeEventProcessor"; //$NON-NLS-1$
    private static final String SUSPENSION_METHOD = "handleSuspensionByException"; //$NON-NLS-1$
    private static final String REASON_SEPARATOR = "по причине:"; //$NON-NLS-1$
    private static final int INNER_LIMIT = 16;

    private static final String DEBUG_VIEW_ID = "org.eclipse.debug.ui.DebugView"; //$NON-NLS-1$
    private static final String VIEW_HOOKED_KEY = "tormozit.comfort.debugErrorSuffix.hooked"; //$NON-NLS-1$
    private static final String COPY_COMMAND_KEY = "tormozit.comfort.debugErrorSuffix.copyCommand"; //$NON-NLS-1$

    /** Полное описание ошибки по потоку: в модели EDT его хранить негде. */
    private static final Map<Object, String> FULL_TEXTS = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean installed;

    @Override
    public void earlyStartup()
    {
        if (installed)
            return;
        installed = true;
        IDebugEventSetListener listener = events -> {
            for (DebugEvent event : events)
            {
                if (event.getSource() instanceof IDebugTarget target)
                    wrapEventProcessor(target);
            }
        };
        DebugPlugin.getDefault().addDebugEventListener(listener);
        // earlyStartup идёт не в потоке UI: обход панелей трогает SWT и падал Invalid thread access,
        // обрывая запуск остальных хуков плагина (все они в одном расширении org.eclipse.ui.startup).
        Display.getDefault().asyncExec(DebugErrorSuffixHook::installViewHooks);
    }

    // -----------------------------------------------------------------------
    // Панель «Отладка»: подсказка и копирование полного описания
    // -----------------------------------------------------------------------

    private static void installViewHooks()
    {
        try
        {
            installViewHooksInUi();
        }
        catch (Exception e)
        {
            Global.tempLogException(LOG_TOPIC, "installViewHooks failed", e); //$NON-NLS-1$
        }
    }

    private static void installViewHooksInUi()
    {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null)
            return;
        workbench.addWindowListener(new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
            }

            @Override
            public void windowActivated(IWorkbenchWindow window)
            {
                hookWindow(window);
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window)
            {
            }
        });
        for (IWorkbenchWindow window : workbench.getWorkbenchWindows())
            hookWindow(window);
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                hookDebugView(ref.getPart(false));
            }

            @Override
            public void partVisible(IWorkbenchPartReference ref)
            {
                hookDebugView(ref.getPart(false));
            }
        });
        IWorkbenchPage page = window.getActivePage();
        if (page == null)
            return;
        for (IViewReference ref : page.getViewReferences())
        {
            if (ref != null)
                hookDebugView(ref.getPart(false));
        }
    }

    private static void hookDebugView(IWorkbenchPart part)
    {
        if (!(part instanceof AbstractDebugView view))
            return;
        if (!DEBUG_VIEW_ID.equals(view.getViewSite().getId()))
            return;
        Viewer viewer = view.getViewer();
        if (viewer == null || !(viewer.getControl() instanceof Tree tree) || tree.isDisposed())
            return;
        installCopyHandler(view);
        if (Boolean.TRUE.equals(tree.getData(VIEW_HOOKED_KEY)))
            return;
        tree.setData(VIEW_HOOKED_KEY, Boolean.TRUE);
        // Подсказка нативного дерева: многострочный текст показывается целиком. Текст ставим
        // заранее, при смене строки под курсором, — на SWT.MouseHover Windows уже показывает
        // подсказку с прежним текстом.
        TreeItem[] hovered = new TreeItem[1];
        tree.addListener(SWT.MouseMove, event -> {
            TreeItem item = tree.getItem(new Point(event.x, event.y));
            if (item == hovered[0])
                return;
            hovered[0] = item;
            tree.setToolTipText(item == null || item.isDisposed() ? null : fullText(item.getData()));
        });
        tree.addListener(SWT.MouseExit, event -> {
            hovered[0] = null;
            tree.setToolTipText(null);
        });
        // Штатное действие копирования панель может переустановить позже — проверяем и при выборе.
        tree.addListener(SWT.Selection, event -> installCopyHandler(view));
        tree.addListener(SWT.MouseDoubleClick, event -> {
            TreeItem item = tree.getItem(new Point(event.x, event.y));
            String full = item == null || item.isDisposed() ? null : fullText(item.getData());
            if (full != null)
                ErrorDescriptionWindow.open(tree.getShell(), full);
        });
        Global.tempLog(LOG_TOPIC, "debug view hooked"); //$NON-NLS-1$
    }

    /**
     * Копирование: у панели «Отладка» есть штатное действие Edit → «Копировать» (копирует метки
     * выделенных строк, то есть у потока — обрезанную строку с признаком). Подменяем обработчик
     * действия у панели ({@link IActionBars#setGlobalActionHandler}) — для строки потока с
     * известным полным описанием кладём в буфер его, для остальных строк отдаём штатному действию.
     */
    private static void installCopyHandler(AbstractDebugView view)
    {
        IActionBars bars = view.getViewSite().getActionBars();
        if (bars == null)
        {
            Global.tempLog(LOG_TOPIC, "copy handler: no action bars"); //$NON-NLS-1$
            return;
        }
        String copyId = ActionFactory.COPY.getId();
        IAction current = bars.getGlobalActionHandler(copyId);
        if (current instanceof FullTextCopyAction)
            return;
        FullTextCopyAction action = new FullTextCopyAction(view, current);
        bars.setGlobalActionHandler(copyId, action);
        bars.updateActionBars();
        Global.tempLog(LOG_TOPIC, "copy handler installed over=" //$NON-NLS-1$
                + (current == null ? "null" : current.getClass().getName())); //$NON-NLS-1$

        // Штатное «Копировать» панели — вклад в контекстное меню с definitionId
        // org.eclipse.ui.edit.copy; он может обрабатывать команду мимо global action handler,
        // поэтому дополнительно активируем свой обработчик команды у самой панели.
        if (Boolean.TRUE.equals(view.getViewer().getControl().getData(COPY_COMMAND_KEY)))
            return;
        IHandlerService handlerService = view.getViewSite().getService(IHandlerService.class);
        if (handlerService == null)
            return;
        view.getViewer().getControl().setData(COPY_COMMAND_KEY, Boolean.TRUE);
        handlerService.activateHandler(copyId, new AbstractHandler()
        {
            @Override
            public Object execute(ExecutionEvent event)
            {
                IAction handler = bars.getGlobalActionHandler(copyId);
                if (handler instanceof FullTextCopyAction ours)
                    ours.run();
                else
                    action.run();
                return null;
            }
        });
    }

    /**
     * Полное описание ошибки для элемента дерева. Отдаём только пока у потока стоит суффикс с
     * описанием: после продолжения выполнения EDT его стирает, и старое описание уже не про
     * текущее состояние. Признак « ...» при этом не требуется — описание может быть и однострочным.
     */
    static String fullText(Object element)
    {
        if (element == null)
            return null;
        String full = FULL_TEXTS.get(element);
        if (full == null)
            return null;
        Object suffix = Global.getField(element, "nameSuffix"); //$NON-NLS-1$
        return suffix instanceof String text && !text.isBlank() ? full : null;
    }

    private static final class FullTextCopyAction extends Action
    {
        private final AbstractDebugView view;
        private final IAction stock;

        FullTextCopyAction(AbstractDebugView view, IAction stock)
        {
            this.view = view;
            this.stock = stock;
            if (stock != null)
            {
                setText(stock.getText());
                setToolTipText(stock.getToolTipText());
                setImageDescriptor(stock.getImageDescriptor());
                setActionDefinitionId(stock.getActionDefinitionId());
            }
        }

        @Override
        public void run()
        {
            String full = selectedFullText();
            if (full == null)
            {
                if (stock != null)
                    stock.run();
                else
                    copyToClipboard(selectedLabels());
                return;
            }
            copyToClipboard(full);
        }

        private String selectedFullText()
        {
            TreeItem[] selection = selection();
            return selection.length == 1 ? fullText(selection[0].getData()) : null;
        }

        /** Запасной вариант, если штатного действия копирования у панели нет. */
        private String selectedLabels()
        {
            StringBuilder text = new StringBuilder();
            for (TreeItem item : selection())
            {
                if (item.isDisposed())
                    continue;
                if (text.length() > 0)
                    text.append(System.lineSeparator());
                text.append(item.getText());
            }
            return text.toString();
        }

        private TreeItem[] selection()
        {
            Viewer viewer = view.getViewer();
            if (viewer == null || !(viewer.getControl() instanceof Tree tree) || tree.isDisposed())
                return new TreeItem[0];
            return tree.getSelection();
        }

        private void copyToClipboard(String text)
        {
            if (text == null || text.isEmpty())
                return;
            Clipboard clipboard = new Clipboard(view.getViewer().getControl().getDisplay());
            try
            {
                clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
            }
            finally
            {
                clipboard.dispose();
            }
        }
    }

    /**
     * Подмена {@code eventProcessor} у диспетчера событий предмета отладки. Диспетчер стартует до
     * события создания предмета отладки, но проверяем на каждом событии — сеансов и предметов
     * отладки может добавляться сколько угодно, а повторная обёртка отсекается по
     * {@link Proxy#isProxyClass(Class)}.
     */
    private static void wrapEventProcessor(IDebugTarget target)
    {
        try
        {
            Object dispatcher = Global.getField(target, "eventDispatcher"); //$NON-NLS-1$
            if (dispatcher == null)
                return;
            Object processor = Global.getField(dispatcher, "eventProcessor"); //$NON-NLS-1$
            if (processor == null || Proxy.isProxyClass(processor.getClass()))
                return;
            Class<?> iface = processor.getClass().getClassLoader().loadClass(PROCESSOR_IFACE);
            if (!iface.isInstance(processor))
                return;
            Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface },
                    new EventProcessorInterceptor(processor));
            boolean ok = Global.setFieldForce(dispatcher, "eventProcessor", proxy); //$NON-NLS-1$
            Global.tempLog(LOG_TOPIC, "wrap eventProcessor=" + processor.getClass().getName() + " ok=" + ok); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Global.tempLogException(LOG_TOPIC, "wrap failed", e); //$NON-NLS-1$
        }
    }

    /** Делегирование всех событий отладчика + дополнение суффикса имени потока после остановки. */
    private static final class EventProcessorInterceptor implements InvocationHandler
    {
        private final Object delegate;

        EventProcessorInterceptor(Object delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            Object result;
            try
            {
                result = method.invoke(delegate, args);
            }
            catch (InvocationTargetException e)
            {
                throw e.getCause() != null ? e.getCause() : e;
            }
            // Только после штатной обработки: EDT там сам ставит суффикс, иначе он затрёт наш.
            if (SUSPENSION_METHOD.equals(method.getName()) && args != null && args.length >= 2)
                applyHiddenLinesMark(args[0], args[1]);
            return result;
        }

        private void applyHiddenLinesMark(Object debugTargetId, Object exception)
        {
            try
            {
                String full = fullExceptionText(exception);
                Global.tempLog(LOG_TOPIC, "suspension exception=" //$NON-NLS-1$
                        + (exception == null ? "null" : exception.getClass().getName()) //$NON-NLS-1$
                        + " text=[" + escape(full) + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                if (full == null || full.isBlank())
                    return;

                String head = BreakpointListHook.firstLine(full);
                if (head.isEmpty())
                    return;
                String suffix = hasHiddenLines(full) ? head + HIDDEN_LINES_MARK : head;

                Object thread = Global.invoke(delegate, "getThread", debugTargetId); //$NON-NLS-1$
                if (thread == null)
                {
                    Global.tempLog(LOG_TOPIC, "thread not found for suspension"); //$NON-NLS-1$
                    return;
                }
                Object current = Global.getField(thread, "nameSuffix"); //$NON-NLS-1$
                Global.tempLog(LOG_TOPIC, "suffix current=[" + escape(current == null ? null : current.toString()) //$NON-NLS-1$
                        + "] new=[" + escape(suffix) + "]"); //$NON-NLS-1$ //$NON-NLS-2$
                FULL_TEXTS.put(thread, full);
                if (suffix.equals(current))
                    return;

                boolean applied = Global.invokeVoid(thread, "setNameSuffix", suffix); //$NON-NLS-1$
                Global.tempLog(LOG_TOPIC, "setNameSuffix applied=" + applied); //$NON-NLS-1$
                if (!applied)
                    return;
                DebugPlugin.getDefault().fireDebugEventSet(
                        new DebugEvent[] { new DebugEvent(thread, DebugEvent.CHANGE, DebugEvent.STATE) });
            }
            catch (Exception e)
            {
                Global.tempLogException(LOG_TOPIC, "apply failed", e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Полное описание ошибки: текст исключения и вся цепочка вложенных причин
     * ({@code GenericException.getInner()}).
     * <p>
     * Полнота зависит от протокола отладки: по TCP платформа отдаёт причину текстом, а по HTTP
     * обрезает её на стороне сервера отладки — у вложенного исключения пустой {@code descr}, и
     * продолжения «по причине: …» нет ни в одном текстовом поле (то же обрезание видно и в
     * конфигураторе по HTTP). Это ограничение платформы, а не потеря текста здесь.
     */
    private static String fullExceptionText(Object exception)
    {
        StringBuilder text = new StringBuilder();
        Object current = exception;
        for (int depth = 0; current != null && depth < INNER_LIMIT; depth++)
        {
            String part = exceptionPart(current);
            if (part != null && !part.isBlank())
            {
                if (text.length() > 0)
                    text.append('\n').append(REASON_SEPARATOR).append('\n');
                text.append(part.strip());
            }
            current = Global.invoke(current, "getInner"); //$NON-NLS-1$
        }
        return text.toString();
    }

    /** Текст одного исключения: у ошибки времени выполнения — сообщение, иначе — описание. */
    private static String exceptionPart(Object exception)
    {
        Object message = Global.invoke(exception, "getMessage"); //$NON-NLS-1$
        if (message instanceof String text && !text.isBlank())
            return text;
        Object descr = Global.invoke(exception, "getDescr"); //$NON-NLS-1$
        return descr instanceof String text ? text : null;
    }

    /** Есть ли непустой текст ниже первой строки. */
    private static boolean hasHiddenLines(String text)
    {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip(); //$NON-NLS-1$ //$NON-NLS-2$
        int nl = normalized.indexOf('\n');
        return nl >= 0 && !normalized.substring(nl + 1).isBlank();
    }

    private static String escape(String text)
    {
        return text == null ? "null" : text.replace("\r", "\\r").replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }
}
