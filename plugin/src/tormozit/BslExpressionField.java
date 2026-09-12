package tormozit;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IEditingSupport;
import org.eclipse.jface.text.IEditingSupportRegistry;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextInputListener;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/**
 * Поле ввода кода BSL в диалогах: выражение инспектора отладки, условие и выражение точки
 * останова, в перспективе — панель «Выражения». Общий слой поведения поверх встроенного
 * редактора EDT ({@code CustomEmbeddedEditor}) — всё, что одинаково для таких мест:
 *
 * <ul>
 * <li><b>Enter из списка предложений.</b> В диалоге Enter приходит как
 * {@code SWT.TRAVERSE_RETURN} и до редактора не доходит — список гаснет без вставки.
 * Вставляем выбранный пункт сами. Дальше ту же клавишу глотаем, если вставлен вызов
 * со скобками (иначе {@code LinkedModeUI} выходит из LinkedMode по ENTER) или если
 * LinkedMode нет (иначе {@code StyledText} вставит {@code \\n} и каретка уедет на
 * следующую строку). При вставке без скобок, но с уже установленным LinkedMode,
 * Enter оставляем — иначе каретка остаётся в начале вставленного слова.</li>
 * <li><b>Ctrl+Shift+Space.</b> В диалоге у штатной команды нет активного контекста
 * редактора, а её собственный {@code InvocationParametersHoverHandler$CustomKeyAdapter}
 * закрывает подсказку по этому же сочетанию. Поэтому обрабатываем на фильтре
 * {@code Display} (он раньше слушателей виджета) и гасим событие целиком.</li>
 * <li><b>Жизнь LinkedMode при всплывающих окнах.</b> {@link IEditingSupport} с
 * {@code ownsFocusShell()}: без него {@code LinkedModeUI.shellDeactivated} уходит из
 * LinkedMode, как только появляется список или подсказка.</li>
 * <li><b>Возврат фокуса.</b> После закрытия всплывающего окна Windows отдаёт фокус
 * «сохранённому» контролу диалога (дереву инспектора), а не полю.</li>
 * <li><b>Esc при открытом попапе.</b> {@code TRAVERSE_ESCAPE} иначе закрывает
 * {@code PreferenceDialog} свойств точки останова. Глотаем обход и прячем список
 * предложений / подсказку — диалог остаётся открытым.</li>
 * </ul>
 *
 * <p>Обёртку текстом модуля, историю выражений и само вычисление делает вызывающий: у
 * инспектора и точки останова они разные. Класс работает с уже созданным
 * {@link SourceViewer} и ничего про диалог не знает.
 */
public final class BslExpressionField
{
    /** Виджет поля кода BSL: по этой метке поле узнаётся в общих механизмах. */
    static final String FIELD_KEY = "tormozit.bslExpressionField"; //$NON-NLS-1$
    private static final String INSTALLED_KEY = "tormozit.bslExpressionFieldInstalled"; //$NON-NLS-1$
    private static final String VIEWER_KEY = FIELD_KEY + ".viewer"; //$NON-NLS-1$
    /** Щелчок мимо поля моложе этого — фокус не отбираем: так решил пользователь. */
    private static final long FOREIGN_CLICK_GRACE_MS = 300;
    /** Окно, в котором Enter после нашей вставки считается тем же нажатием. */
    private static final long ENTER_SWALLOW_WINDOW_MS = 500;

    /** Последняя вставка предложения в поле была вызовом со скобками. */
    private static volatile boolean callProposalApplied;

    /**
     * Поле, в котором пользователь работал последним. В диалоге точки останова два поля
     * с общим {@code wirePopupFocusReturn}: Dispose попапа автодополнения видят оба и без
     * этого якоря оба зовут {@link #restoreFocus} — фокус прыгает в «чужое» поле.
     */
    private static volatile StyledText lastFocusedField;

    private BslExpressionField() {}

    /**
     * Подключает общее поведение к встроенному редактору. Повторный вызов для того же
     * виджета ничего не делает.
     *
     * @param viewer встроенный редактор поля
     * @param logTopic тема временного лога вызывающего ({@code Global.tempLog})
     */
    public static void attach(SourceViewer viewer, String logTopic)
    {
        if (viewer == null)
            return;
        StyledText text = viewer.getTextWidget();
        if (text == null || text.isDisposed())
            return;
        if (Boolean.TRUE.equals(text.getData(INSTALLED_KEY)))
            return;
        text.setData(INSTALLED_KEY, Boolean.TRUE);
        text.setData(FIELD_KEY, Boolean.TRUE);
        text.setData(FIELD_KEY + ".logTopic", logTopic); //$NON-NLS-1$
        text.setData(VIEWER_KEY, viewer);
        text.addListener(SWT.FocusIn, e -> lastFocusedField = text);
        text.addDisposeListener(e ->
        {
            if (lastFocusedField == text)
                lastFocusedField = null;
        });
        wireKeys(text, viewer, logTopic);
        wireLinkedModeFocusOwner(text, viewer);
        wirePopupFocusReturn(text, viewer);
        wireParamHintBounds(text, viewer);
    }

    /** Поле кода BSL, подключённое {@link #attach}. */
    static boolean isFieldWidget(Control control)
    {
        return control != null && !control.isDisposed()
            && Boolean.TRUE.equals(control.getData(FIELD_KEY));
    }

    /**
     * Viewer поля выражения под фокусом или последнего активного (диалог точки
     * останова / инспектор). Нужен подсказке параметров: иначе
     * {@code ParamHintHtmlModifier} читает каретку из активного редактора модуля.
     */
    static SourceViewer focusedOrLastViewer()
    {
        Display display = Display.getCurrent();
        if (display != null && !display.isDisposed())
        {
            SourceViewer fromFocus = viewerOf(display.getFocusControl());
            if (fromFocus != null)
                return fromFocus;
        }
        return viewerOf(lastFocusedField);
    }

    static SourceViewer viewerOf(Control control)
    {
        for (Control current = control; current != null && !current.isDisposed();
                current = current.getParent())
        {
            if (!(current instanceof StyledText text) || !isFieldWidget(text))
                continue;
            Object raw = text.getData(VIEWER_KEY);
            return raw instanceof SourceViewer sourceViewer ? sourceViewer : null;
        }
        return null;
    }

    /**
     * Запоминает, что в поле вставлено предложение и было ли это вызовом со скобками.
     * Определять это по тексту вокруг каретки нельзя: LinkedMode выделяет вставленное
     * слово, и начало выделения приходится на «(» внешнего вызова.
     */
    static void markProposalApplied(String replacement, Object proposal)
    {
        // Шаблон со скобкой — не вызов метода: у него свои позиции перехода по Tab, и
        // Enter в нём завершает шаблон. Глотать Enter и трогать LinkedMode тут нельзя.
        callProposalApplied = replacement != null && replacement.indexOf('(') >= 0
            && !isTemplateProposal(proposal);
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> callProposalApplied = false);
    }

    static boolean isCallProposalJustApplied()
    {
        return callProposalApplied;
    }

    /** Предложение-шаблон ({@code TemplateProposal} и наследники EDT). */
    static boolean isTemplateProposal(Object proposal)
    {
        if (proposal == null)
            return false;
        if (proposal instanceof org.eclipse.jface.text.templates.TemplateProposal)
            return true;
        for (Class<?> type = proposal.getClass(); type != null; type = type.getSuperclass())
        {
            if (type.getSimpleName().contains("Template")) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    static boolean isAssistShowing(SourceViewer viewer)
    {
        return ContentAssistPopupSync.isPopupVisible(
            ContentAssistPatcher.getContentAssistant(viewer));
    }

    static String describeFocus(Control focus)
    {
        if (focus == null)
            return "null"; //$NON-NLS-1$
        return focus.getClass().getSimpleName() + (isFieldWidget(focus) ? "/expr" : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Возвращает фокус в поле, если его перехватил чужой контрол диалога (дерево и т.п.).
     * Не отбирает фокус у другого {@link BslExpressionField} и не восстанавливает поле,
     * которое не было последним активным.
     */
    static void restoreFocus(StyledText text, SourceViewer viewer, String logTopic)
    {
        if (text == null || text.isDisposed())
            return;
        Display display = text.getDisplay();
        if (display == null || display.isDisposed())
            return;
        String role = String.valueOf(text.getData("tormozit.bpFieldRole")); //$NON-NLS-1$
        if (lastFocusedField != null && lastFocusedField != text)
        {
            log(logTopic, "restore-focus skip not-last role=" + role //$NON-NLS-1$
                    + " last=" + describeFocus(lastFocusedField)); //$NON-NLS-1$
            return;
        }
        log(logTopic, "restore-focus schedule role=" + role //$NON-NLS-1$
                + " widget=" + describeFocus(text) //$NON-NLS-1$
                + " stack=" + focusStack(12)); //$NON-NLS-1$
        display.asyncExec(() ->
        {
            if (text.isDisposed())
                return;
            if (isAssistShowing(viewer))
            {
                log(logTopic, "restore-focus skip popup role=" + role); //$NON-NLS-1$
                return;
            }
            Control focus = display.getFocusControl();
            String before = describeFocus(focus);
            if (text.isFocusControl())
            {
                log(logTopic, "restore-focus skip already role=" + role //$NON-NLS-1$
                        + " before=" + before); //$NON-NLS-1$
                return;
            }
            // Другое поле BSL уже в фокусе — не перетягиваем (два поля в одном диалоге).
            if (isFieldWidget(focus) && focus != text)
            {
                log(logTopic, "restore-focus skip other-field role=" + role //$NON-NLS-1$
                        + " before=" + before); //$NON-NLS-1$
                return;
            }
            if (lastFocusedField != null && lastFocusedField != text)
            {
                log(logTopic, "restore-focus skip not-last-async role=" + role //$NON-NLS-1$
                        + " last=" + describeFocus(lastFocusedField)); //$NON-NLS-1$
                return;
            }
            boolean ok = text.setFocus();
            log(logTopic, "restore-focus APPLY role=" + role //$NON-NLS-1$
                    + " ok=" + ok + " before=" + before //$NON-NLS-1$ //$NON-NLS-2$
                    + " after=" + describeFocus(display.getFocusControl()) //$NON-NLS-1$
                    + " stack=" + focusStack(12)); //$NON-NLS-1$
        });
    }

    /**
     * Пока подсказка параметров показана, её границы вызова должны ехать вслед за
     * правками: иначе набранная запятая уводит каретку за {@code lastAvailablePosition}
     * и штатный слушатель закрывает подсказку.
     */
    private static void wireParamHintBounds(StyledText text, SourceViewer viewer)
    {
        IDocumentListener listener = new IDocumentListener()
        {
            @Override
            public void documentAboutToBeChanged(DocumentEvent event)
            {
            }

            @Override
            public void documentChanged(DocumentEvent event)
            {
                ParamHintHtmlModifier.adjustParamHintBounds(text, event.getOffset(),
                    event.getLength(), event.getText());
            }
        };
        IDocument document = viewer.getDocument();
        if (document != null)
            document.addDocumentListener(listener);
        viewer.addTextInputListener(new ITextInputListener()
        {
            @Override
            public void inputDocumentAboutToBeChanged(IDocument oldInput, IDocument newInput)
            {
                if (oldInput != null)
                    oldInput.removeDocumentListener(listener);
            }

            @Override
            public void inputDocumentChanged(IDocument oldInput, IDocument newInput)
            {
                if (newInput != null)
                    newInput.addDocumentListener(listener);
            }
        });
        text.addDisposeListener(e ->
        {
            IDocument current = viewer.getDocument();
            if (current != null)
                current.removeDocumentListener(listener);
        });
    }

    private static void wireKeys(StyledText text, SourceViewer viewer, String logTopic)
    {
        Display display = text.getDisplay();
        if (display == null || display.isDisposed())
            return;
        long[] insertedAt = { 0L };
        Listener enterTraverse = event ->
        {
            if (event.detail != SWT.TRAVERSE_RETURN)
                return;
            if (text.isDisposed() || !isAssistShowing(viewer))
                return;
            event.doit = false;
            boolean inserted = insertSelectedProposal(viewer, logTopic);
            if (inserted)
                insertedAt[0] = System.currentTimeMillis();
            log(logTopic, "enter-traverse inserted=" + inserted //$NON-NLS-1$
                + " focus=" + describeFocus(display.getFocusControl())); //$NON-NLS-1$
        };
        Listener paramHintKey = event ->
        {
            if (text.isDisposed() || event.widget != text)
                return;
            if ((event.stateMask & SWT.MOD1) == 0 || (event.stateMask & SWT.SHIFT) == 0)
                return;
            if (event.keyCode != SWT.SPACE && event.character != ' ')
                return;
            event.doit = false;
            event.type = SWT.None;
            ParamHintHtmlModifier.dismissAllVisible();
            boolean opened = ParamHintHtmlModifier.tryOpenParamHintForViewer(viewer);
            log(logTopic, "ctrlShiftSpace opened=" + opened); //$NON-NLS-1$
        };
        Listener swallowEnter = event ->
        {
            if (insertedAt[0] == 0L)
                return;
            boolean enter = event.character == SWT.CR || event.keyCode == SWT.KEYPAD_CR
                || event.keyCode == SWT.CR;
            if (!enter)
                return;
            if (System.currentTimeMillis() - insertedAt[0] > ENTER_SWALLOW_WINDOW_MS)
            {
                insertedAt[0] = 0L;
                return;
            }
            if (text.isDisposed() || event.widget != text)
                return;
            boolean linked = viewer.getDocument() != null
                    && LinkedModeModel.hasInstalledModel(viewer.getDocument());
            // Вызов со скобками — всегда глотать: иначе LinkedModeUI выходит по ENTER.
            // Простое слово без LinkedMode — тоже глотать: иначе StyledText вставит \n
            // и каретка уедет на следующую строку.
            // Простое слово С LinkedMode — пропустить, чтобы LinkedMode поставил каретку.
            if (!isCallProposalJustApplied() && linked)
            {
                insertedAt[0] = 0L;
                log(logTopic, "enter-key kept: linked non-call"); //$NON-NLS-1$
                return;
            }
            insertedAt[0] = 0L;
            event.doit = false;
            event.type = SWT.None;
            log(logTopic, "enter-key swallowed call=" //$NON-NLS-1$
                    + isCallProposalJustApplied() + " linked=" + linked); //$NON-NLS-1$
        };
        display.addFilter(SWT.Traverse, enterTraverse);
        display.addFilter(SWT.KeyDown, paramHintKey);
        display.addFilter(SWT.KeyDown, swallowEnter);
        Listener escGuard = event -> handleEscapeAgainstDialogClose(event, text, viewer, logTopic);
        display.addFilter(SWT.Traverse, escGuard);
        display.addFilter(SWT.KeyDown, escGuard);
        text.addDisposeListener(e ->
        {
            if (display.isDisposed())
                return;
            display.removeFilter(SWT.Traverse, enterTraverse);
            display.removeFilter(SWT.KeyDown, paramHintKey);
            display.removeFilter(SWT.KeyDown, swallowEnter);
            display.removeFilter(SWT.Traverse, escGuard);
            display.removeFilter(SWT.KeyDown, escGuard);
        });
    }

    /**
     * Esc при открытом списке автодополнения или подсказке параметров не должен закрывать
     * диалог ({@code PreferenceDialog} закрывается по {@link SWT#TRAVERSE_ESCAPE}).
     */
    private static void handleEscapeAgainstDialogClose(Event event,
            StyledText text, SourceViewer viewer, String logTopic)
    {
        if (text == null || text.isDisposed())
            return;
        boolean escapeTraverse = event.type == SWT.Traverse && event.detail == SWT.TRAVERSE_ESCAPE;
        boolean escapeKey = event.type == SWT.KeyDown
                && (event.keyCode == SWT.ESC || event.character == SWT.ESC);
        if (!escapeTraverse && !escapeKey)
            return;

        Shell fieldShell = text.getShell();
        Shell eventShell = null;
        if (event.widget instanceof Control control && !control.isDisposed())
            eventShell = control.getShell();
        Shell active = text.getDisplay().getActiveShell();

        boolean assist = isAssistShowing(viewer);
        boolean onOwnPopup = isOwnPopupShell(text, eventShell) && eventShell != fieldShell
                || isOwnPopupShell(text, active) && active != fieldShell;
        boolean inFieldDialog = eventShell == fieldShell
                || event.widget == text
                || isFieldWidget(event.widget instanceof Control c ? c : null);
        if (!assist && !onOwnPopup)
            return;
        if (!inFieldDialog && !onOwnPopup)
            return;

        if (escapeTraverse)
        {
            event.doit = false;
            event.detail = SWT.TRAVERSE_NONE;
        }
        if (escapeKey)
        {
            event.doit = false;
            event.type = SWT.None;
        }
        if (assist)
        {
            ContentAssistant assistant = ContentAssistPatcher.getContentAssistant(viewer);
            ContentAssistPopupSync.hideProposalPopup(assistant);
        }
        log(logTopic, "esc-guard assist=" + assist + " popup=" + onOwnPopup //$NON-NLS-1$ //$NON-NLS-2$
                + " traverse=" + escapeTraverse); //$NON-NLS-1$
        restoreFocus(text, viewer, logTopic);
    }

    private static boolean insertSelectedProposal(SourceViewer viewer, String logTopic)
    {
        ContentAssistant assistant = ContentAssistPatcher.getContentAssistant(viewer);
        if (assistant == null)
            return false;
        Object popup = ContentAssistPopupSync.getPopupObject(assistant);
        if (popup == null)
            return false;
        try
        {
            return Global.invokeVoid(popup, "insertSelectedProposalWithMask", 0); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            log(logTopic, "insertSelected failed " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static void wireLinkedModeFocusOwner(StyledText text, SourceViewer viewer)
    {
        if (!(viewer instanceof IEditingSupportRegistry registry))
            return;
        IEditingSupport support = new IEditingSupport()
        {
            @Override
            public boolean isOriginator(DocumentEvent event, IRegion subjectRegion)
            {
                return false;
            }

            @Override
            public boolean ownsFocusShell()
            {
                Display display = text.isDisposed() ? null : text.getDisplay();
                if (display == null || display.isDisposed())
                    return false;
                return isOwnPopupShell(text, display.getActiveShell());
            }
        };
        registry.register(support);
        text.addDisposeListener(e -> registry.unregister(support));
    }

    private static void wirePopupFocusReturn(StyledText text, SourceViewer viewer)
    {
        Display display = text.getDisplay();
        if (display == null || display.isDisposed())
            return;
        // topic с attach — в data, чтобы Dispose-фильтр логировал в ту же тему
        String logTopic = (String) text.getData(FIELD_KEY + ".logTopic"); //$NON-NLS-1$
        long[] foreignClickAt = { 0L };
        Listener mouse = event ->
        {
            if (event.widget != text)
                foreignClickAt[0] = System.currentTimeMillis();
        };
        Listener popupGone = event ->
        {
            if (text.isDisposed() || !(event.widget instanceof Shell shell))
                return;
            if (shell == text.getShell() || !isOwnPopupShell(text, shell))
                return;
            String role = String.valueOf(text.getData("tormozit.bpFieldRole")); //$NON-NLS-1$
            long sinceClick = System.currentTimeMillis() - foreignClickAt[0];
            if (sinceClick < FOREIGN_CLICK_GRACE_MS)
            {
                log(logTopic, "popupGone skip foreignClick role=" + role //$NON-NLS-1$
                        + " sinceClick=" + sinceClick); //$NON-NLS-1$
                return;
            }
            if (lastFocusedField != null && lastFocusedField != text)
            {
                log(logTopic, "popupGone skip not-last role=" + role //$NON-NLS-1$
                        + " last=" + describeFocus(lastFocusedField)); //$NON-NLS-1$
                return;
            }
            log(logTopic, "popupGone → restoreFocus role=" + role //$NON-NLS-1$
                    + " popupShell=" + shell.getClass().getSimpleName() //$NON-NLS-1$
                    + "@" + Integer.toHexString(System.identityHashCode(shell)) //$NON-NLS-1$
                    + " focusNow=" + describeFocus(display.getFocusControl()) //$NON-NLS-1$
                    + " stack=" + focusStack(12)); //$NON-NLS-1$
            restoreFocus(text, viewer, logTopic);
        };
        display.addFilter(SWT.MouseDown, mouse);
        display.addFilter(SWT.Dispose, popupGone);
        text.addDisposeListener(e ->
        {
            if (display.isDisposed())
                return;
            display.removeFilter(SWT.MouseDown, mouse);
            display.removeFilter(SWT.Dispose, popupGone);
        });
    }

    /**
     * Своё ли это всплывающее окно: сам диалог поля, окно, им порождённое, или окно EDT
     * ({@code Browser} подсказки параметров, {@code Table} списка автодополнения),
     * привязанное не к диалогу, а к окну workbench.
     */
    private static boolean isOwnPopupShell(StyledText text, Shell shell)
    {
        if (text == null || text.isDisposed() || shell == null || shell.isDisposed())
            return false;
        Shell owner = text.getShell();
        if (owner == null || owner.isDisposed())
            return false;
        if (shell == owner)
            return true;
        for (Composite parent = shell.getParent(); parent != null; parent = parent.getParent())
        {
            if (parent == owner)
                return true;
        }
        int style = shell.getStyle();
        boolean popupStyle = (style & SWT.ON_TOP) != 0 || (style & SWT.TITLE) == 0;
        return popupStyle && hasPopupContent(shell);
    }

    private static boolean hasPopupContent(Composite parent)
    {
        for (Control child : parent.getChildren())
        {
            if (child.isDisposed())
                continue;
            if (child instanceof Browser || child instanceof Table)
                return true;
            if (child instanceof Composite composite && hasPopupContent(composite))
                return true;
        }
        return false;
    }

    private static void log(String topic, String message)
    {
        if (topic != null && !topic.isEmpty())
            Global.tempLog(topic, message);
    }

    private static String focusStack(int maxFrames)
    {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (StackTraceElement frame : frames)
        {
            String cn = frame.getClassName();
            if (cn.startsWith("java.") || cn.startsWith("javax.") //$NON-NLS-1$ //$NON-NLS-2$
                    || cn.startsWith("sun.") || cn.startsWith("jdk.") //$NON-NLS-1$ //$NON-NLS-2$
                    || cn.contains("BslExpressionField.focusStack") //$NON-NLS-1$
                    || cn.contains("BslExpressionField.log")) //$NON-NLS-1$
                continue;
            if (sb.length() > 0)
                sb.append(" <- "); //$NON-NLS-1$
            String simple = cn;
            int dot = cn.lastIndexOf('.');
            if (dot >= 0)
                simple = cn.substring(dot + 1);
            sb.append(simple).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (++taken >= maxFrames)
                break;
        }
        return sb.length() == 0 ? "-" : sb.toString(); //$NON-NLS-1$
    }
}
