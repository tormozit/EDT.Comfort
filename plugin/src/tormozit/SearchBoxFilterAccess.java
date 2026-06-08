package tormozit;

import java.util.function.Consumer;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IViewPart;

/**
 * Доступ к полю ввода {@code SearchBox}: reflection, обход дочерних виджетов,
 * {@code searchTextObservable} или прокси {@code ISearchListener}.
 */
final class SearchBoxFilterAccess
{
    private final Object searchBox;
    private final Control textControl;
    private final Object observable;
    private final String mode;

    private SearchBoxFilterAccess(Object searchBox, Control textControl, Object observable, String mode)
    {
        this.searchBox = searchBox;
        this.textControl = textControl;
        this.observable = observable;
        this.mode = mode;
    }

    static SearchBoxFilterAccess resolve(IViewPart navigator, Object searchBox)
    {
        return resolve(navigator, searchBox, true);
    }

    /** Без {@code activateSearchBox} / {@code layout} — для фонового refresh. */
    static SearchBoxFilterAccess resolveQuiet(IViewPart navigator, Object searchBox)
    {
        return resolve(navigator, searchBox, false);
    }

    private static SearchBoxFilterAccess resolve(IViewPart navigator, Object searchBox, boolean activateUi)
    {
        if (searchBox == null)
            return null;
        if (activateUi)
        {
            for (String method : new String[] { "activateSearchBox", "showSearchBox", "expandSearchBox" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Global.invokeVoid(navigator, method);
            Global.invokeVoid(searchBox, "setVisible", Boolean.TRUE); //$NON-NLS-1$
            if (searchBox instanceof Composite)
            {
                Composite composite = (Composite) searchBox;
                if (!composite.isDisposed())
                    composite.layout(true, true);
            }
        }
        Object observable = Global.getField(searchBox, "searchTextObservable"); //$NON-NLS-1$
        if (observable == null)
            observable = Global.invoke(searchBox, "getSearchTextObservable"); //$NON-NLS-1$
        Control textControl = findTextControl(searchBox);
        String mode;
        // SearchBox хранит ввод в searchTextObservable; StyledText внутри может ещё не существовать.
        if (observable != null)
            mode = "observable"; //$NON-NLS-1$
        else if (textControl instanceof StyledText)
            mode = "styledText"; //$NON-NLS-1$
        else if (textControl instanceof Text)
            mode = "text"; //$NON-NLS-1$
        else
            mode = "listener"; //$NON-NLS-1$
        return new SearchBoxFilterAccess(searchBox, textControl, observable, mode);
    }

    String mode()
    {
        return mode;
    }

    Control focusControl()
    {
        if (textControl != null && !textControl.isDisposed())
            return textControl;
        return searchBox instanceof Control ? (Control) searchBox : null;
    }

    String readPattern()
    {
        // SWTDelayedObservableValueDecorator.getValue() — только UI-поток.
        if (observable != null && isUiThread())
        {
            Object value = Global.invoke(observable, "getValue"); //$NON-NLS-1$
            String extracted = asStringValue(value);
            if (extracted != null)
                return extracted;
        }
        return readPatternWithoutObservable();
    }

    /** Без observable — безопасно с фонового SearchJob. */
    private String readPatternWithoutObservable()
    {
        Object active = Global.invoke(searchBox, "getActivePattern"); //$NON-NLS-1$
        String activePattern = asStringValue(active);
        if (activePattern != null)
            return activePattern;
        for (String field : new String[] { "activePattern", "lastSearchText", "searchPattern", "pattern", "searchText" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        {
            String last = asStringValue(Global.getField(searchBox, field));
            if (last != null)
                return last;
        }
        if (isUiThread())
        {
            StyledText styled = styledTextFromSearchBox(searchBox);
            if (styled != null && !styled.isDisposed())
                return styled.getText();
            if (textControl instanceof StyledText && !textControl.isDisposed())
                return ((StyledText) textControl).getText();
            if (textControl instanceof Text && !textControl.isDisposed())
                return ((Text) textControl).getText();
            Object text = Global.invoke(searchBox, "getText"); //$NON-NLS-1$
            String directText = asStringValue(text);
            if (directText != null)
                return directText;
        }
        return ""; //$NON-NLS-1$
    }

    boolean attachPatternListener(IViewPart navigator, Consumer<String> onPatternChange)
    {
        return attachPatternListener(navigator, null, null, onPatternChange);
    }

    boolean attachPatternListener(IViewPart navigator, Object nativeListener, Object propertyPage,
            Consumer<String> onPatternChange)
    {
        boolean attached = false;
        ModifyListener onModify = e -> notifyPatternChange(onPatternChange, null);
        if ("styledText".equals(mode) && textControl instanceof StyledText) //$NON-NLS-1$
        {
            ((StyledText) textControl).addModifyListener(onModify);
            attached = true;
        }
        else if ("text".equals(mode) && textControl instanceof Text) //$NON-NLS-1$
        {
            ((Text) textControl).addModifyListener(onModify);
            attached = true;
        }
        if (observable != null)
        {
            Object listener = createValueChangeListener(onPatternChange);
            if (listener != null)
            {
                Global.invoke(observable, "addValueChangeListener", listener); //$NON-NLS-1$
                attached = true;
            }
        }
        // Резерв: performSearch (в т.ч. очистка) — observable иногда молчит
        if (attachSearchListenerProxy(navigator, nativeListener, propertyPage, onPatternChange))
            attached = true;
        return attached;
    }

    /** Пустой explicit из performSearch("") не подменяет реальный ввод в observable. */
    private void notifyPatternChange(Consumer<String> onChange, String explicit)
    {
        if (explicit != null && !explicit.isEmpty())
        {
            runOnUiThread(onChange, explicit);
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> {
            String pattern = readPattern();
            onChange.accept(pattern != null ? pattern : ""); //$NON-NLS-1$
        });
    }

    void disableNativeAutoSearch()
    {
        // Нативный поиск перехватывается proxy в attachSearchListenerProxy (performSearch → null).
    }

    static String describe(Object searchBox)
    {
        if (searchBox == null)
            return "searchBox=null"; //$NON-NLS-1$
        StringBuilder sb = new StringBuilder("searchBox=") //$NON-NLS-1$
                .append(searchBox.getClass().getSimpleName());
        sb.append(" fields{"); //$NON-NLS-1$
        for (String field : new String[] { "text", "searchText", "styledText", "searchTextObservable" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Object value = Global.getField(searchBox, field);
            sb.append(field).append('=');
            sb.append(value == null ? "null" : value.getClass().getSimpleName()); //$NON-NLS-1$
            sb.append(' ');
        }
        sb.append("} children="); //$NON-NLS-1$
        if (searchBox instanceof Composite)
        {
            Composite c = (Composite) searchBox;
            Control[] children = c.getChildren();
            sb.append(children.length).append('[');
            for (int i = 0; i < children.length && i < 6; i++)
            {
                if (i > 0)
                    sb.append(','); //$NON-NLS-1$
                sb.append(children[i].getClass().getSimpleName());
            }
            sb.append(']');
        }
        else
            sb.append("n/a"); //$NON-NLS-1$
        StyledText styled = styledTextFromSearchBox(searchBox);
        sb.append(" styledText="); //$NON-NLS-1$
        sb.append(styled == null ? "null" : styled.getClass().getSimpleName()); //$NON-NLS-1$
        Object observable = Global.getField(searchBox, "searchTextObservable"); //$NON-NLS-1$
        if (observable == null)
            observable = Global.invoke(searchBox, "getSearchTextObservable"); //$NON-NLS-1$
        if (observable != null && isUiThread())
        {
            Object value = Global.invoke(observable, "getValue"); //$NON-NLS-1$
            sb.append(" observableValue=\"").append(asStringValue(value)).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Control text = findTextControl(searchBox);
        sb.append(" textControl="); //$NON-NLS-1$
        sb.append(text == null ? "null" : text.getClass().getSimpleName()); //$NON-NLS-1$
        return sb.toString();
    }

    private static StyledText styledTextFromSearchBox(Object searchBox)
    {
        if (searchBox == null)
            return null;
        for (String field : new String[] { "text", "searchText", "styledText" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Object text = Global.getField(searchBox, field);
            if (text instanceof StyledText)
                return (StyledText) text;
        }
        return null;
    }

    private static Control findTextControl(Object searchBox)
    {
        StyledText styled = styledTextFromSearchBox(searchBox);
        if (styled != null)
            return styled;
        if (searchBox instanceof StyledText)
            return (Control) searchBox;
        if (searchBox instanceof Text)
            return (Control) searchBox;
        for (String field : new String[] { "text", "searchText", "styledText", "searchTextWidget" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Object text = Global.getField(searchBox, field);
            if (text instanceof Control)
                return (Control) text;
        }
        for (String method : new String[] { "getText", "getSearchText", "getStyledText", "getInputControl" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            Object text = Global.invoke(searchBox, method);
            if (text instanceof Control)
                return (Control) text;
        }
        if (searchBox instanceof Composite)
            return findTextControlInComposite((Composite) searchBox);
        return null;
    }

    private static Control findTextControlInComposite(Composite parent)
    {
        if (parent == null || parent.isDisposed())
            return null;
        for (Control child : parent.getChildren())
        {
            if (child instanceof StyledText || child instanceof Text)
                return child;
            if (child instanceof Composite)
            {
                Control found = findTextControlInComposite((Composite) child);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static boolean isUiThread()
    {
        Display display = Display.getCurrent();
        return display != null && !display.isDisposed();
    }

    private static void runOnUiThread(Consumer<String> onPatternChange, String pattern)
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        if (isUiThread())
            onPatternChange.accept(pattern);
        else
            display.asyncExec(() -> onPatternChange.accept(pattern));
    }

    private void notifyPatternChangeFromValueEvent(Consumer<String> onChange, Object[] args)
    {
        String fromEvent = patternFromValueChangeArgs(args);
        notifyPatternChange(onChange, fromEvent);
    }

    private static String patternFromPerformSearchArgs(Object[] args)
    {
        if (args == null || args.length == 0)
            return null;
        for (Object arg : args)
        {
            String value = asStringValue(arg);
            if (value != null)
                return value;
        }
        return null;
    }

    private static String patternFromValueChangeArgs(Object[] args)
    {
        if (args == null || args.length == 0 || args[0] == null)
            return null;
        Object event = args[0];
        Object diff = Global.invoke(event, "getDiff"); //$NON-NLS-1$
        if (diff != null)
        {
            Object newValue = Global.invoke(diff, "getNewValue"); //$NON-NLS-1$
            String extracted = asStringValue(newValue);
            if (extracted != null)
                return extracted;
        }
        return null;
    }

    private boolean attachSearchListenerProxy(IViewPart navigator, Object nativeListener, Object propertyPage,
            Consumer<String> onPatternChange)
    {
        Object delegate = nativeListener;
        if (delegate == null && navigator != null)
            delegate = Global.getField(navigator, "searchPerformer"); //$NON-NLS-1$
        Object proxy = createSearchListener(this, onPatternChange, delegate, propertyPage);
        if (proxy == null)
            return false;
        Global.invoke(searchBox, "setSearchListener", proxy); //$NON-NLS-1$
        Global.invoke(searchBox, "setRunSearchOnTextChange", Boolean.TRUE); //$NON-NLS-1$
        return true;
    }

    private Object createValueChangeListener(Consumer<String> onChange)
    {
        try
        {
            Class<?> iface = Class.forName("org.eclipse.core.databinding.observable.value.IValueChangeListener"); //$NON-NLS-1$
            SearchBoxFilterAccess self = this;
            return java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface },
                    (proxy, method, args) -> {
                        if ("handleValueChange".equals(method.getName())) //$NON-NLS-1$
                            self.notifyPatternChangeFromValueEvent(onChange, args);
                        return null;
                    });
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Object createSearchListener(SearchBoxFilterAccess self, Consumer<String> onSearch,
            Object nativeDelegate, Object propertyPage)
    {
        try
        {
            Class<?> iface = Class.forName("com._1c.g5.v8.dt.common.ui.controls.search.SearchBox$ISearchListener"); //$NON-NLS-1$
            return java.lang.reflect.Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] { iface },
                    (proxy, method, args) -> {
                        if ("performSearch".equals(method.getName())) //$NON-NLS-1$
                        {
                            String fromArgs = patternFromPerformSearchArgs(args);
                            self.notifyPatternChange(onSearch, fromArgs);
                            String pattern = fromArgs;
                            if (pattern == null || pattern.isEmpty())
                                pattern = self.readPatternWithoutObservable();
                            // Фильтрация — штатный SearchJob (не блокирует ввод в SearchBox).
                            boolean comfortActive = propertyPage != null
                                    && PropertySheetComfortUi.isInstalled(propertyPage)
                                    && PropertySheetComfortUi.hasRows(propertyPage);
                            if (nativeDelegate != null && (!comfortActive || pattern != null && !pattern.isEmpty()))
                                return method.invoke(nativeDelegate, args);
                            return null;
                        }
                        if (nativeDelegate != null)
                            return method.invoke(nativeDelegate, args);
                        return null;
                    });
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String asStringValue(Object value)
    {
        if (value == null)
            return null;
        if (value instanceof String)
            return (String) value;
        if (value instanceof CharSequence)
            return value.toString();
        Object extracted = Global.invoke(value, "getValue"); //$NON-NLS-1$
        if (extracted != null && extracted != value)
            return asStringValue(extracted);
        return null;
    }
}
