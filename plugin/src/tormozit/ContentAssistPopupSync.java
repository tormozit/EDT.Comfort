package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;

/**
 * Синхронизация {@code CompletionProposalPopup} с {@link SmartContentAssistProcessor}.
 * Штатный incremental filter подменяется через {@link #installFilterOverride}.
 */
public final class ContentAssistPopupSync
{
    private static final IdentityHashMap<Object, Runnable> ORIGINAL_FILTER_RUNNABLES =
        new IdentityHashMap<>();
    /** Выделение до Ctrl+Space (до native {@code handleRepeatedInvocation} меняет таблицу). */
    private static final ThreadLocal<SavedSelection> pendingFilterToggleSelection =
        new ThreadLocal<>();

    private static Field popupField;
    private static Field fComputedProposalsField;
    private static Field fFilteredProposalsField;
    private static Field fIsFilteredSubsetField;
    private static Field fIsFilterPendingField;
    private static Field fFilterRunnableField;
    private static Field fFilterOffsetField;
    private static Field fLastCompletionOffsetField;
    private static Field fInvocationOffsetField;
    private static Field fDocumentEventsField;
    private static Method hidePopupMethod;
    private static Field fProposalTableField;
    private static Field fProposalShellField;
    private static Field fMessageTextField;
    private static Method setStatusLineVisibleMethod;
    private static Field fAdditionalInfoControllerField;
    private static Method setProposalsMethod;
    private static Method selectProposalMethod;
    private static Method configureAndMakeVisibleMethod;
    private static Method handleTableSelectionChangedMethod;

    private ContentAssistPopupSync() {}

    /**
     * Подменяет {@code fFilterRunnable}: при вводе в документе popup вызывает наш пересчёт,
     * а не {@code computeFilteredProposals} по сырому кэшу.
     */
    public static void installFilterOverride(ContentAssistant assistant, SourceViewer viewer,
                                             SmartContentAssistProcessor processor)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return;
            initPopupReflection(popup);
            if (ORIGINAL_FILTER_RUNNABLES.containsKey(popup))
                return;

            Runnable original = (Runnable) fFilterRunnableField.get(popup);
            ORIGINAL_FILTER_RUNNABLES.put(popup, original);
            Runnable replacement = () -> {
                clearFilterPending(popup);
                applyFilteredList(assistant, viewer, processor);
            };
            fFilterRunnableField.set(popup, replacement);
            ContentAssistDebug.log("filterRunnable hijacked"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("filterRunnable hijack ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static void uninstallFilterOverride(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return;
            initPopupReflection(popup);
            Runnable original = ORIGINAL_FILTER_RUNNABLES.remove(popup);
            if (original != null)
                fFilterRunnableField.set(popup, original);
        }
        catch (Exception ignored) {}
    }

    /**
     * Пересчёт через {@link SmartContentAssistProcessor} и обновление таблицы popup.
     */
    public static boolean applyFilteredList(ContentAssistant assistant, SourceViewer viewer,
                                            SmartContentAssistProcessor processor)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
            {
                ContentAssistDebug.log("popupSync SKIP: popup null"); //$NON-NLS-1$
                return false;
            }
            initPopupReflection(popup);
            if (shouldCloseOnPendingDocumentEvents(popup))
            {
                clearPendingDocumentEvents(popup);
                hideProposalPopup(popup);
                return false;
            }
            installFilterOverride(assistant, viewer, processor);
            clearFilterPending(popup);
            hideStatusLine(assistant, popup);

            SavedSelection saved = takePendingFilterToggleSelection();
            if (saved == null)
                saved = saveSelection(popup);

            int caret = viewer.getTextWidget().getCaretOffset();
            syncPopupFilterOffsets(popup, caret);
            ICompletionProposal[] proposals = processor.computeCompletionProposals(viewer, caret);
            if (proposals == null)
                proposals = new ICompletionProposal[0];

            // После compute — SmartFilterTracker уже содержит префикс с текущей каретки.
            boolean resetToFirst = shouldResetSelectionToFirst();

            ArrayList<ICompletionProposal> list = new ArrayList<>(Arrays.asList(proposals));
            if (fIsFilteredSubsetField != null)
                fIsFilteredSubsetField.setBoolean(popup, false);

            // Не трогаем fFilteredProposals до setProposals — иначе guard в setProposals
            // (сравнение снимка списка) и getSelectedProposal→fFilterRunnable ломают обновление.
            setProposalsAndRestoreSelection(popup, list, false, saved, resetToFirst);

            List<ICompletionProposal> applied = (List<ICompletionProposal>)
                fFilteredProposalsField.get(popup);
            if (fComputedProposalsField != null && applied != null)
                fComputedProposalsField.set(popup, applied);

            int tableRows = tableItemCount(popup);
            ContentAssistDebug.log("popupSync caret=" + caret //$NON-NLS-1$
                + " count=" + proposals.length //$NON-NLS-1$
                + " tableRows=" + tableRows //$NON-NLS-1$
                + " resetSel=" + resetToFirst //$NON-NLS-1$
                + ContentAssistDebug.sampleTypes(proposals, 2));

            if (tableRows >= 0 && tableRows != proposals.length)
                forceTableRefresh(popup, applied != null ? applied : list, saved, resetToFirst);

            refreshAdditionalInfo(popup, resetToFirst);
            ContentAssistPopupUi.ensureFilterToggle(assistant, viewer, processor);
            ContentAssistPopupUi.updateContextTypeLabel(viewer);
            clearPendingDocumentEvents(popup);
            return true;
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("popupSync ERROR: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    public static void hideStatusLine(ContentAssistant assistant, Object popup)
    {
        try
        {
            if (assistant != null)
                assistant.setStatusLineVisible(false);
            if (popup == null)
                return;
            initPopupReflection(popup);
            if (setStatusLineVisibleMethod != null)
                setStatusLineVisibleMethod.invoke(popup, Boolean.FALSE);
            if (fMessageTextField != null)
            {
                Label message = (Label) fMessageTextField.get(popup);
                if (message != null && !message.isDisposed())
                    message.dispose();
                fMessageTextField.set(popup, null);
            }
        }
        catch (Exception ignored) {}
    }

    private static void forceTableRefresh(Object popup, List<ICompletionProposal> list,
                                          SavedSelection saved, boolean resetToFirst)
            throws Exception
    {
        if (fProposalTableField == null || selectProposalMethod == null || list == null)
            return;
        Table table = (Table) fProposalTableField.get(popup);
        if (table == null || table.isDisposed())
            return;
        fFilteredProposalsField.set(popup, list);
        table.clearAll();
        table.setItemCount(list.size());
        applySelection(popup, list, saved, resetToFirst);
        ContentAssistDebug.log("popupSync forceTableRefresh rows=" + list.size()); //$NON-NLS-1$
    }

    /** Первая строка только при smart filter + непустом префиксе ({@link SmartContentAssistProcessor#filterAndSort}). */
    private static boolean shouldResetSelectionToFirst()
    {
        return SmartAssistFilterState.isSmartFilterEnabled()
            && !SmartFilterTracker.getCurrentFilter().isEmpty();
    }

    private static int tableItemCount(Object popup)
    {
        try
        {
            if (fProposalTableField == null)
                return -1;
            Table table = (Table) fProposalTableField.get(popup);
            if (table == null || table.isDisposed())
                return -1;
            return table.getItemCount();
        }
        catch (Exception ignored)
        {
            return -1;
        }
    }

    /**
     * Панель справа (AdditionalInfo): install + показ для выбранного элемента.
     * Нужна после {@link #applyFilteredList} и при повторном вызове assist
     * ({@code handleRepeatedInvocation} не вызывает {@code displayProposals}).
     */
    public static void refreshAdditionalInfo(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup != null)
                refreshAdditionalInfo(popup, false);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("additionalInfo ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void refreshAdditionalInfo(Object popup, boolean resetToFirst)
            throws Exception
    {
        initPopupReflection(popup);

        int selectionIndex = 0;
        if (!resetToFirst && fProposalTableField != null)
        {
            Table table = (Table) fProposalTableField.get(popup);
            if (table != null && !table.isDisposed())
            {
                int idx = table.getSelectionIndex();
                if (idx >= 0)
                    selectionIndex = idx;
            }
        }

        if (fProposalTableField != null)
        {
            Table table = (Table) fProposalTableField.get(popup);
            if (table != null && !table.isDisposed() && table.getItemCount() > 0)
            {
                if (selectionIndex >= table.getItemCount())
                    selectionIndex = table.getItemCount() - 1;
                table.setSelection(selectionIndex);
                table.showSelection();
            }
        }

        if (selectProposalMethod != null)
            selectProposalMethod.invoke(popup, selectionIndex, Boolean.FALSE);

        if (fAdditionalInfoControllerField != null && handleTableSelectionChangedMethod != null)
        {
            Object controller = fAdditionalInfoControllerField.get(popup);
            if (controller != null)
                handleTableSelectionChangedMethod.invoke(controller);
        }

        if (configureAndMakeVisibleMethod != null && fProposalShellField != null)
        {
            Object shell = fProposalShellField.get(popup);
            if (shell instanceof org.eclipse.swt.widgets.Shell
                && !((org.eclipse.swt.widgets.Shell) shell).isDisposed()
                && ((org.eclipse.swt.widgets.Shell) shell).isVisible())
            {
                configureAndMakeVisibleMethod.invoke(popup);
            }
        }
    }

    private static void clearFilterPending(Object popup)
    {
        try
        {
            if (fIsFilterPendingField == null)
                return;
            AtomicBoolean pending = (AtomicBoolean) fIsFilterPendingField.get(popup);
            if (pending != null)
                pending.set(false);
        }
        catch (Exception ignored) {}
    }

    public static Object getPopupObject(ContentAssistant assistant)
    {
        try
        {
            return getPopup(assistant);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    public static int getFilterOffset(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return -1;
            initPopupReflection(popup);
            if (fFilterOffsetField == null)
                return -1;
            return fFilterOffsetField.getInt(popup);
        }
        catch (Exception ignored)
        {
            return -1;
        }
    }

    public static boolean isPopupVisible(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return false;
            initPopupReflection(popup);
            if (fProposalShellField == null)
                return false;
            Object shell = fProposalShellField.get(popup);
            return shell instanceof org.eclipse.swt.widgets.Shell
                && !((org.eclipse.swt.widgets.Shell) shell).isDisposed()
                && ((org.eclipse.swt.widgets.Shell) shell).isVisible();
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    public static org.eclipse.swt.widgets.Shell getProposalShell(Object popup)
    {
        try
        {
            if (popup == null)
                return null;
            initPopupReflection(popup);
            if (fProposalShellField == null)
                return null;
            Object shell = fProposalShellField.get(popup);
            return shell instanceof org.eclipse.swt.widgets.Shell
                ? (org.eclipse.swt.widgets.Shell) shell : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    public static Table getProposalTable(Object popup)
    {
        try
        {
            if (popup == null)
                return null;
            initPopupReflection(popup);
            if (fProposalTableField == null)
                return null;
            Object table = fProposalTableField.get(popup);
            return table instanceof Table ? (Table) table : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static Object getPopup(ContentAssistant assistant) throws Exception
    {
        if (popupField == null)
        {
            popupField = ContentAssistant.class.getDeclaredField("fProposalPopup"); //$NON-NLS-1$
            popupField.setAccessible(true);
        }
        return popupField.get(assistant);
    }

    private static void initPopupReflection(Object popup) throws Exception
    {
        if (setProposalsMethod != null)
            return;

        Class<?> popupClass = popup.getClass();
        setProposalsMethod =
            popupClass.getDeclaredMethod("setProposals", List.class, boolean.class); //$NON-NLS-1$
        setProposalsMethod.setAccessible(true);
        selectProposalMethod =
            popupClass.getDeclaredMethod("selectProposal", int.class, boolean.class); //$NON-NLS-1$
        selectProposalMethod.setAccessible(true);
        fComputedProposalsField =
            popupClass.getDeclaredField("fComputedProposals"); //$NON-NLS-1$
        fComputedProposalsField.setAccessible(true);
        fFilteredProposalsField =
            popupClass.getDeclaredField("fFilteredProposals"); //$NON-NLS-1$
        fFilteredProposalsField.setAccessible(true);
        fIsFilteredSubsetField =
            popupClass.getDeclaredField("fIsFilteredSubset"); //$NON-NLS-1$
        fIsFilteredSubsetField.setAccessible(true);
        fIsFilterPendingField =
            popupClass.getDeclaredField("fIsFilterPending"); //$NON-NLS-1$
        fIsFilterPendingField.setAccessible(true);
        fFilterRunnableField =
            popupClass.getDeclaredField("fFilterRunnable"); //$NON-NLS-1$
        fFilterRunnableField.setAccessible(true);
        fProposalTableField =
            popupClass.getDeclaredField("fProposalTable"); //$NON-NLS-1$
        fProposalTableField.setAccessible(true);
        fProposalShellField =
            popupClass.getDeclaredField("fProposalShell"); //$NON-NLS-1$
        fProposalShellField.setAccessible(true);
        fAdditionalInfoControllerField =
            popupClass.getDeclaredField("fAdditionalInfoController"); //$NON-NLS-1$
        fAdditionalInfoControllerField.setAccessible(true);
        configureAndMakeVisibleMethod =
            popupClass.getDeclaredMethod("configureAndMakeVisible"); //$NON-NLS-1$
        configureAndMakeVisibleMethod.setAccessible(true);
        Class<?> controllerClass = Class.forName(
            "org.eclipse.jface.text.contentassist.AdditionalInfoController"); //$NON-NLS-1$
        handleTableSelectionChangedMethod =
            controllerClass.getDeclaredMethod("handleTableSelectionChanged"); //$NON-NLS-1$
        handleTableSelectionChangedMethod.setAccessible(true);
        try
        {
            fMessageTextField = popupClass.getDeclaredField("fMessageText"); //$NON-NLS-1$
            fMessageTextField.setAccessible(true);
            setStatusLineVisibleMethod =
                popupClass.getDeclaredMethod("setStatusLineVisible", boolean.class); //$NON-NLS-1$
            setStatusLineVisibleMethod.setAccessible(true);
        }
        catch (NoSuchFieldException | NoSuchMethodException ignored) {}
        try
        {
            fFilterOffsetField = popupClass.getDeclaredField("fFilterOffset"); //$NON-NLS-1$
            fFilterOffsetField.setAccessible(true);
            fLastCompletionOffsetField =
                popupClass.getDeclaredField("fLastCompletionOffset"); //$NON-NLS-1$
            fLastCompletionOffsetField.setAccessible(true);
            fInvocationOffsetField =
                popupClass.getDeclaredField("fInvocationOffset"); //$NON-NLS-1$
            fInvocationOffsetField.setAccessible(true);
        }
        catch (Exception ignored) {}
        try
        {
            fDocumentEventsField = popupClass.getDeclaredField("fDocumentEvents"); //$NON-NLS-1$
            fDocumentEventsField.setAccessible(true);
            hidePopupMethod = popupClass.getDeclaredMethod("hide"); //$NON-NLS-1$
            hidePopupMethod.setAccessible(true);
        }
        catch (NoSuchFieldException | NoSuchMethodException ignored) {}
    }

    /** Закрыть popup assist (недопустимый символ в фильтре и т.п.). */
    public static void hideProposalPopup(Object popup)
    {
        try
        {
            if (popup == null)
                return;
            initPopupReflection(popup);
            if (hidePopupMethod != null)
                hidePopupMethod.invoke(popup);
            ContentAssistDebug.log("popupSync closed"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("popupSync close ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    public static void hideProposalPopup(ContentAssistant assistant)
    {
        try
        {
            hideProposalPopup(getPopup(assistant));
        }
        catch (Exception ignored) {}
    }

    private static boolean shouldCloseOnPendingDocumentEvents(Object popup)
    {
        try
        {
            if (fDocumentEventsField == null)
                return false;
            @SuppressWarnings("unchecked")
            List<DocumentEvent> events = (List<DocumentEvent>) fDocumentEventsField.get(popup);
            if (events == null || events.isEmpty())
                return false;
            for (DocumentEvent event : events)
            {
                if (SmartContentAssistProcessor.shouldCloseAssistOnDocumentEvent(event))
                    return true;
            }
        }
        catch (Exception ignored) {}
        return false;
    }

    private static void clearPendingDocumentEvents(Object popup)
    {
        try
        {
            if (fDocumentEventsField == null)
                return;
            @SuppressWarnings("unchecked")
            List<DocumentEvent> events = (List<DocumentEvent>) fDocumentEventsField.get(popup);
            if (events != null)
                events.clear();
        }
        catch (Exception ignored) {}
    }

    /**
     * Сбрасывает устаревшие offset'ы popup (повторный вызов assist при живом shell
     * идёт через {@code handleRepeatedInvocation} со старым {@code fFilterOffset}).
     */
    public static int syncSessionOffsets(ContentAssistant assistant, SourceViewer viewer)
    {
        int caret = 0;
        try
        {
            if (viewer.getTextWidget() != null)
                caret = viewer.getTextWidget().getCaretOffset();
        }
        catch (Exception ignored) {}
        if (caret < 0)
            caret = 0;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return caret;
            initPopupReflection(popup);
            syncPopupFilterOffsets(popup, caret);
            clearFilterPending(popup);
            if (fIsFilteredSubsetField != null)
                fIsFilteredSubsetField.setBoolean(popup, false);
        }
        catch (Exception ignored) {}
        return caret;
    }

    /** Синхронизация offset'ов popup при расхождении с кареткой в {@code computeProposals}. */
    public static void syncPopupOffsetsToCaret(org.eclipse.jface.text.ITextViewer viewer, int caret)
    {
        if (viewer == null || caret < 0)
            return;
        ContentAssistant assistant = ContentAssistSessionReloader.getActiveAssistant();
        if (assistant == null && viewer instanceof SourceViewer)
            assistant = ContentAssistPatcher.getContentAssistant((SourceViewer) viewer);
        if (assistant == null)
            return;
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return;
            initPopupReflection(popup);
            syncPopupFilterOffsets(popup, caret);
        }
        catch (Exception ignored) {}
    }

    private static void syncPopupFilterOffsets(Object popup, int caret)
    {
        try
        {
            if (fFilterOffsetField != null)
                fFilterOffsetField.setInt(popup, caret);
            if (fLastCompletionOffsetField != null)
                fLastCompletionOffsetField.setInt(popup, caret);
            if (fInvocationOffsetField != null)
                fInvocationOffsetField.setInt(popup, caret);
        }
        catch (Exception ignored) {}
    }

    public static final class SavedSelection
    {
        final int index;
        final String displayKey;

        SavedSelection(int index, String displayKey)
        {
            this.index = index;
            this.displayKey = displayKey;
        }
    }

    /** Сохранить строку до переключения фильтра (Ctrl+Space / repeated invocation). */
    public static void captureSelectionBeforeFilterToggle(ContentAssistant assistant)
    {
        try
        {
            Object popup = getPopup(assistant);
            if (popup == null)
                return;
            SavedSelection saved = saveSelection(popup);
            pendingFilterToggleSelection.set(saved);
            ContentAssistDebug.log("popupSync captureBeforeToggle idx=" + saved.index //$NON-NLS-1$
                + " key=\"" + saved.displayKey + "\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception ignored) {}
    }

    private static SavedSelection takePendingFilterToggleSelection()
    {
        SavedSelection saved = pendingFilterToggleSelection.get();
        pendingFilterToggleSelection.remove();
        return saved;
    }

    public static void clearPendingFilterToggleSelection()
    {
        pendingFilterToggleSelection.remove();
    }

    private static SavedSelection saveSelection(Object popup)
    {
        try
        {
            initPopupReflection(popup);
            Table table = getProposalTable(popup);
            int index = table != null && !table.isDisposed() ? table.getSelectionIndex() : -1;
            if (index < 0)
                index = 0;

            List<ICompletionProposal> list =
                (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
            String key = proposalDisplayKey(list, index);
            return new SavedSelection(index, key);
        }
        catch (Exception ignored)
        {
            return new SavedSelection(0, null);
        }
    }

    private static void setProposalsAndRestoreSelection(Object popup,
                                                        List<ICompletionProposal> list,
                                                        boolean filteredSubset,
                                                        SavedSelection saved,
                                                        boolean resetToFirst) throws Exception
    {
        if (fComputedProposalsField != null)
            fComputedProposalsField.set(popup, list);
        setProposalsMethod.invoke(popup, list, filteredSubset);
        List<ICompletionProposal> applied =
            (List<ICompletionProposal>) fFilteredProposalsField.get(popup);
        if (fComputedProposalsField != null && applied != null)
            fComputedProposalsField.set(popup, applied);
        applySelection(popup, applied != null ? applied : list, saved, resetToFirst);
    }

    private static void applySelection(Object popup, List<ICompletionProposal> list,
                                       SavedSelection saved, boolean resetToFirst) throws Exception
    {
        if (list == null || list.isEmpty() || selectProposalMethod == null)
            return;
        if (resetToFirst)
            selectFirstProposal(popup, list);
        else
            restoreSelection(popup, saved, list);
    }

    private static void restoreSelection(Object popup, SavedSelection saved,
                                         List<ICompletionProposal> list) throws Exception
    {
        if (list == null || list.isEmpty() || selectProposalMethod == null)
            return;

        int index = findProposalIndex(list, saved);
        if (index < 0)
            index = Math.min(saved.index, list.size() - 1);
        if (index < 0)
            index = 0;

        Table table = getProposalTable(popup);
        if (table != null && !table.isDisposed() && table.getItemCount() > 0)
        {
            if (index >= table.getItemCount())
                index = table.getItemCount() - 1;
            table.setSelection(index);
            table.showSelection();
        }
        selectProposalMethod.invoke(popup, index, Boolean.FALSE);
    }

    private static int findProposalIndex(List<ICompletionProposal> list, SavedSelection saved)
    {
        if (saved == null || saved.displayKey == null || saved.displayKey.isEmpty())
            return -1;
        for (int i = 0; i < list.size(); i++)
        {
            if (saved.displayKey.equals(proposalDisplayKey(list, i)))
                return i;
        }
        return -1;
    }

    private static String proposalDisplayKey(List<ICompletionProposal> list, int index)
    {
        if (list == null || index < 0 || index >= list.size())
            return null;
        ICompletionProposal p = list.get(index);
        if (p == null)
            return null;
        return p.getDisplayString();
    }

    /** После пересортировки smart-фильтром — всегда первая строка. */
    private static void selectFirstProposal(Object popup, List<ICompletionProposal> list)
            throws Exception
    {
        if (list == null || list.isEmpty() || selectProposalMethod == null)
            return;

        Table table = getProposalTable(popup);
        if (table != null && !table.isDisposed() && table.getItemCount() > 0)
        {
            table.setSelection(0);
            table.showSelection();
        }
        selectProposalMethod.invoke(popup, 0, Boolean.FALSE);
    }
}
