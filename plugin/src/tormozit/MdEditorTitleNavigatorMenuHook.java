package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceColors;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProviderChangedEvent;
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
import org.eclipse.ui.IDecoratorManager;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.ActionContext;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.ScrolledForm;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.navigator.ICommonMenuConstants;
import org.eclipse.ui.navigator.NavigatorActionService;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.ui.services.IServiceLocator;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicTemplate;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Имя объекта метаданных в заголовке страницы редактора ({@link DtGranularEditor})
 * становится гиперссылкой, открывающей контекстное меню навигатора для строки этого
 * объекта.
 *
 * <p>Заголовок страницы («Справочник._ДемоКассы.Основные») — это заголовок формы
 * Eclipse Forms: {@code DtGranularEditorPage.createFormContentInternal} вызывает
 * {@code ScrolledForm.setText(getPageTitle())}. Комфорт приводит штатный путь
 * («Справочники → … → Основные») к полному имени: префикс — полное имя объекта
 * из модели редактора (системные слова в единственном числе, имена объектов без
 * изменений — форма «Команды» не превращается в «Команда»), сегменты через точку;
 * имя текущей страницы (последнее звено) не меняется.
 * Область заголовка ({@code TitleRegion})
 * всегда содержит два контрола — {@code Label} и {@code StyledText}, видим ровно один;
 * переключение — {@link Form#setTitleTextSelectable(boolean)}. Ссылку можно оформить только на
 * {@code StyledText} ({@code Label} не поддерживает стили части текста), поэтому хук
 * включает выделяемый заголовок. Побочный эффект — текст заголовка можно выделять мышью.
 *
 * <p>Ссылкой становится сегмент заголовка, точно совпадающий с именем модели редактора,
 * — то есть имя самого объекта, а не тип и не название текущей страницы. Поэтому
 * положение ссылки не зависит от того, какая страница открыта. В редакторе формы или
 * макета то же для имени объекта-владельца ({@code Справочник.Валюты.Форма.…} —
 * кликабельны и {@code Валюты}, и имя формы).
 *
 * <p>Меню — не копия, а само меню навигатора: панель «Навигатор» делается активной частью,
 * объект выделяется в дереве ({@code selectReveal}), затем показывается {@link Menu} дерева.
 * Активация обязательна — видимость части пунктов привязана к активной части, и при активном
 * редакторе меню получается короче настоящего. После закрытия меню фокус возвращается
 * в редактор.
 */
public final class MdEditorTitleNavigatorMenuHook implements IStartup
{
    private static final String TAG = "MdEditorTitleNavigatorMenuHook"; //$NON-NLS-1$

    /** Ключ пометки шапки формы: ссылка уже встроена. */
    private static final String KEY_INSTALLED = "tormozit.mdTitleNavigatorMenu"; //$NON-NLS-1$

    private static final String TITLE_REGION_CLASS = "TitleRegion"; //$NON-NLS-1$

    /** Внутренние классы {@code DtGranularEditor} — среди них слушатель декоратора ({@code $5}). */
    private static final String GRANULAR_EDITOR_CLASS =
        "com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor$"; //$NON-NLS-1$

    /** Меню навигатора EDT — расположение вкладов {@code org.eclipse.ui.menus}. */
    private static final String NAVIGATOR_POPUP_ID = "com._1c.g5.v8.dt.navigator.ui.navigator.popup"; //$NON-NLS-1$

    /** Идентификатор подменю «Ссылки» в объявлении вклада {@code com._1c.g5.v8.dt.search.ui}. */
    private static final String REFERENCES_SUBMENU_ID = "referencesSubMenu"; //$NON-NLS-1$

    private static final String MENUS_EXTENSION_POINT = "org.eclipse.ui.menus"; //$NON-NLS-1$

    private static final String TAG_MENU = "menu"; //$NON-NLS-1$

    private static final String TAG_COMMAND = "command"; //$NON-NLS-1$

    private static final String ATTR_ID = "id"; //$NON-NLS-1$

    private static final String ATTR_LABEL = "label"; //$NON-NLS-1$

    private static final String ATTR_ICON = "icon"; //$NON-NLS-1$

    private static final String ATTR_MNEMONIC = "mnemonic"; //$NON-NLS-1$

    private static final String ATTR_COMMAND_ID = "commandId"; //$NON-NLS-1$

    private static final String ATTR_LOCATION_URI = "locationURI"; //$NON-NLS-1$

    /** Пауза перед возвратом фокуса в редактор — чтобы выбранный пункт успел отработать, мс. */
    private static final int FOCUS_RETURN_DELAY_MS = 300;

    /** Редакторы, к которым уже подключён слушатель смены страницы. */
    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    // =========================================================================
    // IStartup
    // =========================================================================

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w)   {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w)      {}
            });

            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    // =========================================================================
    // Подключение к окну / редактору
    // =========================================================================

    private void hookWindow(IWorkbenchWindow window)
    {
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
            }
        }

        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref)      { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref)   { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference r)  { hookFromRef(r); }
            @Override public void partClosed(IWorkbenchPartReference r)        {}
            @Override public void partDeactivated(IWorkbenchPartReference r)   {}
            @Override public void partHidden(IWorkbenchPartReference r)        {}
            @Override public void partVisible(IWorkbenchPartReference r)       {}
            @Override public void partInputChanged(IWorkbenchPartReference r)  {}

            private void hookFromRef(IWorkbenchPartReference ref)
            {
                if (!(ref instanceof IEditorReference editorRef))
                    return;
                IWorkbenchPart part = editorRef.getPart(false);
                if (part instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
            }
        });
    }

    private void hookEditor(DtGranularEditor<?> editor)
    {
        try
        {
            if (hookedEditors.add(editor))
            {
                // Страницы создаются лениво — встраиваемся в каждую при её первом показе
                editor.addPageChangedListener(event -> installOnActivePage(editor));
            }
            throttleDecoratorTitleRefire();
            installOnActivePage(editor);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "hook editor", e); //$NON-NLS-1$
        }
    }

    private void installOnActivePage(DtGranularEditor<?> editor)
    {
        try
        {
            if (!(editor.getModel() instanceof MdObject mdObject))
                return;
            IFormPage page = editor.getActivePageInstance();
            if (page == null)
                return;
            IManagedForm managedForm = page.getManagedForm();
            if (managedForm == null)
                return;
            ScrolledForm scrolledForm = managedForm.getForm();
            if (scrolledForm == null || scrolledForm.isDisposed())
                return;

            applyPageTitleFormat(scrolledForm, mdObject);
            install(scrolledForm.getForm(), mdObject, editor);
        }
        catch (Exception e)
        {
            Global.logError(TAG, "install on active page", e); //$NON-NLS-1$
        }
    }

    // =========================================================================
    // Встраивание ссылки в заголовок формы
    // =========================================================================

    private static void install(Form form, MdObject mdObject, IEditorPart editor)
    {
        if (form == null || form.isDisposed())
            return;
        Composite head = form.getHead();
        if (head == null || head.isDisposed())
            return;
        Control titleRegion = findTitleRegion(head);
        if (titleRegion == null)
            return;
        if (head.getData(KEY_INSTALLED) != null)
            return;

        // Стиль части текста поддерживает только StyledText-вариант заголовка
        form.setTitleTextSelectable(true);

        StyledText titleText = findTitleText(titleRegion);
        if (titleText == null)
            return;

        head.setData(KEY_INSTALLED, Boolean.TRUE);
        // Win32: Ctrl+C забирает global Copy редактора (дерево вкладки), не StyledText.
        CopyCommandSupport.wireCopyOverride(titleText);
        new TitleObjectLink(form, titleText, mdObject, editor);
    }

    /**
     * Штатный заголовок EDT — «Справочники → Валюты → Формы → …». Комфорт приводит
     * его к полному имени: «Справочник.Валюты.Форма.…».
     */
    private static void applyPageTitleFormat(ScrolledForm scrolledForm, MdObject mdObject)
    {
        if (scrolledForm == null || scrolledForm.isDisposed())
            return;
        String text = scrolledForm.getText();
        String formatted = formatPageTitle(text, mdObject);
        if (formatted != null && !formatted.equals(text))
            scrolledForm.setText(formatted);
    }

    /**
     * Сегменты штатного пути (разделитель {@code →}) → полное имя МД через точку.
     * Префикс берётся из полного имени модели ({@link GetRef#eObjectToFullName}):
     * имена объектов не склоняются, даже когда совпали с системным словом
     * (форма «Команды» — не группа «Команды»). Последнее звено — имя страницы EDT
     * (вкладки), его не трогаем: «Функциональные опции» это заголовок страницы,
     * а не тип МД «ФункциональнаяОпция». Если полное имя модели недоступно или
     * число сегментов не сходится — конвертация системных слов по одному, кроме
     * сегмента, равного имени объекта. Уже отформатированный заголовок (без
     * стрелки) не меняет.
     */
    private static String formatPageTitle(String title, MdObject mdObject)
    {
        if (title == null || title.isEmpty() || title.indexOf('→') < 0)
            return title;

        String[] parts = title.split("\\s*→\\s*", -1); //$NON-NLS-1$
        if (parts.length < 2)
            return title;

        int lastNonEmpty = -1;
        for (int i = 0; i < parts.length; i++)
        {
            if (!parts[i].strip().isEmpty())
                lastNonEmpty = i;
        }
        if (lastNonEmpty < 1)
            return title;

        String pageName = parts[lastNonEmpty].strip();

        String fullName = mdObject == null ? null : GetRef.eObjectToFullName(mdObject);
        if (fullName != null && !fullName.isBlank())
        {
            int prefixSegments = 0;
            for (int i = 0; i < lastNonEmpty; i++)
            {
                if (!parts[i].strip().isEmpty())
                    prefixSegments++;
            }
            if (prefixSegments == fullName.split("\\.", -1).length) //$NON-NLS-1$
                return fullName + '.' + pageName;
        }

        String objectName = mdObject == null ? null : mdObject.getName();
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < parts.length; i++)
        {
            String segment = parts[i].strip();
            if (segment.isEmpty())
                continue;
            if (formatted.length() > 0)
                formatted.append('.');
            if (i != lastNonEmpty && !segment.equals(objectName))
            {
                String singular = MdTypeMapping.systemLabelToSingular(segment);
                formatted.append(singular != null ? singular : segment);
            }
            else
                formatted.append(segment);
        }
        return formatted.length() == 0 ? title : formatted.toString();
    }

    /**
     * Гасит перевыпуск {@code PROP_TITLE} по каждому событию декоратора.
     *
     * <p>Декоратор EDT рассылает {@code labelProviderChanged} около пяти раз в секунду.
     * Слушатель {@code DtGranularEditor$5} на каждое такое событие зовёт
     * {@code firePropertyChange(PROP_TITLE)}, EDT переписывает заголовок формы **тем же текстом**,
     * а {@code TitleRegion.setText} безусловно делает {@code layout()} и {@code redraw()} — то есть
     * перерисовывается вся область части, включая линейку номеров (issue про мигание линейки).
     *
     * <p>Здесь слушатель редактора подменяется обёрткой, которая пропускает событие дальше, только
     * если заголовок или подсказка вкладки действительно изменились. Перевыпуск при неизменном
     * заголовке — чистая потеря, штатное обновление при переименовании или смене маркеров работает
     * как прежде.
     */
    private static void throttleDecoratorTitleRefire()
    {
        try
        {
            IDecoratorManager manager = PlatformUI.getWorkbench().getDecoratorManager();
            Object listeners = Global.getField(manager, "listeners"); //$NON-NLS-1$
            Object array = Global.invoke(listeners, "getListeners"); //$NON-NLS-1$
            if (!(array instanceof Object[] all))
                return;
            for (Object candidate : all)
            {
                if (!(candidate instanceof ILabelProviderListener listener)
                    || candidate instanceof TitleRefireThrottle)
                    continue;
                if (!candidate.getClass().getName().startsWith(GRANULAR_EDITOR_CLASS))
                    continue;
                Object owner = Global.getField(candidate, "this$0"); //$NON-NLS-1$
                if (!(owner instanceof IEditorPart editor))
                    continue;
                manager.removeListener(listener);
                manager.addListener(new TitleRefireThrottle(listener, editor));
            }
        }
        catch (Exception e)
        {
            // молча: без подавления останется прежнее поведение, ничего не ломается
        }
    }

    /** См. {@link #throttleDecoratorTitleRefire()}. */
    private static final class TitleRefireThrottle implements ILabelProviderListener
    {
        private final ILabelProviderListener delegate;

        private final IEditorPart editor;

        private String lastTitle;

        TitleRefireThrottle(ILabelProviderListener delegate, IEditorPart editor)
        {
            this.delegate = delegate;
            this.editor = editor;
        }

        @Override
        public void labelProviderChanged(LabelProviderChangedEvent event)
        {
            String now = editor.getTitle() + ' ' + editor.getTitleToolTip();
            if (now.equals(lastTitle))
                return;
            lastTitle = now;
            delegate.labelProviderChanged(event);
        }
    }

    /** Область заголовка формы — {@code org.eclipse.ui.internal.forms.widgets.TitleRegion}. */
    private static Control findTitleRegion(Composite head)
    {
        for (Control child : head.getChildren())
        {
            if (TITLE_REGION_CLASS.equals(child.getClass().getSimpleName()))
                return child;
        }
        return null;
    }

    /** Выделяемый вариант текста заголовка внутри области заголовка. */
    private static StyledText findTitleText(Control titleRegion)
    {
        if (!(titleRegion instanceof Composite region))
            return null;
        for (Control child : region.getChildren())
        {
            if (child instanceof StyledText styledText && !styledText.isDisposed())
                return styledText;
        }
        return null;
    }

    // =========================================================================
    // Ссылка на имени объекта
    // =========================================================================

    /**
     * Оформляет имя объекта (и владельца формы/макета) в заголовке как гиперссылку
     * и открывает по щелчку меню навигатора.
     */
    private static final class TitleObjectLink
    {
        private final StyledText titleText;

        private final Form form;

        /** Редактор, которому возвращается фокус после закрытия меню. */
        private final IEditorPart editor;

        /** Объект редактора — источник полного имени для {@link #formatPageTitle}. */
        private final MdObject mdObject;

        /** Ссылки в заголовке: объект редактора и, если есть, его владелец. */
        private final LinkedName[] names;

        /** Нажатие левой кнопки пришлось на имя — ждём отпускания на том же имени. */
        private LinkedName pressedName;

        /** Смесь цвета гиперссылки с цветом текста заголовка; свой ресурс, dispose при уничтожении. */
        private Color linkColor;

        /** Идёт запись отформатированного заголовка — не входить в {@link #applyLinkStyle} рекурсивно. */
        private boolean applyingTitle;

        TitleObjectLink(Form form, StyledText titleText, MdObject mdObject, IEditorPart editor)
        {
            this.form = form;
            this.titleText = titleText;
            this.editor = editor;
            this.mdObject = mdObject;
            this.names = linkedNames(mdObject, editor);

            applyLinkStyle();

            // EDT переустанавливает заголовок при обновлении модели — стиль надо вернуть
            titleText.addListener(SWT.Dispose, event ->
            {
                disposeOwnMenus();
                disposeLinkColor();
            });
            titleText.addListener(SWT.Modify, event -> applyLinkStyle());
            titleText.addListener(SWT.MouseMove, event -> updateHover(event.x, event.y));
            titleText.addListener(SWT.MouseExit, event -> updateHover(-1, -1));
            // Меню показывается по MouseUp, а не по MouseDown: menu.setVisible(true)
            // запускает вложенный цикл событий, и MouseUp уходит в меню — StyledText
            // остаётся в режиме протягивания выделения, будто кнопка всё ещё нажата.
            titleText.addListener(SWT.MouseDown, event ->
                pressedName = event.button == 1 ? nameAt(event.x, event.y) : null);
            titleText.addListener(SWT.MouseUp, event ->
            {
                // Непустое выделение — пользователь выделял текст заголовка, а не щёлкал ссылку
                LinkedName clicked = pressedName;
                pressedName = null;
                boolean click = clicked != null && event.button == 1
                    && titleText.getSelectionCount() == 0
                    && nameAt(event.x, event.y) == clicked;
                if (click)
                    openNavigatorMenu(clicked);
            });
        }

        private static LinkedName[] linkedNames(MdObject mdObject, IEditorPart editor)
        {
            MdObject owner = findOwnerMdObject(mdObject, editor);
            if (owner == null || owner == mdObject)
                return new LinkedName[] { new LinkedName(mdObject, false) };
            return new LinkedName[] { new LinkedName(owner, true), new LinkedName(mdObject, false) };
        }

        /**
         * Красит имена объектов цветом гиперссылки. Диапазоны именно заменяются, а не
         * добавляются: иначе после смены текста остаётся стиль от прошлого заголовка.
         */
        private void applyLinkStyle()
        {
            if (titleText.isDisposed() || applyingTitle)
                return;

            String text = titleText.getText();
            String formatted = formatPageTitle(text, mdObject);
            if (formatted != null && !formatted.equals(text))
            {
                applyingTitle = true;
                try
                {
                    if (form != null && !form.isDisposed())
                        form.setText(formatted);
                    else
                        titleText.setText(formatted);
                }
                finally
                {
                    applyingTitle = false;
                }
                text = titleText.getText();
            }

            locateNames(text);
            List<StyleRange> ranges = new ArrayList<>();
            for (LinkedName name : names)
            {
                if (name.start < 0)
                    continue;
                // Без подчёркивания: имена объектов часто начинаются с «_» («_ДемоКассы»),
                // и линия ссылки сливается с самим символом подчёркивания в имени.
                ranges.add(new StyleRange(name.start, name.length, linkForeground(), null));
            }
            ranges.sort((left, right) -> Integer.compare(left.start, right.start));
            titleText.setStyleRanges(ranges.toArray(StyleRange[]::new));
        }

        /**
         * Цвет гиперссылки, сдвинутый на 30% к цвету текста заголовка — ссылка остаётся
         * отличимой, но не спорит с шапкой.
         * <p>
         * JFace отдаёт тёмно-синий цвет ссылки в координатах светлой темы независимо от темы EDT,
         * поэтому в тёмной теме он сливается с тёмной шапкой. Приводим его к текущей теме через
         * {@link ThemeAwareColors#toEffectiveRgb(RGB)} (тон сохраняется) и уже
         * потом смешиваем с цветом текста заголовка.
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

        /**
         * Имя редактируемого объекта — самое правое вхождение (оно ближе к концу пути),
         * владелец — самое левое, не пересекающееся с уже занятым диапазоном. Так два
         * одинаковых имени (форма названа как справочник) попадают на разные сегменты.
         */
        private void locateNames(String title)
        {
            for (LinkedName name : names)
            {
                name.start = -1;
                name.length = 0;
            }
            for (LinkedName name : names)
            {
                if (!name.owner)
                {
                    name.start = findNameStart(title, name.mdObject.getName(), true, -1, 0);
                    name.length = name.start < 0 ? 0 : name.mdObject.getName().length();
                    break;
                }
            }
            LinkedName self = selfName();
            int occupiedStart = self == null ? -1 : self.start;
            int occupiedLength = self == null ? 0 : self.length;
            for (LinkedName name : names)
            {
                if (!name.owner)
                    continue;
                name.start = findNameStart(title, name.mdObject.getName(), false,
                    occupiedStart, occupiedLength);
                name.length = name.start < 0 ? 0 : name.mdObject.getName().length();
            }
        }

        private LinkedName selfName()
        {
            for (LinkedName name : names)
            {
                if (!name.owner)
                    return name;
            }
            return names.length == 0 ? null : names[0];
        }

        /**
         * Начало сегмента заголовка, точно совпадающего с именем объекта. Соседние символы
         * не должны быть частью имени — иначе «Валюты» нашлось бы внутри «ВалютыСписок».
         *
         * @param last {@code true} — последнее подходящее вхождение, {@code false} — первое
         * @param skipStart занятый диапазон, который нельзя пересечь; {@code -1} — нет
         * @return {@code -1}, если такого сегмента нет
         */
        private static int findNameStart(String title, String name, boolean last,
            int skipStart, int skipLength)
        {
            if (title == null || name == null || name.isEmpty())
                return -1;

            int from = 0;
            int found = -1;
            while (true)
            {
                int index = title.indexOf(name, from);
                if (index < 0)
                    return found;

                boolean leftFree = index == 0 || !isNameChar(title.charAt(index - 1));
                int after = index + name.length();
                boolean rightFree = after >= title.length() || !isNameChar(title.charAt(after));
                boolean overlapsSkip = skipLength > 0 && skipStart >= 0
                    && index < skipStart + skipLength && after > skipStart;
                if (leftFree && rightFree && !overlapsSkip)
                {
                    if (!last)
                        return index;
                    found = index;
                }

                from = index + 1;
            }
        }

        private static boolean isNameChar(char c)
        {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        /** Курсор и подсказка меняются, только когда указатель над именем объекта. */
        private void updateHover(int x, int y)
        {
            if (titleText.isDisposed())
                return;

            boolean onName = nameAt(x, y) != null;
            titleText.setCursor(titleText.getDisplay()
                .getSystemCursor(onName ? SWT.CURSOR_HAND : SWT.CURSOR_IBEAM));
            titleText.setToolTipText(onName
                ? "Меню объекта, как в навигаторе" + Global.pluginSignForTooltip() //$NON-NLS-1$
                : null);
        }

        private LinkedName nameAt(int x, int y)
        {
            if (titleText.isDisposed())
                return null;
            int offset;
            try
            {
                offset = titleText.getOffsetAtPoint(new Point(x, y));
            }
            catch (IllegalArgumentException e)
            {
                return null; // точка вне текста
            }
            for (LinkedName name : names)
            {
                if (name.start >= 0 && offset >= name.start && offset < name.start + name.length)
                    return name;
            }
            return null;
        }

        /**
         * Выделяет объект в навигаторе и показывает меню его дерева под именем объекта.
         * Панель «Навигатор» не активируется — фокус остаётся в редакторе.
         */
        private void openNavigatorMenu(LinkedName name)
        {
            try
            {
                CommonNavigator navigator = activateNavigator();
                if (navigator == null)
                    return;
                if (!(Global.invoke(navigator, "getCommonViewer") instanceof CommonViewer viewer) //$NON-NLS-1$
                    || viewer.getTree() == null || viewer.getTree().isDisposed())
                {
                    return;
                }

                navigator.selectReveal(new StructuredSelection(name.mdObject));

                if (isObjectRevealed(viewer, name.mdObject))
                {
                    showNavigatorMenu(navigator, viewer, name);
                    return;
                }

                // Строка скрыта фильтром навигатора — выделить её нельзя (SWT-элемента нет),
                // а без выделения штатное меню строится «ни для чего». Собираем своё из тех же
                // источников, но с нашим выделением.
                showOwnMenu(navigator, name);
            }
            catch (Exception e)
            {
                Global.logError(TAG, "open navigator menu", e); //$NON-NLS-1$
            }
        }

        /**
         * Нашлась ли строка объекта в дереве навигатора.
         *
         * <p>Сравнение по URI, а не по ссылке: навигатор и редактор держат разные экземпляры
         * одного объекта, поэтому сравнение по ссылке здесь всегда даёт {@code false}.
         */
        private static boolean isObjectRevealed(CommonViewer viewer, MdObject mdObject)
        {
            Object selected = viewer.getStructuredSelection().getFirstElement();
            return selected instanceof EObject eObject
                && EcoreUtil.getURI(eObject).equals(EcoreUtil.getURI(mdObject));
        }

        /** Штатное меню дерева навигатора — объект в дереве выделен, подменять нечего. */
        private void showNavigatorMenu(CommonNavigator navigator, CommonViewer viewer, LinkedName name)
        {
            Menu menu = viewer.getTree().getMenu();
            if (menu == null || menu.isDisposed())
                return;
            hookMenuClose(menu, null);
            menu.setLocation(menuLocation(name));
            menu.setVisible(true);
        }

        /**
         * Своё меню — когда строка объекта скрыта фильтром навигатора.
         *
         * <p>Наполняется из тех же двух источников, что и штатное меню, и обоими штатными
         * средствами CNF:
         * <ul>
         * <li>{@link NavigatorActionService#fillContextMenu} с нашим {@link ActionContext} —
         * точки вставки меню и пункты провайдеров действий («Открыть», «Open With»,
         * «Справочная информация»);</li>
         * <li>{@link NavigatorActionService#prepareMenuForPlatformContributions} — регистрация
         * меню с нашим провайдером выделения. Именно она создаёт {@code PopupMenuExtender},
         * а тот выставляет {@code activeMenuSelection}. Без неё вклады
         * {@code org.eclipse.ui.menus} с внешними {@code count}/{@code iterate} отсеиваются:
         * эти проверки идут по переменной по умолчанию, которая иначе не определена — так
         * терялось подменю «Ссылки» ({@code ?endof=group.search}).</li>
         * </ul>
         *
         * <p>Меню создаётся один раз на редактор и переиспользуется: регистрация добавляет
         * extender в сайт навигатора, и делать это на каждый щелчок нельзя. Содержимое
         * пересобирается при каждом показе ({@code setRemoveAllWhenShown}).
         */
        private void showOwnMenu(CommonNavigator navigator, LinkedName name)
        {
            IStructuredSelection selection = new StructuredSelection(menuObject(name));
            SelectionSpoof spoof = SelectionSpoof.apply(navigator, selection);

            if (name.ownMenu == null || name.ownMenu.isDisposed())
                createOwnMenu(navigator, selection, name);
            if (name.ownMenu == null)
                return;

            hookMenuClose(name.ownMenu, spoof);
            name.ownMenu.setLocation(menuLocation(name));
            name.ownMenu.setVisible(true);
        }

        /**
         * Объект, который кладётся в выделение своего меню.
         *
         * <p>Экземпляр из редактора не годится: проверки доступности пунктов
         * ({@code com._1c.g5.v8.dt.md.ui.isAvailable} →
         * {@code NavigatorObjectsAvailability.isAvailable}) начинают с
         * {@code isAlive(eObject)} — {@code ((IBmObject) o).bmGetEngine().isActive()} — и
         * резолвят проект через {@code IV8ProjectManager.getProject(EObject)}. Поэтому берётся
         * экземпляр из BM-модели проекта, то есть тот же, которым оперирует навигатор. Именно
         * на этих проверках отсеивались «Ссылки», «Открыть модуль объекта» и прочие
         * «модульные» пункты.
         *
         * @return BM-экземпляр объекта, а если его не удалось получить — экземпляр редактора
         */
        private MdObject menuObject(LinkedName name)
        {
            if (name.bmObject != null)
                return name.bmObject;

            name.bmObject = name.mdObject;
            try
            {
                IProject project = Global.getActiveProject(editor, false);
                IV8ProjectManager projectManager = Global.getOsgiService(IV8ProjectManager.class);
                IV8Project v8Project = project == null || projectManager == null
                    ? null : projectManager.getProject(project);
                String fullName = GetRef.eObjectToFullName(name.mdObject);
                if (v8Project != null && fullName != null
                    && GoToDefinition.resolveEObjectByQualifiedName(fullName, v8Project) instanceof MdObject resolved)
                {
                    name.bmObject = resolved;
                }
            }
            catch (Exception e)
            {
                Global.logError(TAG, "resolve BM instance", e); //$NON-NLS-1$
            }
            return name.bmObject;
        }

        private void createOwnMenu(CommonNavigator navigator, IStructuredSelection selection, LinkedName name)
        {
            try
            {
                NavigatorActionService actionService = navigator.getNavigatorActionService();

                MenuManager manager = new MenuManager(NAVIGATOR_POPUP_ID, NAVIGATOR_POPUP_ID);
                manager.setRemoveAllWhenShown(true);
                manager.addMenuListener(menu ->
                {
                    // fillContextMenu сам добавляет точки вставки меню навигатора
                    // (customInsertionPoints дескриптора viewer'а либо DEFAULT_GROUPS),
                    // поэтому свой список групп не нужен — он бы ещё и разошёлся с EDT.
                    actionService.setContext(new ActionContext(selection));
                    actionService.fillContextMenu(menu);
                    addSkippedCommands(menu, navigator.getSite());
                });
                actionService.prepareMenuForPlatformContributions(manager,
                    new FixedSelectionProvider(selection), true);

                name.ownManager = manager;
                name.ownMenu = manager.createContextMenu(titleText);

                // Подменю «Комфорт» добавляется SWT-пунктами, а менеджер меню создан с
                // setRemoveAllWhenShown: при показе он очищает меню и строит заново. Поэтому
                // наполняем в своём SWT.Show — он добавлен после слушателя менеджера, то есть
                // отработает уже по готовому меню. Пункты, работающие с деревом навигатора
                // («Свернуть все другие»), сюда не попадают: они не регистрируются как внешние.
                name.ownMenu.addListener(SWT.Show, event ->
                    ComfortSubmenuHelper.fillExternalMenu(name.ownMenu, navigator, selection));
            }
            catch (Exception e)
            {
                Global.logError(TAG, "create own menu", e); //$NON-NLS-1$
            }
        }

        /**
         * Добавляет команды, которые платформа в наше меню не приносит.
         *
         * <p>Их вклады объявлены с условием
         * {@code <test args="objectModule" property="com._1c.g5.v8.dt.md.ui.isAvailable"/>},
         * и оно даёт {@code false} даже при выставленных {@code selection} /
         * {@code activeMenuSelection} и живом объекте из BM-модели проекта. Поэтому нужные
         * команды добавляются напрямую, по объявлению вклада: их видимость определяем мы,
         * а не чужой тестер.
         *
         * <p>Сама команда при этом штатная: выполняется с активной частью «Навигатор» и нашим
         * выделением, как если бы её вызвали из меню навигатора.
         */
        private void addSkippedCommands(IMenuManager menu, IServiceLocator services)
        {
            IConfigurationElement declaration = findReferencesSubMenu();
            if (declaration == null)
                return;

            MenuManager references =
                new MenuManager(label(declaration), declaration.getAttribute(ATTR_ID));
            for (IConfigurationElement command : declaration.getChildren(TAG_COMMAND))
            {
                String commandId = command.getAttribute(ATTR_COMMAND_ID);
                if (commandId == null || !isCommandDefined(commandId))
                    continue;

                CommandContributionItemParameter parameter = new CommandContributionItemParameter(
                    services, null, commandId, CommandContributionItem.STYLE_PUSH);
                parameter.label = label(command);
                parameter.mnemonic = command.getAttribute(ATTR_MNEMONIC);
                parameter.icon = icon(command);
                references.add(new CommandContributionItem(parameter));
            }

            if (references.isEmpty())
                return;
            menu.appendToGroup(ICommonMenuConstants.GROUP_SEARCH, references);
        }

        /**
         * Объявление подменю «Ссылки» в реестре расширений — источник подписи, мнемоники,
         * иконки и состава команд.
         *
         * <p>Берётся именно объявление, а не свои строки: {@link IConfigurationElement#getAttribute}
         * возвращает уже переведённое значение {@code %ключа}, поэтому подпись совпадает
         * с меню навигатора при любом языке интерфейса.
         */
        private static IConfigurationElement findReferencesSubMenu()
        {
            for (IConfigurationElement contribution : Platform.getExtensionRegistry()
                .getConfigurationElementsFor(MENUS_EXTENSION_POINT))
            {
                String uri = contribution.getAttribute(ATTR_LOCATION_URI);
                if (uri == null || !uri.contains(NAVIGATOR_POPUP_ID))
                    continue;
                for (IConfigurationElement child : contribution.getChildren(TAG_MENU))
                {
                    if (REFERENCES_SUBMENU_ID.equals(child.getAttribute(ATTR_ID)))
                        return child;
                }
            }
            return null;
        }

        /** Переведённая подпись объявления; ведущие пробелы в {@code label} EDT здесь лишние. */
        private static String label(IConfigurationElement element)
        {
            String label = element.getAttribute(ATTR_LABEL);
            return label == null ? "" : label.trim(); //$NON-NLS-1$
        }

        private static ImageDescriptor icon(IConfigurationElement element)
        {
            String path = element.getAttribute(ATTR_ICON);
            if (path == null)
                return null;
            return AbstractUIPlugin.imageDescriptorFromPlugin(
                element.getContributor().getName(), path);
        }

        private static boolean isCommandDefined(String commandId)
        {
            ICommandService commandService =
                PlatformUI.getWorkbench().getService(ICommandService.class);
            return commandService != null && commandService.getCommand(commandId).isDefined();
        }

        /**
         * Снимает подмену выделения и возвращает фокус в редактор после закрытия меню.
         *
         * <p>{@code SWT.Hide} приходит раньше {@code Selection} выбранного пункта, поэтому
         * и то, и другое откладывается таймером: иначе команда выполнилась бы уже при
         * восстановленном (пустом) выделении и активном редакторе — ровно тот контекст,
         * ради подмены которого всё и делается. Если команда сама увела фокус (например,
         * открыла редактор), фокус не трогаем.
         */
        private void hookMenuClose(Menu menu, SelectionSpoof spoof)
        {
            Listener[] onHide = new Listener[1];
            onHide[0] = event ->
            {
                menu.removeListener(SWT.Hide, onHide[0]);
                menu.getDisplay().timerExec(FOCUS_RETURN_DELAY_MS, () ->
                {
                    if (spoof != null)
                        spoof.restore();
                    if (isNavigatorActive())
                        NavigatorReveal.reactivateEditorPart(editor);
                });
            };
            menu.addListener(SWT.Hide, onHide[0]);
        }

        /** Свои меню живут вместе с редактором — освобождаются при уничтожении заголовка. */
        private void disposeOwnMenus()
        {
            for (LinkedName name : names)
                disposeOwnMenu(name);
        }

        private static void disposeOwnMenu(LinkedName name)
        {
            if (name.ownManager == null)
                return;
            try
            {
                name.ownManager.dispose();
            }
            catch (Exception e)
            {
                Global.logError(TAG, "dispose own menu", e); //$NON-NLS-1$
            }
            name.ownManager = null;
            name.ownMenu = null;
        }

        private static boolean isNavigatorActive()
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IWorkbenchPage page = window == null ? null : window.getActivePage();
            IWorkbenchPart active = page == null ? null : page.getActivePart();
            return active != null && Global.NAVIGATOR_VIEW_ID.equals(active.getSite().getId());
        }

        /** Точка под именем объекта в экранных координатах. */
        private Point menuLocation(LinkedName name)
        {
            int start = name.start < 0 ? 0 : name.start;
            Point at = titleText.getLocationAtOffset(start);
            return titleText.toDisplay(at.x, at.y + titleText.getLineHeight());
        }

        /**
         * Владелец формы или макета — объект метаданных, в котором они лежат
         * ({@code Справочник.Валюты} для {@code Справочник.Валюты.Форма.ФормаСписка}).
         * Общие форма и макет принадлежат конфигурации — для них {@code null}.
         */
        private static MdObject findOwnerMdObject(MdObject child, IEditorPart editor)
        {
            MdObject owner = ownerFromContainer(child);
            if (owner != null)
                return isNestedFormOrTemplate(child) ? owner : null;
            if (!isNestedFormOrTemplate(child))
                return null;
            return resolveOwnerByFullName(child, editor);
        }

        private static boolean isNestedFormOrTemplate(MdObject child)
        {
            if (child instanceof BasicForm || child instanceof BasicTemplate)
            {
                for (EObject current = child.eContainer(); current != null; current = current.eContainer())
                {
                    if (current instanceof Configuration)
                        return false;
                    if (current instanceof MdObject)
                        return true;
                }
            }
            return ownerFullName(GetRef.eObjectToFullName(child)) != null;
        }

        private static MdObject ownerFromContainer(MdObject child)
        {
            for (EObject current = child.eContainer(); current != null; current = current.eContainer())
            {
                if (current instanceof Configuration)
                    return null;
                if (current instanceof MdObject owner)
                {
                    String name = owner.getName();
                    if (name != null && !name.isEmpty())
                        return owner;
                }
            }
            return null;
        }

        private static MdObject resolveOwnerByFullName(MdObject child, IEditorPart editor)
        {
            String ownerName = ownerFullName(GetRef.eObjectToFullName(child));
            if (ownerName == null)
                return null;
            try
            {
                IProject project = Global.getActiveProject(editor, false);
                IV8ProjectManager projectManager = Global.getOsgiService(IV8ProjectManager.class);
                IV8Project v8Project = project == null || projectManager == null
                    ? null : projectManager.getProject(project);
                if (v8Project != null
                    && GoToDefinition.resolveEObjectByQualifiedName(ownerName, v8Project) instanceof MdObject resolved)
                {
                    return resolved;
                }
            }
            catch (Exception e)
            {
                Global.logError(TAG, "resolve owner MdObject", e); //$NON-NLS-1$
            }
            return null;
        }

        /**
         * Полное имя владельца вложенной формы или макета:
         * {@code Справочник.Валюты.Форма.ФормаСписка} → {@code Справочник.Валюты}.
         */
        private static String ownerFullName(String fullName)
        {
            if (fullName == null || fullName.isEmpty())
                return null;
            int lastDot = fullName.lastIndexOf('.');
            if (lastDot <= 0)
                return null;
            String withoutObjectName = fullName.substring(0, lastDot);
            int typeDot = withoutObjectName.lastIndexOf('.');
            if (typeDot <= 0)
                return null;
            String nestedType = withoutObjectName.substring(typeDot + 1);
            if (!"Форма".equals(nestedType) && !"Макет".equals(nestedType)) //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            return withoutObjectName.substring(0, typeDot);
        }

        /** Имя в заголовке, ведущее в меню навигатора для конкретного объекта. */
        private static final class LinkedName
        {
            final MdObject mdObject;

            /** {@code true} — владелец формы/макета, {@code false} — объект редактора. */
            final boolean owner;

            int start = -1;

            int length;

            MdObject bmObject;

            MenuManager ownManager;

            Menu ownMenu;

            LinkedName(MdObject mdObject, boolean owner)
            {
                this.mdObject = mdObject;
                this.owner = owner;
            }
        }

        /**
         * Временная подмена выделения активной части, пока показывается своё меню.
         *
         * <p>Нужна, когда строка объекта скрыта фильтром навигатора: выделить её в дереве
         * нельзя (SWT-элемента просто нет), а условия видимости вкладов проверяют выделение.
         * Дерево при этом не трогается — ни снятия фильтра, ни перестроения, ни визуальных
         * побочек.
         *
         * <p>Подменяется выделение активной части через {@link ESelectionService} — источник
         * переменной {@code selection}. Проверено логом: при пустом {@code selection} в меню
         * остаются только «Создать», «Импорт», «Обновить» и подобные. Переменную
         * {@code activeMenuSelection} подменять не нужно: её выставляет
         * {@code PopupMenuExtender}, который создаётся при регистрации нашего меню
         * (см. {@link #createOwnMenu}) и уже получает наш провайдер выделения.
         */
        private static final class SelectionSpoof
        {
            private ESelectionService selectionService;

            private Object previousSelection;

            private boolean selectionReplaced;

            static SelectionSpoof apply(CommonNavigator navigator, IStructuredSelection selection)
            {
                SelectionSpoof spoof = new SelectionSpoof();
                Object object = selection.getFirstElement();
                spoof.selectionService = navigator.getSite().getService(ESelectionService.class);
                if (spoof.selectionService == null || object == null)
                    return spoof;

                spoof.previousSelection = spoof.selectionService.getSelection();
                spoof.selectionService.setSelection(object);
                spoof.selectionReplaced = true;
                return spoof;
            }

            void restore()
            {
                if (!selectionReplaced)
                    return;
                selectionService.setSelection(previousSelection);
                selectionReplaced = false;
            }
        }

        /** Провайдер с неизменным выделением — подставляется вместо провайдера навигатора. */
        private static final class FixedSelectionProvider implements ISelectionProvider
        {
            private final IStructuredSelection selection;

            FixedSelectionProvider(IStructuredSelection selection)
            {
                this.selection = selection;
            }

            @Override
            public ISelection getSelection()
            {
                return selection;
            }

            // Слушатели не нужны: провайдер живёт только на время показа меню и выделение
            // никогда не меняет. Исключения бросать нельзя — на него могут подписаться.
            @Override public void addSelectionChangedListener(ISelectionChangedListener listener)    {}
            @Override public void removeSelectionChangedListener(ISelectionChangedListener listener) {}
            @Override public void setSelection(ISelection newSelection)                              {}
        }

        /**
         * Панель «Навигатор», сделанная активной частью.
         *
         * <p>Активация обязательна: видимость пунктов меню («Найти подписки на события»,
         * «Конструкторы», «Стандартные реквизиты», «Удалить», «Свойства» и др.) привязана
         * к активной части, и при активном редакторе такие вклады отсеиваются — меню
         * получается короче настоящего меню навигатора. Скрытая панель при этом
         * показывается: без неё активация невозможна.
         */
        private static CommonNavigator activateNavigator()
        {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null)
                return null;

            IViewPart view = page.findView(Global.NAVIGATOR_VIEW_ID);
            try
            {
                // showView и активирует, и делает панель видимой, если она была скрыта
                view = page.showView(Global.NAVIGATOR_VIEW_ID);
            }
            catch (Exception e)
            {
                Global.logError(TAG, "show navigator view", e); //$NON-NLS-1$
                if (view == null)
                    return null;
            }
            return view instanceof CommonNavigator navigator ? navigator : null;
        }
    }
}
