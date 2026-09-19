package tormozit;

import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.jface.resource.JFaceColors;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.ScrolledForm;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

/**
 * Имя информационной базы в заголовке {@code InfobaseEditor}
 * ({@code com._1c.g5.v8.dt.internal.platform.services.ui.editors.infobase.InfobaseEditor})
 * становится гиперссылкой, открывающей контекстное меню панели «Информационные базы»
 * для строки этой базы — по аналогии с {@link MdEditorTitleNavigatorMenuHook} (имя объекта
 * метаданных в заголовке редактора МД открывает меню навигатора).
 *
 * <p>Заголовок формы ({@code AbstractAefBoundEditor.formatHeaderText}) — «Информационные базы →
 * ИмяБазы»; ссылкой становится только сегмент с именем базы, префикс не трогаем (в отличие от
 * {@link MdEditorTitleNavigatorMenuHook} здесь нет пути с несколькими объектами и переформатирования
 * заголовка не требуется).
 *
 * <p>Панель «Информационные базы» ({@code InfobasesView}) — тоже {@link CommonNavigator}
 * ({@code AbstractPropertySheetBoundNavigator extends CommonNavigator}), поэтому меню — то же
 * самое: активируем панель, выделяем строку ({@code selectReveal}), показываем {@link Menu} её
 * дерева. Если строка скрыта фильтром панели — клик молча ничего не делает (в отличие от
 * {@link MdEditorTitleNavigatorMenuHook}, своего «резервного» меню для этого случая здесь нет —
 * случай редкий: пользователь кликает по имени базы, которую сам же в этот момент редактирует).
 */
public final class InfobaseEditorTitleMenuHook implements IStartup
{
    private static final String TAG = "InfobaseEditorTitleMenuHook"; //$NON-NLS-1$

    private static final String INFOBASE_EDITOR_ID =
        "com._1c.g5.v8.dt.platform.services.ui.InfobaseEditor"; //$NON-NLS-1$

    private static final String INFOBASES_VIEW_ID =
        "com._1c.g5.v8.dt.platform.services.ui.InfobasesView"; //$NON-NLS-1$

    /** Ключ пометки шапки формы: ссылка уже встроена. */
    private static final String KEY_INSTALLED = "tormozit.infobaseTitleNavigatorMenu"; //$NON-NLS-1$

    /** Пауза перед возвратом фокуса в редактор — чтобы выбранный пункт успел отработать, мс. */
    private static final int FOCUS_RETURN_DELAY_MS = 300;

    // =========================================================================
    // IStartup
    // =========================================================================

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
            for (IWorkbenchWindow window : windows)
                hookWindow(window);

            PlatformUI.getWorkbench().addWindowListener(new org.eclipse.ui.IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });
        });
    }

    // =========================================================================
    // Подключение к окну / редактору
    // =========================================================================

    private static void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                if (INFOBASE_EDITOR_ID.equals(ref.getId()))
                {
                    IEditorPart editor = ref.getEditor(false);
                    if (editor != null)
                        hookEditor(editor);
                }
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference ref)
            {
                if (!INFOBASE_EDITOR_ID.equals(ref.getId()))
                    return;
                Display.getDefault().asyncExec(() ->
                {
                    IWorkbenchPart part = ref.getPart(false);
                    if (part instanceof IEditorPart editor)
                        hookEditor(editor);
                });
            }
            @Override public void partActivated(IWorkbenchPartReference r)    {}
            @Override public void partBroughtToTop(IWorkbenchPartReference r) {}
            @Override public void partClosed(IWorkbenchPartReference r)       {}
            @Override public void partDeactivated(IWorkbenchPartReference r)  {}
            @Override public void partHidden(IWorkbenchPartReference r)       {}
            @Override public void partVisible(IWorkbenchPartReference r)      {}
            @Override public void partInputChanged(IWorkbenchPartReference r) {}
        });
    }

    private static void hookEditor(IEditorPart editor)
    {
        try
        {
            Object model = Global.call(editor, "getModel"); //$NON-NLS-1$
            Global.tempLog(TAG, "hookEditor: model=" //$NON-NLS-1$
                + (model == null ? "null" : model.getClass().getName())); //$NON-NLS-1$
            if (!(model instanceof InfobaseReference infobase))
                return;

            // AbstractAefBoundEditor (родитель InfobaseEditor) держит готовую ScrolledForm
            // в приватном поле — тем же способом, каким этот класс уже получает поля EDT.
            Object scrolledFormField = Global.getField(editor, "scrolledForm"); //$NON-NLS-1$
            Global.tempLog(TAG, "hookEditor: scrolledForm=" //$NON-NLS-1$
                + (scrolledFormField == null ? "null" : scrolledFormField.getClass().getName())); //$NON-NLS-1$
            if (!(scrolledFormField instanceof ScrolledForm scrolledForm) || scrolledForm.isDisposed())
                return;

            install(scrolledForm.getForm(), infobase, editor);
        }
        catch (Exception e)
        {
            Global.tempLog(TAG, "hookEditor EXCEPTION: " + e); //$NON-NLS-1$
            Global.logError(TAG, "hook editor", e); //$NON-NLS-1$
        }
    }

    // =========================================================================
    // Встраивание ссылки в заголовок формы
    // =========================================================================

    private static void install(Form form, InfobaseReference infobase, IEditorPart editor)
    {
        if (form == null || form.isDisposed())
            return;
        Composite head = form.getHead();
        if (head == null || head.isDisposed())
            return;
        Control titleRegion = FormHeaderTitleText.findTitleRegion(head);
        Global.tempLog(TAG, "install: titleRegion=" + (titleRegion == null ? "null" : "found")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (titleRegion == null)
            return;
        if (head.getData(KEY_INSTALLED) != null)
        {
            Global.tempLog(TAG, "install: already installed, skip"); //$NON-NLS-1$
            return;
        }

        // Стиль части текста поддерживает только StyledText-вариант заголовка
        form.setTitleTextSelectable(true);

        StyledText titleText = FormHeaderTitleText.findTitleText(titleRegion);
        Global.tempLog(TAG, "install: titleText=" + (titleText == null ? "null" //$NON-NLS-1$ //$NON-NLS-2$
            : ("\"" + titleText.getText() + "\""))); //$NON-NLS-1$ //$NON-NLS-2$
        if (titleText == null)
            return;

        head.setData(KEY_INSTALLED, Boolean.TRUE);
        // Win32: Ctrl+C забирает global Copy редактора, не StyledText.
        CopyCommandSupport.wireCopyOverride(titleText);
        new TitleInfobaseLink(titleText, infobase, editor);
    }

    // =========================================================================
    // Ссылка на имени базы
    // =========================================================================

    /**
     * Оформляет имя информационной базы в заголовке как гиперссылку и открывает по щелчку
     * меню панели «Информационные базы».
     */
    private static final class TitleInfobaseLink
    {
        private final StyledText titleText;

        /** Редактор, которому возвращается фокус после закрытия меню. */
        private final IEditorPart editor;

        private final InfobaseReference infobase;

        private int nameStart = -1;

        private int nameLength;

        /** Нажатие левой кнопки пришлось на имя — ждём отпускания на том же имени. */
        private boolean pressedOnName;

        /** Смесь цвета гиперссылки с цветом текста заголовка; свой ресурс, dispose при уничтожении. */
        private Color linkColor;

        TitleInfobaseLink(StyledText titleText, InfobaseReference infobase, IEditorPart editor)
        {
            this.titleText = titleText;
            this.infobase = infobase;
            this.editor = editor;

            Global.tempLog(TAG, "TitleInfobaseLink: infobase.getName()=\"" + infobase.getName() //$NON-NLS-1$
                + "\" title=\"" + titleText.getText() + "\""); //$NON-NLS-1$ //$NON-NLS-2$
            applyLinkStyle();
            Global.tempLog(TAG, "TitleInfobaseLink: after applyLinkStyle nameStart=" + nameStart //$NON-NLS-1$
                + " nameLength=" + nameLength); //$NON-NLS-1$

            // EDT переустанавливает заголовок при обновлении модели — стиль надо вернуть
            titleText.addListener(SWT.Dispose, event -> disposeLinkColor());
            titleText.addListener(SWT.Modify, event -> applyLinkStyle());
            titleText.addListener(SWT.MouseMove, event -> updateHover(event.x, event.y));
            titleText.addListener(SWT.MouseExit, event -> updateHover(-1, -1));
            // Меню показывается по MouseUp, а не по MouseDown — см. аналогичное место
            // в MdEditorTitleNavigatorMenuHook.TitleObjectLink.
            titleText.addListener(SWT.MouseDown, event ->
            {
                pressedOnName = event.button == 1 && isOnName(event.x, event.y);
                Global.tempLog(TAG, "MouseDown: x=" + event.x + " y=" + event.y //$NON-NLS-1$ //$NON-NLS-2$
                    + " button=" + event.button + " pressedOnName=" + pressedOnName); //$NON-NLS-1$ //$NON-NLS-2$
            });
            titleText.addListener(SWT.MouseUp, event ->
            {
                boolean click = pressedOnName && event.button == 1
                    && titleText.getSelectionCount() == 0
                    && isOnName(event.x, event.y);
                Global.tempLog(TAG, "MouseUp: x=" + event.x + " y=" + event.y //$NON-NLS-1$ //$NON-NLS-2$
                    + " selectionCount=" + titleText.getSelectionCount() //$NON-NLS-1$
                    + " pressedOnName=" + pressedOnName + " click=" + click); //$NON-NLS-1$ //$NON-NLS-2$
                pressedOnName = false;
                if (click)
                    openInfobasesViewMenu();
            });
        }

        private void applyLinkStyle()
        {
            if (titleText.isDisposed())
                return;

            locateName(titleText.getText());
            if (nameStart < 0)
            {
                titleText.setStyleRanges(new StyleRange[0]);
                return;
            }
            titleText.setStyleRanges(
                new StyleRange[] { new StyleRange(nameStart, nameLength, linkForeground(), null) });
        }

        /**
         * Цвет гиперссылки, сдвинутый на 30% к цвету текста заголовка — см. подробности
         * в {@link MdEditorTitleNavigatorMenuHook.TitleObjectLink#linkForeground()}.
         */
        private Color linkForeground()
        {
            Color hyperlink = JFaceColors.getHyperlinkText(titleText.getDisplay());
            Color base = titleText.getForeground();
            if (hyperlink == null || hyperlink.isDisposed())
                return base;
            if (base == null || base.isDisposed())
                return hyperlink;
            RGB themed = ThemeAwareColors.toEffectiveRgb(hyperlink.getRGB());
            RGB desired = blendTowards(themed, base.getRGB(), 0.30);
            if (linkColor != null && !linkColor.isDisposed() && linkColor.getRGB().equals(desired))
                return linkColor;
            disposeLinkColor();
            linkColor = new Color(titleText.getDisplay(), desired);
            return linkColor;
        }

        /** {@code amount} = 0 — {@code from}, 1 — {@code to}. */
        private static RGB blendTowards(RGB from, RGB to, double amount)
        {
            int r = (int) Math.round(from.red + (to.red - from.red) * amount);
            int g = (int) Math.round(from.green + (to.green - from.green) * amount);
            int b = (int) Math.round(from.blue + (to.blue - from.blue) * amount);
            return new RGB(clampChannel(r), clampChannel(g), clampChannel(b));
        }

        private static int clampChannel(int value)
        {
            if (value < 0)
                return 0;
            if (value > 255)
                return 255;
            return value;
        }

        private void disposeLinkColor()
        {
            if (linkColor == null || linkColor.isDisposed())
            {
                linkColor = null;
                return;
            }
            linkColor.dispose();
            linkColor = null;
        }

        /** Сегмент заголовка, точно совпадающий с именем базы — последнее вхождение. */
        private void locateName(String title)
        {
            nameStart = -1;
            nameLength = 0;
            String name = infobase.getName();
            if (title == null || name == null || name.isEmpty())
                return;

            int from = 0;
            while (true)
            {
                int index = title.indexOf(name, from);
                if (index < 0)
                    return;
                boolean leftFree = index == 0 || !isNameChar(title.charAt(index - 1));
                int after = index + name.length();
                boolean rightFree = after >= title.length() || !isNameChar(title.charAt(after));
                if (leftFree && rightFree)
                {
                    nameStart = index;
                    nameLength = name.length();
                }
                from = index + 1;
            }
        }

        private static boolean isNameChar(char c)
        {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        private boolean isOnName(int x, int y)
        {
            if (titleText.isDisposed() || nameStart < 0)
                return false;
            int offset;
            try
            {
                offset = titleText.getOffsetAtPoint(new Point(x, y));
            }
            catch (IllegalArgumentException e)
            {
                return false; // точка вне текста
            }
            return offset >= nameStart && offset < nameStart + nameLength;
        }

        /** Курсор и подсказка меняются, только когда указатель над именем базы. */
        private void updateHover(int x, int y)
        {
            if (titleText.isDisposed())
                return;

            boolean onName = isOnName(x, y);
            titleText.setCursor(titleText.getDisplay()
                .getSystemCursor(onName ? SWT.CURSOR_HAND : SWT.CURSOR_IBEAM));
            titleText.setToolTipText(onName
                ? TooltipText.wrap(titleText, "Меню базы, как в панели «Информационные базы»" //$NON-NLS-1$
                    + Global.pluginSignForTooltip())
                : null);
        }

        /**
         * Выделяет базу в панели «Информационные базы» и показывает штатное меню её строки
         * под именем базы. Панель не активируется, фокус остаётся в редакторе.
         */
        private void openInfobasesViewMenu()
        {
            try
            {
                Global.tempLog(TAG, "openInfobasesViewMenu: start"); //$NON-NLS-1$
                CommonNavigator navigator = activateInfobasesView();
                Global.tempLog(TAG, "openInfobasesViewMenu: navigator=" //$NON-NLS-1$
                    + (navigator == null ? "null" : navigator.getClass().getName())); //$NON-NLS-1$
                if (navigator == null)
                    return;
                Object commonViewer = Global.invoke(navigator, "getCommonViewer"); //$NON-NLS-1$
                Global.tempLog(TAG, "openInfobasesViewMenu: commonViewer=" //$NON-NLS-1$
                    + (commonViewer == null ? "null" : commonViewer.getClass().getName())); //$NON-NLS-1$
                if (!(commonViewer instanceof CommonViewer viewer)
                    || viewer.getTree() == null || viewer.getTree().isDisposed())
                {
                    return;
                }

                navigator.selectReveal(new StructuredSelection(infobase));
                boolean revealed = isInfobaseRevealed(viewer);
                Global.tempLog(TAG, "openInfobasesViewMenu: revealed=" + revealed //$NON-NLS-1$
                    + " selection=" + viewer.getStructuredSelection().getFirstElement()); //$NON-NLS-1$
                if (!revealed)
                    return; // строка скрыта фильтром панели — показывать меню не для чего

                Menu menu = viewer.getTree().getMenu();
                Global.tempLog(TAG, "openInfobasesViewMenu: menu=" + (menu == null ? "null" : "found") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " disposed=" + (menu != null && menu.isDisposed())); //$NON-NLS-1$
                if (menu == null || menu.isDisposed())
                    return;
                hookMenuClose(menu);
                menu.setLocation(menuLocation());
                menu.setVisible(true);
                Global.tempLog(TAG, "openInfobasesViewMenu: menu.setVisible(true) called"); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Global.tempLog(TAG, "openInfobasesViewMenu EXCEPTION: " + e); //$NON-NLS-1$
                Global.logError(TAG, "open infobases view menu", e); //$NON-NLS-1$
            }
        }

        /**
         * Нашлась ли строка базы в дереве панели — сравнение по UUID, а не по ссылке: панель
         * и редактор держат разные экземпляры одной базы.
         */
        private boolean isInfobaseRevealed(CommonViewer viewer)
        {
            Object selected = viewer.getStructuredSelection().getFirstElement();
            return selected instanceof InfobaseReference other
                && infobase.getUuid() != null && infobase.getUuid().equals(other.getUuid());
        }

        /** Точка под именем базы в экранных координатах. */
        private Point menuLocation()
        {
            int start = nameStart < 0 ? 0 : nameStart;
            Point at = titleText.getLocationAtOffset(start);
            return titleText.toDisplay(at.x, at.y + titleText.getLineHeight());
        }

        /** Возвращает фокус в редактор после закрытия меню — см. аналог в {@link MdEditorTitleNavigatorMenuHook}. */
        private void hookMenuClose(Menu menu)
        {
            Listener[] onHide = new Listener[1];
            onHide[0] = event ->
            {
                menu.removeListener(SWT.Hide, onHide[0]);
                menu.getDisplay().timerExec(FOCUS_RETURN_DELAY_MS, () ->
                {
                    if (isInfobasesViewActive())
                        NavigatorReveal.reactivateEditorPart(editor);
                });
            };
            menu.addListener(SWT.Hide, onHide[0]);
        }

        private static boolean isInfobasesViewActive()
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            IWorkbenchPart active = page == null ? null : page.getActivePart();
            return active != null && INFOBASES_VIEW_ID.equals(active.getSite().getId());
        }

        /**
         * Панель «Информационные базы», сделанная активной частью — как и в
         * {@link MdEditorTitleNavigatorMenuHook}, активация обязательна: видимость части пунктов
         * штатного меню привязана к активной части.
         */
        private static CommonNavigator activateInfobasesView()
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;

            IViewPart view = page.findView(INFOBASES_VIEW_ID);
            try
            {
                view = page.showView(INFOBASES_VIEW_ID);
            }
            catch (Exception e)
            {
                Global.tempLog(TAG, "activateInfobasesView showView EXCEPTION: " + e); //$NON-NLS-1$
                Global.logError(TAG, "show infobases view", e); //$NON-NLS-1$
                if (view == null)
                    return null;
            }
            Global.tempLog(TAG, "activateInfobasesView: view=" //$NON-NLS-1$
                + (view == null ? "null" : view.getClass().getName())); //$NON-NLS-1$
            return view instanceof CommonNavigator navigator ? navigator : null;
        }
    }
}
