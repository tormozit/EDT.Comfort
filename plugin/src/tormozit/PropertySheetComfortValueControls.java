package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

/** Создание value-контролов Comfort UI по типу AEF ValueViewModel. */
final class PropertySheetComfortValueControls
{
    enum Kind
    {
        TEXT,
        ACTION_BAR,
        BOOLEAN,
        COMBO,
        HYPERLINK,
        SPINNER
    }

    private static final String ACTION_BAR_TEXT_KEY = "comfort.actionBar.text"; //$NON-NLS-1$
    private static final String ACTION_BAR_BUTTONS_KEY = "comfort.actionBar.buttons"; //$NON-NLS-1$
    private static final String ACTION_BAR_NATIVE_KEY = "comfort.actionBar.native"; //$NON-NLS-1$
    private static final String ACTION_BAR_VIEW_KEY = "comfort.actionBar.view"; //$NON-NLS-1$
    private static final String ACTION_BAR_VM_KEY = "comfort.actionBar.vm"; //$NON-NLS-1$
    private static final String COMBO_FIELD_ACTIVE_KEY = "comfort.combo.active"; //$NON-NLS-1$
    private static final String COMBO_TEXT_KEY = "comfort.combo.text"; //$NON-NLS-1$
    private static final String COMBO_ARROW_KEY = "comfort.combo.arrow"; //$NON-NLS-1$
    private static final String COMBO_ITEMS_KEY = "comfort.combo.items"; //$NON-NLS-1$
    private static final String COMBO_POPUP_KEY = "comfort.combo.popup"; //$NON-NLS-1$
    private static final String COMBO_POPUP_WIRED_KEY = "comfort.combo.popupWired"; //$NON-NLS-1$
    private static final String COMBO_BORDER_KEY = "comfort.combo.border"; //$NON-NLS-1$
    private static final String NATIVE_MIRROR_WIRED_KEY = "comfort.native.mirrorWired"; //$NON-NLS-1$
    private static final String TEXT_VERIFY_GUARD_KEY = "comfort.text.verifyGuard"; //$NON-NLS-1$
    private static final String TEXT_SELECT_ONLY_KEY = "comfort.text.selectOnly"; //$NON-NLS-1$
    private static final String TEXT_PROGRAMMATIC_KEY = "comfort.text.programmatic"; //$NON-NLS-1$
    private static final String FIELD_ACTIVE_KEY = "comfort.field.active"; //$NON-NLS-1$
    private static final String EDITABLE_BG_CACHE_KEY = "comfort.editable.bg"; //$NON-NLS-1$
    private static final int COMBO_ARROW_WIDTH = 18;
    /** Высота панели COMBO/ACTION_BAR (как у перечисления на «Старой» вкладке). */
    private static final int VALUE_FIELD_HEIGHT = 23;
    /** Высота текста/кнопок внутри панели (с учётом margin и рамки). */
    private static final int VALUE_FIELD_INNER_HEIGHT = VALUE_FIELD_HEIGHT - 4;
    private static final int ACTION_BAR_BUTTON_SIZE = COMBO_ARROW_WIDTH;

    static final class Created
    {
        final Kind kind;
        final Control control;
        final String displayValue;

        Created(Kind kind, Control control, String displayValue)
        {
            this.kind = kind;
            this.control = control;
            this.displayValue = displayValue != null ? displayValue : ""; //$NON-NLS-1$
        }
    }

    private PropertySheetComfortValueControls() {}

    static final class KindDecision
    {
        final Kind kind;
        final String reason;

        KindDecision(Kind kind, String reason)
        {
            this.kind = kind;
            this.reason = reason != null ? reason : ""; //$NON-NLS-1$
        }
    }

    static Kind detectKind(Object valueVm, Object valueView, Control nativeValue, String displayValue)
    {
        return detectKindDetailed(valueVm, valueView, nativeValue, displayValue).kind;
    }

    static KindDecision detectKindDetailed(Object valueVm, Object valueView, Control nativeValue,
            String displayValue)
    {
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        if (nativeValue != null && !nativeValue.isDisposed())
        {
            if (hasActionBarButtons(valueVm, valueView, nativeValue))
                return new KindDecision(Kind.ACTION_BAR, "actionBar:buttons"); //$NON-NLS-1$
            if (nativeValue instanceof Link)
                return new KindDecision(Kind.HYPERLINK, "native:Link"); //$NON-NLS-1$
            if (nativeValue instanceof Text)
                return new KindDecision(Kind.TEXT, "native:Text"); //$NON-NLS-1$
            Kind fromNative = detectKindFromControl(nativeValue);
            if (fromNative != Kind.TEXT)
                return new KindDecision(fromNative, "native:" + fromNative); //$NON-NLS-1$
        }
        Kind fromView = kindFromView(valueView);
        if (fromView != null)
            return new KindDecision(fromView, "view:" + PropertySheetDebug.typeName(valueView)); //$NON-NLS-1$
        if (isTextType(valueVm, valueView, model))
            return new KindDecision(Kind.TEXT, "vmText:" + PropertySheetDebug.typeName(valueVm)); //$NON-NLS-1$
        if (isLinkType(valueVm, valueView, model))
            return new KindDecision(Kind.HYPERLINK, "vmLink:" + PropertySheetDebug.typeName(valueVm)); //$NON-NLS-1$
        if (model != null && Global.invoke(model, "getValue") instanceof Boolean) //$NON-NLS-1$
            return new KindDecision(Kind.BOOLEAN, "model:Boolean"); //$NON-NLS-1$
        Kind fromNativeRow = detectKindFromNativeRow(nativeValue);
        if (fromNativeRow != null)
            return new KindDecision(fromNativeRow, "nativeRow:" + fromNativeRow); //$NON-NLS-1$
        if (hasComboItems(model, valueVm) || isComboType(valueVm, valueView, model))
            return new KindDecision(Kind.COMBO, "comboType"); //$NON-NLS-1$
        if (isBooleanType(valueVm, valueView, model))
            return new KindDecision(Kind.BOOLEAN, "boolType"); //$NON-NLS-1$
        if (isNumericType(valueVm, valueView, model, displayValue))
            return new KindDecision(Kind.SPINNER, "numeric"); //$NON-NLS-1$
        if (hasActionBarButtons(valueVm, valueView, nativeValue))
            return new KindDecision(Kind.ACTION_BAR, "actionBar:vm"); //$NON-NLS-1$
        return new KindDecision(Kind.TEXT, "default"); //$NON-NLS-1$
    }

    static Created create(Composite parent, PropertySheetViewModelTree.Entry entry,
            Control nativeValue, String displayText)
    {
        return create(parent, entry, nativeValue, displayText, ""); //$NON-NLS-1$
    }

    static Created create(Composite parent, PropertySheetViewModelTree.Entry entry,
            Control nativeValue, String displayText, String resolvePath)
    {
        return create(parent, entry, nativeValue, displayText, resolvePath, 0);
    }

    static Created create(Composite parent, PropertySheetViewModelTree.Entry entry,
            Control nativeValue, String displayText, String resolvePath, int valueWidthHint)
    {
        return create(parent, entry, nativeValue, displayText, resolvePath, valueWidthHint, null);
    }

    static Created create(Composite parent, PropertySheetViewModelTree.Entry entry,
            Control nativeValue, String displayText, String resolvePath, int valueWidthHint,
            Control nativePaletteRoot)
    {
        return create(parent, entry, nativeValue, displayText, resolvePath, valueWidthHint, nativePaletteRoot, null);
    }

    static Created create(Composite parent, PropertySheetViewModelTree.Entry entry,
            Control nativeValue, String displayText, String resolvePath, int valueWidthHint,
            Control nativePaletteRoot, Object page)
    {
        Object valueVm = entry != null ? entry.valueViewModel : null;
        Object valueView = entry != null ? entry.valueView : null;
        String propName = entry != null ? entry.name : ""; //$NON-NLS-1$
        String display = firstNonEmpty(displayText,
                entry != null && !isOpenPlaceholder(entry.value) ? entry.value : "", //$NON-NLS-1$
                PropertySheetAefValues.readValue(valueVm),
                valueView != null ? PropertySheetControlInterop.displayTextFromView(valueView, valueVm) : "", //$NON-NLS-1$
                resolveModelText(valueVm, valueView), nativeControlText(nativeValue));
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        KindDecision decision = detectKindDetailed(valueVm, valueView, nativeValue, display);
        Kind kind = decision.kind;
        String kindReason = decision.reason;
        if (kind == Kind.HYPERLINK && (isOpenPlaceholder(display) || display.isEmpty()))
            display = "Открыть"; //$NON-NLS-1$
        else if (isOpenPlaceholder(display))
            display = firstNonEmpty(displayText, nativeControlText(nativeValue),
                    PropertySheetAefValues.readValue(valueVm),
                    resolveModelText(valueVm, valueView));
        if (kind == Kind.TEXT && isOpenPlaceholder(display))
            display = firstNonEmpty(displayText, PropertySheetAefValues.readValue(valueVm),
                    resolveModelText(valueVm, valueView));
        Boolean boolFromView = PropertySheetAefValues.readBoolean(valueVm, valueView);
        if (boolFromView != null && kind != Kind.HYPERLINK && kind != Kind.COMBO)
        {
            kind = Kind.BOOLEAN;
            kindReason = "boolFromView"; //$NON-NLS-1$
        }
        else if (isBooleanType(valueVm, valueView, model) && kind == Kind.TEXT)
        {
            kind = Kind.BOOLEAN;
            kindReason = "boolTypeOverride"; //$NON-NLS-1$
        }
        List<String> comboItems = null;
        if (kind == Kind.COMBO)
        {
            comboItems = resolveComboItems(valueVm, valueView, nativeValue);
            if (display.isEmpty())
                display = resolveComboDisplayValue(valueVm, valueView, comboItems);
        }
        logValueRow(propName, kind, kindReason, display, valueVm, valueView, nativeValue, resolvePath);
        logValueControl(propName, kind, display, valueVm, valueView, model, boolFromView, comboItems);
        switch (kind)
        {
            case BOOLEAN:
                return createBoolean(parent, valueVm, valueView, display, nativeValue, boolFromView,
                        nativePaletteRoot, propName, page);
            case COMBO:
                return createCombo(parent, valueVm, valueView, display, nativeValue, comboItems, valueWidthHint,
                        nativePaletteRoot, propName, page);
            case HYPERLINK:
                return createHyperlink(parent, display, valueWidthHint);
            case SPINNER:
                return createSpinner(parent, valueVm, valueView, display, nativeValue, valueWidthHint,
                        nativePaletteRoot, propName, page);
            case ACTION_BAR:
                return createActionBar(parent, display, valueVm, valueView, nativeValue, valueWidthHint,
                        nativePaletteRoot, propName, page);
            default:
                return createText(parent, display,
                        resolveEditable(valueVm, valueView, nativeValue, nativePaletteRoot, propName, page),
                        valueWidthHint);
        }
    }

    static void applyWidthHint(Created created, int valueWidthHint)
    {
        if (created == null || created.control == null || created.control.isDisposed()
                || valueWidthHint <= 0 || created.kind == Kind.BOOLEAN)
            return;
        Object layoutData = created.control.getLayoutData();
        if (layoutData instanceof org.eclipse.swt.layout.GridData)
        {
            org.eclipse.swt.layout.GridData gd = (org.eclipse.swt.layout.GridData) layoutData;
            gd.widthHint = valueWidthHint;
            gd.grabExcessHorizontalSpace = true;
        }
    }

    private static void logValueControl(String propName, Kind kind, String display, Object valueVm,
            Object valueView, Object model, Boolean boolFromView, List<String> comboItems)
    {
        if (propName == null || propName.isEmpty())
            return;
        boolean boolType = isBooleanType(valueVm, valueView, model) || boolFromView != null;
        if (kind == Kind.BOOLEAN)
        {
            PropertySheetDebug.valueControlVerbose("checkbox OK " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                    + " selected=" + boolFromView //$NON-NLS-1$
                    + " vm=" + PropertySheetDebug.safe(valueVm)); //$NON-NLS-1$
            return;
        }
        if (boolType)
        {
            PropertySheetDebug.valueControl("checkbox MISS " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                    + " kind=" + kind //$NON-NLS-1$
                    + " boolFromView=" + boolFromView //$NON-NLS-1$
                    + " vm=" + PropertySheetDebug.safe(valueVm) //$NON-NLS-1$
                    + " view=" + PropertySheetDebug.safe(valueView) //$NON-NLS-1$
                    + " model=" + PropertySheetDebug.safe(model) //$NON-NLS-1$
                    + " types=" + PropertySheetDebug.quote(typeNames(valueVm, valueView, model))); //$NON-NLS-1$
        }
        if (kind == Kind.COMBO)
        {
            int items = comboItems != null ? comboItems.size() : 0;
            if (display == null || display.isEmpty())
            {
                PropertySheetDebug.valueControl("combo EMPTY " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                        + " items=" + items //$NON-NLS-1$
                        + " vm=" + PropertySheetDebug.safe(valueVm) //$NON-NLS-1$
                        + " viewText=" + PropertySheetDebug.quote( //$NON-NLS-1$
                                valueView != null ? PropertySheetControlInterop.displayTextFromView(valueView, valueVm) : "") //$NON-NLS-1$
                        + " modelText=" + PropertySheetDebug.quote(resolveModelText(valueVm, valueView))); //$NON-NLS-1$
            }
            else {
                PropertySheetDebug.valueControlVerbose("combo OK " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                        + " value=" + PropertySheetDebug.quote(display)); //$NON-NLS-1$
            }
        }
        if (kind == Kind.TEXT && (display == null || display.isEmpty()))
        {
            PropertySheetDebug.valueControl("text EMPTY " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                    + " vm=" + PropertySheetDebug.safe(valueVm) //$NON-NLS-1$
                    + " view=" + PropertySheetDebug.safe(valueView) //$NON-NLS-1$
                    + " aef=" + PropertySheetDebug.quote(PropertySheetAefValues.readValue(valueVm))); //$NON-NLS-1$
        }
        if (kind == Kind.HYPERLINK)
            PropertySheetDebug.valueControlVerbose("hyperlink OK " + PropertySheetDebug.quote(propName) //$NON-NLS-1$
                    + " vm=" + PropertySheetDebug.safe(valueVm) //$NON-NLS-1$
                    + " view=" + PropertySheetDebug.safe(valueView)); //$NON-NLS-1$
    }

    static void applyDisplay(Created created, String display, Object valueVm, Object valueView, Control nativeValue)
    {
        applyDisplay(created, display, valueVm, valueView, nativeValue, null, null, null);
    }

    static void applyDisplay(Created created, String display, Object valueVm, Object valueView, Control nativeValue,
            Control nativePaletteRoot, String propertyName)
    {
        applyDisplay(created, display, valueVm, valueView, nativeValue, nativePaletteRoot, propertyName, null);
    }

    static void applyDisplay(Created created, String display, Object valueVm, Object valueView, Control nativeValue,
            Control nativePaletteRoot, String propertyName, Object page)
    {
        if (created == null || created.control == null || created.control.isDisposed())
            return;
        String value = display != null ? display : ""; //$NON-NLS-1$
        if (value.isEmpty())
            value = firstNonEmpty(PropertySheetAefValues.readValue(valueVm),
                    resolveModelText(valueVm, valueView), nativeControlText(nativeValue));
        switch (created.kind)
        {
            case BOOLEAN:
            {
                Boolean parsed = parseBooleanDisplay(value);
                boolean on;
                if (parsed != null)
                    on = parsed.booleanValue();
                else
                {
                    Boolean selected = PropertySheetAefValues.readBoolean(valueVm, valueView);
                    on = selected != null ? selected.booleanValue()
                            : resolveBoolean(valueVm, valueView, value, nativeValue);
                }
                if (created.control instanceof Button)
                    ((Button) created.control).setSelection(on);
                break;
            }
            case COMBO:
            {
                java.util.List<String> items = resolveComboItems(valueVm, valueView, nativeValue);
                if (value.isEmpty())
                    value = PropertySheetAefValues.readComboSelection(valueVm, items);
                Text comboText = comboText(created.control);
                if (comboText != null && !comboText.isDisposed())
                {
                    List<String> merged = new ArrayList<>(items);
                    if (!value.isEmpty() && indexOf(merged, value) < 0)
                        merged.add(0, value);
                    created.control.setData(COMBO_ITEMS_KEY, merged.toArray(new String[0]));
                    setTextProgrammatic(comboText, value);
                }
                else if (created.control instanceof CCombo)
                {
                    List<String> merged = new ArrayList<>(items);
                    if (!value.isEmpty() && indexOf(merged, value) < 0)
                        merged.add(0, value);
                    syncComboItemsAndSelection((CCombo) created.control, merged, value);
                }
                else if (created.control instanceof Combo)
                {
                    List<String> merged = new ArrayList<>(items);
                    if (!value.isEmpty() && indexOf(merged, value) < 0)
                        merged.add(0, value);
                    syncComboItemsAndSelection((Combo) created.control, merged, value);
                }
                break;
            }
            case SPINNER:
            {
                int number = resolveSpinner(valueVm, valueView, value, nativeValue);
                if (created.control instanceof Text)
                    setTextProgrammatic((Text) created.control, String.valueOf(number));
                else if (created.control instanceof Spinner)
                    ((Spinner) created.control).setSelection(number);
                break;
            }
            case TEXT:
                if (created.control instanceof Text)
                    setTextProgrammatic((Text) created.control, value);
                break;
            case ACTION_BAR:
            {
                Text barText = actionBarText(created.control);
                if (barText != null && !barText.isDisposed())
                    setTextProgrammatic(barText, value != null ? value : ""); //$NON-NLS-1$
                break;
            }
            default:
                break;
        }
    }

    static String readDisplayValue(Control control, Kind kind)
    {
        if (control == null || control.isDisposed())
            return ""; //$NON-NLS-1$
        switch (kind)
        {
            case BOOLEAN:
                if (control instanceof Button)
                    return ((Button) control).getSelection() ? "true" : "false"; //$NON-NLS-1$ //$NON-NLS-2$
                break;
            case COMBO:
            {
                Text comboText = comboText(control);
                if (comboText != null && !comboText.isDisposed())
                    return comboText.getText();
                if (control instanceof Combo)
                    return ((Combo) control).getText();
                if (control instanceof CCombo)
                    return ((CCombo) control).getText();
                break;
            }
            case SPINNER:
                if (control instanceof Text)
                    return ((Text) control).getText();
                if (control instanceof Spinner)
                    return String.valueOf(((Spinner) control).getSelection());
                break;
            case HYPERLINK:
                if (control instanceof Link)
                    return stripLinkMarkup(((Link) control).getText());
                break;
            case ACTION_BAR:
            {
                Text text = actionBarText(control);
                if (text != null && !text.isDisposed())
                    return text.getText();
                break;
            }
            default:
                break;
        }
        return PropertySheetControlInterop.controlText(control);
    }

    /** Слушатель на привязанном native-контроле (фиксируется при создании строки). */
    static void wireNativeMirror(Control nativeValue, Kind kind, Runnable onNativeChange)
    {
        wireNativeMirror(nativeValue, null, kind, onNativeChange);
    }

    static void wireNativeMirror(Control nativeValue, Object valueView, Kind kind, Runnable onNativeChange)
    {
        if (onNativeChange == null || kind == null)
            return;
        wireNativeMirrorControl(nativeValue, kind, onNativeChange);
        if (valueView != null)
        {
            Control fromView = PropertySheetControlInterop.unwrapToSwtControl(valueView);
            if (fromView != null && fromView != nativeValue)
                wireNativeMirrorControl(fromView, kind, onNativeChange);
        }
    }

    private static void wireNativeMirrorControl(Control nativeValue, Kind kind, Runnable onNativeChange)
    {
        if (nativeValue == null || nativeValue.isDisposed() || onNativeChange == null || kind == null)
            return;
        if (Boolean.TRUE.equals(nativeValue.getData(NATIVE_MIRROR_WIRED_KEY)))
            return;
        nativeValue.setData(NATIVE_MIRROR_WIRED_KEY, Boolean.TRUE);
        switch (kind)
        {
            case BOOLEAN:
                wireBooleanNativeMirror(nativeValue, onNativeChange);
                break;
            case HYPERLINK:
                nativeValue.addListener(SWT.Selection, e -> onNativeChange.run());
                break;
            case COMBO:
                if (nativeValue instanceof Combo || nativeValue instanceof CCombo)
                    nativeValue.addListener(SWT.Selection, e -> onNativeChange.run());
                break;
            case SPINNER:
                if (nativeValue instanceof Spinner)
                    nativeValue.addListener(SWT.Selection, e -> onNativeChange.run());
                else if (nativeValue instanceof Text)
                    ((Text) nativeValue).addModifyListener(e -> onNativeChange.run());
                break;
            case ACTION_BAR:
            case TEXT:
            {
                Text text = nativeValue instanceof Text ? (Text) nativeValue
                        : nativeValue instanceof Composite ? findFirstText((Composite) nativeValue) : null;
                if (text != null && !text.isDisposed())
                    text.addModifyListener(e -> onNativeChange.run());
                break;
            }
            default:
                break;
        }
        PropertySheetDebug.sync("wireNativeMirror kind=" + kind //$NON-NLS-1$
                + " native=" + PropertySheetDebug.controlBrief(nativeValue)); //$NON-NLS-1$
    }

    private static void wireBooleanNativeMirror(Control control, Runnable onNativeChange)
    {
        if (control == null || control.isDisposed())
            return;
        control.addListener(SWT.Selection, e -> onNativeChange.run());
        control.addListener(SWT.MouseUp, e -> {
            if (e.button == 1)
                onNativeChange.run();
        });
        if (control instanceof Composite)
        {
            Button check = findCheckButton(control);
            if (check != null && check != control && !Boolean.TRUE.equals(check.getData(NATIVE_MIRROR_WIRED_KEY)))
                wireBooleanNativeMirror(check, onNativeChange);
        }
    }

    static void wireChange(Created created, Runnable onChange)
    {
        if (created == null || created.control == null || created.control.isDisposed() || onChange == null)
            return;
        Control control = created.control;
        switch (created.kind)
        {
            case BOOLEAN:
            case HYPERLINK:
                control.addListener(SWT.Selection, e -> onChange.run());
                break;
            case SPINNER:
                if (control instanceof Text)
                    ((Text) control).addModifyListener(e -> onChange.run());
                else
                    control.addListener(SWT.Selection, e -> onChange.run());
                break;
            case COMBO:
                wireComboChange(control, onChange);
                break;
            case ACTION_BAR:
                wireActionBarChange(control, onChange);
                break;
            default:
                if (control instanceof Text)
                    ((Text) control).addModifyListener(e -> onChange.run());
                break;
        }
    }

    static void applyToNative(Object sessionObj, Created created, Control nativeValue, Object valueVm,
            String propertyName)
    {
        applyToNative(sessionObj, created, nativeValue, valueVm, propertyName, null);
    }

    static void applyToNative(Object sessionObj, Created created, Control nativeValue, Object valueVm,
            String propertyName, Object valueView)
    {
        applyToNative(sessionObj, created, nativeValue, valueVm, propertyName, valueView, null);
    }

    static void applyToNative(Object sessionObj, Created created, Control nativeValue, Object valueVm,
            String propertyName, Object valueView, Object renderer)
    {
        if (created == null || created.control == null || created.control.isDisposed())
            return;
        if (created.kind == Kind.HYPERLINK)
        {
            fireHyperlink(sessionObj, nativeValue, valueVm, propertyName, valueView);
            return;
        }
        Object pushValue = readComfortPushValue(created);
        PropertySheetDebug.sync("comfort→native " + PropertySheetDebug.quote(propertyName) //$NON-NLS-1$
                + " kind=" + created.kind //$NON-NLS-1$
                + " value=" + PropertySheetDebug.quote(String.valueOf(pushValue)) //$NON-NLS-1$
                + " native=" + PropertySheetDebug.controlBrief(nativeValue)); //$NON-NLS-1$
        switch (created.kind)
        {
            case BOOLEAN:
                applyBoolean(created, nativeValue, valueVm, valueView, pushValue, renderer);
                return;
            case COMBO:
                applyCombo(created, nativeValue, valueVm, valueView, pushValue);
                return;
            case SPINNER:
                applySpinner(created, nativeValue, valueVm, valueView, pushValue);
                return;
            case ACTION_BAR:
                applyText(created, nativeValue, valueVm, valueView, pushValue);
                return;
            default:
                applyText(created, nativeValue, valueVm, valueView, pushValue);
                return;
        }
    }

    static Object readComfortPushValue(Created created)
    {
        if (created == null)
            return ""; //$NON-NLS-1$
        if (created.kind == Kind.BOOLEAN)
        {
            boolean selected = created.control instanceof Button && ((Button) created.control).getSelection();
            return Boolean.valueOf(selected);
        }
        return readDisplayValue(created.control, created.kind);
    }

    private static Created createText(Composite parent, String value, boolean editable, int valueWidthHint)
    {
        Text text = new Text(parent, SWT.BORDER | SWT.SINGLE);
        setTextProgrammatic(text, value != null ? value : ""); //$NON-NLS-1$
        applyTextFieldState(text, editable, editable);
        applyValueGridData(text, valueWidthHint);
        return new Created(Kind.TEXT, text, value);
    }

    private static Created createActionBar(Composite parent, String value, Object valueVm, Object valueView,
            Control nativeValue, int valueWidthHint, Control nativePaletteRoot, String propertyName, Object page)
    {
        boolean editable = resolveEditable(valueVm, valueView, nativeValue, nativePaletteRoot, propertyName, page);
        List<Button> nativeButtons = collectPushButtons(findActionBarHost(nativeValue, valueView));
        int buttonCount = resolveActionBarButtonCount(valueVm, valueView, nativeButtons);

        Composite bar = new Composite(parent, SWT.BORDER);
        GridLayout layout = new GridLayout(1 + buttonCount, false);
        layout.marginWidth = 1;
        layout.marginHeight = 1;
        layout.horizontalSpacing = 0;
        bar.setLayout(layout);

        Text text = new Text(bar, SWT.NONE);
        setTextProgrammatic(text, value != null ? value : ""); //$NON-NLS-1$
        applyTextFieldState(text, editable, resolveActionBarAllowTyping(nativeValue, editable));
        GridData textData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        applyInnerValueFieldGridData(textData);
        text.setLayoutData(textData);

        List<Button> comfortButtons = new ArrayList<>();
        for (int i = 0; i < buttonCount; i++)
        {
            Button button = new Button(bar, SWT.PUSH | SWT.FLAT);
            applyActionBarButtonLayout(button);
            comfortButtons.add(button);
        }
        decorateActionBarButtons(comfortButtons, nativeButtons, valueVm, valueView, editable);

        bar.setData(ACTION_BAR_TEXT_KEY, text);
        bar.setData(ACTION_BAR_BUTTONS_KEY, comfortButtons);
        bar.setData(ACTION_BAR_NATIVE_KEY, nativeValue);
        bar.setData(ACTION_BAR_VIEW_KEY, valueView);
        bar.setData(ACTION_BAR_VM_KEY, valueVm);

        applyActionBarFieldState(bar, editable, nativeValue);
        applyValueGridData(bar, valueWidthHint);
        applyValueFieldHeight(bar);
        return new Created(Kind.ACTION_BAR, bar, value);
    }

    private static Created createBoolean(Composite parent, Object valueVm, Object valueView,
            String display, Control nativeValue, Boolean boolFromView, Control nativePaletteRoot,
            String propertyName, Object page)
    {
        boolean selected = boolFromView != null ? boolFromView.booleanValue()
                : resolveBoolean(valueVm, valueView, display, nativeValue);
        Button check = new Button(parent, SWT.CHECK);
        check.setSelection(selected);
        applyEnabledState(check, resolveEditable(valueVm, valueView, nativeValue, nativePaletteRoot, propertyName, page));
        GridDataFactory.swtDefaults().align(SWT.LEFT, SWT.CENTER).applyTo(check);
        return new Created(Kind.BOOLEAN, check, selected ? "true" : "false"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Created createCombo(Composite parent, Object valueVm, Object valueView,
            String display, Control nativeValue, List<String> items, int valueWidthHint,
            Control nativePaletteRoot, String propertyName, Object page)
    {
        if (items == null)
            items = resolveComboItems(valueVm, valueView, nativeValue);
        String value = display != null ? display : ""; //$NON-NLS-1$
        if (value.isEmpty())
            value = resolveComboDisplayValue(valueVm, valueView, items);
        List<String> merged = new ArrayList<>(items);
        if (!value.isEmpty() && indexOf(merged, value) < 0)
            merged.add(0, value);

        Composite panel = new Composite(parent, SWT.BORDER);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 1;
        layout.marginHeight = 1;
        layout.horizontalSpacing = 0;
        panel.setLayout(layout);

        Text text = new Text(panel, SWT.NONE);
        setTextProgrammatic(text, value);
        GridData comboTextData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        applyInnerValueFieldGridData(comboTextData);
        text.setLayoutData(comboTextData);

        Button arrow = new Button(panel, SWT.ARROW | SWT.DOWN);
        GridData arrowData = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        arrowData.widthHint = COMBO_ARROW_WIDTH;
        applyInnerValueFieldGridData(arrowData);
        arrow.setLayoutData(arrowData);

        panel.setData(COMBO_TEXT_KEY, text);
        panel.setData(COMBO_ARROW_KEY, arrow);
        panel.setData(COMBO_ITEMS_KEY, merged.toArray(new String[0]));
        wireComboPanelBorder(panel);

        boolean editable = resolveEditable(valueVm, valueView, nativeValue, nativePaletteRoot, propertyName, page);
        wireComboPopup(panel);
        applyComboFieldState(panel, editable, propertyName);
        applyValueGridData(panel, valueWidthHint);
        applyValueFieldHeight(panel);
        return new Created(Kind.COMBO, panel, text.getText());
    }

    private static Created createHyperlink(Composite parent, String display, int valueWidthHint)
    {
        String value = display != null && !display.isEmpty() ? display : "Открыть"; //$NON-NLS-1$
        Link link = new Link(parent, SWT.NONE);
        link.setText("<a>" + escapeLinkText(value) + "</a>"); //$NON-NLS-1$ //$NON-NLS-2$
        applyValueGridData(link, valueWidthHint);
        return new Created(Kind.HYPERLINK, link, value);
    }

    private static Created createSpinner(Composite parent, Object valueVm, Object valueView,
            String display, Control nativeValue, int valueWidthHint, Control nativePaletteRoot,
            String propertyName, Object page)
    {
        int number = resolveSpinner(valueVm, valueView, display, nativeValue);
        Text text = new Text(parent, SWT.BORDER);
        boolean editable = resolveEditable(valueVm, valueView, nativeValue, nativePaletteRoot, propertyName, page);
        setTextProgrammatic(text, String.valueOf(number));
        applyTextFieldState(text, editable, editable);
        applyValueGridData(text, valueWidthHint);
        return new Created(Kind.SPINNER, text, text.getText());
    }

    private static void applyInnerValueFieldGridData(GridData gd)
    {
        if (gd == null)
            return;
        gd.heightHint = VALUE_FIELD_INNER_HEIGHT;
        gd.minimumHeight = VALUE_FIELD_INNER_HEIGHT;
    }

    private static void applyValueFieldHeight(Control control)
    {
        if (control == null || control.isDisposed())
            return;
        Object layoutData = control.getLayoutData();
        GridData gd;
        if (layoutData instanceof GridData)
            gd = (GridData) layoutData;
        else
        {
            gd = new GridData();
            control.setLayoutData(gd);
        }
        gd.heightHint = VALUE_FIELD_HEIGHT;
        gd.minimumHeight = VALUE_FIELD_HEIGHT;
    }

    private static void applyValueGridData(Control control, int valueWidthHint)
    {
        if (valueWidthHint > 0)
            GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER)
                    .hint(valueWidthHint, SWT.DEFAULT).applyTo(control);
        else
            GridDataFactory.fillDefaults().grab(true, false).align(SWT.FILL, SWT.CENTER).applyTo(control);
        if (valueWidthHint > 0)
        {
            Object layoutData = control.getLayoutData();
            if (layoutData instanceof org.eclipse.swt.layout.GridData)
            {
                org.eclipse.swt.layout.GridData gd = (org.eclipse.swt.layout.GridData) layoutData;
                gd.widthHint = valueWidthHint;
                gd.grabExcessHorizontalSpace = true;
            }
        }
    }

    private static void applyText(Created created, Control nativeValue, Object valueVm, Object valueView,
            Object pushValue)
    {
        String value = pushValue != null ? String.valueOf(pushValue) : ""; //$NON-NLS-1$
        if (isNativePushTarget(nativeValue, created.kind))
            pushNativeText(nativeValue, value, created.kind);
        PropertySheetControlInterop.simulateNativeValueChange(created.kind, valueView, valueVm, nativeValue,
                value);
        pushModelValue(valueVm, value);
    }

    private static void applyBoolean(Created created, Control nativeValue, Object valueVm, Object valueView,
            Object pushValue, Object renderer)
    {
        boolean selected = pushValue instanceof Boolean ? ((Boolean) pushValue).booleanValue()
                : created.control instanceof Button && ((Button) created.control).getSelection();
        boolean hasNative = nativeValue != null && !nativeValue.isDisposed();
        if (hasNative && nativeValue instanceof Button && (((Button) nativeValue).getStyle() & SWT.CHECK) != 0)
        {
            ((Button) nativeValue).setSelection(selected);
            nativeValue.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            PropertySheetDebug.sync("comfort→native SWT.check selected=" + selected); //$NON-NLS-1$
            pushModelValue(valueVm, Boolean.valueOf(selected));
            return;
        }
        if (hasNative && isNativePushTarget(nativeValue, Kind.BOOLEAN))
        {
            pushNativeText(nativeValue, selected ? "true" : "false", Kind.BOOLEAN); //$NON-NLS-1$ //$NON-NLS-2$
            PropertySheetDebug.sync("comfort→native nativeText selected=" + selected); //$NON-NLS-1$
            pushModelValue(valueVm, Boolean.valueOf(selected));
            return;
        }
        if (PropertySheetControlInterop.invokeCheckboxOnView(renderer, valueView, valueVm, selected))
        {
            pushModelValue(valueVm, Boolean.valueOf(selected));
            return;
        }
        // Fallback для LwtCheckboxView: valueVm — это CheckboxViewModel с методом setChecked(boolean).
        // EMF databinding автоматически передаёт изменение в LightCheckbox.
        PropertySheetDebug.sync("comfort→native boolean FALLBACK vm=" //$NON-NLS-1$
                + PropertySheetDebug.safe(valueVm) //$NON-NLS-1$
                + " vmClass=" + (valueVm != null ? valueVm.getClass().getName() : "null") //$NON-NLS-1$ //$NON-NLS-2$
                + " view=" + PropertySheetDebug.safe(valueView)); //$NON-NLS-1$
        if (valueVm != null && Global.invokeVoid(valueVm, "setChecked", Boolean.valueOf(selected))) //$NON-NLS-1$
        {
            PropertySheetDebug.sync("comfort→native boolean setChecked(" + selected + ") OK"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        // valueVm не CheckboxViewModel — ищем CheckboxViewModel через valueView.getViewModel()
        Object checkboxVm = valueView != null ? Global.invoke(valueView, "getViewModel") : null; //$NON-NLS-1$
        if (checkboxVm == null && valueView != null)
            checkboxVm = Global.getField(valueView, "viewModel"); //$NON-NLS-1$
        PropertySheetDebug.sync("comfort→native boolean checkboxVm=" //$NON-NLS-1$
                + (checkboxVm != null ? checkboxVm.getClass().getName() : "null")); //$NON-NLS-1$
        if (checkboxVm != null && Global.invokeVoid(checkboxVm, "setChecked", Boolean.valueOf(selected))) //$NON-NLS-1$
        {
            PropertySheetDebug.sync("comfort→native boolean checkboxVm.setChecked(" + selected + ") OK"); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        PropertySheetDebug.sync("comfort→native FAIL checkbox selected=" + selected //$NON-NLS-1$
                + " view=" + PropertySheetDebug.safe(valueView)); //$NON-NLS-1$
    }

    private static void applyCombo(Created created, Control nativeValue, Object valueVm, Object valueView,
            Object pushValue)
    {
        String value = pushValue != null ? String.valueOf(pushValue) : ""; //$NON-NLS-1$
        if (nativeValue instanceof Combo)
        {
            Combo combo = (Combo) nativeValue;
            int idx = combo.indexOf(value);
            if (idx >= 0)
                combo.select(idx);
            else
                combo.setText(value);
            combo.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
        }
        else if (nativeValue instanceof CCombo)
        {
            CCombo combo = (CCombo) nativeValue;
            combo.setText(value);
            combo.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
        }
        else if (isNativePushTarget(nativeValue, Kind.COMBO))
        {
            pushNativeText(nativeValue, value, Kind.COMBO);
        }
        PropertySheetControlInterop.simulateNativeValueChange(Kind.COMBO, valueView, valueVm, nativeValue,
                value);
        pushModelValue(valueVm, value);
    }

    private static void applySpinner(Created created, Control nativeValue, Object valueVm, Object valueView,
            Object pushValue)
    {
        String value = pushValue != null ? String.valueOf(pushValue) : ""; //$NON-NLS-1$
        if (nativeValue instanceof Spinner)
        {
            try
            {
                ((Spinner) nativeValue).setSelection(Integer.parseInt(value));
                nativeValue.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
            }
            catch (NumberFormatException ignored) {}
        }
        else if (isNativePushTarget(nativeValue, Kind.SPINNER))
        {
            pushNativeText(nativeValue, value, Kind.SPINNER);
        }
        PropertySheetControlInterop.simulateNativeValueChange(Kind.SPINNER, valueView, valueVm, nativeValue,
                value);
        pushModelValue(valueVm, value);
    }

    private static void fireHyperlink(Object sessionObj, Control nativeValue, Object valueVm, String propertyName,
            Object valueView)
    {
        for (String method : new String[] {
                "linkActivated", "open", "execute", "performOpen", "handleOpen", "doOpen", "openEditor", "openLink" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        })
        {
            Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
            try
            {
                if (valueView != null && Global.invokeVoid(valueView, method))
                {
                    PropertySheetDebug.valueControl("hyperlink OK view." + method + " " //$NON-NLS-1$ //$NON-NLS-2$
                            + PropertySheetDebug.quote(propertyName));
                    return;
                }
                if (model != null && Global.invokeVoid(model, method))
                {
                    PropertySheetDebug.valueControl("hyperlink OK model." + method + " " //$NON-NLS-1$ //$NON-NLS-2$
                            + PropertySheetDebug.quote(propertyName));
                    return;
                }
                if (valueVm != null && Global.invokeVoid(valueVm, method))
                {
                    PropertySheetDebug.valueControl("hyperlink OK vm." + method + " " //$NON-NLS-1$ //$NON-NLS-2$
                            + PropertySheetDebug.quote(propertyName));
                    return;
                }
            }
            catch (Throwable ignored) {}
        }
        Control target = nativeValue;
        if ((target == null || target.isDisposed()) && sessionObj instanceof PropertySheetComfortUi.SessionAccessor)
            target = ((PropertySheetComfortUi.SessionAccessor) sessionObj)
                    .resolveNativeValueControl(propertyName, valueVm);
        if (target != null && !target.isDisposed())
            PropertySheetDebug.valueControl("hyperlink skip native notify " //$NON-NLS-1$
                    + PropertySheetDebug.quote(propertyName) + " target=" + PropertySheetDebug.safe(target)); //$NON-NLS-1$
        else
            PropertySheetDebug.valueControl("hyperlink FAIL " + PropertySheetDebug.quote(propertyName)); //$NON-NLS-1$
    }

    static boolean isNativePushTarget(Control nativeValue, Kind kind)
    {
        if (nativeValue == null || nativeValue.isDisposed() || kind == null)
            return false;
        if (nativeValue instanceof Link)
            return kind == Kind.HYPERLINK;
        switch (kind)
        {
            case HYPERLINK:
                return nativeValue instanceof Link;
            case BOOLEAN:
                return findCheckButton(nativeValue) != null || isBooleanLikeControl(nativeValue);
            case COMBO:
                if (nativeValue instanceof Combo || nativeValue instanceof CCombo)
                    return true;
                if (nativeValue instanceof Composite)
                    return findComboInComposite((Composite) nativeValue) != null;
                return false;
            case SPINNER:
                return nativeValue instanceof Spinner;
            case ACTION_BAR:
            case TEXT:
                if (nativeValue instanceof Text)
                    return true;
                if (nativeValue instanceof Composite)
                    return findFirstText((Composite) nativeValue) != null;
                return false;
            default:
                return false;
        }
    }

    static Control filterNativeValueControl(Control nativeValue, Kind kind)
    {
        if (nativeValue == null || nativeValue.isDisposed())
            return null;
        if (kind == null)
            return nativeValue;
        return isNativePushTarget(nativeValue, kind) ? nativeValue : null;
    }

    /** Однократная привязка к источнику при создании проекции (не вызывать при изменении значения). */
    static Control bindNativePushTarget(Control nativeValue, Kind kind)
    {
        if (nativeValue == null || nativeValue.isDisposed() || kind == null)
            return nativeValue;
        if (isNativePushTarget(nativeValue, kind))
            return nativeValue;
        switch (kind)
        {
            case BOOLEAN:
            {
                Button check = findCheckButton(nativeValue);
                if (check != null)
                    return check;
                break;
            }
            case COMBO:
                if (nativeValue instanceof Composite)
                {
                    Control combo = findComboInComposite((Composite) nativeValue);
                    if (combo != null)
                        return combo;
                }
                break;
            case ACTION_BAR:
                return nativeValue;
            case TEXT:
            case SPINNER:
                if (nativeValue instanceof Composite)
                {
                    Text text = findFirstText((Composite) nativeValue);
                    if (text != null)
                        return text;
                }
                break;
            default:
                break;
        }
        return nativeValue;
    }

    private static Button findCheckButton(Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        if (control instanceof Button && (((Button) control).getStyle() & SWT.CHECK) != 0)
            return (Button) control;
        if (control instanceof Composite)
        {
            for (Control child : ((Composite) control).getChildren())
            {
                Button found = findCheckButton(child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean isBooleanLikeControl(Control control)
    {
        if (control == null || control.isDisposed())
            return false;
        String cn = control.getClass().getName();
        return cn.contains("Check") || cn.contains("Boolean") || cn.contains("Toggle"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void pushNativeText(Control nativeValue, String value, Kind kind)
    {
        if (!isNativePushTarget(nativeValue, kind))
            return;
        if (value == null)
            value = ""; //$NON-NLS-1$
        try
        {
            if (kind == Kind.BOOLEAN)
            {
                Button check = findCheckButton(nativeValue);
                if (check != null)
                {
                    boolean selected = "true".equalsIgnoreCase(value) //$NON-NLS-1$
                            || "да".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) //$NON-NLS-1$ //$NON-NLS-2$
                            || "1".equals(value); //$NON-NLS-1$
                    check.setSelection(selected);
                    check.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
                    return;
                }
            }
            if (nativeValue instanceof Text)
                ((Text) nativeValue).setText(value);
            else if (nativeValue instanceof Combo)
                ((Combo) nativeValue).setText(value);
            else if (nativeValue instanceof CCombo)
                ((CCombo) nativeValue).setText(value);
            else if (kind == Kind.ACTION_BAR || kind == Kind.TEXT)
            {
                Text rowText = nativeValue instanceof Composite
                        ? findFirstText((Composite) nativeValue) : null;
                if (rowText != null && !rowText.isDisposed())
                    rowText.setText(value);
            }
            else
                Global.invoke(nativeValue, "setText", value); //$NON-NLS-1$
            nativeValue.notifyListeners(SWT.Modify, new org.eclipse.swt.widgets.Event());
            nativeValue.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
        }
        catch (Throwable ignored) {}
    }

    private static void pushModelValue(Object valueVm, Object value)
    {
        if (valueVm == null)
            return;
        Object model = Global.invoke(valueVm, "getModel"); //$NON-NLS-1$
        if (model != null)
        {
            Global.invoke(model, "setValue", value); //$NON-NLS-1$
            Global.invoke(model, "valueChanged"); //$NON-NLS-1$
        }
        Global.invoke(valueVm, "setValue", value); //$NON-NLS-1$
    }

    private static String resolveComboDisplayValue(Object valueVm, Object valueView, List<String> items)
    {
        String fromAef = PropertySheetAefValues.readComboSelection(valueVm, items);
        if (!fromAef.isEmpty())
            return fromAef;
        if (valueView != null)
        {
            String fromView = PropertySheetControlInterop.displayTextFromView(valueView, valueVm);
            if (!fromView.isEmpty())
                return fromView;
        }
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        if (model != null)
        {
            for (String method : new String[] {
                    "getSelectedIndex", "getSelectionIndex", "getSelectedItemIndex" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            })
            {
                Object idxObj = Global.invoke(model, method);
                if (idxObj instanceof Number)
                {
                    int idx = ((Number) idxObj).intValue();
                    if (idx >= 0 && items != null && idx < items.size())
                        return items.get(idx);
                }
            }
            Object value = Global.invoke(model, "getValue"); //$NON-NLS-1$
            String label = labelForEnumValue(value, items);
            if (!label.isEmpty())
                return label;
            Object single = Global.invoke(model, "getSingleValue"); //$NON-NLS-1$
            label = labelForEnumValue(single, items);
            if (!label.isEmpty())
                return label;
        }
        return resolveModelText(valueVm, valueView);
    }

    private static String labelForEnumValue(Object value, List<String> items)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        if (value instanceof Number && items != null && !items.isEmpty())
        {
            int idx = ((Number) value).intValue();
            if (idx >= 0 && idx < items.size())
                return items.get(idx);
        }
        String direct = asString(value);
        if (!direct.isEmpty())
        {
            int idx = indexOfIgnoreCase(items, direct);
            if (idx >= 0 && items != null)
                return items.get(idx);
            if (!looksLikeClassName(direct, value))
                return direct;
        }
        for (String method : new String[] {
                "getLabel", "getName", "getLiteral", "getPresentation", "getDisplayName", "getText" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        })
        {
            String nested = asString(Global.invoke(value, method));
            if (!nested.isEmpty())
            {
                int idx = indexOfIgnoreCase(items, nested);
                if (idx >= 0 && items != null)
                    return items.get(idx);
                return nested;
            }
        }
        return ""; //$NON-NLS-1$
    }

    private static boolean looksLikeClassName(String text, Object value)
    {
        return value != null && text.equals(value.getClass().getName());
    }

    private static int indexOfIgnoreCase(List<String> items, String value)
    {
        if (items == null || value == null)
            return -1;
        for (int i = 0; i < items.size(); i++)
        {
            if (value.equalsIgnoreCase(items.get(i)))
                return i;
        }
        return -1;
    }

    private static String resolveModelText(Object valueVm, Object valueView)
    {
        String fromAef = PropertySheetAefValues.readValue(valueVm);
        if (!fromAef.isEmpty())
            return fromAef;
        if (valueVm == null && valueView == null)
            return ""; //$NON-NLS-1$
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        if (model != null)
        {
                for (String method : new String[] {
                    "getValue", "getSingleValue", "getItemLabel", "getDisplayValue", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "getPresentation", "getLabel", "getText", "getSelectedItem", "getCurrentLiteral" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            })
            {
                String text = asString(Global.invoke(model, method));
                if (!text.isEmpty())
                    return text;
            }
        }
        for (String method : new String[] { "getText", "getValue", "getPresentation", "getDisplayValue" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            String text = asString(valueVm != null ? Global.invoke(valueVm, method) : Global.invoke(valueView, method));
            if (!text.isEmpty())
                return text;
        }
        return ""; //$NON-NLS-1$
    }

    private static Boolean parseBooleanDisplay(String display)
    {
        if (display == null || display.isEmpty())
            return null;
        String text = display.trim();
        if ("true".equalsIgnoreCase(text) || "да".equalsIgnoreCase(text) //$NON-NLS-1$ //$NON-NLS-2$
                || "yes".equalsIgnoreCase(text) || "1".equals(text)) //$NON-NLS-1$ //$NON-NLS-2$
            return Boolean.TRUE;
        if ("false".equalsIgnoreCase(text) || "нет".equalsIgnoreCase(text) //$NON-NLS-1$ //$NON-NLS-2$
                || "no".equalsIgnoreCase(text) || "0".equals(text)) //$NON-NLS-1$ //$NON-NLS-2$
            return Boolean.FALSE;
        return null;
    }

    private static boolean resolveBoolean(Object valueVm, Object valueView, String display, Control nativeValue)
    {
        Boolean parsed = parseBooleanDisplay(display);
        if (parsed != null)
            return parsed.booleanValue();
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        if (model != null)
        {
            Object value = Global.invoke(model, "getValue"); //$NON-NLS-1$
            if (value instanceof Boolean)
                return (Boolean) value;
        }
        if (nativeValue instanceof Button && (((Button) nativeValue).getStyle() & SWT.CHECK) != 0)
            return ((Button) nativeValue).getSelection();
        Object selected = valueView != null ? Global.invoke(valueView, "getSelection") : null; //$NON-NLS-1$
        return selected instanceof Boolean && (Boolean) selected;
    }

    private static int resolveSpinner(Object valueVm, Object valueView, String display, Control nativeValue)
    {
        if (nativeValue instanceof Spinner)
            return ((Spinner) nativeValue).getSelection();
        String text = firstNonEmpty(display, resolveModelText(valueVm, valueView), nativeControlText(nativeValue));
        try
        {
            if (!text.isEmpty())
                return (int) Double.parseDouble(text.replace(',', '.'));
        }
        catch (NumberFormatException ignored) {}
        return 0;
    }

    private static List<String> resolveComboItems(Object valueVm, Object valueView, Control nativeValue)
    {
        List<String> items = new ArrayList<>();
        collectItems(items, valueVm);
        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        collectItems(items, model);
        if (items.isEmpty() && valueVm != null)
            collectItems(items, valueVm);
        if (items.isEmpty() && nativeValue instanceof Combo)
        {
            for (String item : ((Combo) nativeValue).getItems())
                addItem(items, item);
        }
        if (items.isEmpty() && nativeValue instanceof CCombo)
        {
            for (String item : ((CCombo) nativeValue).getItems())
                addItem(items, item);
        }
        return items;
    }

    private static void collectItems(List<String> items, Object source)
    {
        if (source == null)
            return;
        for (String method : new String[] {
                "getItems", "getEnumLiterals", "getAllowedValues", "getPossibleValues", "getChoices" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        })
        {
            Object raw = Global.invoke(source, method);
            if (appendItems(items, raw))
                return;
        }
    }

    private static boolean appendItems(List<String> items, Object raw)
    {
        Iterator<?> it = toIterator(raw);
        if (it == null)
            return false;
        while (it.hasNext())
        {
            Object item = it.next();
            if (item == null)
                continue;
            String label = asString(item);
            if (label.isEmpty())
            {
                for (String method : new String[] { "getLabel", "getName", "getPresentation", "getDisplayName" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                {
                    label = asString(Global.invoke(item, method));
                    if (!label.isEmpty())
                        break;
                }
            }
            addItem(items, label.isEmpty() ? item.toString() : label);
        }
        return !items.isEmpty();
    }

    private static Kind detectKindFromControl(Control control)
    {
        if (control == null || control.isDisposed())
            return Kind.TEXT;
        if (control instanceof Button && (((Button) control).getStyle() & SWT.CHECK) != 0)
            return Kind.BOOLEAN;
        if (control instanceof Combo || control instanceof CCombo)
            return Kind.COMBO;
        if (control instanceof Link)
            return Kind.HYPERLINK;
        if (control instanceof Spinner)
            return Kind.SPINNER;
        return Kind.TEXT;
    }

    private static String nativeControlText(Control control)
    {
        return control != null ? PropertySheetControlInterop.controlText(control) : ""; //$NON-NLS-1$
    }

    static Kind kindFromViewPublic(Object valueView)
    {
        return kindFromView(valueView);
    }

    private static Kind kindFromView(Object valueView)
    {
        if (valueView == null)
            return null;
        String cn = valueView.getClass().getName();
        if (cn.contains("LwtLinkView") || cn.contains("SwtLinkView") || cn.contains("LinkView")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return Kind.HYPERLINK;
        if (cn.contains("LwtTextView") || cn.contains("SwtTextView") || cn.contains("TextView")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return Kind.TEXT;
        if (cn.contains("LwtCheckboxView") || cn.contains("SwtCheckBoxView") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("LwtCheckableLabelView") || cn.contains("SwtCheckableLabelView")) //$NON-NLS-1$ //$NON-NLS-2$
            return Kind.BOOLEAN;
        if (cn.contains("LwtComboView") || cn.contains("SwtComboView") || cn.contains("ImageComboView") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("ComboSelectView")) //$NON-NLS-1$
            return Kind.COMBO;
        if (cn.contains("SpinnerView")) //$NON-NLS-1$
            return Kind.SPINNER;
        return null;
    }

    private static boolean isLinkType(Object valueVm, Object valueView, Object model)
    {
        if (kindFromView(valueView) == Kind.HYPERLINK)
            return true;
        String cn = typeNames(valueVm, valueView, model);
        return cn.contains("LinkViewModel") || cn.contains("Hyperlink") || cn.contains("LinkValue") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("LinkEditor") || cn.contains("OpenEditor") || cn.contains("ModuleLink") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("ReferenceEditor") || cn.contains("LwtLinkView") || cn.contains("SwtLinkView"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean isTextType(Object valueVm, Object valueView, Object model)
    {
        if (kindFromView(valueView) == Kind.TEXT)
            return true;
        String cn = typeNames(valueVm, valueView, model);
        if (cn.contains("LinkViewModel") || cn.contains("LwtLinkView") || cn.contains("SwtLinkView")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false;
        return cn.contains("StringValueControlViewModel") || cn.contains("ValueControlViewModel") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("TextViewModel") || cn.contains("StringValue") || cn.contains("TextValue") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("MdText") || cn.contains("LightText") || cn.contains("InputValue") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("EditableString") || cn.contains("StringEditor") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("LwtTextView") || cn.contains("SwtTextView") || cn.contains("FormattedText"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void logValueRow(String propName, Kind kind, String kindReason, String display,
            Object valueVm, Object valueView, Control nativeValue, String resolvePath)
    {
        if (propName == null || propName.isEmpty())
            return;
        PropertySheetDebug.valueControl(PropertySheetDebug.quote(propName)
                + " kind=" + kind //$NON-NLS-1$
                + " reason=" + kindReason //$NON-NLS-1$
                + " display=" + PropertySheetDebug.quote(display) //$NON-NLS-1$
                + " vm=" + PropertySheetDebug.typeName(valueVm) //$NON-NLS-1$
                + " view=" + PropertySheetDebug.typeName(valueView) //$NON-NLS-1$
                + " native=" + PropertySheetDebug.quote(nativeControlText(nativeValue)) //$NON-NLS-1$
                + " resolve=" + PropertySheetDebug.quote(resolvePath != null ? resolvePath : "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Kind detectKindFromNativeRow(Control nativeValue)
    {
        Composite row = nativeRowOf(nativeValue);
        if (row == null)
            return null;
        NativeRowKinds kinds = new NativeRowKinds();
        collectNativeRowKinds(row, nativeValue, kinds);
        if (kinds.hasText)
            return Kind.TEXT;
        if (kinds.hasCheck)
            return Kind.BOOLEAN;
        if (kinds.hasCombo)
            return Kind.COMBO;
        if (kinds.hasSpinner)
            return Kind.SPINNER;
        if (kinds.hasLink)
            return Kind.HYPERLINK;
        return null;
    }

    private static final class NativeRowKinds
    {
        boolean hasText;
        boolean hasLink;
        boolean hasCheck;
        boolean hasCombo;
        boolean hasSpinner;
    }

    private static Composite nativeRowOf(Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        Composite row = control instanceof Composite ? (Composite) control : control.getParent();
        while (row != null && !row.isDisposed())
        {
            if (row.getParent() instanceof org.eclipse.swt.custom.ScrolledComposite)
                return row;
            Control[] children = row.getChildren();
            if (children.length >= 2)
                return row;
            row = row.getParent();
        }
        return control.getParent();
    }

    private static void collectNativeRowKinds(Composite row, Control skip, NativeRowKinds kinds)
    {
        if (row == null || row.isDisposed() || kinds == null)
            return;
        for (Control child : row.getChildren())
        {
            if (child == null || child.isDisposed() || child == skip)
                continue;
            if (child instanceof Text)
                kinds.hasText = true;
            else if (child instanceof Link)
                kinds.hasLink = true;
            else if (child instanceof Button && (((Button) child).getStyle() & SWT.CHECK) != 0)
                kinds.hasCheck = true;
            else if (child instanceof Combo || child instanceof CCombo)
                kinds.hasCombo = true;
            else if (child instanceof Spinner)
                kinds.hasSpinner = true;
            else if (child instanceof Composite)
                collectNativeRowKinds((Composite) child, skip, kinds);
        }
    }

    static boolean isOpenPlaceholder(String text)
    {
        return "Открыть".equals(text); //$NON-NLS-1$
    }

    private static boolean isComboType(Object valueVm, Object valueView, Object model)
    {
        String cn = typeNames(valueVm, valueView, model);
        return cn.contains("Combo") || cn.contains("Enum") || cn.contains("Choice") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("ListValue") || cn.contains("Select") || cn.contains("Literal") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("LightCombo"); //$NON-NLS-1$
    }

    private static boolean isBooleanType(Object valueVm, Object valueView, Object model)
    {
        if (model != null && Global.invoke(model, "getValue") instanceof Boolean) //$NON-NLS-1$
            return true;
        String cn = typeNames(valueVm, valueView, model);
        return cn.contains("Boolean") || cn.contains("CheckBox") || cn.contains("Checkbox") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("Toggle") || cn.contains("LightCheckBox") || cn.contains("MdCheck"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean isNumericType(Object valueVm, Object valueView, Object model, String displayValue)
    {
        String cn = typeNames(valueVm, valueView, model);
        if (cn.contains("Spinner") || cn.contains("IntegerValue") || cn.contains("Decimal") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("NumberValue") || cn.contains("Numeric") || cn.contains("LightSpinner")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return true;
        if (displayValue != null && !displayValue.isEmpty() && displayValue.matches("-?\\d+(?:[.,]\\d+)?")) //$NON-NLS-1$
        {
            String cnAll = typeNames(valueVm, valueView, model);
            return !cnAll.contains("Enum") && !cnAll.contains("Combo") && !cnAll.contains("Code"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return false;
    }

    private static boolean hasComboItems(Object model, Object valueVm)
    {
        List<String> items = new ArrayList<>();
        collectItems(items, model);
        if (items.size() < 2 && valueVm != null)
            collectItems(items, valueVm);
        return items.size() >= 2;
    }

    private static String typeNames(Object valueVm, Object valueView, Object model)
    {
        StringBuilder sb = new StringBuilder();
        if (valueVm != null)
            sb.append(valueVm.getClass().getName());
        if (valueView != null)
            sb.append(valueView.getClass().getName());
        if (model != null)
            sb.append(model.getClass().getName());
        return sb.toString();
    }

    private static String firstNonEmpty(String... values)
    {
        for (String value : values)
        {
            if (value != null && !value.isEmpty())
                return value;
        }
        return ""; //$NON-NLS-1$
    }

    private static int indexOf(List<String> items, String value)
    {
        if (value == null || items == null)
            return -1;
        for (int i = 0; i < items.size(); i++)
        {
            if (value.equals(items.get(i)))
                return i;
        }
        return -1;
    }

    private static void addItem(List<String> items, String item)
    {
        if (item == null || item.isEmpty())
            return;
        if (!items.contains(item))
            items.add(item);
    }

    private static String asString(Object value)
    {
        if (value == null)
            return ""; //$NON-NLS-1$
        if (value instanceof String)
            return (String) value;
        if (value instanceof Boolean || value instanceof Number)
            return value.toString();
        Object text = Global.invoke(value, "getText"); //$NON-NLS-1$
        if (text instanceof String && !((String) text).isEmpty())
            return (String) text;
        Object label = Global.invoke(value, "getLabel"); //$NON-NLS-1$
        if (label instanceof String && !((String) label).isEmpty())
            return (String) label;
        Object name = Global.invoke(value, "getName"); //$NON-NLS-1$
        if (name instanceof String && !((String) name).isEmpty())
            return (String) name;
        String asString = value.toString();
        return asString != null ? asString : ""; //$NON-NLS-1$
    }

    private static Iterator<?> toIterator(Object raw)
    {
        if (raw instanceof Iterable)
            return ((Iterable<?>) raw).iterator();
        if (raw instanceof Object[])
        {
            Object[] arr = (Object[]) raw;
            List<Object> list = new ArrayList<>(arr.length);
            for (Object o : arr)
                list.add(o);
            return list.iterator();
        }
        return null;
    }

    private static String escapeLinkText(String text)
    {
        return text.replace("&", "&&"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String stripLinkMarkup(String text)
    {
        if (text == null)
            return ""; //$NON-NLS-1$
        return text.replace("<a>", "").replace("</a>", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static boolean isActionBarField(Object valueVm, Object valueView)
    {
        String cn = typeNames(valueVm, valueView, null);
        return cn.contains("ActionBarView") || cn.contains("SelectViewModel") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("ActionBarViewModel"); //$NON-NLS-1$
    }

    private static boolean hasActionBarButtons(Object valueVm, Object valueView, Control nativeValue)
    {
        if (!isActionBarField(valueVm, valueView))
            return false;
        if (visibleActionButtonItemCount(valueVm, valueView) > 0)
            return true;
        Composite host = findActionBarHost(nativeValue, valueView);
        return host != null && countIconPushButtons(host) > 0;
    }

    private static int visibleActionButtonItemCount(Object valueVm, Object valueView)
    {
        Object buttons = valueVm != null ? Global.invoke(valueVm, "getButtons") : null; //$NON-NLS-1$
        if (buttons == null && valueView != null)
            buttons = Global.invoke(valueView, "getButtons"); //$NON-NLS-1$
        Iterator<?> it = toIterator(buttons);
        if (it == null)
            return 0;
        int count = 0;
        while (it.hasNext())
        {
            Object item = it.next();
            if (!readBooleanProperty(item, "isVisible", true)) //$NON-NLS-1$
                continue;
            if (hasActionButtonMarker(item))
                count++;
        }
        return count;
    }

    private static boolean hasActionButtonMarker(Object item)
    {
        if (item == null)
            return false;
        if (Global.invoke(item, "getImage") != null) //$NON-NLS-1$
            return true;
        String contextId = asString(Global.invoke(item, "getContextId")); //$NON-NLS-1$
        return contextId != null && !contextId.isEmpty();
    }

    private static int resolveActionBarButtonCount(Object valueVm, Object valueView, List<Button> nativeButtons)
    {
        int visible = visibleActionButtonItemCount(valueVm, valueView);
        if (visible > 0)
            return visible;
        if (nativeButtons != null && !nativeButtons.isEmpty())
            return nativeButtons.size();
        return 2;
    }

    private static int collectionSize(Object collection)
    {
        if (collection == null)
            return 0;
        if (collection instanceof java.util.Collection)
            return ((java.util.Collection<?>) collection).size();
        Iterator<?> it = toIterator(collection);
        if (it == null)
            return 0;
        int count = 0;
        while (it.hasNext())
        {
            it.next();
            count++;
        }
        return count;
    }

    static boolean resolveEditableForRow(Object page, Object valueVm, Object valueView, Control nativeValue,
            Control nativePaletteRoot, String propertyName)
    {
        return resolveEditable(valueVm, valueView, nativeValue, nativePaletteRoot, propertyName, page);
    }

    static void applyEditableState(Created created, boolean editable, Object valueVm, Object valueView,
            Control nativeValue)
    {
        if (created == null || created.control == null || created.control.isDisposed())
            return;
        logFieldState(null, created.kind, "applyEditableState", editable, //$NON-NLS-1$
                "ctrl=" + PropertySheetDebug.controlBrief(created.control)); //$NON-NLS-1$
        switch (created.kind)
        {
            case BOOLEAN:
                applyEnabledState(created.control, editable);
                break;
            case COMBO:
                applyComboFieldState(created.control, editable, null);
                break;
            case SPINNER:
            case TEXT:
                if (created.control instanceof Text)
                    applyTextFieldState((Text) created.control, editable, editable);
                else if (created.kind == Kind.SPINNER && created.control instanceof Spinner)
                    applySpinnerFieldState((Spinner) created.control, editable);
                break;
            case ACTION_BAR:
                applyActionBarEditable(created.control, editable, valueVm, valueView, nativeValue);
                break;
            default:
                break;
        }
    }

    private static void applyActionBarEditable(Control control, boolean editable, Object valueVm,
            Object valueView, Control nativeValue)
    {
        applyActionBarFieldState(control, editable, nativeValue);
        decorateActionBarButtons(actionBarButtons(control),
                collectPushButtons(findActionBarHost(nativeValue, valueView)), valueVm, valueView, editable);
    }

    private static boolean resolveEditable(Object valueVm, Object valueView, Control nativeValue)
    {
        return resolveEditable(valueVm, valueView, nativeValue, null, null, null);
    }

    private static boolean resolveEditable(Object valueVm, Object valueView, Control nativeValue,
            Control nativePaletteRoot, String propertyName, Object page)
    {
        String prop = propertyName != null ? propertyName : "?"; //$NON-NLS-1$

        Boolean fromPalette = PropertySheetComfortUi.readNativePaletteEditable(nativePaletteRoot, propertyName,
                nativeValue);
        logEditableProbe(prop, "palette", fromPalette); //$NON-NLS-1$
        if (fromPalette != null && fromPalette.booleanValue())
            return finishEditable(prop, true, "palette=true"); //$NON-NLS-1$

        if (nativeValue == null || nativeValue.isDisposed())
            PropertySheetDebug.valueControlVerbose("nativeValue MISS " + PropertySheetDebug.quote(prop)); //$NON-NLS-1$

        Boolean nativeEnabled = readNativeEnabled(nativeValue);
        logEditableProbe(prop, "nativeSwt", nativeEnabled); //$NON-NLS-1$
        if (nativeEnabled != null && nativeEnabled.booleanValue())
            return finishEditable(prop, true, "nativeSwt=true"); //$NON-NLS-1$

        Boolean fromRendererSwt = PropertySheetControlInterop.readSwtEditableFromRenderer(page, valueVm, valueView);
        logEditableProbe(prop, "rendererSwt", fromRendererSwt); //$NON-NLS-1$
        if (fromRendererSwt != null && fromRendererSwt.booleanValue())
            return finishEditable(prop, true, "rendererSwt=true"); //$NON-NLS-1$

        Boolean fromRenderer = PropertySheetControlInterop.readEditableFromRenderer(page, valueVm, valueView);
        logEditableProbe(prop, "renderer", fromRenderer); //$NON-NLS-1$
        if (fromRenderer != null && fromRenderer.booleanValue())
            return finishEditable(prop, true, "renderer"); //$NON-NLS-1$

        Boolean fromViewInterop = PropertySheetControlInterop.readEditableFromView(valueView, valueVm);
        logEditableProbe(prop, "viewInterop", fromViewInterop); //$NON-NLS-1$
        if (fromViewInterop != null && fromViewInterop.booleanValue())
            return finishEditable(prop, true, "viewInterop"); //$NON-NLS-1$

        Boolean readOnly = readFieldReadOnly(valueVm, valueView, page);
        logEditableProbe(prop, "readOnly", readOnly); //$NON-NLS-1$
        if (readOnly != null && !readOnly.booleanValue())
            return finishEditable(prop, false, "readOnly"); //$NON-NLS-1$

        if (valueView != null)
        {
            Object enabled = Global.invoke(valueView, "isEnabled"); //$NON-NLS-1$
            if (enabled instanceof Boolean && ((Boolean) enabled).booleanValue())
                return finishEditable(prop, true, "view.isEnabled"); //$NON-NLS-1$
            Object editable = Global.invoke(valueView, "isEditable"); //$NON-NLS-1$
            if (editable instanceof Boolean && ((Boolean) editable).booleanValue())
                return finishEditable(prop, true, "view.isEditable"); //$NON-NLS-1$
        }

        Object chooser = findValueChooser(valueView, nativeValue);
        if (chooser != null)
        {
            Object editable = Global.invoke(chooser, "isEditable"); //$NON-NLS-1$
            if (editable instanceof Boolean && ((Boolean) editable).booleanValue())
                return finishEditable(prop, true, "chooser.isEditable"); //$NON-NLS-1$
            editable = Global.invoke(chooser, "getEditable"); //$NON-NLS-1$
            if (editable instanceof Boolean && ((Boolean) editable).booleanValue())
                return finishEditable(prop, true, "chooser.getEditable"); //$NON-NLS-1$
            Object enabled = Global.invoke(chooser, "isEnabled"); //$NON-NLS-1$
            if (enabled instanceof Boolean && ((Boolean) enabled).booleanValue())
                return finishEditable(prop, true, "chooser.isEnabled"); //$NON-NLS-1$
        }

        Object model = valueVm != null ? Global.invoke(valueVm, "getModel") : null; //$NON-NLS-1$
        if (model != null)
        {
            Object editable = Global.invoke(model, "isEditable"); //$NON-NLS-1$
            if (editable instanceof Boolean && ((Boolean) editable).booleanValue())
                return finishEditable(prop, true, "model.isEditable"); //$NON-NLS-1$
            Object enabled = Global.invoke(model, "isEnabled"); //$NON-NLS-1$
            if (enabled instanceof Boolean && ((Boolean) enabled).booleanValue())
                return finishEditable(prop, true, "model.isEnabled"); //$NON-NLS-1$
        }
        if (valueVm != null)
        {
            Object enabled = Global.invoke(valueVm, "isEnabled"); //$NON-NLS-1$
            if (enabled instanceof Boolean && ((Boolean) enabled).booleanValue())
                return finishEditable(prop, true, "vm.isEnabled"); //$NON-NLS-1$
            Object editable = Global.invoke(valueVm, "isEditable"); //$NON-NLS-1$
            if (editable instanceof Boolean && ((Boolean) editable).booleanValue())
                return finishEditable(prop, true, "vm.isEditable"); //$NON-NLS-1$
        }

        if (fromPalette != null)
            return finishEditable(prop, fromPalette.booleanValue(), "palette=false"); //$NON-NLS-1$

        if (nativeEnabled != null)
            return finishEditable(prop, nativeEnabled.booleanValue(), "nativeSwt=false"); //$NON-NLS-1$

        if (fromRendererSwt != null)
            return finishEditable(prop, fromRendererSwt.booleanValue(), "rendererSwt=false"); //$NON-NLS-1$

        if (fromRenderer != null)
            return finishEditable(prop, fromRenderer.booleanValue(), "renderer=false"); //$NON-NLS-1$

        if (fromViewInterop != null)
            return finishEditable(prop, fromViewInterop.booleanValue(), "viewInterop=false"); //$NON-NLS-1$

        boolean fallback = nativeValue != null || valueVm != null;
        return finishEditable(prop, fallback, "fallback"); //$NON-NLS-1$
    }

    /** true = редактируемо, false = ТолькоПросмотр/readonly, null = неизвестно. */
    private static Boolean readFieldReadOnly(Object valueVm, Object valueView, Object page)
    {
        for (Object target : new Object[] { valueVm, valueView,
                valueVm != null ? Global.invoke(valueVm, "getModel") : null, page }) //$NON-NLS-1$
        {
            if (target == null)
                continue;
            Object readOnly = Global.invoke(target, "isReadOnly"); //$NON-NLS-1$
            if (readOnly instanceof Boolean)
                return Boolean.valueOf(!((Boolean) readOnly).booleanValue());
        }
        Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
        if (scene != null)
        {
            Object readOnly = Global.invoke(scene, "isReadOnly"); //$NON-NLS-1$
            if (readOnly instanceof Boolean)
                return Boolean.valueOf(!((Boolean) readOnly).booleanValue());
        }
        return null;
    }

    private static void logEditableProbe(String propertyName, String source, Boolean value)
    {
        PropertySheetDebug.valueControl("editable? " + PropertySheetDebug.quote(propertyName) //$NON-NLS-1$
                + " " + source + "=" + (value != null ? value : "null")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static boolean finishEditable(String propertyName, boolean editable, String source)
    {
        PropertySheetDebug.valueControl("editable " + PropertySheetDebug.quote(propertyName) //$NON-NLS-1$
                + " → " + editable + " (" + source + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return editable;
    }

    private static void logFieldState(String propertyName, Kind kind, String event, boolean active,
            String detail)
    {
        PropertySheetDebug.valueControl((propertyName != null ? PropertySheetDebug.quote(propertyName) + " " : "") //$NON-NLS-1$ //$NON-NLS-2$
                + kind + " " + event + " active=" + active //$NON-NLS-1$ //$NON-NLS-2$
                + (detail != null && !detail.isEmpty() ? " " + detail : "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Boolean readNativeEnabled(Control nativeValue)
    {
        if (nativeValue == null || nativeValue.isDisposed())
            return null;
        if (nativeValue instanceof Text)
            return Boolean.valueOf(((Text) nativeValue).getEditable() && nativeValue.getEnabled());
        if (nativeValue instanceof CCombo)
            return Boolean.valueOf(((CCombo) nativeValue).getEnabled());
        if (nativeValue instanceof Combo)
            return Boolean.valueOf(((Combo) nativeValue).getEnabled());
        if (nativeValue instanceof Spinner)
            return Boolean.valueOf(((Spinner) nativeValue).getEnabled());
        if (nativeValue instanceof Button)
            return Boolean.valueOf(nativeValue.getEnabled());
        if (nativeValue instanceof Composite)
        {
            Boolean nested = readNativeEnabledInComposite((Composite) nativeValue);
            if (nested != null)
                return nested;
        }
        Composite row = nativeRowOf(nativeValue);
        if (row == null)
            return null;
        for (Control child : row.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            if (child instanceof CCombo)
                return Boolean.valueOf(((CCombo) child).getEnabled());
            if (child instanceof Combo)
                return Boolean.valueOf(((Combo) child).getEnabled());
            if (child instanceof Spinner)
                return Boolean.valueOf(((Spinner) child).getEnabled());
            if (child instanceof Text)
                return Boolean.valueOf(((Text) child).getEditable() && child.getEnabled());
            if (child instanceof Composite)
            {
                Boolean nested = readNativeEnabledInComposite((Composite) child);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }

    private static Boolean readNativeEnabledInComposite(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return null;
        Text actionBarText = null;
        boolean hasPushButtons = false;
        boolean anyPushEnabled = false;
        for (Control child : composite.getChildren())
        {
            if (child instanceof Text && actionBarText == null)
                actionBarText = (Text) child;
            else if (child instanceof Button && (child.getStyle() & SWT.PUSH) != 0)
            {
                hasPushButtons = true;
                if (child.getEnabled())
                    anyPushEnabled = true;
            }
        }
        if (actionBarText != null && hasPushButtons)
        {
            if (!composite.getEnabled())
                return Boolean.FALSE;
            if (anyPushEnabled || (actionBarText.getEditable() && actionBarText.getEnabled()))
                return Boolean.TRUE;
            return Boolean.FALSE;
        }
        for (Control child : composite.getChildren())
        {
            if (child instanceof CCombo)
                return Boolean.valueOf(((CCombo) child).getEnabled());
            if (child instanceof Combo)
                return Boolean.valueOf(((Combo) child).getEnabled());
            if (child instanceof Spinner)
                return Boolean.valueOf(((Spinner) child).getEnabled());
            if (child instanceof Text)
                return Boolean.valueOf(((Text) child).getEditable() && child.getEnabled());
            if (child instanceof Composite)
            {
                Boolean nested = readNativeEnabledInComposite((Composite) child);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }

    private static Color editableBackground(Control control)
    {
        org.eclipse.swt.widgets.Display display = control.getDisplay();
        Object cached = display.getData(EDITABLE_BG_CACHE_KEY);
        if (cached instanceof Color && !((Color) cached).isDisposed())
            return (Color) cached;
        Color bg = new Color(display, 255, 255, 255);
        display.setData(EDITABLE_BG_CACHE_KEY, bg);
        return bg;
    }

    private static Color readOnlyBackground(Control control)
    {
        return control.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
    }

    private static void applyEditableBackground(Control control, boolean active)
    {
        if (control == null || control.isDisposed())
            return;
        if (control instanceof CCombo)
        {
            applyComboAppearance((CCombo) control, active);
            return;
        }
        if (control instanceof Combo)
        {
            applyComboAppearance((Combo) control, active);
            return;
        }
        control.setData(FIELD_ACTIVE_KEY, Boolean.valueOf(active));
        Color bg = active ? editableBackground(control) : readOnlyBackground(control);
        control.setBackground(bg);
        control.setData("org.eclipse.e4.ui.css.swt.theme.backgroundColor", //$NON-NLS-1$
                active ? "COLOR_LIST_BACKGROUND" : "COLOR_WIDGET_BACKGROUND"); //$NON-NLS-1$ //$NON-NLS-2$
        control.redraw();
    }

    /**
     * Фон закрытого поля CCombo/Combo без CSS-хинтов — иначе Eclipse перекрашивает
     * выпадающий список и пропадает подсветка выбранного/hover пункта.
     */
    private static void applyComboAppearance(Combo combo, boolean active)
    {
        if (combo == null || combo.isDisposed())
            return;
        combo.setData(FIELD_ACTIVE_KEY, Boolean.valueOf(active));
        combo.setBackground(active ? editableBackground(combo) : readOnlyBackground(combo));
        combo.setData("org.eclipse.e4.ui.css.swt.theme.backgroundColor", null); //$NON-NLS-1$
    }

    private static void applyComboAppearance(CCombo combo, boolean active)
    {
        if (combo == null || combo.isDisposed())
            return;
        combo.setData(FIELD_ACTIVE_KEY, Boolean.valueOf(active));
        combo.setBackground(active ? editableBackground(combo) : readOnlyBackground(combo));
        combo.setData("org.eclipse.e4.ui.css.swt.theme.backgroundColor", null); //$NON-NLS-1$
    }

    private static void syncComboItemsAndSelection(CCombo combo, List<String> items, String value)
    {
        if (combo == null || combo.isDisposed())
            return;
        List<String> merged = items != null ? new ArrayList<>(items) : new ArrayList<>();
        String text = value != null ? value : ""; //$NON-NLS-1$
        if (!text.isEmpty() && indexOf(merged, text) < 0 && indexOfIgnoreCase(merged, text) < 0)
            merged.add(0, text);
        if (!merged.isEmpty())
            combo.setItems(merged.toArray(new String[0]));
        syncComboSelectionFromText(combo, text);
    }

    private static void syncComboItemsAndSelection(Combo combo, List<String> items, String value)
    {
        if (combo == null || combo.isDisposed())
            return;
        List<String> merged = items != null ? new ArrayList<>(items) : new ArrayList<>();
        String text = value != null ? value : ""; //$NON-NLS-1$
        if (!text.isEmpty() && indexOf(merged, text) < 0 && indexOfIgnoreCase(merged, text) < 0)
            merged.add(0, text);
        if (!merged.isEmpty())
            combo.setItems(merged.toArray(new String[0]));
        syncComboSelectionFromText(combo, text);
    }

    private static void syncComboSelectionFromText(CCombo combo)
    {
        syncComboSelectionFromText(combo, combo != null ? combo.getText() : null);
    }

    private static void syncComboSelectionFromText(CCombo combo, String value)
    {
        if (combo == null || combo.isDisposed())
            return;
        if (value == null || value.isEmpty())
            return;
        int idx = combo.indexOf(value);
        if (idx < 0)
            idx = indexOfIgnoreCase(java.util.Arrays.asList(combo.getItems()), value);
        if (idx >= 0 && combo.getSelectionIndex() != idx)
            combo.select(idx);
    }

    private static void syncComboSelectionFromText(Combo combo)
    {
        syncComboSelectionFromText(combo, combo != null ? combo.getText() : null);
    }

    private static void syncComboSelectionFromText(Combo combo, String value)
    {
        if (combo == null || combo.isDisposed())
            return;
        if (value == null || value.isEmpty())
            return;
        int idx = combo.indexOf(value);
        if (idx < 0)
            idx = indexOfIgnoreCase(java.util.Arrays.asList(combo.getItems()), value);
        if (idx >= 0 && combo.getSelectionIndex() != idx)
            combo.select(idx);
    }

    private static final String COMBO_SELECTION_SYNC_KEY = "comfort.combo.selectionSync"; //$NON-NLS-1$

    private static void wireCComboSelectionSync(CCombo combo)
    {
        if (combo == null || combo.isDisposed()
                || Boolean.TRUE.equals(combo.getData(COMBO_SELECTION_SYNC_KEY)))
            return;
        combo.setData(COMBO_SELECTION_SYNC_KEY, Boolean.TRUE);
        combo.addListener(SWT.MouseDown, e -> {
            if (e.button == 1)
                syncComboSelectionFromText(combo);
        });
        combo.addListener(SWT.FocusIn, e -> syncComboSelectionFromText(combo));
    }

    private static void wireCComboSelectionSync(Combo combo)
    {
        if (combo == null || combo.isDisposed()
                || Boolean.TRUE.equals(combo.getData(COMBO_SELECTION_SYNC_KEY)))
            return;
        combo.setData(COMBO_SELECTION_SYNC_KEY, Boolean.TRUE);
        combo.addListener(SWT.MouseDown, e -> {
            if (e.button == 1)
                syncComboSelectionFromText(combo);
        });
        combo.addListener(SWT.FocusIn, e -> syncComboSelectionFromText(combo));
    }

    private static Control findComboInComposite(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return null;
        for (Control child : composite.getChildren())
        {
            if (child instanceof Combo || child instanceof CCombo)
                return child;
            if (child instanceof Composite)
            {
                Control nested = findComboInComposite((Composite) child);
                if (nested != null)
                    return nested;
            }
        }
        return null;
    }

    private static void applyTextFieldState(Text text, boolean active, boolean allowTyping)
    {
        if (text == null || text.isDisposed())
            return;
        text.setEnabled(true);
        if (active)
        {
            if (allowTyping)
            {
                text.setEditable(true);
                text.setData(TEXT_SELECT_ONLY_KEY, null);
            }
            else
            {
                text.setEditable(true);
                text.setData(TEXT_SELECT_ONLY_KEY, Boolean.TRUE);
                wireSelectOnlyVerify(text);
            }
            applyEditableBackground(text, true);
        }
        else
        {
            text.setEditable(false);
            text.setData(TEXT_SELECT_ONLY_KEY, null);
            applyEditableBackground(text, false);
        }
    }

    private static void setTextProgrammatic(Text text, String value)
    {
        if (text == null || text.isDisposed())
            return;
        text.setData(TEXT_PROGRAMMATIC_KEY, Boolean.TRUE);
        try
        {
            text.setText(value != null ? value : ""); //$NON-NLS-1$
        }
        finally
        {
            text.setData(TEXT_PROGRAMMATIC_KEY, null);
        }
    }

    private static void wireSelectOnlyVerify(Text text)
    {
        if (text == null || text.isDisposed()
                || Boolean.TRUE.equals(text.getData(TEXT_VERIFY_GUARD_KEY)))
            return;
        text.setData(TEXT_VERIFY_GUARD_KEY, Boolean.TRUE);
        text.addVerifyListener(e -> {
            if (Boolean.TRUE.equals(text.getData(TEXT_PROGRAMMATIC_KEY)))
                return;
            if (!Boolean.TRUE.equals(text.getData(TEXT_SELECT_ONLY_KEY)))
                return;
            e.doit = false;
        });
    }

    private static boolean resolveActionBarAllowTyping(Control nativeValue, boolean editable)
    {
        if (!editable)
            return false;
        Text nativeText = findRowText(nativeValue);
        return nativeText != null && !nativeText.isDisposed() && nativeText.getEditable();
    }

    private static void applyComboFieldState(Control control, boolean fieldActive, String propertyName)
    {
        if (control == null || control.isDisposed())
            return;
        if (control instanceof CCombo)
        {
            CCombo combo = (CCombo) control;
            combo.setEnabled(fieldActive);
            applyComboAppearance(combo, fieldActive);
            wireCComboSelectionSync(combo);
            syncComboSelectionFromText(combo);
            control.setData(COMBO_FIELD_ACTIVE_KEY, Boolean.valueOf(fieldActive));
            logFieldState(propertyName, Kind.COMBO, "applyComboFieldState", fieldActive, //$NON-NLS-1$
                    "ccombo=true enabled=" + combo.getEnabled() //$NON-NLS-1$
                    + " sel=" + combo.getSelectionIndex() //$NON-NLS-1$
                    + " bg=" + (fieldActive ? "white" : "gray")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        }
        if (control instanceof Combo)
        {
            Combo combo = (Combo) control;
            combo.setEnabled(fieldActive);
            applyComboAppearance(combo, fieldActive);
            wireCComboSelectionSync(combo);
            syncComboSelectionFromText(combo);
            control.setData(COMBO_FIELD_ACTIVE_KEY, Boolean.valueOf(fieldActive));
            logFieldState(propertyName, Kind.COMBO, "applyComboFieldState", fieldActive, //$NON-NLS-1$
                    "combo=true enabled=" + combo.getEnabled() //$NON-NLS-1$
                    + " sel=" + combo.getSelectionIndex() //$NON-NLS-1$
                    + " bg=" + (fieldActive ? "white" : "gray")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return;
        }
        Text text = comboText(control);
        Button arrow = comboArrow(control);
        if (text != null && !text.isDisposed())
            applyComboTextState(text, fieldActive);
        if (arrow != null && !arrow.isDisposed())
        {
            arrow.setEnabled(fieldActive);
            arrow.setMenu(null);
            applyEditableBackground(arrow, fieldActive);
        }
        if (!fieldActive)
            closeComboPopup((Composite) control);
        if (control instanceof Composite)
            applyComboPanelChrome((Composite) control);
        control.setData(COMBO_FIELD_ACTIVE_KEY, Boolean.valueOf(fieldActive));
        if (control instanceof Composite)
            wireComboPopup((Composite) control);
        logFieldState(propertyName, Kind.COMBO, "applyComboFieldState", fieldActive, //$NON-NLS-1$
                "arrow=" + (arrow != null && arrow.getEnabled()) //$NON-NLS-1$
                + " textEditable=" + (text != null && text.getEditable()) //$NON-NLS-1$
                + " bg=" + (fieldActive ? "white" : "gray")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Рамка панели COMBO: фон панели не белим — иначе SWT.BORDER сливается с соседними полями. */
    private static void applyComboPanelChrome(Composite panel)
    {
        if (panel == null || panel.isDisposed())
            return;
        Color widgetBg = panel.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND);
        panel.setBackground(widgetBg);
        panel.setData("org.eclipse.e4.ui.css.swt.theme.backgroundColor", "COLOR_WIDGET_BACKGROUND"); //$NON-NLS-1$ //$NON-NLS-2$
        panel.redraw();
    }

    private static void wireComboPanelBorder(Composite panel)
    {
        if (panel == null || panel.isDisposed() || Boolean.TRUE.equals(panel.getData(COMBO_BORDER_KEY)))
            return;
        panel.setData(COMBO_BORDER_KEY, Boolean.TRUE);
        panel.addPaintListener(PropertySheetComfortValueControls::paintComboPanelBorder);
    }

    private static void paintComboPanelBorder(PaintEvent e)
    {
        Composite panel = (Composite) e.widget;
        if (panel.isDisposed())
            return;
        Rectangle area = panel.getClientArea();
        e.gc.setForeground(panel.getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
        e.gc.drawRectangle(0, 0, area.width - 1, area.height - 1);
    }

    /** Текст перечисления: выделение/копирование; ввод с клавиатуры блокируется. */
    private static void applyComboTextState(Text text, boolean fieldActive)
    {
        if (text == null || text.isDisposed())
            return;
        text.setEnabled(true);
        text.setEditable(true);
        text.setData(TEXT_SELECT_ONLY_KEY, Boolean.TRUE);
        wireSelectOnlyVerify(text);
        applyEditableBackground(text, fieldActive);
    }

    private static Text comboText(Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        Object data = control.getData(COMBO_TEXT_KEY);
        return data instanceof Text ? (Text) data : null;
    }

    private static Button comboArrow(Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        Object data = control.getData(COMBO_ARROW_KEY);
        return data instanceof Button ? (Button) data : null;
    }

    private static void wireComboChange(Control control, Runnable onChange)
    {
        Text text = comboText(control);
        if (text != null && !text.isDisposed())
            text.addModifyListener(e -> onChange.run());
        else if (control != null && !control.isDisposed())
            control.addListener(SWT.Selection, e -> onChange.run());
    }

    private static void wireComboPopup(Composite panel)
    {
        if (panel == null || panel.isDisposed()
                || Boolean.TRUE.equals(panel.getData(COMBO_POPUP_WIRED_KEY)))
            return;
        panel.setData(COMBO_POPUP_WIRED_KEY, Boolean.TRUE);
        Button arrow = comboArrow(panel);
        if (arrow != null && !arrow.isDisposed())
            arrow.addListener(SWT.Selection, e -> showComboListPopup(panel));
    }

    private static void closeComboPopup(Composite panel)
    {
        if (panel == null || panel.isDisposed())
            return;
        Object data = panel.getData(COMBO_POPUP_KEY);
        if (data instanceof Shell)
        {
            Shell shell = (Shell) data;
            if (!shell.isDisposed())
                shell.close();
        }
        panel.setData(COMBO_POPUP_KEY, null);
    }

    private static void showComboListPopup(Composite panel)
    {
        if (panel == null || panel.isDisposed())
            return;
        if (!Boolean.TRUE.equals(panel.getData(COMBO_FIELD_ACTIVE_KEY)))
        {
            PropertySheetDebug.valueControl("combo list blocked (read-only)"); //$NON-NLS-1$
            return;
        }
        Text text = comboText(panel);
        Object raw = panel.getData(COMBO_ITEMS_KEY);
        if (text == null || text.isDisposed() || !(raw instanceof String[])
                || ((String[]) raw).length == 0)
            return;
        String[] items = (String[]) raw;
        closeComboPopup(panel);

        Shell popup = new Shell(panel.getShell(), SWT.NO_TRIM | SWT.ON_TOP);
        panel.setData(COMBO_POPUP_KEY, popup);
        org.eclipse.swt.widgets.List list = new org.eclipse.swt.widgets.List(popup, SWT.SINGLE | SWT.V_SCROLL);
        list.setItems(items);
        Font fieldFont = text.getFont();
        if (fieldFont != null && !fieldFont.isDisposed())
            list.setFont(fieldFont);
        String current = text.getText();
        int sel = indexOf(java.util.Arrays.asList(items), current);
        if (sel < 0)
            sel = indexOfIgnoreCase(java.util.Arrays.asList(items), current);
        if (sel >= 0)
            list.select(sel);

        int itemHeight = list.getItemHeight();
        int visible = Math.min(Math.max(items.length, 1), 12);
        int width = Math.max(panel.getSize().x, 80);
        int height = visible * itemHeight + 4;
        list.setBounds(0, 0, width, height);
        popup.setSize(width, height);
        Point location = panel.toDisplay(0, panel.getSize().y);
        popup.setLocation(location);

        Runnable commitSelection = () -> {
            if (list.isDisposed() || text.isDisposed())
                return;
            int idx = list.getSelectionIndex();
            if (idx >= 0)
            {
                setTextProgrammatic(text, items[idx]);
                PropertySheetDebug.valueControlVerbose("combo select " //$NON-NLS-1$
                        + PropertySheetDebug.quote(items[idx]));
                Event modify = new Event();
                text.notifyListeners(SWT.Modify, modify);
            }
            closeComboPopup(panel);
        };

        list.addListener(SWT.MouseMove, e -> {
            if (list.isDisposed())
                return;
            int index = list.getTopIndex() + e.y / Math.max(1, itemHeight);
            if (index >= 0 && index < list.getItemCount() && index != list.getSelectionIndex())
                list.select(index);
        });
        list.addListener(SWT.MouseUp, e -> {
            if (e.button == 1)
                commitSelection.run();
        });
        list.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_RETURN)
            {
                commitSelection.run();
                e.doit = false;
            }
            else if (e.detail == SWT.TRAVERSE_ESCAPE)
            {
                closeComboPopup(panel);
                e.doit = false;
            }
        });
        popup.addListener(SWT.Deactivate, e -> closeComboPopup(panel));

        popup.setVisible(true);
        list.setFocus();
        PropertySheetDebug.valueControlVerbose("combo list open items=" + items.length); //$NON-NLS-1$
    }

    private static void applySpinnerFieldState(Spinner spinner, boolean editable)
    {
        if (spinner == null || spinner.isDisposed())
            return;
        spinner.setEnabled(editable);
        applyEditableBackground(spinner, editable);
    }

    private static void applyActionBarFieldState(Control control, boolean editable, Control nativeValue)
    {
        if (control == null || control.isDisposed())
            return;
        boolean allowTyping = resolveActionBarAllowTyping(nativeValue, editable);
        applyEditableBackground(control, editable);
        applyValueFieldHeight(control);
        Text text = actionBarText(control);
        if (text != null && !text.isDisposed())
        {
            Object layoutData = text.getLayoutData();
            if (layoutData instanceof GridData)
                applyInnerValueFieldGridData((GridData) layoutData);
            applyTextFieldState(text, editable, allowTyping);
        }
        for (Button button : actionBarButtons(control))
            applyActionBarButtonLayout(button);
        logFieldState(null, Kind.ACTION_BAR, "applyActionBarFieldState", editable, //$NON-NLS-1$
                "allowTyping=" + allowTyping + " bg=" + (editable ? "white" : "gray")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private static void applyEnabledState(Control control, boolean enabled)
    {
        if (control == null || control.isDisposed())
            return;
        control.setEnabled(enabled);
    }

    private static Text findRowText(Control nativeValue)
    {
        Composite row = nativeRowOf(nativeValue);
        if (row == null)
            return null;
        return findFirstText(row);
    }

    private static Text findFirstText(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return null;
        for (Control child : composite.getChildren())
        {
            if (child instanceof Text)
                return (Text) child;
            if (child instanceof Composite)
            {
                Text found = findFirstText((Composite) child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Object findValueChooser(Object valueView, Control nativeValue)
    {
        if (valueView != null)
        {
            Object nativeControl = Global.invoke(valueView, "getNativeControl"); //$NON-NLS-1$
            if (nativeControl == null)
                nativeControl = Global.getField(valueView, "nativeControl"); //$NON-NLS-1$
            Object chooser = asValueChooser(nativeControl);
            if (chooser != null)
                return chooser;
        }
        Composite row = nativeRowOf(nativeValue);
        if (row != null)
            return findValueChooserInComposite(row);
        return null;
    }

    private static Object findValueChooserInComposite(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return null;
        Object chooser = asValueChooser(composite);
        if (chooser != null)
            return chooser;
        for (Control child : composite.getChildren())
        {
            if (child instanceof Composite)
            {
                chooser = findValueChooserInComposite((Composite) child);
                if (chooser != null)
                    return chooser;
            }
            else
            {
                chooser = asValueChooser(child);
                if (chooser != null)
                    return chooser;
            }
        }
        return null;
    }

    private static Object asValueChooser(Object value)
    {
        if (value == null)
            return null;
        String cn = value.getClass().getName();
        if (cn.contains("ValueChooser") || cn.contains("ValueComboChooser")) //$NON-NLS-1$ //$NON-NLS-2$
            return value;
        return null;
    }

    private static List<Button> collectPushButtons(Composite row)
    {
        List<Button> buttons = new ArrayList<>();
        if (row == null || row.isDisposed())
            return buttons;
        collectPushButtons(row, buttons);
        return buttons;
    }

    private static void collectPushButtons(Composite composite, List<Button> buttons)
    {
        if (composite == null || composite.isDisposed() || buttons == null)
            return;
        for (Control child : composite.getChildren())
        {
            if (child instanceof Button && (((Button) child).getStyle() & SWT.CHECK) == 0)
                buttons.add((Button) child);
            else if (child instanceof Composite)
                collectPushButtons((Composite) child, buttons);
        }
    }

    private static int countPushButtons(Composite row)
    {
        return collectPushButtons(row).size();
    }

    private static int countIconPushButtons(Composite composite)
    {
        int count = 0;
        for (Button button : collectPushButtons(composite))
        {
            if (button != null && !button.isDisposed()
                    && (button.getImage() != null || hasButtonTextMarker(button)))
                count++;
        }
        return count;
    }

    private static boolean hasButtonTextMarker(Button button)
    {
        if (button == null || button.isDisposed())
            return false;
        String text = button.getText();
        return text != null && !text.isEmpty() && !"...".equals(text); //$NON-NLS-1$
    }

    private static Composite findActionBarHost(Control nativeValue, Object valueView)
    {
        Object chooser = findValueChooser(valueView, nativeValue);
        if (chooser instanceof Composite)
            return (Composite) chooser;
        if (chooser != null)
        {
            Object control = Global.invoke(chooser, "getControl"); //$NON-NLS-1$
            if (control instanceof Composite)
                return (Composite) control;
        }
        if (nativeValue != null && !nativeValue.isDisposed())
        {
            Composite current = nativeValue instanceof Composite ? (Composite) nativeValue : nativeValue.getParent();
            while (current != null && !current.isDisposed())
            {
                if (isActionBarHostComposite(current))
                    return current;
                current = current.getParent();
            }
        }
        Composite row = nativeRowOf(nativeValue);
        if (row == null)
            return null;
        return findActionBarHostInComposite(row);
    }

    private static boolean isActionBarHostComposite(Composite composite)
    {
        if (composite == null)
            return false;
        String cn = composite.getClass().getName();
        return cn.contains("ValueComboChooser") || cn.contains("ValueChooser") //$NON-NLS-1$ //$NON-NLS-2$
                || cn.contains("ActionBar"); //$NON-NLS-1$
    }

    private static Composite findActionBarHostInComposite(Composite composite)
    {
        if (composite == null || composite.isDisposed())
            return null;
        if (isActionBarHostComposite(composite) && countPushButtons(composite) > 0)
            return composite;
        for (Control child : composite.getChildren())
        {
            if (!(child instanceof Composite))
                continue;
            Composite found = findActionBarHostInComposite((Composite) child);
            if (found != null)
                return found;
        }
        return null;
    }

    private static void applyActionBarButtonLayout(Button button)
    {
        GridData gd = new GridData(SWT.CENTER, SWT.CENTER, false, false);
        gd.widthHint = ACTION_BAR_BUTTON_SIZE;
        gd.minimumWidth = ACTION_BAR_BUTTON_SIZE;
        applyInnerValueFieldGridData(gd);
        button.setLayoutData(gd);
    }

    @SuppressWarnings("unchecked")
    private static List<Button> actionBarButtons(Control control)
    {
        if (control == null || control.isDisposed())
            return Collections.emptyList();
        Object data = control.getData(ACTION_BAR_BUTTONS_KEY);
        if (data instanceof List)
            return (List<Button>) data;
        return Collections.emptyList();
    }

    private static List<Object> collectVisibleActionButtonItems(Object valueVm)
    {
        List<Object> items = new ArrayList<>();
        Object buttons = valueVm != null ? Global.invoke(valueVm, "getButtons") : null; //$NON-NLS-1$
        Iterator<?> it = toIterator(buttons);
        while (it != null && it.hasNext())
        {
            Object item = it.next();
            if (!readBooleanProperty(item, "isVisible", true)) //$NON-NLS-1$
                continue;
            if (!hasActionButtonMarker(item))
                continue;
            items.add(item);
        }
        return items;
    }

    private static List<Button> collectActionBarItemButtons(Object valueView)
    {
        List<Button> buttons = new ArrayList<>();
        Object items = valueView != null ? Global.invoke(valueView, "getActionBarItems") : null; //$NON-NLS-1$
        Iterator<?> it = toIterator(items);
        while (it != null && it.hasNext())
        {
            Object item = it.next();
            if (item == null)
                continue;
            Object control = Global.invoke(item, "getControl"); //$NON-NLS-1$
            if (control instanceof Button)
                buttons.add((Button) control);
        }
        return buttons;
    }

    private static void decorateActionBarButtons(List<Button> comfortButtons, List<Button> nativeButtons,
            Object valueVm, Object valueView, boolean editable)
    {
        if (comfortButtons == null || comfortButtons.isEmpty())
            return;
        List<Object> buttonItems = collectVisibleActionButtonItems(valueVm);
        List<Button> itemButtons = collectActionBarItemButtons(valueView);
        if (nativeButtons == null)
            nativeButtons = Collections.emptyList();
        for (int i = 0; i < comfortButtons.size(); i++)
        {
            Button button = comfortButtons.get(i);
            if (button == null || button.isDisposed())
                continue;
            button.setImage(null);
            button.setText(""); //$NON-NLS-1$
            if (i < buttonItems.size())
                applyButtonItemAppearance(button, buttonItems.get(i));
            if (!hasButtonDecoration(button) && i < itemButtons.size())
                copyButtonAppearance(button, itemButtons.get(i));
            if (!hasButtonDecoration(button) && i < nativeButtons.size())
                copyButtonAppearance(button, nativeButtons.get(i));
            if (!hasButtonDecoration(button) && i == 0)
                button.setText("..."); //$NON-NLS-1$
            applyActionBarButtonLayout(button);
            Button sizeSource = i < itemButtons.size() ? itemButtons.get(i)
                    : i < nativeButtons.size() ? nativeButtons.get(i) : null;
            applyNativeButtonSize(button, sizeSource);
            boolean buttonEnabled = false;
            if (editable)
            {
                if (i < buttonItems.size())
                    buttonEnabled = readBooleanProperty(buttonItems.get(i), "isEnabled", true); //$NON-NLS-1$
                else
                    buttonEnabled = true;
            }
            button.setVisible(true);
            button.setEnabled(buttonEnabled);
            PropertySheetDebug.valueControlVerbose("actionBar btn[" + i + "] enabled=" + buttonEnabled //$NON-NLS-1$ //$NON-NLS-2$
                    + " fieldEditable=" + editable); //$NON-NLS-1$
        }
        PropertySheetDebug.valueControl("actionBar decorate buttons=" + comfortButtons.size() //$NON-NLS-1$
                + " fieldEditable=" + editable); //$NON-NLS-1$
    }

    private static boolean hasButtonDecoration(Button button)
    {
        if (button == null || button.isDisposed())
            return false;
        if (button.getImage() != null)
            return true;
        String text = button.getText();
        return text != null && !text.isEmpty();
    }

    private static void applyButtonItemAppearance(Button target, Object item)
    {
        if (target == null || target.isDisposed() || item == null)
            return;
        Object image = Global.invoke(item, "getImage"); //$NON-NLS-1$
        if (image instanceof Image)
            target.setImage((Image) image);
        if (target.getImage() == null)
        {
            image = Global.getField(item, "image"); //$NON-NLS-1$
            if (image instanceof Image)
                target.setImage((Image) image);
        }
        String tooltip = asString(Global.invoke(item, "getTooltip")); //$NON-NLS-1$
        if (!tooltip.isEmpty())
            target.setToolTipText(tooltip);
    }

    private static void applyNativeButtonSize(Button target, Button nativeButton)
    {
        if (target == null || target.isDisposed() || nativeButton == null || nativeButton.isDisposed())
            return;
        org.eclipse.swt.graphics.Point size = nativeButton.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        if (size.x <= 0)
            return;
        Object layoutData = target.getLayoutData();
        if (layoutData instanceof GridData)
        {
            GridData gd = (GridData) layoutData;
            gd.widthHint = Math.max(ACTION_BAR_BUTTON_SIZE, size.x);
            gd.minimumWidth = gd.widthHint;
            applyInnerValueFieldGridData(gd);
        }
    }

    private static void copyButtonAppearance(Button target, Button source)
    {
        if (target == null || target.isDisposed() || source == null || source.isDisposed())
            return;
        if (source.getImage() != null && target.getImage() == null)
            target.setImage(source.getImage());
        String text = source.getText();
        if (text != null && !text.isEmpty() && !hasButtonDecoration(target))
            target.setText(text);
        String tooltip = source.getToolTipText();
        if (tooltip != null && !tooltip.isEmpty() && target.getToolTipText() == null)
            target.setToolTipText(tooltip);
    }

    private static boolean readBooleanProperty(Object target, String method, boolean fallback)
    {
        if (target == null)
            return fallback;
        Object value = Global.invoke(target, method);
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    private static Text actionBarText(Control control)
    {
        if (control == null || control.isDisposed())
            return null;
        Object data = control.getData(ACTION_BAR_TEXT_KEY);
        return data instanceof Text ? (Text) data : null;
    }

    private static void applyActionBarDisplay(Control control, String value, Object valueVm, Object valueView,
            Control nativeValue, boolean editable)
    {
        Text text = actionBarText(control);
        if (text != null && !text.isDisposed())
        {
            setTextProgrammatic(text, value != null ? value : ""); //$NON-NLS-1$
            applyActionBarFieldState(control, editable, nativeValue);
        }
        decorateActionBarButtons(actionBarButtons(control),
                collectPushButtons(findActionBarHost(nativeValue, valueView)), valueVm, valueView, editable);
    }

    private static void wireActionBarChange(Control control, Runnable onChange)
    {
        Text text = actionBarText(control);
        if (text != null && !text.isDisposed())
            text.addModifyListener(e -> onChange.run());
        Control nativeValue = control.getData(ACTION_BAR_NATIVE_KEY) instanceof Control
                ? (Control) control.getData(ACTION_BAR_NATIVE_KEY) : null;
        Object valueView = control.getData(ACTION_BAR_VIEW_KEY);
        Object valueVm = control.getData(ACTION_BAR_VM_KEY);
        List<Button> buttons = actionBarButtons(control);
        for (int i = 0; i < buttons.size(); i++)
        {
            final int buttonIndex = i;
            Button button = buttons.get(i);
            if (button == null || button.isDisposed())
                continue;
            button.addListener(SWT.Selection, e -> {
                fireNativeActionBarButton(nativeValue, valueView, valueVm, buttonIndex);
                if (buttonIndex == 1)
                    onChange.run();
            });
        }
    }

    private static void fireNativeActionBarButton(Control nativeValue, Object valueView, Object valueVm,
            int buttonIndex)
    {
        if (fireNativeActionBarViaView(valueView, valueVm, buttonIndex))
            return;
        if (fireNativeActionBarViaChooser(valueView, nativeValue, buttonIndex))
            return;
        fireNativeActionBarViaSwtButtons(nativeValue, valueView, buttonIndex);
    }

    private static boolean fireNativeActionBarViaView(Object valueView, Object valueVm, int buttonIndex)
    {
        if (valueView == null)
            return false;
        Object buttons = valueVm != null ? Global.invoke(valueVm, "getButtons") : null; //$NON-NLS-1$
        Iterator<?> it = toIterator(buttons);
        if (it == null)
            return false;
        int visibleIndex = 0;
        while (it.hasNext())
        {
            Object item = it.next();
            if (!readBooleanProperty(item, "isVisible", true)) //$NON-NLS-1$
                continue;
            if (!hasActionButtonMarker(item))
                continue;
            if (visibleIndex == buttonIndex)
            {
                org.eclipse.swt.widgets.Event event = new org.eclipse.swt.widgets.Event();
                for (String method : new String[] { "handleButtonClick", "buttonClicked" }) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    if (Global.invokeVoid(valueView, method, item))
                        return true;
                    if (Global.invokeVoid(valueView, method, item, event))
                        return true;
                }
                return false;
            }
            visibleIndex++;
        }
        return false;
    }

    private static boolean fireNativeActionBarViaChooser(Object valueView, Control nativeValue, int buttonIndex)
    {
        Object chooser = findValueChooser(valueView, nativeValue);
        if (chooser == null)
            return false;
        if (buttonIndex == 0)
            return Global.invokeVoid(chooser, "showSelection"); //$NON-NLS-1$
        if (buttonIndex == 1)
        {
            if (Global.invokeVoid(chooser, "clear")) //$NON-NLS-1$
                return true;
            return Global.invokeVoid(chooser, "clearSelection"); //$NON-NLS-1$
        }
        return false;
    }

    private static void fireNativeActionBarViaSwtButtons(Control nativeValue, Object valueView, int buttonIndex)
    {
        List<Button> buttons = collectPushButtons(findActionBarHost(nativeValue, valueView));
        if (buttonIndex < 0 || buttonIndex >= buttons.size())
            return;
        Button target = buttons.get(buttonIndex);
        if (target != null && !target.isDisposed())
            target.notifyListeners(SWT.Selection, new org.eclipse.swt.widgets.Event());
    }
}
