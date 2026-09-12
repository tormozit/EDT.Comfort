/******************************************************************************
 * Copyright (c) 2006-2023 The IndentGuide Authors.
 * Copyright (c) 2026 EDT Comfort contributors.
 *
 * Adapted from net.certiv.tools.indentguide GuidePage (MIT License):
 * https://opensource.org/licenses/MIT
 *****************************************************************************/
package tormozit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.ColorFieldEditor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

/**
 * Страница «Параметры → Комфорт → Направляющие отступов».
 */
public final class IndentGuidePreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
    private static final String[] STYLES = {
        "Сплошная", //$NON-NLS-1$
        "Штрих", //$NON-NLS-1$
        "Точки", //$NON-NLS-1$
        "Штрих-точка", //$NON-NLS-1$
        "Штрих-точка-точка" //$NON-NLS-1$
    };

    private static Set<IContentType> platformTextTypesCache;

    private final List<Composite> blocks = new LinkedList<>();
    private final List<Object> parts = new LinkedList<>();

    private final IContentType txtType;
    private Set<IContentType> excludeTypes;

    public IndentGuidePreferencePage()
    {
        ContentAssistSettings cas = ContentAssistSettings.getInstance();
        if (cas != null)
            setPreferenceStore(cas.getPreferenceStore());
        else if (ComfortSettings.getInstance() != null)
            setPreferenceStore(ComfortSettings.getInstance().getPreferenceStore());

        txtType = Platform.getContentTypeManager().getContentType(IContentTypeManager.CT_TEXT);

        Set<String> exclude = IndentGuideHook.undelimit(
            getPreferenceStore() != null
                ? getPreferenceStore().getString(ComfortSettings.PREF_INDENT_GUIDE_CONTENT_TYPES)
                : ""); //$NON-NLS-1$
        excludeTypes = exclude.stream()
            .map(id -> Platform.getContentTypeManager().getContentType(id))
            .filter(t -> t != null && !txtType.equals(t))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public void init(IWorkbench workbench)
    {
    }

    @Override
    protected Control createContents(Composite parent)
    {
        initializeDialogUnits(parent);

        Composite comp = new Composite(parent, SWT.NONE);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(comp);
        GridLayoutFactory.fillDefaults().applyTo(comp);

        createEnabledCheckBox(comp);
        createAttributeGroup(comp);
        createDrawingGroup(comp);
        createContentTypesGroup(comp);
        setBlocksEnabled(getPreferenceStore().getBoolean(ComfortSettings.PREF_INDENT_GUIDE_ENABLED));

        applyDialogFont(comp);
        return comp;
    }

    private void createEnabledCheckBox(Composite comp)
    {
        Button btn = createLabeledCheckbox(comp, "Включить направляющие отступов текстовых редакторов", //$NON-NLS-1$
            ComfortSettings.PREF_INDENT_GUIDE_ENABLED);
        btn.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(final SelectionEvent e)
            {
                setBlocksEnabled(btn.getSelection());
            }
        });
    }

    private void setBlocksEnabled(boolean active)
    {
        for (Composite block : blocks)
        {
            for (Control control : block.getChildren())
                control.setEnabled(active);
        }
    }

    private void createAttributeGroup(Composite parent)
    {
        Composite comp = createGroup(parent, "Атрибуты линии", false, 3); //$NON-NLS-1$
        blocks.add(comp);

        createLabeledSpinner(comp, "Прозрачность", "(0 — прозрачная, 255 — непрозрачная)", //$NON-NLS-1$ //$NON-NLS-2$
            0, 255, ComfortSettings.PREF_INDENT_GUIDE_LINE_ALPHA);
        createLabeledCombo(comp, "Стиль", "", STYLES, ComfortSettings.PREF_INDENT_GUIDE_LINE_STYLE); //$NON-NLS-1$ //$NON-NLS-2$
        createLabeledSpinner(comp, "Толщина", "(1–8 пикселей)", //$NON-NLS-1$ //$NON-NLS-2$
            1, 8, ComfortSettings.PREF_INDENT_GUIDE_LINE_WIDTH);
        createLabeledSpinner(comp, "Сдвиг", "(0–8 пикселей)", //$NON-NLS-1$ //$NON-NLS-2$
            0, 8, ComfortSettings.PREF_INDENT_GUIDE_LINE_SHIFT);
        createLabeledColorEditor(comp, "Цвет", "", ComfortSettings.indentGuideLineColorKey()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void createDrawingGroup(Composite parent)
    {
        Composite comp = createGroup(parent, "Параметры рисования", false, 1); //$NON-NLS-1$
        blocks.add(comp);

        createLabeledCheckbox(comp, "Рисовать на первой колонке", //$NON-NLS-1$
            ComfortSettings.PREF_INDENT_GUIDE_DRAW_LEAD_EDGE);
        createLabeledCheckbox(comp, "Рисовать на пустых строках", //$NON-NLS-1$
            ComfortSettings.PREF_INDENT_GUIDE_DRAW_BLANK_LINE);
        createLabeledCheckbox(comp, "Рисовать в блочных комментариях /* */", //$NON-NLS-1$
            ComfortSettings.PREF_INDENT_GUIDE_DRAW_COMMENT_BLOCK);
    }

    private void createContentTypesGroup(Composite parent)
    {
        Composite comp = createGroup(parent, "Типы файлов", true, 1); //$NON-NLS-1$
        blocks.add(comp);

        CheckboxTreeViewer viewer = new CheckboxTreeViewer(comp,
            SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        Tree tree = viewer.getTree();
        tree.setFont(comp.getFont());
        GridDataFactory.fillDefaults().hint(SWT.DEFAULT, 150).grab(true, true).applyTo(tree);
        CopyCommandSupport.wireCopyOverride(tree);
        parts.add(viewer);

        viewer.setContentProvider(new TypesContentProvider());
        viewer.setLabelProvider(new TypesLabelProvider());
        viewer.setComparator(new ViewerComparator());
        viewer.addFilter(new TextTypeFilter());
        viewer.setInput(Platform.getContentTypeManager());

        viewer.expandAll();
        viewer.collapseAll();
        viewer.expandToLevel(2);

        for (Object item : platformTextTypes())
        {
            viewer.setChecked(item, true);
            viewer.setGrayed(item, false);
        }

        for (IContentType exType : excludeTypes)
        {
            viewer.setChecked(exType, false);
            updateCheckState(viewer, exType, false);
        }

        viewer.addCheckStateListener(evt -> {
            IContentType type = (IContentType) evt.getElement();
            boolean state = viewer.getChecked(type);
            updateCheckState(viewer, type, state);
        });
    }

    private Composite createGroup(Composite parent, String label, boolean vert, int cols)
    {
        Group group = new Group(parent, SWT.NONE);
        group.setText(label);
        GridLayoutFactory.fillDefaults().applyTo(group);
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.FILL).grab(true, vert).applyTo(group);

        Composite comp = new Composite(group, SWT.NONE);
        GridLayoutFactory.fillDefaults().numColumns(cols).equalWidth(false).margins(5, 5).applyTo(comp);
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.FILL).grab(true, vert).indent(5, 0).applyTo(comp);
        return comp;
    }

    private Label createLabel(Composite comp, String text)
    {
        Label label = new Label(comp, SWT.NONE);
        label.setText(text);
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.CENTER).applyTo(label);
        return label;
    }

    private Button createLabeledCheckbox(Composite comp, String label, String key)
    {
        Button btn = new Button(comp, SWT.CHECK);
        btn.setText(label);
        btn.setData(key);
        btn.setSelection(getPreferenceStore().getBoolean(key));
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.CENTER).applyTo(btn);
        parts.add(btn);
        return btn;
    }

    private Combo createLabeledCombo(Composite comp, String label, String trail, String[] styles, String key)
    {
        createLabel(comp, label);
        Combo combo = new Combo(comp, SWT.READ_ONLY);
        combo.setData(key);
        combo.setItems(styles);
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.CENTER).indent(9, 0).applyTo(combo);

        int idx = getPreferenceStore().getInt(key) - 1;
        idx = (idx >= 0 && idx < styles.length) ? idx : 0;
        combo.setText(styles[idx]);

        createLabel(comp, trail);
        parts.add(combo);
        return combo;
    }

    private Spinner createLabeledSpinner(Composite comp, String label, String trail, int min, int max, String key)
    {
        createLabel(comp, label);
        Spinner spin = new Spinner(comp, SWT.BORDER);
        spin.setData(key);
        spin.setMinimum(min);
        spin.setMaximum(max);
        spin.setSelection(getPreferenceStore().getInt(key));
        GridDataFactory.fillDefaults().align(SWT.FILL, SWT.CENTER).indent(9, 0).applyTo(spin);
        createLabel(comp, trail);
        parts.add(spin);
        return spin;
    }

    private void createLabeledColorEditor(Composite comp, String label, String trail, String key)
    {
        createLabel(comp, label);
        Composite inner = new Composite(comp, SWT.NONE);
        GridLayoutFactory.fillDefaults().applyTo(inner);
        GridDataFactory.fillDefaults().applyTo(inner);

        ColorFieldEditor editor = new ColorFieldEditor(key, "", inner); //$NON-NLS-1$
        editor.setPreferenceStore(getPreferenceStore());
        editor.load();

        createLabel(comp, trail);
        parts.add(editor);
    }

    @Override
    protected void performDefaults()
    {
        IPreferenceStore store = getPreferenceStore();
        for (Object part : parts)
        {
            if (part instanceof Button btn)
            {
                String key = (String) btn.getData();
                btn.setSelection(store.getDefaultBoolean(key));
            }
            else if (part instanceof Combo combo)
            {
                String key = (String) combo.getData();
                int idx = store.getDefaultInt(key) - 1;
                if (idx >= 0 && idx < STYLES.length)
                    combo.setText(STYLES[idx]);
            }
            else if (part instanceof Spinner spin)
            {
                String key = (String) spin.getData();
                spin.setSelection(store.getDefaultInt(key));
            }
            else if (part instanceof ColorFieldEditor editor)
            {
                editor.loadDefault();
            }
            else if (part instanceof CheckboxTreeViewer viewer)
            {
                excludeTypes.clear();
                for (Object type : platformTextTypes())
                {
                    if (!viewer.getChecked(type))
                        viewer.setChecked(type, true);
                }
            }
        }
        super.performDefaults();
    }

    @Override
    public boolean performOk()
    {
        IPreferenceStore store = getPreferenceStore();
        for (Object part : parts)
        {
            if (part instanceof Button btn)
            {
                String key = (String) btn.getData();
                store.setValue(key, btn.getSelection());
            }
            else if (part instanceof Combo combo)
            {
                String key = (String) combo.getData();
                store.setValue(key, combo.getSelectionIndex() + 1);
            }
            else if (part instanceof Spinner spin)
            {
                String key = (String) spin.getData();
                store.setValue(key, spin.getSelection());
            }
            else if (part instanceof ColorFieldEditor editor)
            {
                editor.store();
            }
            else if (part instanceof CheckboxTreeViewer viewer)
            {
                excludeTypes = getUnChecked(viewer);
                store.setValue(ComfortSettings.PREF_INDENT_GUIDE_CONTENT_TYPES,
                    IndentGuideHook.delimit(excludeTypes));
            }
        }
        return super.performOk();
    }

    private Set<IContentType> getChecked(CheckboxTreeViewer viewer, boolean grayed)
    {
        Set<IContentType> checked = Arrays.stream(viewer.getCheckedElements())
            .map(e -> (IContentType) e)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (grayed)
            return checked;
        return checked.stream().filter(t -> !viewer.getGrayed(t))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<IContentType> getUnChecked(CheckboxTreeViewer viewer)
    {
        Set<IContentType> all = new LinkedHashSet<>(platformTextTypes());
        all.removeAll(getChecked(viewer, true));
        return all;
    }

    private void updateCheckState(CheckboxTreeViewer viewer, IContentType type, boolean state)
    {
        viewer.setGrayed(type, false);
        TypesContentProvider provider = (TypesContentProvider) viewer.getContentProvider();
        for (Object child : provider.getChildren(type))
            viewer.setChecked(child, state);

        IContentType parent = (IContentType) provider.getParent(type);
        while (parent != null)
        {
            LinkedList<Object> children = new LinkedList<>(Arrays.asList(provider.getChildren(parent)));
            boolean all = children.stream().allMatch(e -> viewer.getChecked(e));
            boolean any = children.stream().anyMatch(e -> viewer.getChecked(e));
            viewer.setChecked(parent, all || any);
            viewer.setGrayed(parent, any && !all);
            parent = parent.getBaseType();
        }
    }

    static Set<IContentType> platformTextTypes()
    {
        if (platformTextTypesCache == null)
        {
            IContentTypeManager mgr = Platform.getContentTypeManager();
            IContentType text = mgr.getContentType(IContentTypeManager.CT_TEXT);
            platformTextTypesCache = Stream.of(mgr.getAllContentTypes())
                .filter(t -> t.isKindOf(text))
                .collect(Collectors.toCollection(LinkedHashSet::new));
            platformTextTypesCache = Collections.unmodifiableSet(platformTextTypesCache);
        }
        return platformTextTypesCache;
    }

    private final class TextTypeFilter extends ViewerFilter
    {
        @Override
        public boolean select(Viewer viewer, Object parent, Object element)
        {
            if (element instanceof IContentType type)
                return type.isKindOf(txtType);
            return false;
        }
    }

    private static final class TypesLabelProvider extends LabelProvider
    {
        /** Русские подписи для типов, у которых в платформе/EDT только английское name. */
        private static final java.util.Map<String, String> RU_NAMES = Map.ofEntries(
            Map.entry(IContentTypeManager.CT_TEXT, "Текст"), //$NON-NLS-1$
            Map.entry("com._1c.g5.v8.dt.bsl.contenttype", "Модуль BSL"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("com._1c.g5.v8.dt.bsl.binary.contenttype", "Бинарный модуль BSL"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("com._1c.g5.v8.dt.ql.contenttype", "Язык запросов"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("org.eclipse.core.runtime.xml", "XML"), //$NON-NLS-1$ //$NON-NLS-2$
            Map.entry("org.eclipse.wst.xml.core.xmlsource", "Исходный XML") //$NON-NLS-1$ //$NON-NLS-2$
        );

        @Override
        public String getText(Object element)
        {
            IContentType type = (IContentType) element;
            String ru = RU_NAMES.get(type.getId());
            return ru != null ? ru : type.getName();
        }
    }

    private final class TypesContentProvider implements ITreeContentProvider
    {
        @Override
        public Object[] getChildren(Object parent)
        {
            List<IContentType> elements = new ArrayList<>();
            IContentType base = (IContentType) parent;
            for (IContentType type : platformTextTypes())
            {
                if (Objects.equals(type.getBaseType(), base))
                    elements.add(type);
            }
            return elements.toArray(new IContentType[0]);
        }

        @Override
        public Object getParent(Object element)
        {
            return ((IContentType) element).getBaseType();
        }

        @Override
        public boolean hasChildren(Object element)
        {
            return getChildren(element).length > 0;
        }

        @Override
        public Object[] getElements(Object inputElement)
        {
            return getChildren(null);
        }

        @Override
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
        {
        }
    }
}
