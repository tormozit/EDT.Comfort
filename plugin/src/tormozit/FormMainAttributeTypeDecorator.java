package tormozit;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.ILightweightLabelDecorator;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IDecoratorManager;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.AbstractForm;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.ui.dialog.ListItemSelectionDialog;

/**
 * Суффикс типа основного реквизита формы в представлении {@link BasicForm}
 * (обычная и общая форма) в навигаторе EDT, в дереве категории «Формы»
 * редактора объекта метаданных и в многоцелевом диалоге выбора
 * ({@link ListItemSelectionDialog}, например выбор общей формы в свойстве).
 * Берётся первый фрагмент русского имени типа до точки, например
 * «СправочникОбъект.Номенклатура» → «СправочникОбъект».
 */
public final class FormMainAttributeTypeDecorator extends LabelProvider implements ILightweightLabelDecorator, IStartup
{
    private static final String DECORATOR_ID = "tormozit.formMainAttributeTypeDecorator"; //$NON-NLS-1$
    private static final String PREF_AUTO_ENABLED = "tormozit.formMainAttributeTypeDecorator.autoEnabled"; //$NON-NLS-1$
    private static final String DIALOG_PATCHED_KEY = "tormozit.formMainAttributeTypeDialogPatched"; //$NON-NLS-1$
    private static final String WINDOW_KEY = "org.eclipse.jface.window.Window"; //$NON-NLS-1$
    private static final String NONE = ""; //$NON-NLS-1$

    private static final ConcurrentHashMap<String, String> SUFFIX_CACHE = new ConcurrentHashMap<>();

    /**
     * Декоратор {@code state="true"} в plugin.xml задаёт значение по умолчанию только для
     * никогда не виденного id; если первая попытка загрузки класса когда-либо провалилась
     * (напр. гонка при первом старте в PDE-разработке), Eclipse сам гасит декоратор и сохраняет
     * "false" в настройках рабочей области — навсегда, до ручного включения. Один раз за всё
     * время жизни плагина в этой рабочей области принудительно включаем декоратор, если он ещё
     * ни разу не был подтверждён включённым нами.
     */
    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        if (display != null)
        {
            display.asyncExec(() ->
            {
                ensureEnabledOnce();
                installDialogHook(display);
            });
        }
    }

    private static void ensureEnabledOnce()
    {
        Activator activator = Activator.getDefault();
        IPreferenceStore store = activator != null ? activator.getPreferenceStore() : null;
        if (store == null || store.getBoolean(PREF_AUTO_ENABLED))
            return;
        try
        {
            IDecoratorManager manager = PlatformUI.getWorkbench().getDecoratorManager();
            if (!manager.getEnabled(DECORATOR_ID))
                manager.setEnabled(DECORATOR_ID, true);
        }
        catch (Exception ex)
        {
            Global.log("FormMainAttributeTypeDecorator ensureEnabledOnce error: " + ex); //$NON-NLS-1$
        }
        finally
        {
            store.setValue(PREF_AUTO_ENABLED, true);
        }
    }

    private static void installDialogHook(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (event.widget instanceof Shell shell && !shell.isDisposed())
                onShellEvent(shell);
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    private static void onShellEvent(Shell shell)
    {
        if (Boolean.TRUE.equals(shell.getData(DIALOG_PATCHED_KEY)))
            return;
        if (resolveListItemDialog(shell) == null)
            return;
        scheduleDialogPatch(shell, 0);
    }

    private static void scheduleDialogPatch(Shell shell, int attempt)
    {
        Display display = shell.getDisplay();
        int delay = attempt == 0 ? 0 : 50;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || Boolean.TRUE.equals(shell.getData(DIALOG_PATCHED_KEY)))
                return;
            if (tryPatchDialog(shell))
                return;
            if (attempt < 12)
                scheduleDialogPatch(shell, attempt + 1);
        });
    }

    private static boolean tryPatchDialog(Shell shell)
    {
        ListItemSelectionDialog dialog = resolveListItemDialog(shell);
        if (dialog == null)
            return true;

        Object viewerObj = Global.getField(dialog, "elementsTableViewer"); //$NON-NLS-1$
        if (!(viewerObj instanceof TableViewer viewer)
                || viewer.getControl() == null
                || viewer.getControl().isDisposed())
            return false;

        IBaseLabelProvider current = viewer.getLabelProvider();
        if (current instanceof SuffixLabelProvider
                || current instanceof SelectionAwareStyledCellLabelProvider)
        {
            shell.setData(DIALOG_PATCHED_KEY, Boolean.TRUE);
            return true;
        }
        if (!(current instanceof ILabelProvider base))
            return false;

        SuffixLabelProvider wrapped = new SuffixLabelProvider(base);
        // TableViewer.setLabelProvider(ILabelProvider) рисует через getText() — цвет StyleRange
        // не доходит. SelectionAwareStyledCellLabelProvider вызывает getStyledText и ставит
        // COLORS_ON_SELECTION (штатный DelegatingStyledCellLabelProvider цвет на выделенной
        // строке отбрасывает). Фильтр остаётся на ILabelProvider — CellLabelProvider им не является.
        viewer.setLabelProvider(new SelectionAwareStyledCellLabelProvider(wrapped));
        for (ViewerFilter filter : viewer.getFilters())
        {
            if (filter != null && filter.getClass().getName().contains("TextListViewerFilter")) //$NON-NLS-1$
                Global.setField(filter, "labelProvider", wrapped); //$NON-NLS-1$
        }
        shell.setData(DIALOG_PATCHED_KEY, Boolean.TRUE);
        return true;
    }

    private static ListItemSelectionDialog resolveListItemDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data instanceof ListItemSelectionDialog dialog)
            return dialog;
        Object window = shell.getData(WINDOW_KEY);
        return window instanceof ListItemSelectionDialog dialog ? dialog : null;
    }

    @Override
    public void decorate(Object element, IDecoration decoration)
    {
        String suffix = suffixForElement(element);
        if (suffix != null)
            decoration.addSuffix(" (" + suffix + ")");
    }

    static String suffixForElement(Object element)
    {
        if (!isDecoratorEnabled())
            return null;
        Object model = resolveFormModel(element);
        if (!(model instanceof BasicForm basicForm))
            return null;
        return suffixFor(basicForm);
    }

    private static Object resolveFormModel(Object element)
    {
        if (element instanceof BasicForm)
            return element;
        Object model = NavigatorElementModels.resolveModel(element);
        if (model instanceof BasicForm)
            return model;
        EObject fromDesc = eObjectFromDescription(element);
        if (fromDesc instanceof BasicForm)
            return fromDesc;
        Object desc = Global.getField(element, "descriptionRu"); //$NON-NLS-1$
        if (desc == null)
            desc = Global.getField(element, "description"); //$NON-NLS-1$
        fromDesc = eObjectFromDescription(desc);
        if (fromDesc instanceof BasicForm)
            return fromDesc;
        return model;
    }

    private static EObject eObjectFromDescription(Object desc)
    {
        if (!(desc instanceof org.eclipse.xtext.resource.IEObjectDescription ieod))
            return null;
        try
        {
            EObject obj = ieod.getEObjectOrProxy();
            if (obj == null)
                return null;
            if (obj.eIsProxy())
                obj = EcoreUtil.resolve(obj, obj);
            return obj.eIsProxy() ? null : obj;
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private static boolean isDecoratorEnabled()
    {
        try
        {
            return PlatformUI.getWorkbench().getDecoratorManager().getEnabled(DECORATOR_ID);
        }
        catch (RuntimeException ex)
        {
            return true;
        }
    }

    private static String suffixFor(BasicForm basicForm)
    {
        String cacheKey = cacheKey(basicForm);
        if (cacheKey != null)
        {
            String cached = SUFFIX_CACHE.get(cacheKey);
            if (cached != null)
                return cached.isEmpty() ? null : cached;
        }

        String suffix = computeSuffix(basicForm);
        if (cacheKey != null)
            SUFFIX_CACHE.put(cacheKey, suffix == null ? NONE : suffix);
        return suffix;
    }

    private static String cacheKey(BasicForm basicForm)
    {
        try
        {
            URI uri = EcoreUtil.getURI(basicForm);
            return uri != null ? uri.toString() : null;
        }
        catch (RuntimeException ex)
        {
            return null;
        }
    }

    private static String computeSuffix(BasicForm basicForm)
    {
        try
        {
            AbstractForm abstractForm = basicForm.getForm();
            if (abstractForm != null && abstractForm.eIsProxy())
                abstractForm = (AbstractForm) EcoreUtil.resolve(abstractForm, basicForm);
            if (!(abstractForm instanceof Form form))
                return null;

            for (FormAttribute attribute : form.getAttributes())
            {
                if (attribute == null || !attribute.isMain())
                    continue;
                String fragment = firstTypeFragment(attribute, form);
                if (fragment != null)
                    return fragment;
            }
        }
        catch (RuntimeException ex)
        {
            return null;
        }
        return null;
    }

    private static String firstTypeFragment(FormAttribute attribute, Form form)
    {
        TypeDescription valueType = attribute.getValueType();
        if (valueType == null)
            return null;
        for (TypeItem typeItem : valueType.getTypes())
        {
            if (typeItem == null)
                continue;
            TypeItem resolved = typeItem;
            if (typeItem.eIsProxy())
                resolved = (TypeItem) EcoreUtil.resolve(typeItem, form);

            String typeName = McoreUtil.getTypeNameRu(resolved);
            if (typeName == null || typeName.isBlank())
                typeName = McoreUtil.getTypeName(resolved);
            if (typeName == null || typeName.isBlank())
                typeName = McoreUtil.getTypeCategoryRu(resolved);
            if (typeName == null || typeName.isBlank())
                continue;

            int dot = typeName.indexOf('.');
            String fragment = dot > 0 ? typeName.substring(0, dot) : typeName;
            if (!fragment.isBlank())
                return fragment;
        }
        return null;
    }

    static String withSuffix(String text, Object element)
    {
        if (text == null)
            text = ""; //$NON-NLS-1$
        String suffix = suffixForElement(element);
        if (suffix == null)
            return text;
        String add = " (" + suffix + ")";
        if (text.endsWith(add))
            return text;
        return text + add;
    }

    /**
     * Обёртка label provider диалога {@link ListItemSelectionDialog}: штатный
     * {@code DecoratorManager} туда не применяется.
     */
    private static final class SuffixLabelProvider extends LabelProvider implements IStyledLabelProvider
    {
        private final ILabelProvider base;
        private final IStyledLabelProvider baseStyled;

        SuffixLabelProvider(ILabelProvider base)
        {
            this.base = base;
            this.baseStyled = base instanceof IStyledLabelProvider styled ? styled : null;
        }

        @Override
        public String getText(Object element)
        {
            return withSuffix(base != null ? base.getText(element) : super.getText(element), element);
        }

        @Override
        public Image getImage(Object element)
        {
            return base != null ? base.getImage(element) : super.getImage(element);
        }

        @Override
        public StyledString getStyledText(Object element)
        {
            String name;
            if (baseStyled != null)
            {
                StyledString fromBase = baseStyled.getStyledText(element);
                name = fromBase != null
                    ? fromBase.getString()
                    : nullToEmpty(base != null ? base.getText(element) : null);
            }
            else
            {
                name = nullToEmpty(base != null ? base.getText(element) : null);
            }
            String suffix = suffixForElement(element);
            String add = suffix != null ? " (" + suffix + ")" : null;
            if (add != null && name.endsWith(add))
                name = name.substring(0, name.length() - add.length());
            StyledString styled = new StyledString(name);
            if (add != null)
                styled.append(add, StyledString.DECORATIONS_STYLER);
            return styled;
        }

        private static String nullToEmpty(String text)
        {
            return text == null ? "" : text; //$NON-NLS-1$
        }
    }
}
