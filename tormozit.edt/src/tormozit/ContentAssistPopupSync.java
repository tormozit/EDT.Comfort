package tormozit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.widgets.Table;

/**
 * Синхронизация {@code CompletionProposalPopup} с {@link SmartContentAssistProcessor}.
 * Штатный incremental filter подменяется через {@link #installFilterOverride}.
 */
public final class ContentAssistPopupSync
{
    private static final IdentityHashMap<Object, Runnable> ORIGINAL_FILTER_RUNNABLES =
        new IdentityHashMap<>();

    private static Field popupField;
    private static Field fComputedProposalsField;
    private static Field fFilteredProposalsField;
    private static Field fIsFilteredSubsetField;
    private static Field fIsFilterPendingField;
    private static Field fFilterRunnableField;
    private static Field fProposalTableField;
    private static Field fProposalShellField;
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
            installFilterOverride(assistant, viewer, processor);
            clearFilterPending(popup);

            int caret = viewer.getTextWidget().getCaretOffset();
            ICompletionProposal[] proposals = processor.computeCompletionProposals(viewer, caret);
            if (proposals == null)
                proposals = new ICompletionProposal[0];

            ArrayList<ICompletionProposal> list = new ArrayList<>(Arrays.asList(proposals));
            if (fIsFilteredSubsetField != null)
                fIsFilteredSubsetField.setBoolean(popup, false);

            // Не трогаем fFilteredProposals до setProposals — иначе guard в setProposals
            // (сравнение снимка списка) и getSelectedProposal→fFilterRunnable ломают обновление.
            setProposalsMethod.invoke(popup, list, Boolean.FALSE);

            List<ICompletionProposal> applied = (List<ICompletionProposal>)
                fFilteredProposalsField.get(popup);
            if (fComputedProposalsField != null && applied != null)
                fComputedProposalsField.set(popup, applied);

            int tableRows = tableItemCount(popup);
            ContentAssistDebug.log("popupSync caret=" + caret //$NON-NLS-1$
                + " count=" + proposals.length //$NON-NLS-1$
                + " tableRows=" + tableRows //$NON-NLS-1$
                + ContentAssistDebug.sampleTypes(proposals, 2));

            if (tableRows >= 0 && tableRows != proposals.length)
                forceTableRefresh(popup, applied != null ? applied : list);

            refreshAdditionalInfo(popup);
            return true;
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("popupSync ERROR: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    private static void forceTableRefresh(Object popup, List<ICompletionProposal> list)
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
        selectProposalMethod.invoke(popup, 0, Boolean.FALSE);
        ContentAssistDebug.log("popupSync forceTableRefresh rows=" + list.size()); //$NON-NLS-1$
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
                refreshAdditionalInfo(popup);
        }
        catch (Exception e)
        {
            ContentAssistDebug.log("additionalInfo ERROR: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void refreshAdditionalInfo(Object popup) throws Exception
    {
        initPopupReflection(popup);

        if (fProposalTableField != null)
        {
            Table table = (Table) fProposalTableField.get(popup);
            if (table != null && !table.isDisposed() && table.getItemCount() > 0)
            {
                table.setSelection(0);
                table.showSelection();
            }
        }

        if (selectProposalMethod != null)
            selectProposalMethod.invoke(popup, 0, Boolean.FALSE);

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
    }
}
