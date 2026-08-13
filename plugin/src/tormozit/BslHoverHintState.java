package tormozit;

import java.util.Vector;

import org.eclipse.jface.text.AbstractInformationControlManager;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.internal.win32.OS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.editor.IFormPage;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;
import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditorXtextEditorPage;

/**
 * Состояние глобального переключателя «Подсказки при наведении без Ctrl»
 * (пункт подменю «Комфорт» в контекстном меню BSL-редактора).
 *
 * <p>Два режима:
 * <ul>
 * <li>тумблер включен — обычный hover при удержании указателя мыши (без Ctrl);
 * <li>тумблер выключен — hover только при зажатом Ctrl.
 * </ul>
 *
 * <p>Механизм тот же, что у {@code BslModuleSpellCheckHook}: у {@link ISourceViewer}
 * находится поле {@code fTextHoverManager} — наследник {@link AbstractInformationControlManager} —
 * и у него выставляется {@code setEnabled(...)}. Так гасятся и штатный doc-hover,
 * и ИР-обогащение ({@code IrBslTextHoverWrapper} живёт внутри того же менеджера).
 * Состояние Ctrl отслеживается глобальным Display-фильтром. На {@code MouseMove}
 * нельзя полагаться на {@code event.stateMask}: {@code TextEditorCtrlClickSelectWordHook}
 * снимает бит Ctrl, чтобы не рисовать гиперссылку до выделения слова.
 *
 * <p>{@code fInformationPresenter} (Ctrl+hover / Ctrl+F2) сознательно не трогаем:
 * это не «удержание указателя мыши», а осознанное действие с клавиатуры.
 */
final class BslHoverHintState
{
    private static final String[] MANAGER_FIELDS = { "fTextHoverManager" }; //$NON-NLS-1$

    /** Кэш состояния Ctrl, обновляется глобальным Display-фильтром. */
    private static volatile boolean ctrlHeld;

    private static boolean filterInstalled;

    private BslHoverHintState() {}

    static boolean isEnabled()
    {
        return ComfortSettings.isHoverHintsEnabled();
    }

    /** Сохранить настройку и применить ко всем открытым BSL-редакторам. */
    static void setEnabled(boolean enabled)
    {
        ComfortSettings.setHoverHintsEnabled(enabled);
        applyToAllEditors();
    }

    /** Итоговая доступность hover: тумблер вкл → всегда; тумблер выкл → только при Ctrl. */
    static boolean isHoverHintsCurrentlyEnabled()
    {
        return ComfortSettings.isHoverHintsEnabled() || ctrlHeld;
    }

    /**
     * Включить/выключить hover-менеджеры конкретного viewer согласно текущему
     * состоянию (тумблер + Ctrl-гейт). При выключении закрывает открытую подсказку.
     */
    static void applyToViewer(ISourceViewer viewer)
    {
        if (viewer == null)
            return;
        ensureCtrlFilterInstalled();
        boolean enabled = isHoverHintsCurrentlyEnabled();
        for (String field : MANAGER_FIELDS)
        {
            Object mgr = Global.getField(viewer, field);
            if (!(mgr instanceof AbstractInformationControlManager aim))
                continue;
            try
            {
                if (!enabled)
                    aim.disposeInformationControl();
                aim.setEnabled(enabled);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    static void applyToAllEditors()
    {
        try
        {
            if (!PlatformUI.isWorkbenchRunning())
                return;
            ensureCtrlFilterInstalled();
            for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
            {
                for (IWorkbenchPage page : window.getPages())
                {
                    for (IEditorReference ref : page.getEditorReferences())
                        applyToEditor(ref.getEditor(false));
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static void applyToEditor(IEditorPart editor)
    {
        if (editor instanceof BslXtextEditor bsl)
        {
            applyToViewer(bsl.getInternalSourceViewer());
        }
        else if (editor instanceof DtGranularEditor<?> granular)
        {
            applyToFormPage(granular.getActivePageInstance());
            // Все страницы, включая неактивные вкладки: FormEditor не публикует
            // перечисление страниц — читаем защищённое поле Vector pages.
            Object pagesField = Global.getField(granular, "pages"); //$NON-NLS-1$
            if (pagesField instanceof Vector<?> pages)
            {
                for (Object page : pages)
                    if (page instanceof IFormPage formPage)
                        applyToFormPage(formPage);
            }
        }
    }

    private static void applyToFormPage(IFormPage page)
    {
        if (!(page instanceof DtGranularEditorXtextEditorPage<?> xtextPage))
            return;
        IEditorPart embedded = xtextPage.getEmbeddedEditor();
        if (embedded instanceof BslXtextEditor bsl)
            applyToViewer(bsl.getInternalSourceViewer());
    }

    /**
     * Установить (один раз) глобальный Display-фильтр слежения за Ctrl.
     * При смене состояния применяет гейт ко всем открытым BSL-редакторам.
     */
    private static void ensureCtrlFilterInstalled()
    {
        if (filterInstalled)
            return;
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
            return;
        filterInstalled = true;
        Listener listener = (Event event) -> updateCtrlState(isCtrlHeld(event));
        display.addFilter(SWT.KeyDown, listener);
        display.addFilter(SWT.KeyUp, listener);
        display.addFilter(SWT.MouseMove, listener);
    }

    /**
     * Реальное удержание Ctrl. {@code KeyDown}/{@code KeyUp} надёжны сами по себе;
     * на {@code MouseMove} {@code stateMask} может быть уже без Ctrl — другой Display-фильтр
     * снимает {@link SWT#MOD1}, чтобы {@code HyperlinkManager} не активировался.
     */
    private static boolean isCtrlHeld(Event event)
    {
        if (event.type == SWT.KeyDown && event.keyCode == SWT.CTRL)
            return true;
        if (event.type == SWT.KeyUp && event.keyCode == SWT.CTRL)
            return false;
        return (OS.GetKeyState(OS.VK_CONTROL) & 0x8000) != 0;
    }

    private static void updateCtrlState(boolean now)
    {
        if (ctrlHeld == now)
            return;
        ctrlHeld = now;
        if (!ComfortSettings.isHoverHintsEnabled())
            applyToAllEditors();
    }
}
