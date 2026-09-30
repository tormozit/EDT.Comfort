package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.expressions.Expression;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.e4.ui.workbench.modeling.EPartService.PartState;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EClassifier;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.aef2.standard.definitions.ContainerDefinition;
import com._1c.g5.aef2.standard.definitions.IDefinition;
import com._1c.g5.aef2.standard.definitions.IFieldDefinition;
import com._1c.g5.aef2.standard.definitions.SectionDefinition;
import com._1c.g5.aef2.standard.parameterization.LinkParameterization;
import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.form.ui.editor.FormEditor;
import com._1c.g5.v8.dt.form.ui.editor.FormEditorPage;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.ui.aef.definitions.builder.DtSectionDefinitionBuilder;
import com._1c.g5.v8.dt.ui.editor.aef.definition.IDtGranularEditorManagingDefinition;
import com._1c.g5.v8.dt.ui.editor.aef.descriptor.DtEditorDescriptorRegistry;
import com._1c.g5.v8.dt.ui.editor.aef.descriptor.IDtGranularEditorAefPageDescriptor;
import com.e1c.g5.v8.dt.check.suppress.ui.aef.components.OpenSuppressionSettingsEditorByLinkComponent;

/**
 * Issue 2202: если панели «Свойства» и «Схема» (Outline) стоят в одной группе вкладок,
 * активная вкладка группы переключается вслед за редактором — вручную дёргать панели не
 * нужно.
 *
 * <p>При активации редактора модуля (standalone {@link BslXtextEditor} либо страница
 * «Модуль» внутри {@link DtGranularEditor}, см. {@link GetRef#getActiveBslEditor}), если
 * активна панель «Свойства» — активировать панель «Схема». При активации редактора формы
 * (страница «Форма», {@link FormEditorPage}, или «Основные», {@link ChildFormMainPage}),
 * если активна панель «Схема» — активировать панель «Свойства» (симметрично).
 * Обе проверки — только пока обе панели открыты и лежат в
 * одной группе вкладок. Повторные срабатывания активации одного и того же, уже активного
 * редактора (Eclipse присылает {@code partActivated} не только на реальный переход из другой
 * части) не переоткрывают проверку — иначе ручной выбор пользователем панели тут же
 * откатывался бы обратно.
 *
 * <p>Показ панели — {@code EPartService.showPart(MPart, PartState.VISIBLE)}: делает вкладку
 * видимой в её группе, не забирая ввод у редактора (в отличие от {@code activate}). Сервис
 * получен через {@code getSite().getService(...)}, поэтому метод вызывается через рефлексию
 * по классу самого возвращённого объекта — как и {@link NavigatorReveal#reactivateEditorPart}.
 */
public final class SchemaPropertiesTabSyncHook implements IStartup
{
    private static final String PROPERTY_SHEET_VIEW_ID = "org.eclipse.ui.views.PropertySheet"; //$NON-NLS-1$
    /**
     * {@code IPageLayout.ID_OUTLINE} — id ПРЕДСТАВЛЕНИЯ «Схема». Не путать с FQN самого
     * Java-класса {@code org.eclipse.ui.views.contentoutline.ContentOutline} (см.
     * {@link BslOutlineEventsSupport}, где это именно класс, а не id) — этой строкой
     * {@link IWorkbenchPage#findViewReference} панель не находил (issue 2202, «не работает»).
     */
    private static final String OUTLINE_VIEW_ID = "org.eclipse.ui.views.ContentOutline"; //$NON-NLS-1$

    /** Многостраничные редакторы, на которых уже висит слушатель смены страницы. */
    private static final Set<DtGranularEditor<?>> HOOKED_EDITORS =
        Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Редактор, для которого уже выполнена проверка «при активации». Без этой отметки
     * повторный {@code partActivated} того же самого, уже активного редактора (Eclipse
     * присылает его не только на реальный переход из другой части) заставлял бы хук снова и
     * снова принудительно возвращать «Схема», даже если пользователь только что сам вручную
     * открыл «Свойства», работая в этом же редакторе — то есть заменял бы разовую проверку «в
     * момент активации» на постоянное удержание состояния.
     */
    private static IWorkbenchPart lastActivatedEditor;

    @Override
    public void earlyStartup()
    {
        MdMainPageHook.earlyStartup();
        Display.getDefault().asyncExec(SchemaPropertiesTabSyncHook::install);
    }

    private static void install()
    {
        IWorkbench wb = PlatformUI.getWorkbench();
        if (wb == null)
            return;
        for (IWorkbenchWindow window : wb.getWorkbenchWindows())
            hookWindow(window);
        wb.addWindowListener(new IWindowListener()
        {
            @Override public void windowOpened(IWorkbenchWindow w)      { hookWindow(w); }
            @Override public void windowActivated(IWorkbenchWindow w)   {}
            @Override public void windowDeactivated(IWorkbenchWindow w) {}
            @Override public void windowClosed(IWorkbenchWindow w)      {}
        });
    }

    private static void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        for (IWorkbenchPage page : window.getPages())
        {
            if (page == null)
                continue;
            for (IEditorReference ref : page.getEditorReferences())
            {
                IEditorPart editor = ref.getEditor(false);
                if (editor instanceof DtGranularEditor<?> granular)
                    hookGranularEditor(granular);
            }
            IEditorPart active = page.getActiveEditor();
            if (active != null)
            {
                lastActivatedEditor = active;
                syncFromActiveEditor(active);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partActivated(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (part instanceof IEditorPart editor)
                {
                    if (editor instanceof DtGranularEditor<?> granular)
                        hookGranularEditor(granular);
                    if (editor != lastActivatedEditor)
                    {
                        lastActivatedEditor = editor;
                        syncFromActiveEditor(editor);
                    }
                }
                else
                {
                    // Ушли из редакторов вообще (например, в навигатор): возврат в тот же
                    // редактор потом — снова настоящая активация, проверку не пропускать.
                    lastActivatedEditor = null;
                }
            }

            @Override public void partOpened(IWorkbenchPartReference ref)
            {
                IWorkbenchPart part = ref != null ? ref.getPart(false) : null;
                if (part instanceof DtGranularEditor<?> granular)
                    hookGranularEditor(granular);
            }

            @Override public void partVisible(IWorkbenchPartReference ref)      {}
            @Override public void partInputChanged(IWorkbenchPartReference ref) {}
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) {}
            @Override public void partClosed(IWorkbenchPartReference ref)       {}
            @Override public void partDeactivated(IWorkbenchPartReference ref)  {}
            @Override public void partHidden(IWorkbenchPartReference ref)       {}
        });
    }

    private static void hookGranularEditor(DtGranularEditor<?> editor)
    {
        if (editor == null || !HOOKED_EDITORS.add(editor))
            return;
        IPageChangedListener listener = event -> syncFromActiveEditor(editor);
        editor.addPageChangedListener(listener);
    }

    /** Определяет режим по активному редактору/странице и вызывает нужную проверку. */
    private static void syncFromActiveEditor(IEditorPart editor)
    {
        if (editor.getSite() == null)
            return;
        if (GetRef.getActiveBslEditor(editor) != null)
        {
            // При активации редактора модуля, если активна панель «Свойства» —
            // активировать панель «Схема».
            onModuleEditorActivated(editor.getSite().getPage());
        }
        else if (editor instanceof FormEditor formEditor
            && (formEditor.getActivePageInstance() instanceof FormEditorPage
                || formEditor.getActivePageInstance() instanceof ChildFormMainPage))
        {
            // При активации вкладки «Форма» или «Основные», если активна панель
            // «Схема» — активировать панель «Свойства» (симметрично предыдущему).
            onFormPageActivated(editor.getSite().getPage());
        }
    }

    private static void onModuleEditorActivated(IWorkbenchPage page)
    {
        Panels panels = resolvePanels(page);
        if (panels == null)
            return;
        if (isSelectedTab(panels.folder, panels.propertiesPart))
            showPartVisible(panels.outlineView, panels.outlinePart);
    }

    private static void onFormPageActivated(IWorkbenchPage page)
    {
        Panels panels = resolvePanels(page);
        if (panels == null)
            return;
        if (isSelectedTab(panels.folder, panels.outlinePart))
            showPartVisible(panels.propertiesView, panels.propertiesPart);
    }

    /** Обе панели, их {@link MPart} и общий {@link CTabFolder}; {@code null} — условия нет. */
    private static Panels resolvePanels(IWorkbenchPage page)
    {
        if (page == null)
            return null;
        IViewReference propRef = page.findViewReference(PROPERTY_SHEET_VIEW_ID);
        IViewReference outlineRef = page.findViewReference(OUTLINE_VIEW_ID);
        // getView(true): панель может быть открытой фоновой вкладкой группы, ещё не
        // материализованной — getView(false) в этом случае молча даёт null (issue 2202).
        IViewPart propView = propRef != null ? propRef.getView(true) : null;
        IViewPart outlineView = outlineRef != null ? outlineRef.getView(true) : null;
        if (propView == null || outlineView == null)
            return null; // одна из панелей не открыта — синхронизировать нечего

        MPart propPart = mpartOf(propView);
        MPart outlinePart = mpartOf(outlineView);
        if (propPart == null || outlinePart == null)
            return null;

        // MPart.getParent() у этих панелей не заполнен (compatibility-обёртка e3-view над
        // CompatibilityView) — группу определяем по общему CTabFolder-предку виджетов,
        // а не по модели.
        CTabFolder propFolder = folderOf(propPart);
        CTabFolder outlineFolder = folderOf(outlinePart);
        if (propFolder == null || propFolder != outlineFolder)
            return null; // не в одной группе вкладок

        return new Panels(propView, propPart, outlineView, outlinePart, propFolder);
    }

    private record Panels(IViewPart propertiesView, MPart propertiesPart,
        IViewPart outlineView, MPart outlinePart, CTabFolder folder)
    {
    }

    /** Виджет {@code part} сейчас показан выбранной вкладкой {@code folder}. */
    private static boolean isSelectedTab(CTabFolder folder, MPart part)
    {
        CTabItem item = folder.getSelection();
        Control selectedControl = item != null && !item.isDisposed() ? item.getControl() : null;
        Object widget = part.getWidget();
        if (selectedControl == null || !(widget instanceof Control partControl))
            return false;
        return selectedControl == partControl || isChild(selectedControl, partControl)
            || isChild(partControl, selectedControl);
    }

    private static boolean isChild(Control control, Control ancestor)
    {
        for (Control c = control; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c == ancestor)
                return true;
        }
        return false;
    }

    /** Ближайший предок-{@link CTabFolder} виджета панели; {@code null} — не найден. */
    private static CTabFolder folderOf(MPart part)
    {
        Object widget = part.getWidget();
        if (!(widget instanceof Control control) || control.isDisposed())
            return null;
        for (Control c = control; c != null && !c.isDisposed(); c = c.getParent())
        {
            if (c instanceof CTabFolder folder)
                return folder;
        }
        return null;
    }

    private static MPart mpartOf(IWorkbenchPart part)
    {
        try
        {
            if (part == null || part.getSite() == null)
                return null;
            Object raw = part.getSite().getService(MPart.class);
            return raw instanceof MPart mpart ? mpart : null;
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    /**
     * Делает {@code desired} видимой вкладкой её группы без передачи ей фокуса
     * ({@code PartState.VISIBLE}) — активный редактор ввод не теряет.
     */
    private static void showPartVisible(IWorkbenchPart anyPartInWindow, MPart desired)
    {
        try
        {
            Object partService = anyPartInWindow.getSite().getService(EPartService.class);
            if (partService == null)
                return;
            Method m = partService.getClass().getMethod("showPart", MPart.class, PartState.class); //$NON-NLS-1$
            m.invoke(partService, desired, PartState.VISIBLE);
        }
        catch (ReflectiveOperationException | RuntimeException ex)
        {
            // Переключение вкладки — необязательное улучшение UX.
        }
    }

    /** Дополняет штатные страницы «Основные» свойством подавления проверок. */
    private static final class MdMainPageHook
    {
        private static final String TEMP_LOG = "mdMainPageSuppression"; //$NON-NLS-1$

        static void earlyStartup()
        {
            Global.tempLog(TEMP_LOG, "earlyStartup"); //$NON-NLS-1$
            Display.getDefault().asyncExec(MdMainPageHook::install);
        }

        private static void install()
        {
            try
            {
                // В EDT нет расширения для добавления поля в уже зарегистрированный дескриптор.
                // Инициализация и чтение реестра выполняются под его собственным монитором.
                synchronized (DtEditorDescriptorRegistry.class)
                {
                    DtEditorDescriptorRegistry registry = DtEditorDescriptorRegistry.INSTANCE;
                    Field initialized = DtEditorDescriptorRegistry.class.getDeclaredField("initialized"); //$NON-NLS-1$
                    initialized.setAccessible(true);
                    if (!initialized.getBoolean(registry))
                    {
                        Method init = DtEditorDescriptorRegistry.class.getDeclaredMethod("init"); //$NON-NLS-1$
                        init.setAccessible(true);
                        init.invoke(registry);
                        initialized.setBoolean(registry, true);
                    }

                    Field descriptorsField = DtEditorDescriptorRegistry.class.getDeclaredField("descriptors"); //$NON-NLS-1$
                    Field expressionsField = DtEditorDescriptorRegistry.class.getDeclaredField("expressions"); //$NON-NLS-1$
                    descriptorsField.setAccessible(true);
                    expressionsField.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, Collection<IDtGranularEditorAefPageDescriptor>> descriptors =
                        (Map<String, Collection<IDtGranularEditorAefPageDescriptor>>) descriptorsField.get(registry);
                    @SuppressWarnings("unchecked")
                    Map<IDtGranularEditorAefPageDescriptor, Expression> expressions =
                        (Map<IDtGranularEditorAefPageDescriptor, Expression>) expressionsField.get(registry);
                    int wrapped = 0;
                    for (Map.Entry<String, Collection<IDtGranularEditorAefPageDescriptor>> entry : descriptors.entrySet())
                    {
                        EStructuralFeature feature = suppressionFeature(entry.getKey());
                        if (feature == null)
                            continue;
                        List<IDtGranularEditorAefPageDescriptor> replacements = new ArrayList<>();
                        for (IDtGranularEditorAefPageDescriptor descriptor : entry.getValue())
                        {
                            if (descriptor instanceof SuppressionDescriptor)
                            {
                                replacements.add(descriptor);
                                continue;
                            }
                            SuppressionDescriptor wrapper = new SuppressionDescriptor(descriptor, entry.getKey(), feature);
                            expressions.put(wrapper, expressions.remove(descriptor));
                            replacements.add(wrapper);
                            wrapped++;
                        }
                        entry.setValue(replacements);
                    }
                    Global.tempLog(TEMP_LOG, "wrapped=" + wrapped); //$NON-NLS-1$
                }
            }
            catch (Throwable error)
            {
                Global.tempLogException(TEMP_LOG, "install failed", error); //$NON-NLS-1$
            }
        }

        private static EStructuralFeature suppressionFeature(String pageId)
        {
            if (pageId == null || !pageId.startsWith("editors.") || !pageId.endsWith(".pages.main")) //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            String objectName = pageId.substring("editors.".length(), pageId.length() - ".pages.main".length()); //$NON-NLS-1$ //$NON-NLS-2$
            for (EClassifier classifier : MdClassPackage.eINSTANCE.getEClassifiers())
            {
                if (classifier instanceof EClass objectClass && classifier.getName().equalsIgnoreCase(objectName))
                    return objectClass.getEStructuralFeature("suppressObject"); //$NON-NLS-1$
            }
            return null;
        }

        private static void addSuppression(IDtGranularEditorManagingDefinition definition,
            EStructuralFeature feature, String pageId)
        {
            if (definition.getPageFeatures().contains(feature))
            {
                Global.tempLog(TEMP_LOG, pageId + " already contains property"); //$NON-NLS-1$
                return;
            }
            if (!(definition instanceof ContainerDefinition root))
                throw new IllegalStateException("Unknown definition type: " + definition.getClass()); //$NON-NLS-1$
            ContainerDefinition left = root;
            List<IDefinition> children = root.getChildren();
            if (!children.isEmpty() && children.get(0) instanceof ContainerDefinition column
                && !(column instanceof SectionDefinition))
                left = column;
            SectionDefinition main = null;
            for (IDefinition child : left.getChildren())
            {
                if (child instanceof SectionDefinition section)
                {
                    main = section;
                    break;
                }
            }
            if (main == null)
                throw new IllegalStateException("Main section not found: " + pageId); //$NON-NLS-1$

            SectionDefinition addedRows = new SectionDefinition("Основные"); //$NON-NLS-1$
            DtSectionDefinitionBuilder.builder(() -> addedRows, ignored -> {}, null)
                .separator()
                .element(feature)
                .setup()
                .component(OpenSuppressionSettingsEditorByLinkComponent.class, LinkParameterization.OPEN)
                .endSetup()
                .endSection();
            int insertAt = "editors.commonmodule.pages.main".equals(pageId) //$NON-NLS-1$
                ? afterComment(main) : -1;
            for (IDefinition row : addedRows.getChildren())
            {
                if (insertAt >= 0)
                    main.addDefinition(insertAt++, row);
                else
                    main.addDefinition(row);
            }
            Global.tempLog(TEMP_LOG, pageId + " added: " + feature.getName() //$NON-NLS-1$
                + ", afterComment=" + (insertAt >= 0)); //$NON-NLS-1$
        }

        private static int afterComment(SectionDefinition main)
        {
            List<IDefinition> rows = main.getChildren();
            for (int i = 0; i < rows.size(); i++)
            {
                if (!(rows.get(i) instanceof IFieldDefinition field))
                    continue;
                for (var path : field.getFeaturePaths())
                {
                    EStructuralFeature[] features = path.getFeaturePath();
                    if (features.length > 0
                        && features[features.length - 1] == MdClassPackage.Literals.MD_OBJECT__COMMENT)
                        return i + 1;
                }
            }
            return -1;
        }

        private static final class SuppressionDescriptor implements IDtGranularEditorAefPageDescriptor
        {
            private final IDtGranularEditorAefPageDescriptor delegate;
            private final String pageId;
            private final EStructuralFeature feature;
            private boolean added;

            private SuppressionDescriptor(IDtGranularEditorAefPageDescriptor delegate, String pageId,
                EStructuralFeature feature)
            {
                this.delegate = delegate;
                this.pageId = pageId;
                this.feature = feature;
            }

            @Override
            public synchronized IDtGranularEditorManagingDefinition getDefinition()
            {
                Global.tempLog(TEMP_LOG, pageId + " getDefinition"); //$NON-NLS-1$
                IDtGranularEditorManagingDefinition definition;
                try
                {
                    definition = delegate.getDefinition();
                }
                catch (RuntimeException | Error error)
                {
                    Global.tempLogException(TEMP_LOG, pageId + " getDefinition failed", error); //$NON-NLS-1$
                    throw error;
                }
                if (!added)
                {
                    added = true;
                    try
                    {
                        addSuppression(definition, feature, pageId);
                    }
                    catch (RuntimeException | Error error)
                    {
                        // Ошибка нашего дополнения не должна ломать штатную страницу EDT.
                        Global.tempLogException(TEMP_LOG, pageId + " addSuppression failed", error); //$NON-NLS-1$
                    }
                }
                return definition;
            }
        }
    }
}
