package tormozit;

import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/**
 * Нижняя панель popup: переключатель фильтра и тип контекста списка.
 */
public final class ContentAssistPopupUi
{
    /** Data-key нижней панели popup (для определения таблицы автодополнения). */
    public static final String FILTER_BAR_DATA_KEY = "tormozit.filterBar"; //$NON-NLS-1$
    private static final String BAR_DATA_KEY = FILTER_BAR_DATA_KEY;
    private static final String TOGGLE_DATA_KEY = "tormozit.filterToggleButton"; //$NON-NLS-1$
    private static final String CONTEXT_LABEL_DATA_KEY = "tormozit.contextTypeLabel"; //$NON-NLS-1$
    private static final String EXPECTED_LABEL_DATA_KEY = "tormozit.expectedTypeLabel"; //$NON-NLS-1$
    /** Не реагировать на {@code setSelection} при программной синхронизации с Ctrl+Space. */
    private static boolean suppressToggleSelection;
    private static final String PARENT_PLACEHOLDER = "Родитель: —"; //$NON-NLS-1$

    private ContentAssistPopupUi() {}

    public static void ensureFilterToggle(ContentAssistant assistant, SourceViewer viewer,
                                          SmartContentAssistProcessor processor)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            removeFilterToggle(assistant);
            return;
        }
        try
        {
            Object popup = ContentAssistPopupSync.getPopupObject(assistant);
            if (popup == null)
                return;

            Shell shell = ContentAssistPopupSync.getProposalShell(popup);
            if (shell == null || shell.isDisposed())
                return;

            SelectionStyleSupport.install(ContentAssistPopupSync.getProposalTable(popup));
            ContentAssistPopupSync.hideStatusLine(assistant, popup);

            Composite bar = (Composite) shell.getData(BAR_DATA_KEY);
            Button toggle;
            Label contextLabel;
            if (bar == null || bar.isDisposed())
            {
                bar = createFilterBar(shell, assistant, viewer, processor);
                toggle = (Button) bar.getData(TOGGLE_DATA_KEY);
                contextLabel = (Label) bar.getData(CONTEXT_LABEL_DATA_KEY);
            }
            else
            {
                toggle = (Button) bar.getData(TOGGLE_DATA_KEY);
                contextLabel = (Label) bar.getData(CONTEXT_LABEL_DATA_KEY);
            }

            if (toggle != null && !toggle.isDisposed())
            {
                setToggleSelectionQuiet(toggle, SmartAssistFilterState.isSmartFilterEnabled());
                applyFilterToggleAvailability(toggle, viewer, processor);
            }
            if (contextLabel != null && !contextLabel.isDisposed())
                applyContextTypeLabel(contextLabel, viewer);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("filterToggle UI ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Пока идёт пересчёт типов метода под кареткой перед расчётом списка, вместо «Родитель: …»
     * показывается он ({@code null} — нет пересчёта).
     */
    private static volatile String recomputeStatus;

    /**
     * Показать или снять ({@code null}) в нижней панели списка состояние пересчёта типов.
     * Можно звать из любого потока: подпись обновляется в UI.
     */
    static void setRecomputeStatus(org.eclipse.jface.text.ITextViewer viewer, SmartContentAssistProcessor processor,
        String status)
    {
        recomputeStatus = status;
        // Фоновый расчёт автодополнения получает не сам редактор, а обёртку: прежняя проверка
        // на SourceViewer молча выходила, и снятие надписи не доходило до окна (18.10 09:13 —
        // «Пересчёт типов: …» висела после конца пересчёта).
        org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() ->
        {
            // «Активный» редактор в момент пересчёта не задан (сеанс ещё не открыт) — окно не
            // находилось, подпись не менялась (18.10 09:58, assistant=false). Процессор знает свой.
            ContentAssistSessionReloader own = ContentAssistSessionReloader.forProcessor(processor);
            SourceViewer sourceViewer = own != null ? own.getViewer()
                : viewer instanceof SourceViewer direct ? direct : ContentAssistSessionReloader.getActiveViewer();
            applyRecomputeStatus(sourceViewer, status);
        });
    }

    /**
     * Окно ищется по редактору, а не по «активному» сеансу: из UI-потока тот находится, только
     * пока сеанс помечен открытым, и подпись при пересчёте не появлялась (18.10 09:04).
     */
    private static void applyRecomputeStatus(SourceViewer viewer, String status)
    {
        try
        {
            ContentAssistSessionReloader reloader = ContentAssistSessionReloader.forViewer(viewer);
            ContentAssistant assistant = reloader != null ? reloader.getAssistant()
                : ContentAssistSessionReloader.getActiveAssistant();
            Object popup = assistant == null ? null : ContentAssistPopupSync.getPopupObject(assistant);
            Shell shell = popup == null ? null : ContentAssistPopupSync.getProposalShell(popup);
            Composite bar = shell == null || shell.isDisposed() ? null : (Composite) shell.getData(BAR_DATA_KEY);
            Label contextLabel = bar == null || bar.isDisposed() ? null : (Label) bar.getData(CONTEXT_LABEL_DATA_KEY);
            if (contextLabel == null || contextLabel.isDisposed())
                return;
            if (viewer != null)
                applyContextTypeLabel(contextLabel, viewer);
            else
            {
                // Редактор не найден — хотя бы не оставлять надпись о пересчёте.
                contextLabel.setText(status != null ? status : PARENT_PLACEHOLDER);
                contextLabel.setData(TypeLabelMenu.TEXT_KEY, ""); //$NON-NLS-1$
            }
        }
        catch (Exception ignored)
        {
            // Окно списка закрылось или пересоздаётся — надпись обновится при следующем показе.
        }
    }

    private static void applyContextTypeLabel(Label contextLabel, SourceViewer viewer)
    {
        String old = contextLabel.getText();
        String status = recomputeStatus;
        String text = status != null ? status : resolveContextTypeLabel(viewer);
        if (text == null)
            text = ""; //$NON-NLS-1$
        Composite bar = contextLabel.getParent();

        String shown = text;
        if (bar != null && !bar.isDisposed())
            shown = truncateToPopupWidth(contextLabel, bar, text);

        contextLabel.setText(shown);
        contextLabel.setData(TypeLabelMenu.TEXT_KEY, status == null ? text : ""); //$NON-NLS-1$
        Global.tempLog("assist-parent-type", "apply text=" + text + " shown=" + shown //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " bounds=" + contextLabel.getBounds() + " status=" + status); //$NON-NLS-1$ //$NON-NLS-2$
        if (shown.equals(text))
            contextLabel.setToolTipText(null);
        else
            contextLabel.setToolTipText(TooltipText.wrap(contextLabel, text));

        boolean expectedChanged = applyExpectedTypeLabel(bar, viewer);
        if (shown.equals(old) && !expectedChanged)
            return;
        if (bar == null || bar.isDisposed())
            return;
        bar.layout(true, true);
        Shell shell = bar.getShell();
        if (shell != null && !shell.isDisposed())
            shell.layout(true, true);
    }

    private static void copyContextTypeLabel(SourceViewer viewer)
    {
        copyTypeLabel(viewer, resolveContextTypeLabel(viewer));
    }

    private static String resolveExpectedTypeLabel(SourceViewer viewer)
    {
        if (viewer == null || viewer.getDocument() == null)
            return ""; //$NON-NLS-1$
        String types = BslExpectedTypePremiums.expectedTypeTextAt(viewer.getDocument(), resolveViewerCaret(viewer));
        return types == null || types.isEmpty() ? "" : "Ожидаем: " + types; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean applyExpectedTypeLabel(Composite bar, SourceViewer viewer)
    {
        Label label = bar != null && !bar.isDisposed() ? (Label) bar.getData(EXPECTED_LABEL_DATA_KEY) : null;
        if (label == null || label.isDisposed())
            return false;
        String text = resolveExpectedTypeLabel(viewer);
        String shown = truncateToPopupWidth(label, bar, text);
        boolean changed = !shown.equals(label.getText()) || label.getVisible() != !text.isEmpty();
        label.setText(shown);
        label.setData(TypeLabelMenu.TEXT_KEY, text);
        label.setVisible(!text.isEmpty());
        ((GridData)label.getLayoutData()).exclude = text.isEmpty();
        label.setToolTipText(shown.equals(text) ? null : TooltipText.wrap(label, text));
        return changed;
    }

    private static void copyTypeLabel(SourceViewer viewer, String text)
    {
        if (text == null || text.isBlank())
            return;
        org.eclipse.swt.dnd.Clipboard clipboard =
            new org.eclipse.swt.dnd.Clipboard(viewer.getTextWidget().getDisplay());
        try
        {
            clipboard.setContents(new Object[] { text },
                new org.eclipse.swt.dnd.Transfer[] { org.eclipse.swt.dnd.TextTransfer.getInstance() });
        }
        finally
        {
            clipboard.dispose();
        }
        ToastNotification.show("Скопировано в буфер обмена", text, 3_000); //$NON-NLS-1$
    }

    /**
     * Обрезает подпись «Родитель: …» так, чтобы она вписалась в ширину popup;
     * отброшенный хвост (список типов составного типа) остаётся в подсказке при наведении.
     */
    private static String truncateToPopupWidth(Label contextLabel, Composite bar, String text)
    {
        if (text.isEmpty())
            return text;
        Shell shell = bar.getShell();
        if (shell == null || shell.isDisposed())
            return text;
        int budget = shell.getClientArea().width - 12;
        for (org.eclipse.swt.widgets.Control c : bar.getChildren())
        {
            if (c == contextLabel || c instanceof Label || c.isDisposed())
                continue;
            budget -= c.computeSize(SWT.DEFAULT, SWT.DEFAULT).x + 10;
        }
        if (budget <= 0)
            return text;
        org.eclipse.swt.graphics.GC gc = new org.eclipse.swt.graphics.GC(contextLabel);
        try
        {
            gc.setFont(contextLabel.getFont());
            if (gc.textExtent(text).x <= budget)
                return text;
            String ellipsis = "…"; //$NON-NLS-1$
            int lo = 0;
            int hi = text.length();
            while (lo < hi)
            {
                int mid = (lo + hi + 1) >>> 1;
                if (gc.textExtent(text.substring(0, mid) + ellipsis).x <= budget)
                    lo = mid;
                else
                    hi = mid - 1;
            }
            return lo <= 0 ? ellipsis : text.substring(0, lo) + ellipsis;
        }
        finally
        {
            gc.dispose();
        }
    }

    public static void updateContextTypeLabel(SourceViewer viewer)
    {
        try
        {
            ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
            if (assistant == null || viewer == null)
                return;
            Object popup = ContentAssistPopupSync.getPopupObject(assistant);
            if (popup == null)
                return;
            Shell shell = ContentAssistPopupSync.getProposalShell(popup);
            if (shell == null || shell.isDisposed())
                return;
            Composite bar = (Composite) shell.getData(BAR_DATA_KEY);
            if (bar == null || bar.isDisposed())
                return;
            Label contextLabel = (Label) bar.getData(CONTEXT_LABEL_DATA_KEY);
            if (contextLabel != null && !contextLabel.isDisposed())
                applyContextTypeLabel(contextLabel, viewer);
        }
        catch (Exception error)
        {
            Global.tempLogException("assist-parent-type", "update.failed", error); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    public static boolean isFilterBarCreated(ContentAssistant assistant)
    {
        try
        {
            Object popup = ContentAssistPopupSync.getPopupObject(assistant);
            if (popup == null)
                return false;
            Shell shell = ContentAssistPopupSync.getProposalShell(popup);
            if (shell == null || shell.isDisposed())
                return false;
            Composite bar = (Composite) shell.getData(BAR_DATA_KEY);
            return bar != null && !bar.isDisposed();
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /** Текст «Родитель: …» без записи в label (для H17). */
    public static String peekContextTypeLabel(SourceViewer viewer)
    {
        return resolveContextTypeLabel(viewer);
    }

    public static void removeFilterToggle(ContentAssistant assistant)
    {
        try
        {
            Object popup = ContentAssistPopupSync.getPopupObject(assistant);
            if (popup == null)
                return;
            Shell shell = ContentAssistPopupSync.getProposalShell(popup);
            if (shell == null || shell.isDisposed())
                return;
            Composite bar = (Composite) shell.getData(BAR_DATA_KEY);
            if (bar != null && !bar.isDisposed())
            {
                bar.dispose();
                shell.setData(BAR_DATA_KEY, null);
                shell.layout(true, true);
            }
        }
        catch (Exception ignored) {}
    }

    public static void syncFilterToggle(ContentAssistant assistant, SourceViewer viewer)
    {
        if (!ComfortSettings.isReplaceListFiltersEnabled())
        {
            removeFilterToggle(assistant);
            return;
        }
        try
        {
            Object popup = ContentAssistPopupSync.getPopupObject(assistant);
            if (popup == null)
                return;
            Shell shell = ContentAssistPopupSync.getProposalShell(popup);
            if (shell == null || shell.isDisposed())
                return;
            Composite bar = (Composite) shell.getData(BAR_DATA_KEY);
            if (bar == null || bar.isDisposed())
                return;
            Button toggle = (Button) bar.getData(TOGGLE_DATA_KEY);
            if (toggle != null && !toggle.isDisposed())
            {
                setToggleSelectionQuiet(toggle, SmartAssistFilterState.isSmartFilterEnabled());
                applyFilterToggleAvailability(toggle, viewer,
                    ContentAssistSessionReloader.getActiveProcessor());
            }
            Label contextLabel = (Label) bar.getData(CONTEXT_LABEL_DATA_KEY);
            if (contextLabel != null && !contextLabel.isDisposed())
                applyContextTypeLabel(contextLabel, viewer);
        }
        catch (Exception ignored) {}
    }

    private static String resolveContextTypeLabel(SourceViewer viewer)
    {
        if (viewer == null || viewer.getDocument() == null)
        {
            return ""; //$NON-NLS-1$
        }
        int modelCaret = resolveViewerCaret(viewer);
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        int filterOff = assistant != null ? ContentAssistPopupSync.getFilterOffset(assistant) : -1;
        int invOff = assistant != null ? ContentAssistPopupSync.getInvocationOffset(assistant) : -1;
        int lastOff = assistant != null ? ContentAssistPopupSync.getLastCompletionOffset(assistant) : -1;
        int caret;
        // Позиции popup могли остаться от списка слов до ввода точки. Контекст подписи
        // определяется по текущей каретке редактора; поля popup — только запасной источник.
        if (modelCaret >= 0)
        {
            caret = modelCaret;
        }
        else if (filterOff >= 0)
        {
            caret = filterOff;
        }
        else if (invOff >= 0)
        {
            caret = invOff;
        }
        else if (lastOff >= 0)
        {
            caret = lastOff;
        }
        else
        {
            caret = modelCaret;
        }
        Global.tempLog("assist-parent-type", "resolve caret=" + caret + " live=" + modelCaret //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " filter=" + filterOff + " invocation=" + invOff + " last=" + lastOff); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        boolean inLiteral = SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
        if (inLiteral)
        {
            ContentAssistSessionReloader reloader =
                ContentAssistSessionReloader.getActiveReloader();
            String ctx = BslQueryLiteralSupport.parentTypeTextAt(viewer.getDocument(), caret);
            if (ctx == null)
                ctx = reloader != null ? reloader.getIrAssistContextTypeLabel() : null;
            return ctx != null && !ctx.isEmpty()
                ? "Родитель: " + ctx : PARENT_PLACEHOLDER;
        }
        int dot = SmartContentAssistProcessor.ReceiverTypeLabel.findMemberAccessDot(
            viewer.getDocument(), caret);
        if (dot < 0)
            return ""; //$NON-NLS-1$
        SmartContentAssistProcessor processor = ContentAssistSessionReloader.getActiveProcessor();
        String label = processor != null
            ? processor.resolveReceiverTypeLabel(viewer.getDocument(), caret) : ""; //$NON-NLS-1$
        // Только готовый фоновый снимок: readOnly на UI ждёт разрешения ссылок EDT
        // (16.10.2026: waitAllBslResolveCrossReferencesJob). До готовности показываем «—».
        // Не кэшируем по одной точке: полный тип может прийти после первого показа окна.
        return label != null && !label.isEmpty() ? "Родитель: " + label : PARENT_PLACEHOLDER; //$NON-NLS-1$
    }

    private static Composite createFilterBar(Shell shell, ContentAssistant assistant,
                                             SourceViewer viewer,
                                             SmartContentAssistProcessor processor)
    {
        Composite bar = new Composite(shell, SWT.NONE);
        GridLayout row = new GridLayout(2, false);
        row.marginWidth = 0;
        row.marginHeight = 0;
        row.marginLeft = 4;
        row.marginRight = 4;
        row.marginTop = 2;
        row.marginBottom = 2;
        row.horizontalSpacing = 10;
        row.verticalSpacing = 2;
        bar.setLayout(row);
        GridData barData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        bar.setLayoutData(barData);

        Button toggle = new Button(bar, SWT.CHECK);
        toggle.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 1, 2));
        toggle.setText("Фильтр"); //$NON-NLS-1$
        setToggleSelectionQuiet(toggle, SmartAssistFilterState.isSmartFilterEnabled());
        toggle.addListener(SWT.Selection, e -> {
            if (suppressToggleSelection)
                return;
            ContentAssistPopupSync.captureSelectionBeforeFilterToggle(assistant);
            SmartAssistFilterState.setSmartFilterEnabled(toggle.getSelection());
            ContentAssistPopupSync.recomputePopupList(assistant, viewer, processor);
        });

        Label contextLabel = new Label(bar, SWT.NONE);
        contextLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        contextLabel.addListener(SWT.MouseDoubleClick, e -> copyContextTypeLabel(viewer));
        TypeLabelMenu.install(contextLabel, viewer);

        Label expectedLabel = new Label(bar, SWT.NONE);
        expectedLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        expectedLabel.addListener(SWT.MouseDoubleClick, e -> copyTypeLabel(viewer, resolveExpectedTypeLabel(viewer)));
        TypeLabelMenu.install(expectedLabel, viewer);

        bar.setData(TOGGLE_DATA_KEY, toggle);
        bar.setData(CONTEXT_LABEL_DATA_KEY, contextLabel);
        bar.setData(EXPECTED_LABEL_DATA_KEY, expectedLabel);
        shell.setData(BAR_DATA_KEY, bar);
        applyContextTypeLabel(contextLabel, viewer);

        shell.addListener(SWT.KeyDown, e -> {
            ContentAssistSessionReloader.CtrlSpaceFilter filter =
                ContentAssistSessionReloader.ctrlSpaceFilter(assistant);
            if (filter != null)
                filter.handleEvent(e);
        });
        shell.layout(true, true);
        ContentAssistDebug.log("filterBar UI created"); //$NON-NLS-1$
        return bar;
    }

    /** Контекстное меню полного текста типов, независимо от обрезки надписи. */
    private static final class TypeLabelMenu
    {
        static final String TEXT_KEY = "tormozit.assist.typeLabelText"; //$NON-NLS-1$
        private final Label label;
        private final SourceViewer viewer;
        private final Menu menu;
        private String fullText;

        private TypeLabelMenu(Label label, SourceViewer viewer)
        {
            this.label = label;
            this.viewer = viewer;
            menu = new Menu(label);
            label.setMenu(menu);
        }

        static void install(Label label, SourceViewer viewer)
        {
            TypeLabelMenu support = new TypeLabelMenu(label, viewer);
            support.menu.addListener(SWT.Show, event -> {
                try
                {
                    support.show();
                }
                catch (RuntimeException | LinkageError error)
                {
                    Global.tempLogException("assist-type-menu", "show.failed", error); //$NON-NLS-1$ //$NON-NLS-2$
                    for (MenuItem item : support.menu.getItems())
                        item.dispose();
                }
            });
            label.addListener(SWT.Dispose, event -> {
                if (!support.menu.isDisposed())
                    support.menu.dispose();
            });
        }

        private void show()
        {
            for (MenuItem item : menu.getItems())
                item.dispose();
            fullText = (String)label.getData(TEXT_KEY);
            if (fullText == null || fullText.isBlank() || fullText.equals(PARENT_PLACEHOLDER))
                return;
            Global.tempLog("assist-type-menu", "show text=" + fullText); //$NON-NLS-1$ //$NON-NLS-2$
            addAction("Копировать", () -> copyTypeLabel(viewer, fullText)); //$NON-NLS-1$
            java.util.List<String> names = typeNames(fullText);
            com._1c.g5.v8.dt.mcore.TypeItem single = names.size() == 1
                ? MetadataTypeMenu.resolveType(viewer, names.get(0)) : null;
            ContentAssistSessionReloader reloader = ContentAssistSessionReloader.forViewer(viewer);
            TextEditorFacade facade = reloader != null ? reloader.getFacade() : null;
            if (single != null && BslEditorHyperlinkHook.typeProducer(single) != null
                && facade != null && facade.getRaw() instanceof com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor editor)
            {
                for (org.eclipse.jface.text.hyperlink.IHyperlink link : BslEditorHyperlinkHook.metadataTypeLinks(single, editor))
                {
                    if (!BslEditorHyperlinkHook.isPrimary(link))
                        addAction(link.getHyperlinkText(), () -> {
                            label.getShell().setVisible(false);
                            link.open();
                        });
                }
            }
            else if (names.size() > 1)
                addAction("Открыть типы", () -> openTypes(names)); //$NON-NLS-1$
        }

        private void addAction(String text, Runnable action)
        {
            MenuItem item = new MenuItem(menu, SWT.PUSH);
            item.setText(text);
            item.addListener(SWT.Selection, event -> {
                Global.tempLog("assist-type-menu", "action text=" + text + " types=" + fullText); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                try
                {
                    action.run();
                }
                catch (RuntimeException | LinkageError error)
                {
                    Global.tempLogException("assist-type-menu", "action.failed text=" + text, error); //$NON-NLS-1$ //$NON-NLS-2$
                    ToastNotification.show(text, "Не удалось выполнить команду: " + error.getMessage(), 5000); //$NON-NLS-1$
                }
            });
        }

        private void openTypes(java.util.List<String> names)
        {
            Global.tempLog("assist-type-menu", "openTypes names=" + names); //$NON-NLS-1$ //$NON-NLS-2$
            java.util.List<com._1c.g5.v8.dt.mcore.TypeItem> types = new java.util.ArrayList<>();
            for (String name : names)
            {
                var type = MetadataTypeMenu.resolveType(viewer, name);
                if (type == null)
                {
                    Global.tempLog("assist-type-menu", "unresolved=" + name); //$NON-NLS-1$ //$NON-NLS-2$
                    ToastNotification.show("Открыть типы", "Не удалось найти тип: " + name, 5000); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                types.add(type);
            }
            if (viewer.getDocument() instanceof org.eclipse.xtext.ui.editor.model.IXtextDocument document)
            {
                org.eclipse.xtext.resource.XtextResource resource = ContentAssistSessionReloader.readOnlyPeekAst(
                    document, value -> value);
                Shell parent = viewer.getTextWidget().getShell();
                label.getShell().setVisible(false);
                SelectTypeDialogHook.openReadOnly(parent, types, resource);
            }
        }

        private static java.util.List<String> typeNames(String text)
        {
            String types = text.substring(text.indexOf(':') + 1).trim().replaceFirst("^\\(\\d+\\)\\s*", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (types.startsWith("<") && types.endsWith(">")) //$NON-NLS-1$ //$NON-NLS-2$
                types = types.substring(1, types.length() - 1);
            return java.util.Arrays.stream(types.split("[,|]")) //$NON-NLS-1$
                .map(String::trim).filter(name -> !name.isEmpty()).distinct().toList();
        }

    }

    private static void setToggleSelectionQuiet(Button toggle, boolean selected)
    {
        suppressToggleSelection = true;
        try
        {
            toggle.setSelection(selected);
        }
        finally
        {
            suppressToggleSelection = false;
        }
    }

    private static void applyFilterToggleAvailability(Button toggle, SourceViewer viewer,
                                                      SmartContentAssistProcessor processor)
    {
        int caret = resolveViewerCaret(viewer);
        boolean inLiteral = SmartContentAssistProcessor.isStringLiteralAssistContext(viewer, caret);
        boolean irConnected = false;
        if (inLiteral)
        {
            ContentAssistSessionReloader reloader = ContentAssistSessionReloader.getActiveReloader();
            TextEditorFacade facade = reloader != null ? reloader.getFacade() : null;
            irConnected = IrBslExpressionHtmlSupport.resolveIrSessionForAssist(facade, viewer)
                != null;
        }
        toggle.setEnabled(true);
        toggle.setToolTipText(null);
    }

    /** Стили JFace сохраняются и на текущей строке; фон выделения рисуем сами. */
    private static final class SelectionStyleSupport
    {
        private static final String DATA_KEY = "tormozit.assist.selectionStyles"; //$NON-NLS-1$
        private Color selectionBackground;
        private RGB baseBackground;
        private boolean focused;

        static void install(Table table)
        {
            if (table == null || table.isDisposed() || table.getData(DATA_KEY) != null)
                return;
            SelectionStyleSupport support = new SelectionStyleSupport();
            table.setData(DATA_KEY, support);
            // TableOwnerDrawSupport удаляет цвета StyleRange уже в MeasureItem.
            // Наш слушатель должен отработать перед ним, а не только перед PaintItem.
            Listener keepStyles = event -> event.detail &= ~(SWT.SELECTED | SWT.HOT);
            for (int eventType : new int[] { SWT.MeasureItem, SWT.PaintItem })
            {
                Listener[] existing = table.getListeners(eventType);
                for (Listener listener : existing)
                    table.removeListener(eventType, listener);
                table.addListener(eventType, keepStyles);
                for (Listener listener : existing)
                    table.addListener(eventType, listener);
            }
            table.addListener(SWT.EraseItem, event -> {
                boolean selected = (event.detail & SWT.SELECTED) != 0;
                event.detail &= ~(SWT.SELECTED | SWT.HOT);
                if (!selected)
                    return;
                Color oldBackground = event.gc.getBackground();
                event.gc.setBackground(support.selectionBackground(table));
                event.gc.fillRectangle(0, event.y, table.getClientArea().width, event.height);
                event.gc.setBackground(oldBackground);
                event.detail &= ~SWT.BACKGROUND;
            });
            table.addListener(SWT.Dispose, event -> {
                if (support.selectionBackground != null && !support.selectionBackground.isDisposed())
                    support.selectionBackground.dispose();
            });
            table.redraw();
        }

        private Color selectionBackground(Table table)
        {
            RGB background = table.getBackground().getRGB();
            boolean hasFocus = table.isFocusControl();
            if (selectionBackground == null || selectionBackground.isDisposed()
                || !background.equals(baseBackground) || hasFocus != focused)
            {
                if (selectionBackground != null && !selectionBackground.isDisposed())
                    selectionBackground.dispose();
                selectionBackground = ListSelectionPalette.rowSelectionBackground(table,
                    ListSelectionPalette.Mode.OWN_SELECTION);
                baseBackground = background;
                focused = hasFocus;
            }
            return selectionBackground;
        }
    }

    private static int resolveViewerCaret(SourceViewer viewer)
    {
        return SmartContentAssistProcessor.resolveWidgetCaret(viewer);
    }
}
