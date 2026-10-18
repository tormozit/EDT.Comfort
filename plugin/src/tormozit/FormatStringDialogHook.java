package tormozit;


import org.eclipse.core.databinding.observable.map.IMapChangeListener;
import org.eclipse.core.databinding.observable.map.IObservableMap;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.common.Pair;
import com._1c.g5.v8.dt.core.format.FormatStringParameter;

/** Дополняет список «Формат даты» в штатном конструкторе форматной строки. */
public final class FormatStringDialogHook implements IStartup
{
    private static final String DIALOG_CLASS =
        "com._1c.g5.v8.dt.ui.dialog.FormatStringDialog"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.formatStringDialogPatched"; //$NON-NLS-1$
    private static final String FULL_DATE_FORMAT = "dd.MM.yyyy HH:mm:ss"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            Display display = Display.getDefault();
            if (display.isDisposed())
                return;
            display.addFilter(SWT.Show, event ->
            {
                if (!(event.widget instanceof Shell shell) || shell.isDisposed()
                    || shell.getData(PATCHED_KEY) != null)
                    return;
                Object dialog = shell.getData();
                if (dialog == null || !DIALOG_CLASS.equals(dialog.getClass().getName()))
                    return;
                attach(shell, dialog);
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static void attach(Shell shell, Object dialog)
    {
        // FormatStringDialog.fillDateTab(): df — именно DATE_FORMAT, не DATE_LOCAL_FORMAT.
        Object field = Global.getField(dialog, "df"); //$NON-NLS-1$
        Object values = Global.getField(dialog, "valueMap"); //$NON-NLS-1$
        if (!(field instanceof ComboViewer viewer) || !(values instanceof IObservableMap<?, ?>))
            return;
        IObservableMap<FormatStringParameter, String> map =
            (IObservableMap<FormatStringParameter, String>)values;
        addFullDateFormat(viewer, dialog);
        // updateLocaleDependencyData() заменяет input при изменении LOCALE_NAME.
        IMapChangeListener<FormatStringParameter, String> listener = event ->
        {
            if (event.diff.getAddedKeys().contains(FormatStringParameter.LOCALE_NAME)
                || event.diff.getChangedKeys().contains(FormatStringParameter.LOCALE_NAME)
                || event.diff.getRemovedKeys().contains(FormatStringParameter.LOCALE_NAME))
                shell.getDisplay().asyncExec(() -> addFullDateFormat(viewer, dialog));
        };
        map.addMapChangeListener(listener);
        shell.addListener(SWT.Dispose, event ->
        {
            if (!map.isDisposed())
                map.removeMapChangeListener(listener);
        });
        shell.setData(PATCHED_KEY, Boolean.TRUE);
    }

    private static void addFullDateFormat(ComboViewer viewer, Object dialog)
    {
        Combo combo = viewer.getCombo();
        if (combo.isDisposed())
            return;
        // Штатные конвертеры ожидают Pair[]: first — формат, second — надпись в списке.
        if (!(viewer.getInput() instanceof Pair<?, ?>[] items))
            return;
        for (Pair<?, ?> item : items)
            if (FULL_DATE_FORMAT.equals(item.first))
                return;
        // getPresentation() вставляет шаблон в форматную строку без экранирования.
        // Пробел требует кавычек, как в штатном buildAndApplyFormat().
        Object value = Global.invoke(dialog, "getComboValues", //$NON-NLS-1$
            "'" + FULL_DATE_FORMAT + "'", FormatStringParameter.DATE_FORMAT); //$NON-NLS-1$ //$NON-NLS-2$
        if (!(value instanceof Pair<?, ?>[] variants) || variants.length != 1)
            return;
        String text = combo.getText();
        Point textSelection = combo.getSelection();
        ISelection selection = viewer.getSelection();
        Pair<?, ?>[] extended = new Pair<?, ?>[items.length + 1];
        extended[0] = new Pair<>(FULL_DATE_FORMAT, variants[0].second);
        System.arraycopy(items, 0, extended, 1, items.length);
        viewer.setInput(extended);
        viewer.setSelection(selection);
        combo.setText(text);
        combo.setSelection(textSelection);
    }
}
