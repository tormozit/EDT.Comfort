package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.core.databinding.Binding;
import org.eclipse.core.databinding.DataBindingContext;
import org.eclipse.core.databinding.observable.value.IObservableValue;
import org.eclipse.jface.databinding.swt.ISWTObservableValue;
import org.eclipse.jface.databinding.swt.typed.WidgetProperties;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;

/**
 * Орфография Hunspell в окне «Строки на разных языках»
 * ({@code com._1c.g5.v8.dt.ui.dialog.LocalStringDialog} и наследник {@code LocalStringFormatDialog}).
 *
 * <p>Поля этого окна — нативные {@link Text}, а нативное поле не умеет ни рисовать волну, ни
 * отдавать координаты символа переносимым способом. Поэтому штатное поле <b>не заменяется, а
 * скрывается</b> ({@code setVisible(false)} + {@code GridData.exclude}), а на его место встаёт свой
 * {@link StyledText}, привязанный к <b>той же модели</b> {@code LocalString.getTextValue()}.
 * Ни один чужой слушатель не снимается и не переопределяется.
 *
 * <p>Это корректно, потому что источник истины в диалоге — модель, а не виджеты: {@code close()}
 * собирает результат через {@code convert(localStrings)} → {@code LocalString.getText()}. Скрытое
 * штатное поле остаётся привязанным к той же модели и продолжает получать её изменения вхолостую.
 *
 * <p>Соответствие «поле → модель» берётся не по порядку и не по подписи, а точно: у поля ищется
 * его собственная привязка в {@code DataBindingContext} диалога, а по её модели — нужный
 * {@code LocalString}. Если хоть одно поле разобрать не удалось, окно не трогается вовсе —
 * ошибиться языком здесь означало бы записать текст не в ту строку.
 *
 * <p>Три поведения штатного поля после его скрытия перестают работать сами и воспроизводятся
 * здесь: Ctrl+Enter (ОК), разворот свёрнутого многострочного поля при получении фокуса и высота
 * поля при переключении «(раскрыть)/(свернуть)».
 */
public final class LocalStringDialogSpellCheckHook implements IStartup
{
    private static final String DIALOG_CLASS =
        "com._1c.g5.v8.dt.ui.dialog.LocalStringDialog"; //$NON-NLS-1$

    /** Высота развёрнутого многострочного поля — как в {@code LocalStringDialog}. */
    private static final int EXPANDED_HEIGHT_PX = 150;

    private static final Set<Shell> ATTACHED = Collections.newSetFromMap(new WeakHashMap<>());

    private static boolean filterInstalled;

    @Override
    public void earlyStartup()
    {
        if (!ComfortJdtAvailability.isJdtUiAvailable())
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        display.asyncExec(() -> installShellFilter(display));
    }

    private static void installShellFilter(Display display)
    {
        if (filterInstalled || display.isDisposed())
            return;
        filterInstalled = true;
        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (!SpellCheckHook.isComfortPlatformSpellingActive())
                return;
            Object dialog = resolveLocalStringDialog(shell);
            if (dialog == null)
                return;
            if (!ATTACHED.add(shell))
                return;
            // Подменять на самом Show нельзя: разметка ещё не устоялась, pack даст не ту высоту.
            shell.getDisplay().asyncExec(() -> attach(shell, dialog));
        };
        display.addFilter(SWT.Show, listener);
        display.addFilter(SWT.Activate, listener);
    }

    /** Диалог окна, если это «Строки на разных языках» (учитывая наследников). */
    private static Object resolveLocalStringDialog(Shell shell)
    {
        Object data = shell.getData();
        if (data == null)
            return null;
        for (Class<?> c = data.getClass(); c != null; c = c.getSuperclass())
        {
            if (DIALOG_CLASS.equals(c.getName()))
                return data;
        }
        return null;
    }

    private static void attach(Shell shell, Object dialog)
    {
        if (shell.isDisposed() || !SpellCheckHook.isComfortPlatformSpellingActive())
            return;
        DataBindingContext context = resolveContext(dialog);
        Object localStringsObject = Global.invoke(dialog, "getLocalStrings"); //$NON-NLS-1$
        if (context == null || !(localStringsObject instanceof List<?> localStrings)
            || localStrings.isEmpty())
            return;

        List<Text> fields = new ArrayList<>();
        collectTexts(shell, fields);
        if (fields.isEmpty())
            return;

        // Сначала разбираем соответствие целиком и только потом подменяем: привязка к чужому
        // языку записала бы текст не в ту строку, поэтому при любой неясности не трогаем окно.
        Map<Text, Object> byField = new LinkedHashMap<>();
        for (Text field : fields)
        {
            Object model = modelForWidget(context, field);
            if (model == null)
                continue; // не поле языка (своей привязки нет)
            Object localString = localStringFor(localStrings, model);
            if (localString == null)
                return;
            byField.put(field, localString);
        }
        if (byField.isEmpty())
            return;

        Composite content = byField.keySet().iterator().next().getParent();
        for (Map.Entry<Text, Object> entry : byField.entrySet())
            replaceField(entry.getKey(), entry.getValue(), context, dialog);
        if (content != null && !content.isDisposed())
        {
            content.layout(true, true);
            content.pack();
        }
    }

    private static DataBindingContext resolveContext(Object dialog)
    {
        Object support = Global.getField(dialog, "bindingSupport"); //$NON-NLS-1$
        Object context = support == null ? null : Global.invoke(support, "getDBContext"); //$NON-NLS-1$
        return context instanceof DataBindingContext dbc ? dbc : null;
    }

    /** Модель привязки, целью которой является именно этот виджет. */
    private static Object modelForWidget(DataBindingContext context, Text field)
    {
        for (Object object : context.getBindings())
        {
            if (!(object instanceof Binding binding))
                continue;
            if (binding.getTarget() instanceof ISWTObservableValue<?> target
                && target.getWidget() == field)
                return binding.getModel();
        }
        return null;
    }

    private static Object localStringFor(List<?> localStrings, Object model)
    {
        for (Object localString : localStrings)
        {
            if (Global.invoke(localString, "getTextValue") == model) //$NON-NLS-1$
                return localString;
        }
        return null;
    }

    private static void collectTexts(Control root, List<Text> into)
    {
        if (root == null || root.isDisposed())
            return;
        if (root instanceof Text text)
            into.add(text);
        if (root instanceof Composite composite)
        {
            for (Control child : composite.getChildren())
                collectTexts(child, into);
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceField(Text stock, Object localString, DataBindingContext context,
        Object dialog)
    {
        Composite parent = stock.getParent();
        if (parent == null || parent.isDisposed())
            return;
        Object textValue = Global.invoke(localString, "getTextValue"); //$NON-NLS-1$
        if (!(textValue instanceof IObservableValue))
            return;

        boolean multiLine = (stock.getStyle() & SWT.MULTI) != 0;
        int style = SWT.BORDER | (multiLine ? SWT.MULTI | SWT.WRAP | SWT.V_SCROLL : SWT.SINGLE);
        StyledText styled = new StyledText(parent, style);
        styled.moveAbove(stock);
        styled.setFont(stock.getFont());
        styled.setBackground(stock.getBackground());
        styled.setForeground(stock.getForeground());
        styled.setEditable(stock.getEditable());
        // У нативного поля есть внутренний отступ текста от рамки, у StyledText его нет.
        styled.setLeftMargin(2);
        styled.setRightMargin(2);
        // У StyledText alwaysShowScroll по умолчанию true — полоса висела бы всегда. Штатное окно
        // её, наоборот, прячет, пока текст помещается (свой слушатель на Modify/Resize у поля).
        styled.setAlwaysShowScrollBars(false);

        GridData layout = copyGridData(stock.getLayoutData());
        IObservableValue<Boolean> expandValue = expandValue(localString);
        boolean expanded = expandValue != null && Boolean.TRUE.equals(expandValue.getValue());
        // Считаем до привязки: с текстом в несколько строк computeSize даст не высоту строки.
        int collapsedHeight = styled.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        if (!expanded && layout.heightHint > 0)
            collapsedHeight = layout.heightHint; // диалог уже посчитал её сам
        styled.setLayoutData(layout);

        hide(stock);

        context.bindValue(WidgetProperties.<StyledText> text(SWT.Modify).observe(styled),
            (IObservableValue<String>)textValue);

        // Ctrl+Enter — ОК: у штатного поля это был слушатель SWT.KeyDown, у StyledText клавиши
        // перехватываются только через VerifyKey (см. правила репозитория).
        styled.addVerifyKeyListener(event ->
        {
            if (event.keyCode == SWT.CR && event.stateMask == SWT.MOD1)
            {
                event.doit = false;
                Global.invoke(dialog, "okPressed"); //$NON-NLS-1$
            }
        });
        // Tab уводит фокус дальше, как в обычном поле, а не вставляет табуляцию.
        styled.addListener(SWT.Traverse, event ->
        {
            if (event.detail == SWT.TRAVERSE_TAB_NEXT || event.detail == SWT.TRAVERSE_TAB_PREVIOUS)
                event.doit = true;
        });
        // Колесо мыши. Нативное поле, которому нечего прокручивать, отдаёт колесо окну само
        // (DefWindowProc пересылает его родителю), и список языков прокручивался. StyledText —
        // Canvas со своими полосами: он забирает колесо себе. Поэтому когда полю прокручивать
        // нечего, гасим событие (при doit=false SWT не вызывает обработку по умолчанию) и
        // прокручиваем окно сами.
        styled.addListener(SWT.MouseWheel, event ->
        {
            if (styled.isDisposed() || canScrollItself(styled))
                return;
            event.doit = false;
            scrollEnclosing(styled, event.count);
        });

        if (multiLine && expandValue != null)
        {
            final int collapsed = collapsedHeight;
            layout.heightHint = expanded ? EXPANDED_HEIGHT_PX : collapsed;
            expandValue.addValueChangeListener(event ->
            {
                if (styled.isDisposed())
                    return;
                boolean now = Boolean.TRUE.equals(event.diff.getNewValue());
                layout.heightHint = now ? EXPANDED_HEIGHT_PX : collapsed;
                Composite host = styled.getParent();
                if (host != null && !host.isDisposed())
                {
                    host.layout(true, true);
                    host.pack();
                }
                // Штатный «(раскрыть)» переводил фокус в поле — теперь оно наше.
                if (now && !styled.isFocusControl())
                    styled.setFocus();
            });
            styled.addListener(SWT.FocusIn, event ->
            {
                if (!Boolean.TRUE.equals(expandValue.getValue()))
                    expandValue.setValue(Boolean.TRUE);
                styled.setCaretOffset(styled.getCharCount());
            });
        }

        StyledTextSpellCheck.install(styled);
        if (expanded)
            styled.setFocus();
    }

    /**
     * Есть ли самому полю что прокручивать. Проверяем не видимость полосы, а её диапазон:
     * {@link StyledText} держит его в соответствии с содержимым независимо от того, показана
     * полоса или скрыта. У однострочного поля полосы нет вовсе.
     */
    private static boolean canScrollItself(StyledText styled)
    {
        ScrollBar bar = styled.getVerticalBar();
        return bar != null && bar.getMaximum() - bar.getMinimum() > bar.getThumb();
    }

    /** Прокрутить окно (его {@link ScrolledComposite}) вместо поля. */
    private static void scrollEnclosing(StyledText styled, int count)
    {
        ScrolledComposite scrolled = enclosingScrolled(styled);
        if (scrolled == null || scrolled.isDisposed())
            return;
        ScrollBar bar = scrolled.getVerticalBar();
        if (bar == null)
            return;
        int max = bar.getMaximum() - bar.getThumb();
        if (max <= bar.getMinimum())
            return;
        int step = styled.getLineHeight() > 0 ? styled.getLineHeight() : 16;
        Point origin = scrolled.getOrigin();
        // count > 0 — колесо от себя, содержимое уезжает вниз.
        int y = Math.max(bar.getMinimum(), Math.min(max, origin.y - count * step));
        if (y != origin.y)
            scrolled.setOrigin(origin.x, y);
    }

    private static ScrolledComposite enclosingScrolled(Control control)
    {
        for (Control c = control.getParent(); c != null; c = c.getParent())
        {
            if (c instanceof ScrolledComposite scrolled)
                return scrolled;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static IObservableValue<Boolean> expandValue(Object localString)
    {
        Object value = Global.invoke(localString, "getExpandValue"); //$NON-NLS-1$
        return value instanceof IObservableValue ? (IObservableValue<Boolean>)value : null;
    }

    /**
     * Штатное поле остаётся живым и привязанным к той же модели — просто перестаёт занимать место
     * и получать ввод. Его слушатели продолжают срабатывать вхолостую, снимать их не нужно.
     */
    private static void hide(Text stock)
    {
        stock.setVisible(false);
        if (stock.getLayoutData() instanceof GridData gd)
            gd.exclude = true;
    }

    private static GridData copyGridData(Object source)
    {
        GridData copy = new GridData(SWT.FILL, SWT.CENTER, true, false);
        if (!(source instanceof GridData src))
            return copy;
        copy.horizontalAlignment = src.horizontalAlignment;
        copy.verticalAlignment = src.verticalAlignment;
        copy.grabExcessHorizontalSpace = src.grabExcessHorizontalSpace;
        copy.grabExcessVerticalSpace = src.grabExcessVerticalSpace;
        copy.horizontalSpan = src.horizontalSpan;
        copy.verticalSpan = src.verticalSpan;
        copy.horizontalIndent = src.horizontalIndent;
        copy.verticalIndent = src.verticalIndent;
        copy.widthHint = src.widthHint;
        copy.heightHint = src.heightHint;
        copy.minimumWidth = src.minimumWidth;
        copy.minimumHeight = src.minimumHeight;
        return copy;
    }
}
