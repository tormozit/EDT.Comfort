package tormozit;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.IPageChangeProvider;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.IWizard;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StandardCommandGroup;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.BasicCommand;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Окна «Новая команда» и «Новая общая команда» (мастера {@code CommandWizard} и
 * {@code CommonCommandWizard}): если команда создаётся у объекта со ссылочным типом, под полем
 * «Группа» добавляется флажок «Параметризованная». Включённый флажок ставит типом параметра
 * команды ссылку на этот объект (issue 706). У общей команды родитель — конфигурация, флажка нет.
 *
 * <p>После «Готово» открывается панель «Свойства» с созданной командой (issue 859). Мастер сам
 * выделяет команду во всех частях-получателях ({@code WorkbenchUtils.selectAndReveal}, через два
 * {@code asyncExec}), а общей команде ещё и открывает редактор; если активная часть команду не
 * выделила, активируется навигатор.
 *
 * <p>Механика (проверено декомпиляцией {@code com._1c.g5.v8.dt.md.ui}):
 * <ul>
 * <li>Создаваемая команда до «Готово» — отдельный объект {@code IDtNewWizardContext.getModel()},
 * в модель конфигурации он попадает только в задаче {@code DtNewWizard$1}. Штатное поле «Группа»
 * пишет прямо в него ({@code BASIC_COMMAND__GROUP}); тип параметра ставим так же.</li>
 * <li>Страница рисуется LWT: её контрол — {@code Composite} с {@code SwtLightLayout}
 * ({@code LightTwoColumnLayout}), поля — «лёгкие» элементы. Флажок — обычная SWT-кнопка,
 * включённая в ту же раскладку штатным {@code SwtLightComposite.addChild(Control)} на всю ширину
 * ({@code LightTwoColumnLayoutData.setWide}).</li>
 * <li>Бандл LWT в манифесте плагина не подключён (как и в {@link AefFieldFocus}) — всё через
 * рефлексию; загрузчик классов берём у раскладки страницы.</li>
 * </ul>
 */
public final class NewCommandWizardHook implements IStartup
{
    private static final String TAG = "NewCommandWizard"; //$NON-NLS-1$

    /** Ключ {@link Shell#setData}: сеанс хука либо отметка «не наш мастер». */
    private static final String SESSION_KEY = "tormozit.newCommandWizardSession"; //$NON-NLS-1$

    /** Общий предок мастеров команды объекта ({@code CommandWizard}) и общей команды ({@code CommonCommandWizard}). */
    private static final String WIZARD_CLASS = "com._1c.g5.v8.dt.md.ui.wizards.AbstractCommandWizard"; //$NON-NLS-1$

    /** Компонент главной страницы мастера — {@code AbstractCommandWizard$CommandWizardPage}. */
    private static final String PAGE_COMPONENT_SUFFIX = "$CommandWizardPage"; //$NON-NLS-1$

    /** Имя стандартной группы «Командная панель формы.Важное». */
    private static final String FORM_COMMAND_BAR_IMPORTANT = "FormCommandBarImportant"; //$NON-NLS-1$

    private static final int RETRY_DELAY_MS = 60;

    private static final int MAX_ATTEMPTS = 15;

    /** Отступ слева, как у подписей полей ({@code LightTwoColumnLayoutData.setLeft}). */
    private static final int LEFT_INDENT = 4;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (shell.getData(SESSION_KEY) != null || !(shell.getData() instanceof IWizardContainer container))
                return;
            IWizardPage page = container.getCurrentPage();
            IWizard wizard = page != null ? page.getWizard() : null;
            if (wizard == null)
                return;
            if (!isCommandWizard(wizard))
            {
                shell.setData(SESSION_KEY, Boolean.FALSE);
                return;
            }
            Session session = new Session(shell, wizard);
            shell.setData(SESSION_KEY, session);
            // Со страницей выбора родителя главная страница строится позже — ловим её по смене страницы.
            if (container instanceof IPageChangeProvider provider)
                provider.addPageChangedListener(e -> session.sync());
            if (container instanceof Window window)
                shell.addListener(SWT.Dispose, e -> session.onClosed(window));
            scheduleAttach(display, session, 0);
        };
        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean isCommandWizard(IWizard wizard)
    {
        for (Class<?> c = wizard.getClass(); c != null; c = c.getSuperclass())
            if (WIZARD_CLASS.equals(c.getName()))
                return true;
        return false;
    }

    /** Ждёт, пока страница построит свою раскладку. */
    private static void scheduleAttach(Display display, Session session, int attempt)
    {
        display.timerExec(attempt == 0 ? 0 : RETRY_DELAY_MS, () ->
        {
            if (session.sync() || attempt >= MAX_ATTEMPTS)
                return;
            scheduleAttach(display, session, attempt + 1);
        });
    }

    // =========================================================================
    // Сеанс одного окна
    // =========================================================================

    private static final class Session
    {
        private final Shell shell;

        private final IWizard wizard;

        private Button button;

        /** Команда, которой флажок поставил тип параметра; при смене родителя мастер создаёт новую. */
        private BasicCommand appliedModel;

        /** Тип параметра этой команды до включения флажка — возвращается при выключении. */
        private TypeDescription originalType;

        Session(Shell shell, IWizard wizard)
        {
            this.shell = shell;
            this.wizard = wizard;
        }

        /**
         * Приводит флажок и тип параметра в соответствие с текущими родителем и командой мастера.
         *
         * @return {@code true}, если флажок на странице уже есть (ждать больше нечего)
         */
        boolean sync()
        {
            if (shell.isDisposed())
                return true;
            try
            {
                Object context = Global.invoke(wizard, "getContext"); //$NON-NLS-1$
                BasicCommand model = Global.invoke(context, "getModel") instanceof BasicCommand command //$NON-NLS-1$
                    ? command : null;
                TypeItem refType = Global.invoke(context, "getParent") instanceof MdObject parent //$NON-NLS-1$
                    ? MdEditorDefinedTypesPageHook.refTypeOf(parent) : null;
                boolean available = model != null && refType != null;
                if (button == null || button.isDisposed())
                {
                    if (!available || !createButton())
                        return false;
                }
                else if (button.getVisible() != available)
                {
                    button.setVisible(available);
                    relayout(button.getParent());
                }
                if (available)
                    apply(model, refType);
                return true;
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "sync", e); //$NON-NLS-1$
                return true;
            }
        }

        /** Окно закрыто; после «Готово» показывает созданную команду в панели «Свойства». */
        void onClosed(Window window)
        {
            if (window.getReturnCode() != Window.OK)
                return;
            try
            {
                if (!(Global.invoke(Global.invoke(wizard, "getContext"), "getModel") instanceof EObject command)) //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                Display display = shell.getDisplay();
                // Мастер выделяет команду в asyncExec, а получатели выделения — в следующем asyncExec.
                display.asyncExec(() -> display.asyncExec(() -> showInProperties(command)));
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "onClosed", e); //$NON-NLS-1$
            }
        }

        private static void showInProperties(EObject command)
        {
            try
            {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                IWorkbenchPage page = window != null ? window.getActivePage() : null;
                if (page == null)
                    return;
                if (!selects(page.getActivePart(), command))
                {
                    IViewPart navigator = page.findView(Global.NAVIGATOR_VIEW_ID);
                    if (navigator != null && selects(navigator, command))
                        page.activate(navigator);
                    else
                        Global.log(TAG, "созданная команда не выделена ни в активной части, ни в навигаторе"); //$NON-NLS-1$
                }
                page.showView(IPageLayout.ID_PROP_SHEET, null, IWorkbenchPage.VIEW_VISIBLE);
            }
            catch (PartInitException | RuntimeException e)
            {
                Global.logError(TAG, "showInProperties", e); //$NON-NLS-1$
            }
        }

        /** Выделение части содержит эту команду. */
        private static boolean selects(IWorkbenchPart part, EObject command)
        {
            ISelectionProvider provider = part != null && part.getSite() != null
                ? part.getSite().getSelectionProvider() : null;
            if (provider == null || !(provider.getSelection() instanceof IStructuredSelection selection))
                return false;
            URI uri = EcoreUtil.getURI(command);
            for (Object element : selection.toList())
            {
                EObject model = NavigatorElementModels.resolveEObject(element);
                if (model == command || model != null && uri != null && uri.equals(EcoreUtil.getURI(model)))
                    return true;
            }
            return false;
        }

        private void apply(BasicCommand model, TypeItem refType)
        {
            if (button.getSelection())
            {
                if (appliedModel != model)
                {
                    appliedModel = model;
                    originalType = model.getCommandParameterType();
                }
                TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
                description.getTypes().add(refType);
                model.setCommandParameterType(description);
            }
            else if (appliedModel == model)
            {
                model.setCommandParameterType(originalType);
                appliedModel = null;
                originalType = null;
            }
        }

        /**
         * Параметризуемая команда показывается только в панелях формы. Если выбранная группа к ним
         * не относится — ставит «Командная панель формы.Важное».
         *
         * <p>Пишем через модель поля «Группа» ({@code CommandModel.getCommandGroup()},
         * {@code Value.set}) — тем же путём, что и само поле: значение видно в окне сразу и не
         * затирается при завершении мастера. Группу берём из штатного списка выбора.
         */
        private void ensureParameterizableGroup()
        {
            try
            {
                Object groupModel = Global.invoke(Global.getField(wizard, "model"), "getCommandGroup"); //$NON-NLS-1$ //$NON-NLS-2$
                if (groupModel == null || CommandDecorator.allowsParameter(Global.invoke(groupModel, "get"))) //$NON-NLS-1$
                    return;
                if (!(Global.invoke(groupModel, "getContentProvider") instanceof IStructuredContentProvider provider)) //$NON-NLS-1$
                    return;
                for (Object element : provider.getElements(Global.invoke(groupModel, "getInput"))) //$NON-NLS-1$
                {
                    if (element instanceof StandardCommandGroup group
                        && FORM_COMMAND_BAR_IMPORTANT.equals(group.getName()))
                    {
                        Global.invokeVoid(groupModel, "set", group); //$NON-NLS-1$
                        return;
                    }
                }
                Global.log(TAG, "группа " + FORM_COMMAND_BAR_IMPORTANT + " не найдена в списке выбора"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "ensureParameterizableGroup", e); //$NON-NLS-1$
            }
        }

        /** Добавляет флажок последней строкой раскладки главной страницы; {@code false} — страница не готова. */
        private boolean createButton()
        {
            Composite pageControl = mainPageControl();
            if (pageControl == null || pageControl.getLayout() == null)
                return false;
            ClassLoader loader = pageControl.getLayout().getClass().getClassLoader();
            Class<?> compositeClass;
            Class<?> wrapperClass;
            Class<?> dataClass;
            try
            {
                compositeClass = Class.forName("com._1c.g5.lwt.interop.SwtLightComposite", false, loader); //$NON-NLS-1$
                wrapperClass = Class.forName("com._1c.g5.lwt.interop.SwtLightControl", false, loader); //$NON-NLS-1$
                dataClass = Class.forName("com._1c.g5.lwt.layouts.LightTwoColumnLayoutData", false, loader); //$NON-NLS-1$
            }
            catch (ClassNotFoundException e)
            {
                // Страница на «тяжёлых» контролах (-D PREFER_HEAVY_CONTROLS) — раскладка другая, не трогаем.
                return false;
            }
            Object light = Global.invoke(compositeClass, "getSwtLightComposite", pageControl); //$NON-NLS-1$
            if (light == null)
                return false;

            Button check = new Button(pageControl, SWT.CHECK);
            check.setText("Параметризованная"); //$NON-NLS-1$
            check.setBackground(pageControl.getBackground());
            check.setToolTipText(TooltipText.wrap(check,
                "Параметром команды станет ссылка на объект, которому команда принадлежит." //$NON-NLS-1$
                    + Global.pluginSignForTooltip()));
            Global.invoke(light, "addChild", check); //$NON-NLS-1$
            Object wrapper = Global.invoke(wrapperClass, "getSwtLightControl", check); //$NON-NLS-1$
            if (wrapper == null)
            {
                check.dispose();
                Global.log(TAG, "флажок не вошёл в раскладку страницы"); //$NON-NLS-1$
                return false;
            }
            Object data = Global.invoke(dataClass, "setWide", wrapper); //$NON-NLS-1$
            Global.setField(data, "horizontalIndent", Integer.valueOf(LEFT_INDENT)); //$NON-NLS-1$
            check.addListener(SWT.Selection, e ->
            {
                // Группу меняем только в момент включения: дальнейший выбор пользователя не трогаем.
                if (check.getSelection())
                    ensureParameterizableGroup();
                sync();
            });
            button = check;
            relayout(pageControl);
            Global.log(TAG, "флажок «Параметризованная» добавлен"); //$NON-NLS-1$
            return true;
        }

        /** Контрол главной страницы мастера; {@code null}, пока она не построена. */
        private Composite mainPageControl()
        {
            for (IWizardPage page : wizard.getPages())
            {
                Object component = Global.getField(page, "component"); //$NON-NLS-1$
                if (component == null || !component.getClass().getName().endsWith(PAGE_COMPONENT_SUFFIX))
                    continue;
                Control control = page.getControl();
                return control instanceof Composite composite && !composite.isDisposed() ? composite : null;
            }
            return null;
        }

        /** Перекладывает страницу; окно растёт, если новая строка в него не помещается. */
        private void relayout(Composite pageControl)
        {
            pageControl.layout(true, true);
            pageControl.redraw();
            Point size = shell.getSize();
            Point preferred = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
            if (preferred.y > size.y)
                shell.setSize(size.x, preferred.y);
        }
    }
}
