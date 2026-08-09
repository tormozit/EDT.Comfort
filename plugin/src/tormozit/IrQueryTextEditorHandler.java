package tormozit;

import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com._1c.g5.v8.dt.core.platform.IDtProject;

/**
 * Открытие текста запроса в текстовом редакторе ИР (псевдомодально).
 */
public final class IrQueryTextEditorHandler
{
    static final String MENU_LABEL = "Редактор ИР"; //$NON-NLS-1$
    private static final long DIALOG_WAIT_MS = 5_000;
    private static final Pattern TITLE_PATTERN = Pattern.compile("^Текст.*"); //$NON-NLS-1$ //$NON-NLS-2$

    private IrQueryTextEditorHandler() {}

    public static void openQueryTextInIrEditor(ISourceViewer viewer, Object queryDialog,
        Shell shell)
    {
        if (viewer == null || viewer.getDocument() == null)
            return;

        String text = viewer.getDocument().get();
        if (text == null || text.isEmpty())
            return;

        openTextInIrEditor(queryDialog, shell.getText(), text,
            normalized ->
            {
                if (viewer.getDocument() != null)
                    viewer.getDocument().set(normalized);
            },
            null, null);
    }

    /**
     * Общая часть: резолв проекта, {@code IRSession}, псевдомодальный вызов
     * {@code ОткрытьТекстЛкс} и получение изменённого текста обратно. Остальные вызывающие
     * (например, свойство дерева инспектора отладчика) отличаются только источником/приёмником
     * текста — тем, что делает {@code onTextChanged}.
     *
     * @param queryDialog контекст для {@link IrFormatTextHandler#resolveDtProjectForQuery};
     *     {@code null}, если такого контекста нет (проект резолвится по активному редактору)
     * @param sourceRef заголовок ({@code Заголовок} в {@code ОткрытьТекстЛкс}) — для вызывающего
     *     из редактора запроса это заголовок Shell'а диалога, для свойства дерева инспектора —
     *     полное имя свойства (watch-выражение)
     * @param onTextChanged вызывается на UI-потоке с нормализованным новым текстом, только если
     *     он отличается от {@code currentText}
     * @param onBegin вызывается на вызывающем потоке непосредственно перед отправкой фонового
     *     вызова ИР в {@code irSession.executor} — не раньше (после успешного резолва проекта
     *     и сессии), чтобы не начинать без гарантии парного {@code onFinished}; может быть
     *     {@code null}
     * @param onFinished вызывается на UI-потоке в {@code finally} фонового вызова — независимо
     *     от результата (успех / отмена / ошибка); может быть {@code null}
     */
    static void openTextInIrEditor(Object queryDialog, String sourceRef, String currentText,
        Consumer<String> onTextChanged, Runnable onBegin, Runnable onFinished)
    {
        if (currentText == null || currentText.isEmpty())
            return;

        IDtProject dtProject = IrFormatTextHandler.resolveDtProjectForQuery(queryDialog);
        if (dtProject == null)
        {
            toast(MENU_LABEL, "Не удалось определить проект EDT"); //$NON-NLS-1$
            return;
        }

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        if (onBegin != null)
            onBegin.run();
        irSession.executor.submit(() -> {
            try
            {
                String newText = irSession.runIrModalDialog(TITLE_PATTERN, DIALOG_WAIT_MS, () -> {
                    Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
                    String lfText = Global.normalizeLineSeparators(currentText);
                    // (Текст, Заголовок, ВариантПросмотра, ТолькоПросмотр,
                    //  КлючУникальности, ВладелецФормы, ВыделитьВсе, Модально,
                    //  ВыделениеДвумерное, ИскомаяСтрока, КлючИсточника)
                    // Модально здесь — параметр самой формы ИР относительно её владельца
                    // (внутри ИР), к модальности EDT отношения не имеет.
                    Object result = ComBridge.invoke(irClient, "ОткрытьТекстЛкс", lfText, sourceRef, //$NON-NLS-1$
                        null, false, null, null, false, true, null, ""); //$NON-NLS-1$
                    if (IRApplication.isCancelled(result))
                        return null;
                    return ComBridge.toString(result);
                });
                if (newText == null || newText.equals(currentText))
                    return;
                String normalized = Global.normalizeLineSeparators(newText);
                Display ui = Display.getDefault();
                if (ui != null && !ui.isDisposed())
                    ui.asyncExec(() -> onTextChanged.accept(normalized));
            }
            catch (Exception e)
            {
                toast(MENU_LABEL, "Ошибка вызова ИР: " + e.getMessage(), 5_000); //$NON-NLS-1$
            }
            finally
            {
                if (onFinished != null)
                {
                    Display ui = Display.getDefault();
                    if (ui != null && !ui.isDisposed())
                        ui.asyncExec(onFinished);
                }
            }
        });
    }

    private static void toast(String title, String message)
    {
        toast(title, message, 3_000);
    }

    private static void toast(String title, String message, int durationMs)
    {
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
            display.asyncExec(() -> ToastNotification.show(title, message, durationMs));
    }
}
