package tormozit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.notify.Adapter;
import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.notify.Notifier;
import org.eclipse.emf.common.notify.impl.AdapterImpl;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.md.ui.aef.viewModels.ChoiceParameterItem;
import com._1c.g5.v8.dt.md.ui.aef.viewModels.ChoiceParametersViewModel;
import com._1c.g5.v8.dt.md.ui.aef.viewModels.MdAefPackage;
import com._1c.g5.v8.dt.mcore.Field;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.mcore.FieldSource;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.md.ui.aef.models.IChoiceParametersModel;
import com._1c.g5.v8.dt.md.ui.aef.providers.FieldLabelProvider;
import com._1c.g5.v8.dt.md.ui.aef.providers.ScriptVariantProvider;
import com._1c.g5.v8.dt.md.ui.controls.value.ValueRecord;
import com._1c.g5.v8.dt.metadata.mdclass.ScriptVariant;
import com._1c.g5.v8.dt.metadata.mdtype.MdRefType;
import java.util.Collections;
import java.util.LinkedHashMap;
import org.eclipse.emf.common.util.EList;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.xtext.EcoreUtil2;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.FixedArrayValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.ReferenceValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdtype.BasicDbObjectTypes;
import com._1c.g5.v8.dt.metadata.mdtype.EmptyRef;
import com._1c.g5.v8.dt.metadata.mdtype.EnumTypes;
import java.math.BigDecimal;
import java.util.Set;
import org.eclipse.emf.ecore.EStructuralFeature;

/**
 * В диалоге «Редактирование параметров выбора» автоматически выставляет тип значения
 * при выборе имени параметра ({@code Отбор.*} / {@code Filter.*}).
 */
public class ChoiceParametersHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.choiceParamsPatched"; //$NON-NLS-1$
    private static final String SESSION_KEY = "tormozit.choiceParamsSession"; //$NON-NLS-1$
    private static final String DIALOG_TITLE =
            "Редактирование параметров выбора"; //$NON-NLS-1$
    private static final String DIALOG_CLASS =
            "com._1c.g5.v8.dt.md.ui.aef.components.choiceparameters.ChoiceParametersDialog"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;

        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell))
                return;
            Shell shell = (Shell) event.widget;
            if (shell.isDisposed())
                return;
            if (shell.getData(PATCHED_KEY) != null)
                return;
            if (!isChoiceParametersShell(shell))
                return;
            schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean isChoiceParametersShell(Shell shell)
    {
        Object data = shell.getData();
        if (data != null && DIALOG_CLASS.equals(data.getClass().getName()))
            return true;
        String title = shell.getText();
        return title != null && title.contains(DIALOG_TITLE);
    }

    private static void schedulePatchAttempt(Display display, Shell shell, int attempt)
    {
        if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
            return;
        int delay = attempt == 0 ? 0 : 80;
        display.timerExec(delay, () ->
        {
            if (shell.isDisposed() || shell.getData(PATCHED_KEY) != null)
                return;
            if (tryPatch(shell))
                return;
            if (attempt < 12)
                schedulePatchAttempt(display, shell, attempt + 1);
        });
    }

    private static boolean tryPatch(Shell shell)
    {
        Object dialog = shell.getData();
        if (dialog == null)
            dialog = shell.getData("org.eclipse.jface.window.Window"); //$NON-NLS-1$
        if (dialog == null || !DIALOG_CLASS.equals(dialog.getClass().getName()))
            return false;

        ChoiceParametersViewModel viewModel =
                (ChoiceParametersViewModel) Global.getField(dialog, "viewModel"); //$NON-NLS-1$
        IV8Project v8Project = (IV8Project) Global.getField(dialog, "v8Project"); //$NON-NLS-1$
        ColumnViewer viewer = (ColumnViewer) Global.getField(dialog, "viewer"); //$NON-NLS-1$
        if (viewModel == null || v8Project == null || viewer == null)
            return false;

        Map<String, Field> fieldMap = ChoiceParameterFieldResolver.buildMap(viewModel, v8Project);
        if (fieldMap.isEmpty())
            return false;

        PatchSession session = new PatchSession(viewModel, viewer, fieldMap);
        session.install();
        shell.setData(PATCHED_KEY, Boolean.TRUE);
        shell.setData(SESSION_KEY, session);
        shell.addDisposeListener(e -> session.dispose());
        ChoiceParametersDebug.log("PATCH OK fields=" + fieldMap.size()); //$NON-NLS-1$
        return true;
    }

    private static final class PatchSession
    {
        private final ChoiceParametersViewModel viewModel;
        private final ColumnViewer viewer;
        private final Map<String, Field> fieldMap;
        private final ChoiceParameterValueFactory valueFactory = new ChoiceParameterValueFactory();
        private final List<Adapter> itemAdapters = new ArrayList<>();
        private final EContentAdapter viewModelAdapter;

        PatchSession(ChoiceParametersViewModel viewModel, ColumnViewer viewer,
                Map<String, Field> fieldMap)
        {
            this.viewModel = viewModel;
            this.viewer = viewer;
            this.fieldMap = fieldMap;
            viewModelAdapter = new EContentAdapter()
            {
                @Override
                public void notifyChanged(Notification notification)
                {
                    super.notifyChanged(notification);
                    if (notification.getFeature() == MdAefPackage.Literals.CHOICE_PARAMETERS_VIEW_MODEL__ITEMS
                            && notification.getEventType() == Notification.ADD)
                    {
                        Object newItem = notification.getNewValue();
                        if (newItem instanceof ChoiceParameterItem)
                            attachItemAdapter((ChoiceParameterItem) newItem);
                    }
                }
            };
        }

        void install()
        {
            EObject viewModelObject = asEObject(viewModel);
            if (viewModelObject == null)
                return;
            viewModelObject.eAdapters().add(viewModelAdapter);
            for (ChoiceParameterItem item : viewModel.getItems())
                attachItemAdapter(item);
        }

        void dispose()
        {
            EObject viewModelObject = asEObject(viewModel);
            if (viewModelObject != null)
                viewModelObject.eAdapters().remove(viewModelAdapter);
            for (Adapter adapter : itemAdapters)
            {
                Notifier notifier = adapter.getTarget();
                if (notifier != null)
                    notifier.eAdapters().remove(adapter);
            }
            itemAdapters.clear();
        }

        private void attachItemAdapter(ChoiceParameterItem item)
        {
            EObject itemObject = asEObject(item);
            if (itemObject == null)
                return;

            Adapter adapter = new AdapterImpl()
            {
                @Override
                public void notifyChanged(Notification notification)
                {
                    if (notification.getEventType() != Notification.SET)
                        return;
                    Object feature = notification.getFeature();
                    if (!(feature instanceof org.eclipse.emf.ecore.EStructuralFeature))
                        return;
                    if (!"text".equals(((org.eclipse.emf.ecore.EStructuralFeature) feature).getName())) //$NON-NLS-1$
                        return;
                    applyTypeForName(item, (String) notification.getNewValue());
                }

                @Override
                public boolean isAdapterForType(Object type)
                {
                    return type == ChoiceParameterItem.class;
                }
            };
            itemObject.eAdapters().add(adapter);
            itemAdapters.add(adapter);
            applyTypeForName(item, getItemText(item));
        }

        private static EObject asEObject(Object model)
        {
            return model instanceof EObject ? (EObject) model : null;
        }

        /** {@code getText()} объявлен в {@code ItemViewModel} (aef2), не в {@link ChoiceParameterItem}. */
        private static String getItemText(ChoiceParameterItem item)
        {
            Object text = Global.invoke(item, "getText"); //$NON-NLS-1$
            return text instanceof String ? (String) text : ""; //$NON-NLS-1$
        }

        private void applyTypeForName(ChoiceParameterItem item, String name)
        {
            if (name == null || name.isEmpty())
                return;

            Field field = fieldMap.get(name);
            if (field == null)
            {
                ChoiceParametersDebug.log("skip unknown name " + ChoiceParametersDebug.quote(name)); //$NON-NLS-1$
                return;
            }

            TypeItem typeItem = valueFactory.pickType(field);
            if (typeItem == null)
            {
                ChoiceParametersDebug.log("skip no type for " + ChoiceParametersDebug.quote(name)); //$NON-NLS-1$
                return;
            }

            Value current = item.getValue();
            if (current != null && valueFactory.sameValueType(current, typeItem))
            {
                ChoiceParametersDebug.log("keep " + ChoiceParametersDebug.quote(name) //$NON-NLS-1$
                        + " value=" + current.getClass().getSimpleName()); //$NON-NLS-1$
                return;
            }

            Value newValue = valueFactory.createDefault(typeItem);
            if (newValue == null)
            {
                ChoiceParametersDebug.log("reset FAIL createDefault " + ChoiceParametersDebug.quote(name)); //$NON-NLS-1$
                return;
            }

            item.setValue(newValue);
            refreshItem(item);
            ChoiceParametersDebug.log("reset " + ChoiceParametersDebug.quote(name) //$NON-NLS-1$
                    + " -> " + newValue.getClass().getSimpleName()); //$NON-NLS-1$
        }

        private void refreshItem(ChoiceParameterItem item)
        {
            Display display = viewer.getControl().getDisplay();
            if (display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                if (!viewer.getControl().isDisposed())
                    viewer.refresh(item, true);
            });
        }
    }

    /**
     * Карта имён параметров выбора ({@code Отбор.*} / {@code Filter.*}) → {@link Field}.
     */
    private static final class ChoiceParameterFieldResolver
    {
        private static final String PREFIX_RU = "Отбор."; //$NON-NLS-1$
        private static final String PREFIX_EN = "Filter."; //$NON-NLS-1$

        private ChoiceParameterFieldResolver() {}

        static Map<String, Field> buildMap(ChoiceParametersViewModel viewModel, IV8Project v8Project)
        {
            if (viewModel == null || v8Project == null)
                return Collections.emptyMap();

            ValueRecord record = viewModel.getRecord();
            if (record == null || record.typeDescription == null)
                return Collections.emptyMap();

            FieldSource selfSource = findSelfFieldSource(record.typeDescription);
            if (selfSource == null)
            {
                ChoiceParametersDebug.log("buildMap: FieldSource not found"); //$NON-NLS-1$
                return Collections.emptyMap();
            }

            Object[] elements = IChoiceParametersModel.COMPLETION_PROVIDER.getElements(selfSource);
            if (elements == null || elements.length == 0)
                return Collections.emptyMap();

            String prefix = v8Project.getScriptVariant() == ScriptVariant.ENGLISH ? PREFIX_EN : PREFIX_RU;
            ILabelProvider labels = new FieldLabelProvider(new ScriptVariantProvider(v8Project));
            Map<String, Field> map = new LinkedHashMap<>();
            try
            {
                for (Object element : elements)
                {
                    if (!(element instanceof Field))
                        continue;
                    Field field = (Field) element;
                    String label = labels.getText(field);
                    if (label == null || label.isEmpty())
                        continue;
                    map.put(prefix + label, field);
                }
            }
            finally
            {
                labels.dispose();
            }

            ChoiceParametersDebug.log("buildMap: " + map.size() + " fields"); //$NON-NLS-1$ //$NON-NLS-2$
            return map;
        }

        private static FieldSource findSelfFieldSource(TypeDescription typeDescription)
        {
            EList<TypeItem> types = typeDescription.getTypes();
            if (types == null)
                return null;
            for (TypeItem item : types)
            {
                if (!(item instanceof Type))
                    continue;
                if (((Type) item).eContainer() instanceof MdRefType)
                {
                    FieldSource fs = EcoreUtil2.getContainerOfType(item, FieldSource.class);
                    if (fs != null)
                        return fs;
                }
            }
            return null;
        }
    }


    /**
     * Создание пустых {@link Value} по {@link TypeItem} — логика как в EDT
     * {@code TypeSelectionEditor.getValue}, через публичные API.
     */
    private static final class ChoiceParameterValueFactory
    {
        private static final Set<String> REFERENCE_CATEGORIES = Set.of(
                "CatalogRef", //$NON-NLS-1$
                "EnumRef", //$NON-NLS-1$
                "ChartOfCharacteristicTypesRef", //$NON-NLS-1$
                "ChartOfCalculationTypesRef", //$NON-NLS-1$
                "BusinessProcessRoutePointRef", //$NON-NLS-1$
                "ChartOfAccountsRef"); //$NON-NLS-1$

        TypeItem pickType(Field field)
        {
            if (field == null)
                return null;
            TypeDescription td = field.getType();
            if (td == null || td.getTypes().isEmpty())
                return null;
            return td.getTypes().get(0);
        }

        Value createDefault(TypeItem typeItem)
        {
            if (typeItem == null)
                return null;

            String typeName = McoreUtil.getTypeName(typeItem);
            if (typeName != null)
            {
                switch (typeName)
                {
                    case "Boolean": //$NON-NLS-1$
                        BooleanValue bool = McoreFactory.eINSTANCE.createBooleanValue();
                        bool.setValue(false);
                        return bool;
                    case "String": //$NON-NLS-1$
                        StringValue str = McoreFactory.eINSTANCE.createStringValue();
                        str.setValue(""); //$NON-NLS-1$
                        return str;
                    case "Number": //$NON-NLS-1$
                        NumberValue num = McoreFactory.eINSTANCE.createNumberValue();
                        num.setValue(BigDecimal.ZERO);
                        return num;
                    case "Date": //$NON-NLS-1$
                        return McoreFactory.eINSTANCE.createDateValue();
                    case "FixedArray": //$NON-NLS-1$
                        return McoreFactory.eINSTANCE.createFixedArrayValue();
                    default:
                        break;
                }
            }

            String category = McoreUtil.getTypeCategory(typeItem);
            if (category != null && REFERENCE_CATEGORIES.contains(category))
            {
                EmptyRef emptyRef = getEmptyRef(typeItem);
                if (emptyRef == null)
                {
                    ChoiceParametersDebug.log("createDefault: no EmptyRef for " + category); //$NON-NLS-1$
                    return null;
                }
                ReferenceValue ref = McoreFactory.eINSTANCE.createReferenceValue();
                ref.setValue(emptyRef);
                return ref;
            }

            ChoiceParametersDebug.log("createDefault: unsupported type " //$NON-NLS-1$
                    + ChoiceParametersDebug.quote(typeName) + " / " + ChoiceParametersDebug.quote(category)); //$NON-NLS-1$
            return null;
        }

        boolean sameValueType(Value current, TypeItem expectedType)
        {
            if (current == null || expectedType == null)
                return false;

            String expectedKey = typeKey(expectedType);
            if (expectedKey == null)
                return false;

            if (current instanceof ReferenceValue)
            {
                if (!isReferenceKey(expectedKey))
                    return false;
                MdObject curMd = resolveMdObject(((ReferenceValue) current).getValue());
                MdObject expMd = EcoreUtil2.getContainerOfType(expectedType, MdObject.class);
                return curMd != null && expMd != null && (curMd == expMd || curMd.equals(expMd));
            }

            String currentKey = valueTypeKey(current);
            return currentKey != null && currentKey.equals(expectedKey);
        }

        private static String typeKey(TypeItem typeItem)
        {
            String typeName = McoreUtil.getTypeName(typeItem);
            if (typeName != null && !typeName.isEmpty())
                return typeName;
            return McoreUtil.getTypeCategory(typeItem);
        }

        private static boolean isReferenceKey(String key)
        {
            return key != null && REFERENCE_CATEGORIES.contains(key);
        }

        private static String valueTypeKey(Value value)
        {
            if (value instanceof BooleanValue)
                return "Boolean"; //$NON-NLS-1$
            if (value instanceof StringValue)
                return "String"; //$NON-NLS-1$
            if (value instanceof NumberValue)
                return "Number"; //$NON-NLS-1$
            if (value instanceof DateValue)
                return "Date"; //$NON-NLS-1$
            if (value instanceof FixedArrayValue)
                return "FixedArray"; //$NON-NLS-1$
            return null;
        }

        private static EmptyRef getEmptyRef(TypeItem typeItem)
        {
            MdObject md = EcoreUtil2.getContainerOfType(typeItem, MdObject.class);
            if (md == null)
                return null;

            EStructuralFeature feature = md.eClass().getEStructuralFeature("producedTypes"); //$NON-NLS-1$
            if (feature == null)
                return null;

            Object produced = md.eGet(feature);
            if (produced instanceof BasicDbObjectTypes)
                return ((BasicDbObjectTypes) produced).getRefType().getEmptyRef();
            if (produced instanceof EnumTypes)
                return ((EnumTypes) produced).getRefType().getEmptyRef();
            return null;
        }

        private static MdObject resolveMdObject(EObject ref)
        {
            if (ref == null)
                return null;
            return EcoreUtil2.getContainerOfType(ref, MdObject.class);
        }
    }

}
