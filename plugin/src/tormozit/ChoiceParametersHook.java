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
import org.eclipse.jface.fieldassist.ContentProposal;
import org.eclipse.jface.fieldassist.ContentProposalAdapter;
import org.eclipse.jface.fieldassist.IContentProposal;
import org.eclipse.jface.fieldassist.IContentProposalProvider;
import org.eclipse.jface.viewers.ColumnViewer;
import org.eclipse.jface.viewers.ColumnViewerEditor;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationEvent;
import org.eclipse.jface.viewers.ColumnViewerEditorActivationListener;
import org.eclipse.jface.viewers.ColumnViewerEditorDeactivationEvent;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.jface.viewers.ComboBoxViewerCellEditor;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import com._1c.g5.v8.bm.integration.IBmModel;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.window.Window;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionChoiceParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionChoiceParameters;
import com._1c.g5.v8.dt.dcs.model.core.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.core.DesignTimeValue;
import com._1c.g5.v8.dt.dcs.model.core.DesignTimeValueValue;
import com._1c.g5.v8.dt.dcs.ui.util.DcsUiUtil;
import com._1c.g5.v8.dt.dcs.ui.valueeditors.AvailableFieldInfoProvider;
import com._1c.g5.v8.dt.dcs.util.DcsUtil;
import com._1c.g5.v8.dt.md.ui.aef.viewModels.MdAefFactory;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField;
import com._1c.g5.v8.dt.dcs.typedvalue.TypedValueFactory;
import com._1c.g5.v8.dt.dcs.ui.valueeditors.DesignTimeValueEditor;
import com._1c.g5.v8.dt.dcs.ui.valueeditors.MultiTypedEditor;
import com._1c.g5.v8.dt.dcs.ui.valueeditors.ValueEditControl;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory;
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
 * Из конструктора СКД вместо своего диалога открывает тот же диалог реквизита ({@link DcsDialogReplacer}).
 */
public class ChoiceParametersHook implements IStartup
{
    private static final String PATCHED_KEY = "tormozit.choiceParamsPatched"; //$NON-NLS-1$
    private static final String SESSION_KEY = "tormozit.choiceParamsSession"; //$NON-NLS-1$
    private static final String DIALOG_TITLE =
            "Редактирование параметров выбора"; //$NON-NLS-1$
    private static final String DIALOG_CLASS =
            "com._1c.g5.v8.dt.md.ui.aef.components.choiceparameters.ChoiceParametersDialog"; //$NON-NLS-1$

    private static final String DCS_DIALOG_CLASS =
            "com._1c.g5.v8.dt.dcs.ui.valueeditors.choiceparameters.ChoiceParametersDialog"; //$NON-NLS-1$
    /** Окно связей параметров выбора СКД — остаётся штатным (дерево доступных полей схемы), дополняется списком имён. */
    private static final String DCS_LINKS_DIALOG_CLASS =
            "com._1c.g5.v8.dt.dcs.ui.valueeditors.choiceparameterlinks.ChoiceParameterLinksDialog"; //$NON-NLS-1$

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
            // SWT.Show приходит до ShowWindow — окно СКД ещё не на экране
            if (event.type == SWT.Show && DcsDialogReplacer.tryReplace(shell))
                return;
            schedulePatchAttempt(display, shell, 0);
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean isChoiceParametersShell(Shell shell)
    {
        Object data = shell.getData();
        if (data != null && (DIALOG_CLASS.equals(data.getClass().getName())
                || DCS_LINKS_DIALOG_CLASS.equals(data.getClass().getName())))
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
        if (dialog != null && DCS_DIALOG_CLASS.equals(dialog.getClass().getName()))
            return DcsNameProposals.tryPatch(shell, dialog, false);
        if (dialog != null && DCS_LINKS_DIALOG_CLASS.equals(dialog.getClass().getName()))
            return DcsNameProposals.tryPatch(shell, dialog, true);
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

        /**
         * Колонка «Имя» — выпадающий список, создаваемый только при активации ячейки: первый щелчок
         * по кнопке «⌄» неактивной ячейки лишь создаёт редактор, кнопка появляется под курсором позже.
         * Если активирующий щелчок пришёлся в область кнопки — раскрываем список сразу.
         */
        private final ColumnViewerEditorActivationListener openListOnArrowClick =
                new ColumnViewerEditorActivationListener()
        {
            @Override
            public void afterEditorActivated(ColumnViewerEditorActivationEvent event)
            {
                if (event.eventType != ColumnViewerEditorActivationEvent.MOUSE_CLICK_SELECTION
                        || !(event.sourceEvent instanceof MouseEvent)
                        || !(event.getSource() instanceof ViewerCell)
                        || ((ViewerCell) event.getSource()).getColumnIndex() != 0)
                    return;
                Object cellEditor = Global.getField(viewer.getColumnViewerEditor(), "cellEditor"); //$NON-NLS-1$
                if (!(cellEditor instanceof ComboBoxViewerCellEditor))
                    return;
                Control control = ((ComboBoxViewerCellEditor) cellEditor).getControl();
                if (!(control instanceof CCombo) || control.isDisposed())
                    return;
                CCombo combo = (CCombo) control;
                Object arrow = Global.getField(combo, "arrow"); //$NON-NLS-1$
                if (!(arrow instanceof Control))
                    return;
                Rectangle arrowBounds = ((Control) arrow).getBounds();
                Rectangle comboBounds = combo.getBounds();
                MouseEvent mouse = (MouseEvent) event.sourceEvent;
                Point click = combo.getParent().toControl(((Control) mouse.widget).toDisplay(mouse.x, mouse.y));
                if (!comboBounds.contains(click) || click.x < comboBounds.x + arrowBounds.x)
                    return;
                combo.getDisplay().asyncExec(() ->
                {
                    if (!combo.isDisposed() && combo.isVisible() && !combo.getListVisible())
                        combo.setListVisible(true);
                });
            }

            @Override
            public void afterEditorDeactivated(ColumnViewerEditorDeactivationEvent event) {}

            @Override
            public void beforeEditorActivated(ColumnViewerEditorActivationEvent event) {}

            @Override
            public void beforeEditorDeactivated(ColumnViewerEditorDeactivationEvent event) {}
        };

        void install()
        {
            if (viewer.getColumnViewerEditor() != null)
                viewer.getColumnViewerEditor().addEditorActivationListener(openListOnArrowClick);
            EObject viewModelObject = asEObject(viewModel);
            if (viewModelObject == null)
                return;
            viewModelObject.eAdapters().add(viewModelAdapter);
            for (ChoiceParameterItem item : viewModel.getItems())
                attachItemAdapter(item);
        }

        void dispose()
        {
            if (viewer.getColumnViewerEditor() != null)
                viewer.getColumnViewerEditor().removeEditorActivationListener(openListOnArrowClick);
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
     * Вместо диалога параметров выбора конструктора СКД открывает диалог реквизита
     * ({@code md.ui} {@code ChoiceParametersDialog}) — та же задача, одно окно.
     * <p>
     * Штатный вызывающий ({@code dcs.ui ChoiceParametersEditor$1}) передаёт диалогу копию параметров
     * ({@code values}) и при коде OK забирает её. Поэтому в {@code SWT.Show} (окно ещё не показано)
     * открываем диалог реквизита, результат пишем в {@code values} и закрываем диалог СКД с тем же кодом.
     * <p>
     * Ссылки СКД хранит строкой ({@link DesignTimeValueValue}, напр. {@code Справочник.Валюты.ПустаяСсылка});
     * обратного преобразования в объект EDT не даёт — объект метаданных ищется как в
     * {@code DesignTimeValueEditor.getMdObjectByName}, элемент — по имени. Если хоть одно значение
     * однозначно не преобразуется — остаётся штатный диалог СКД.
     */
    private static final class DcsDialogReplacer
    {
        private static final String EMPTY_REF_RU = "ПустаяСсылка"; //$NON-NLS-1$
        private static final String EMPTY_REF = "EmptyRef"; //$NON-NLS-1$
        private static final String MD_UI_BUNDLE = "com._1c.g5.v8.dt.md.ui"; //$NON-NLS-1$

        private DcsDialogReplacer() {}

        static boolean tryReplace(Shell shell)
        {
            Object dialog = shell.getData();
            if (dialog == null || !DCS_DIALOG_CLASS.equals(dialog.getClass().getName()))
                return false;
            try
            {
                return replace(shell, (Window) dialog);
            }
            catch (RuntimeException | LinkageError e)
            {
                ChoiceParametersDebug.log("dcs replace FAIL " + e); //$NON-NLS-1$
                return false;
            }
        }

        private static boolean replace(Shell shell, Window dcsDialog)
        {
            Object values = Global.getField(dcsDialog, "values"); //$NON-NLS-1$
            IV8Project v8Project = (IV8Project) Global.getField(dcsDialog, "v8project"); //$NON-NLS-1$
            Object parentField = Global.getField(dcsDialog, "parentField"); //$NON-NLS-1$
            if (!(values instanceof DataCompositionChoiceParameters) || v8Project == null)
                return false;
            DataCompositionChoiceParameters parameters = (DataCompositionChoiceParameters) values;

            List<ChoiceParameterItem> items = new ArrayList<>();
            for (DataCompositionChoiceParameter parameter : parameters.getItems())
            {
                ChoiceParameterItem item = MdAefFactory.eINSTANCE.createChoiceParameterItem();
                Global.invokeVoid(item, "setText", parameter.getName()); //$NON-NLS-1$
                Value value = toMdValue(parameter.getValue(), v8Project);
                if (value == null && !parameter.getValue().isEmpty())
                {
                    ChoiceParametersDebug.log("dcs replace skip: value of " //$NON-NLS-1$
                            + ChoiceParametersDebug.quote(parameter.getName()));
                    return false;
                }
                item.setValue(value);
                items.add(item);
            }

            TypeDescription ownerType = ownerType(parentField, v8Project);
            EObject contextObject = DcsUtil.getContextObject(v8Project);
            ChoiceParametersViewModel viewModel = MdAefFactory.eINSTANCE.createChoiceParametersViewModel();
            viewModel.setRecord(new ValueRecord(ownerType, null, null, contextObject));
            viewModel.getItems().addAll(items);
            if (ownerType != null)
                viewModel.getCompletion().addAll(DcsNameProposals.collectFields(ownerType, v8Project).keySet());
            Global.invokeVoid(viewModel, "setEditable", Boolean.TRUE); //$NON-NLS-1$
            ChoiceParametersDebug.log("dcs replace open items=" + items.size() //$NON-NLS-1$
                    + " completion=" + viewModel.getCompletion().size() //$NON-NLS-1$
                    + " owner=" + (parentField == null ? "null" : parentField.getClass().getSimpleName())); //$NON-NLS-1$ //$NON-NLS-2$

            Shell parentShell = shell.getParent() instanceof Shell ? (Shell) shell.getParent() : null;
            Window mdDialog = createMdDialog(parentShell, viewModel, v8Project);
            if (mdDialog == null)
                return false;
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            int code = mdDialog.open();
            Object resultItems = Global.invoke(mdDialog, "getItems"); //$NON-NLS-1$
            if (code == Window.OK && resultItems instanceof java.util.Collection)
            {
                parameters.getItems().clear();
                for (Object element : (java.util.Collection<?>) resultItems)
                {
                    if (!(element instanceof ChoiceParameterItem))
                        continue;
                    ChoiceParameterItem item = (ChoiceParameterItem) element;
                    DataCompositionChoiceParameter parameter = DcsFactory.eINSTANCE.createDataCompositionChoiceParameter();
                    parameter.setName(PatchSession.getItemText(item));
                    parameter.getValue().addAll(toDcsValues(item.getValue(), v8Project));
                    parameters.getItems().add(parameter);
                }
            }
            ChoiceParametersDebug.log("dcs replace closed code=" + code); //$NON-NLS-1$
            Global.invokeVoid(dcsDialog, "setReturnCode", Integer.valueOf(code)); //$NON-NLS-1$
            dcsDialog.close();
            return true;
        }

        /** Пакет диалога реквизита бандлом {@code md.ui} не экспортирован — класс берём из самого бандла. */
        private static Window createMdDialog(Shell parentShell, ChoiceParametersViewModel viewModel,
                IV8Project v8Project)
        {
            try
            {
                org.osgi.framework.Bundle bundle = org.eclipse.core.runtime.Platform.getBundle(MD_UI_BUNDLE);
                if (bundle == null)
                    return null;
                Class<?> dialogClass = bundle.loadClass(DIALOG_CLASS);
                java.lang.reflect.Constructor<?> constructor = dialogClass.getConstructor(
                        Shell.class, ChoiceParametersViewModel.class, IV8Project.class);
                return (Window) constructor.newInstance(parentShell, viewModel, v8Project);
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e)
            {
                ChoiceParametersDebug.log("dcs createMdDialog FAIL " + e); //$NON-NLS-1$
                return null;
            }
        }

        /** Тип владельца параметров выбора: поле набора данных — как штатно, по доступным полям схемы. */
        private static TypeDescription ownerType(Object parentField, IV8Project v8Project)
        {
            TypeDescription type = null;
            if (parentField instanceof DataCompositionSchemaDataSetField)
            {
                Object fieldUse = Global.invoke(DcsUiUtil.getSettingsProvider(), "getAvailableFieldsUse"); //$NON-NLS-1$
                Object info = Global.invoke(new AvailableFieldInfoProvider(), "getAvailableFieldInfo", //$NON-NLS-1$
                        ((DataCompositionSchemaDataSetField) parentField).getDataPath(), fieldUse);
                Object source = info == null ? null : Global.getField(info, "valueType"); //$NON-NLS-1$
                Object td = source == null ? null : Global.invoke(source, "toTypeDescription"); //$NON-NLS-1$
                if (td instanceof TypeDescription)
                    type = (TypeDescription) td;
            }
            if (type == null && parentField != null)
            {
                Object td = Global.invoke(parentField, "getValueType"); //$NON-NLS-1$
                if (!(td instanceof TypeDescription))
                    td = Global.invoke(parentField, "getType"); //$NON-NLS-1$
                if (td instanceof TypeDescription)
                    type = (TypeDescription) td;
            }
            if (type == null || McorePackage.Literals.TYPE_DESCRIPTION__TYPES.isContainment())
                return type;
            // диалогу реквизита нужны разрешённые типы (поиск FieldSource) — копия со ссылками на них
            IBmModel bmModel = v8Project.getAdapter(IBmModel.class);
            if (bmModel == null)
                return type;
            TypeDescription resolved = McoreFactory.eINSTANCE.createTypeDescription();
            for (TypeItem item : type.getTypes())
                resolved.getTypes().add((TypeItem) EcoreUtil.resolve(item, bmModel.getEngine().getResourceSet()));
            return resolved;
        }

        private static Value toMdValue(List<Value> values, IV8Project v8Project)
        {
            if (values.isEmpty())
                return null;
            if (values.size() == 1)
                return toMdValue(values.get(0), v8Project);
            FixedArrayValue array = McoreFactory.eINSTANCE.createFixedArrayValue();
            for (Value value : values)
            {
                Value converted = toMdValue(value, v8Project);
                if (converted == null)
                    return null;
                array.getValues().add(converted);
            }
            return array;
        }

        private static Value toMdValue(Value value, IV8Project v8Project)
        {
            if (value instanceof StringValue || value instanceof NumberValue || value instanceof DateValue
                    || value instanceof BooleanValue)
                return EcoreUtil.copy(value);
            if (!(value instanceof DesignTimeValueValue))
                return null;
            DesignTimeValue designTime = ((DesignTimeValueValue) value).getValue();
            String text = designTime == null ? null : designTime.getValue();
            int dot = text == null ? -1 : text.lastIndexOf('.');
            if (dot <= 0)
                return null;
            Object md = Global.invoke(DesignTimeValueEditor.class, "getMdObjectByName", //$NON-NLS-1$
                    text.substring(0, dot), v8Project);
            if (!(md instanceof MdObject))
                return null;
            EObject target = findRefTarget((MdObject) md, text.substring(dot + 1));
            if (target == null)
                return null;
            ReferenceValue ref = McoreFactory.eINSTANCE.createReferenceValue();
            ref.setValue(target);
            return ref;
        }

        private static EObject findRefTarget(MdObject md, String name)
        {
            if (EMPTY_REF_RU.equals(name) || EMPTY_REF.equals(name))
                return ChoiceParameterValueFactory.getEmptyRef(md);
            EObject found = null;
            for (java.util.Iterator<EObject> it = md.eAllContents(); it.hasNext();)
            {
                EObject child = it.next();
                String childName = child instanceof PredefinedItem ? ((PredefinedItem) child).getName()
                        : child instanceof com._1c.g5.v8.dt.metadata.mdclass.EnumValue
                                ? ((com._1c.g5.v8.dt.metadata.mdclass.EnumValue) child).getName() : null;
                if (!name.equals(childName))
                    continue;
                if (found != null)
                    return null; // неоднозначно
                found = child;
            }
            return found;
        }

        private static List<Value> toDcsValues(Value value, IV8Project v8Project)
        {
            List<Value> result = new ArrayList<>();
            if (value instanceof FixedArrayValue)
            {
                for (Value element : ((FixedArrayValue) value).getValues())
                    result.addAll(toDcsValues(element, v8Project));
            }
            else if (value instanceof ReferenceValue)
            {
                EObject target = ((ReferenceValue) value).getValue();
                MdObject md = target == null ? null : EcoreUtil2.getContainerOfType(target, MdObject.class);
                if (md != null)
                    result.add(DesignTimeValueEditor.createValue(target, md, v8Project));
                else
                    ChoiceParametersDebug.log("dcs toDcs skip ref " + target); //$NON-NLS-1$
            }
            else if (value != null)
                result.add(EcoreUtil.copy(value));
            return result;
        }
    }

    /**
     * Список выбора в поле «Имя» диалога параметров выбора конструктора СКД. Штатный диалог
     * ({@code dcs.ui}) знает только про поле набора данных; для остальных владельцев (параметр схемы,
     * вычисляемое поле и т.п.) список пуст, хотя владелец имеет тип значения.
     * Диалог получает лишь владельца модели ({@code parentField}) — тип берём у него.
     */
    private static final class DcsNameProposals
    {
        private DcsNameProposals() {}

        /**
         * @param links окно связей параметров выбора: штатный список имён там тоже пуст (для поля набора данных
         *        берётся {@code getField()} вместо пути к данным), поэтому подключаемся для любого владельца,
         *        а тип значения при смене имени не трогаем — там его нет
         */
        static boolean tryPatch(Shell shell, Object dialog, boolean links)
        {
            ColumnViewer viewer = (ColumnViewer) Global.getField(dialog, "viewer"); //$NON-NLS-1$
            IV8Project v8Project = (IV8Project) Global.getField(dialog, "v8project"); //$NON-NLS-1$
            Object parentField = Global.getField(dialog, "parentField"); //$NON-NLS-1$
            if (viewer == null || v8Project == null)
                return false;
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            if (!links && parentField instanceof DataCompositionSchemaDataSetField)
                return true;
            Map<String, Field> fields = new java.util.TreeMap<>();
            boolean[] computed = { false };
            java.util.function.Supplier<Map<String, Field>> fieldsLazy = () ->
            {
                if (!computed[0])
                {
                    computed[0] = true;
                    TypeDescription ownerType = DcsDialogReplacer.ownerType(parentField, v8Project);
                    if (ownerType != null)
                        fields.putAll(collectFields(ownerType, v8Project));
                    ChoiceParametersDebug.log("dcs names=" + fields.size() + " links=" + links); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return fields;
            };
            if (!links)
                installValueTypeReset(shell, dialog, viewer, v8Project, fieldsLazy);
            Object viewerEditor = Global.getField(viewer, "viewerEditor"); //$NON-NLS-1$
            if (!(viewerEditor instanceof ColumnViewerEditor))
                return true;
            List<String> names = new ArrayList<>();
            ((ColumnViewerEditor) viewerEditor).addEditorActivationListener(new ColumnViewerEditorActivationListener()
            {
                @Override
                public void afterEditorActivated(ColumnViewerEditorActivationEvent event)
                {
                    Object cell = Global.getField(viewerEditor, "cell"); //$NON-NLS-1$
                    if (!(cell instanceof ViewerCell) || ((ViewerCell) cell).getColumnIndex() != 0)
                        return;
                    Object cellEditor = Global.getField(viewerEditor, "cellEditor"); //$NON-NLS-1$
                    Object control = cellEditor == null ? null : Global.getField(cellEditor, "editControl"); //$NON-NLS-1$
                    if (!(control instanceof ValueEditControl))
                        return;
                    if (names.isEmpty())
                        names.addAll(fieldsLazy.get().keySet());
                    if (names.isEmpty())
                        return;
                    IContentProposalProvider provider = new IContentProposalProvider()
                    {
                        @Override
                        public IContentProposal[] getProposals(String contents, int position)
                        {
                            String filter = contents == null ? "" : contents.toLowerCase(); //$NON-NLS-1$
                            List<IContentProposal> result = new ArrayList<>();
                            for (String name : names)
                            {
                                if (filter.isEmpty() || name.toLowerCase().contains(filter))
                                    result.add(new ContentProposal(name));
                            }
                            return result.toArray(new IContentProposal[0]);
                        }
                    };
                    ValueEditControl editControl = (ValueEditControl) control;
                    editControl.setProposalProvider(provider);
                    // штатный StringEditor может выставить свой (пустой) провайдер уже после активации
                    editControl.getDisplay().asyncExec(() ->
                    {
                        if (editControl.isDisposed())
                            return;
                        editControl.setProposalProvider(provider);
                        // штатно автоактивация выключена (пустой набор символов) — список только по Ctrl+Space;
                        // для списка выбора открываем сразу и по любому набранному символу
                        Object adapter = Global.getField(editControl, "proposalAdapter"); //$NON-NLS-1$
                        if (adapter instanceof ContentProposalAdapter)
                        {
                            ContentProposalAdapter proposalAdapter = (ContentProposalAdapter) adapter;
                            proposalAdapter.setAutoActivationCharacters(null);
                            if (!proposalAdapter.isProposalPopupOpen())
                                proposalAdapter.openProposalPopup();
                        }
                    });
                    // кнопка «...» штатного StringEditor открывает ProposalsDialog по его собственному
                    // провайдеру (штатный — пуст для владельцев кроме поля набора данных)
                    Object stringEditor = Global.getField(cellEditor, "currentEditor"); //$NON-NLS-1$
                    if (stringEditor != null
                            && Global.getField(stringEditor, "proposalProvider") instanceof IContentProposalProvider) //$NON-NLS-1$
                        Global.setFieldForce(stringEditor, "proposalProvider", provider); //$NON-NLS-1$
                }

                @Override
                public void afterEditorDeactivated(ColumnViewerEditorDeactivationEvent event) {}

                @Override
                public void beforeEditorActivated(ColumnViewerEditorActivationEvent event) {}

                @Override
                public void beforeEditorDeactivated(ColumnViewerEditorDeactivationEvent event) {}
            });
            return true;
        }

        /**
         * Смена имени параметра → значение пустого значения типа поля, как штатно для поля набора данных
         * ({@code ChoiceParametersEditingSupport.setValue}, колонка имени).
         */
        private static void installValueTypeReset(Shell shell, Object dialog, ColumnViewer viewer,
                IV8Project v8Project, java.util.function.Supplier<Map<String, Field>> fields)
        {
            Object values = Global.getField(dialog, "values"); //$NON-NLS-1$
            if (!(values instanceof EObject))
                return;
            EContentAdapter adapter = new EContentAdapter()
            {
                @Override
                public void notifyChanged(Notification notification)
                {
                    super.notifyChanged(notification);
                    if (notification.getEventType() != Notification.SET
                            || !(notification.getNotifier() instanceof DataCompositionChoiceParameter)
                            || !(notification.getFeature() instanceof EStructuralFeature)
                            || !"name".equals(((EStructuralFeature) notification.getFeature()).getName())) //$NON-NLS-1$
                        return;
                    Object newName = notification.getNewValue();
                    if (newName == null || newName.equals(notification.getOldValue()))
                        return;
                    Field field = fields.get().get(newName);
                    Value value = field == null ? null : createDefaultValue(field, v8Project);
                    ChoiceParametersDebug.log("dcs name set " + ChoiceParametersDebug.quote(String.valueOf(newName)) //$NON-NLS-1$
                            + " value=" + (value == null ? "null" : value.getClass().getSimpleName())); //$NON-NLS-1$ //$NON-NLS-2$
                    if (value == null)
                        return;
                    DataCompositionChoiceParameter parameter = (DataCompositionChoiceParameter) notification.getNotifier();
                    parameter.getValue().clear();
                    parameter.getValue().add(value);
                    Display display = viewer.getControl().getDisplay();
                    display.asyncExec(() ->
                    {
                        if (!viewer.getControl().isDisposed())
                            viewer.refresh();
                    });
                }
            };
            ((EObject) values).eAdapters().add(adapter);
            shell.addDisposeListener(e -> ((EObject) values).eAdapters().remove(adapter));
        }

        private static Value createDefaultValue(Field field, IV8Project v8Project)
        {
            TypeDescription type = field.getType();
            if (type == null || type.getTypes().isEmpty())
                return null;
            try
            {
                TypeItem first = type.getTypes().get(0);
                IBmModel bmModel = v8Project.getAdapter(IBmModel.class);
                if (bmModel != null)
                    first = (TypeItem) org.eclipse.emf.ecore.util.EcoreUtil.resolve(first,
                            bmModel.getEngine().getResourceSet());
                if (MultiTypedEditor.isRefType(first, v8Project))
                {
                    MdObject md = EcoreUtil2.getContainerOfType(first, MdObject.class);
                    return md == null ? null
                            : DesignTimeValueEditor.createValue(MdTypeFactory.eINSTANCE.createEmptyRef(), md, v8Project);
                }
                return TypedValueFactory.INSTANCE.createValue(type);
            }
            catch (RuntimeException e)
            {
                ChoiceParametersDebug.log("dcs createDefaultValue FAIL " + e); //$NON-NLS-1$
                return null;
            }
        }

        static Map<String, Field> collectFields(TypeDescription valueType, IV8Project v8Project)
        {
            IBmModel bmModel = v8Project.getAdapter(IBmModel.class);
            String prefix = v8Project.getScriptVariant() == ScriptVariant.ENGLISH ? "Filter." : "Отбор."; //$NON-NLS-1$ //$NON-NLS-2$
            ILabelProvider labels = new FieldLabelProvider(new ScriptVariantProvider(v8Project));
            Map<String, Field> result = new java.util.TreeMap<>();
            try
            {
                for (TypeItem item : ((TypeDescription) valueType).getTypes())
                {
                    EObject resolved = bmModel == null ? item
                            : org.eclipse.emf.ecore.util.EcoreUtil.resolve(item, bmModel.getEngine().getResourceSet());
                    if (!(resolved instanceof Type) || !(resolved.eContainer() instanceof MdRefType))
                        continue;
                    FieldSource source = EcoreUtil2.getContainerOfType(resolved, FieldSource.class);
                    if (source == null)
                        continue;
                    Object[] elements = IChoiceParametersModel.COMPLETION_PROVIDER.getElements(source);
                    if (elements == null)
                        continue;
                    for (Object element : elements)
                    {
                        if (!(element instanceof Field))
                            continue;
                        String label = labels.getText(element);
                        if (label != null && !label.isEmpty())
                            result.putIfAbsent(prefix + label, (Field) element);
                    }
                }
            }
            finally
            {
                labels.dispose();
            }
            return result;
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
            return getEmptyRef(EcoreUtil2.getContainerOfType(typeItem, MdObject.class));
        }

        static EmptyRef getEmptyRef(MdObject md)
        {
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
