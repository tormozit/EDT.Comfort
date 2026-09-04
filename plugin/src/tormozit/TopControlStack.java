package tormozit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Layout;

/**
 * То же, что {@code StackLayout}, но предпочтительный размер берётся только у текущего верхнего
 * контрола. У таблицы с {@link org.eclipse.jface.layout.TableColumnLayout} он равен сумме ширин
 * колонок и иначе задавал бы ширину всего контейнера (страницы мастера, панели результатов поиска).
 *
 * <p>Потребители: табличный режим страницы «Вносимые изменения» мастера рефакторинга
 * ({@link RefactoringPreviewTableHook}) и неотключаемый табличный режим панели результатов «Найти
 * ссылки» на программный элемент BSL ({@link BslReferenceSearchTableHook}) — оба кладут скрытое
 * штатное дерево и свою таблицу в один {@link Composite} и переключают верхний контрол.
 */
final class TopControlStack extends Layout
{
    Control topControl;

    @Override
    protected Point computeSize(Composite composite, int wHint, int hHint, boolean flushCache)
    {
        Point size = topControl == null || topControl.isDisposed() ? new Point(0, 0)
            : topControl.computeSize(wHint, hHint, flushCache);
        if (wHint != SWT.DEFAULT)
            size.x = wHint;
        if (hHint != SWT.DEFAULT)
            size.y = hHint;
        return size;
    }

    @Override
    protected void layout(Composite composite, boolean flushCache)
    {
        Rectangle client = composite.getClientArea();
        for (Control child : composite.getChildren())
        {
            child.setVisible(child == topControl);
            if (child == topControl)
                child.setBounds(client);
        }
    }
}
