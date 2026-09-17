package tormozit;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.regex.Pattern;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IStartup;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.metadata.mdclass.CommonPicture;
import com._1c.g5.v8.dt.platform.pictures.IPictureManager;
import com._1c.g5.v8.dt.platform.pictures.IPictureManifest;
import com._1c.g5.v8.dt.platform.pictures.IPictureManifestQueryComputer;
import com._1c.g5.v8.dt.platform.pictures.zip.IZipPictureContent;
import com._1c.g5.v8.dt.platform.pictures.zip.IZipPictureManifest;
import com._1c.g5.v8.dt.platform.pictures.zip.ZipPictureContentStore;

public class PictureDialogHook implements IStartup {

    private static final String PATCHED_KEY = "tormozit.pictureDialogPatched"; //$NON-NLS-1$
    private static final String CMDS_KEY = "tormozit.pictureDialogCmds"; //$NON-NLS-1$
    private static final int EDIT_BUTTON_ID = 1031;
    private static final int NAV_BUTTON_ID = 1032;
    private static final int IR_BUTTON_ID = 1033;
    private static final Pattern IR_PICTURE_TITLE = Pattern.compile("(?i)картин"); //$NON-NLS-1$
    private static final long IR_DIALOG_WAIT_MS = 8_000;

    @Override
    public void earlyStartup() {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display) {
        if (display == null || display.isDisposed())
            return;

        PictureFieldEnhance.ensureGlobalFilters(display);

        Listener listener = new Listener() {
            @Override
            public void handleEvent(Event event) {
                if (!(event.widget instanceof Shell shell))
                    return;
                if (shell.isDisposed())
                    return;
                Object dialog = shell.getData();
                if (dialog == null)
                    return;
                String dialogName = dialog.getClass().getName();
                if (!dialogName.contains("PictureDialog")) //$NON-NLS-1$
                    return;
                if (shell.getData(PATCHED_KEY) != null && shell.getData(CMDS_KEY) != null)
                    return;

                shell.getDisplay().asyncExec(() -> {
                    if (!shell.isDisposed())
                        tryPatchDialog(shell, dialog);
                });
            }
        };

        display.addFilter(SWT.Activate, listener);
        display.addFilter(SWT.Show, listener);
    }

    private static boolean tryPatchDialog(Shell shell, Object dialog) {
        try {
            if (dialog == null)
                return false;

            TableViewer commonViewer = (TableViewer) Global.getField(dialog, "commonPictureViewer"); //$NON-NLS-1$
            if (commonViewer == null || commonViewer.getControl().isDisposed()) {
                shell.getDisplay().asyncExec(() -> {
                    if (!shell.isDisposed()
                        && (shell.getData(PATCHED_KEY) == null || shell.getData(CMDS_KEY) == null))
                        tryPatchDialog(shell, dialog);
                });
                return false;
            }

            if (shell.getData(CMDS_KEY) == null) {
                installCommands(shell, dialog, commonViewer);
                installMinWidth(shell, dialog);
                installPreviewEnhance(dialog);
                shell.setData(CMDS_KEY, Boolean.TRUE);
            }

            if (shell.getData(PATCHED_KEY) == null
                && ComfortSettings.isReplaceListFiltersEnabled())
            {
                if (!installSmartFilter(shell, dialog, commonViewer))
                    return false;
            }

            return true;

        } catch (Exception e) {
            shell.setData(PATCHED_KEY, null);
            Global.logError("PictureDialog", "patch", e); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
    }

    private static boolean installSmartFilter(Shell shell, Object dialog,
        TableViewer commonViewer)
    {
        Text search = (Text) Global.getField(dialog, "search"); //$NON-NLS-1$
        if (search == null || search.isDisposed())
            return false;

        synchronized (shell) {
            if (shell.getData(PATCHED_KEY) != null)
                return true;
            shell.setData(PATCHED_KEY, Boolean.TRUE);
        }

        TableViewer standartViewer = (TableViewer) Global.getField(dialog, "standartPictureViewer"); //$NON-NLS-1$

        Object oldFilter = Global.getField(dialog, "filter"); //$NON-NLS-1$
        Object oldFilterStandart = Global.getField(dialog, "filterStandart"); //$NON-NLS-1$

        SmartMatcherPictureFilter customFilter = new SmartMatcherPictureFilter();
        SmartMatcherPictureFilter customFilterStandart = new SmartMatcherPictureFilter();

        if (commonViewer != null && !commonViewer.getControl().isDisposed()) {
            if (oldFilter instanceof ViewerFilter vf)
                commonViewer.removeFilter(vf);
            commonViewer.addFilter(customFilter);
        }

        if (standartViewer != null && !standartViewer.getControl().isDisposed()) {
            if (oldFilterStandart instanceof ViewerFilter vf)
                standartViewer.removeFilter(vf);
            standartViewer.addFilter(customFilterStandart);
        }

        for (Listener l : search.getListeners(SWT.Modify))
            search.removeListener(SWT.Modify, l);

        FilterInputBox filterInput = FilterInputBox.replacePatternText(
                search, FilterInputBox.Scope.PICTURE_DIALOG, null);
        if (filterInput == null) {
            shell.setData(PATCHED_KEY, null);
            return false;
        }

        Control fc = filterInput.inputControl();
        if (fc == null)
            fc = filterInput.widget();
        final Control filterControl = fc;

        final Runnable[] pendingFilterTask = new Runnable[1];

        addFilterModifyListener(filterControl, new ModifyListener() {
            @Override
            public void modifyText(ModifyEvent e) {
                String pattern = getFilterPattern(filterControl);
                Display display = filterControl.getDisplay();

                if (pendingFilterTask[0] != null)
                    display.timerExec(-1, pendingFilterTask[0]);

                pendingFilterTask[0] = () -> applyFilter(
                        dialog, customFilter, customFilterStandart,
                        commonViewer, standartViewer, pattern);

                display.timerExec(150, pendingFilterTask[0]);
            }
        });

        filterControl.addDisposeListener(e -> {
            if (pendingFilterTask[0] != null && !filterControl.getDisplay().isDisposed())
                filterControl.getDisplay().timerExec(-1, pendingFilterTask[0]);
        });

        addHighlightToTable(commonViewer, customFilter);
        addHighlightToTable(standartViewer, customFilterStandart);

        installFilterNavigation(filterControl, dialog,
                commonViewer, standartViewer);

        hookTabSwitching(dialog, customFilter, customFilterStandart,
                commonViewer, standartViewer, filterControl);

        filterInput.scheduleFocusWhenReady();

        String initialText = filterInput.getText();
        if (initialText != null && !initialText.isEmpty())
            applyFilter(dialog, customFilter, customFilterStandart,
                    commonViewer, standartViewer, initialText);

        return true;
    }

    private static void installCommands(Shell shell, Object dialog, TableViewer commonViewer)
    {
        addOperationButtons(dialog, shell, commonViewer);
        updateCommandEnablement(dialog, commonViewer);
        commonViewer.addSelectionChangedListener(e ->
        {
            updateCommandEnablement(dialog, commonViewer);
            notifyPreviewImageChanged(dialog);
        });
        TableViewer standartViewer = (TableViewer) Global.getField(dialog, "standartPictureViewer"); //$NON-NLS-1$
        if (standartViewer != null && !standartViewer.getControl().isDisposed())
        {
            standartViewer.addSelectionChangedListener(e -> notifyPreviewImageChanged(dialog));
        }
        if (Global.getField(dialog, "fileListViewer") instanceof TableViewer fileViewer //$NON-NLS-1$
            && !fileViewer.getControl().isDisposed())
        {
            fileViewer.addSelectionChangedListener(e -> notifyPreviewImageChanged(dialog));
        }
        CTabFolder tabFolder = findTabFolder(dialog);
        if (tabFolder != null && !tabFolder.isDisposed())
        {
            tabFolder.addSelectionListener(new SelectionAdapter()
            {
                @Override
                public void widgetSelected(SelectionEvent e)
                {
                    updateCommandEnablement(dialog, commonViewer);
                }
            });
        }
    }

    private static void addOperationButtons(Object dialog, Shell shell, TableViewer commonViewer)
    {
        Button addButton = (Button) Global.invoke(dialog, "getButton", 1030); //$NON-NLS-1$
        if (addButton == null || addButton.isDisposed())
            return;
        Composite bar = addButton.getParent();
        if (bar == null || bar.isDisposed())
            return;

        // Штатно — 1 колонка (только «Добавить»); для тулбара в ряд — 4 колонки.
        if (bar.getLayout() instanceof org.eclipse.swt.layout.GridLayout gl)
        {
            gl.numColumns = 4;
            gl.makeColumnsEqualWidth = false;
            gl.horizontalSpacing = 4;
        }

        // Штатный «Добавить» тянется на всю колонку — сузить по тексту.
        shrinkButtonToText(addButton);

        Button edit = createButton(bar, EDIT_BUTTON_ID, "Изменить"); //$NON-NLS-1$
        Button nav = createButton(bar, NAV_BUTTON_ID, "Показать в навигаторе"); //$NON-NLS-1$
        Button ir = createButton(bar, IR_BUTTON_ID, "Выбрать ИР"); //$NON-NLS-1$
        shrinkButtonToText(edit);
        shrinkButtonToText(nav);
        shrinkButtonToText(ir);

        edit.setToolTipText(TooltipText.wrap(edit,
            "Открыть редактор общей картинки" + Global.pluginSignForTooltip())); //$NON-NLS-1$
        nav.setToolTipText(TooltipText.wrap(nav,
            "Показать в навигаторе" + Global.pluginSignForTooltip())); //$NON-NLS-1$
        ir.setToolTipText(TooltipText.wrap(ir,
            "Выбрать картинку через ИР" + Global.pluginSignForTooltip())); //$NON-NLS-1$

        edit.addListener(SWT.Selection, e -> openEdit(shell, dialog, commonViewer));
        nav.addListener(SWT.Selection, e -> showInNavigator(commonViewer));
        ir.addListener(SWT.Selection, e -> selectInIr(shell, dialog, commonViewer));

        bar.setData("tormozit.pictureEditBtn", edit); //$NON-NLS-1$
        bar.setData("tormozit.pictureNavBtn", nav); //$NON-NLS-1$
        bar.setData("tormozit.pictureIrBtn", ir); //$NON-NLS-1$
        bar.layout(true, true);
        if (bar.getParent() != null)
            bar.getParent().layout(true, true);
    }

    private static void shrinkButtonToText(Button button)
    {
        if (button == null || button.isDisposed())
            return;
        GridData gd;
        Object ld = button.getLayoutData();
        if (ld instanceof GridData existing)
            gd = existing;
        else
        {
            gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
            button.setLayoutData(gd);
        }
        gd.grabExcessHorizontalSpace = false;
        gd.horizontalAlignment = SWT.BEGINNING;
        Point size = button.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        gd.widthHint = size.x;
    }

    private static Button createButton(Composite bar, int id, String text)
    {
        Button button = new Button(bar, SWT.PUSH);
        button.setText(text);
        button.setData(Integer.valueOf(id));
        return button;
    }

    private static void updateCommandEnablement(Object dialog, TableViewer commonViewer)
    {
        boolean onConfig = isConfigTab(dialog);
        CommonPicture selected = selectedCommonPicture(commonViewer);
        boolean has = onConfig && selected != null;

        Button addButton = (Button) Global.invoke(dialog, "getButton", 1030); //$NON-NLS-1$
        if (addButton != null && !addButton.isDisposed())
        {
            Composite bar = addButton.getParent();
            enableButton(bar, "tormozit.pictureEditBtn", has); //$NON-NLS-1$
            enableButton(bar, "tormozit.pictureNavBtn", has); //$NON-NLS-1$
            enableButton(bar, "tormozit.pictureIrBtn", onConfig); //$NON-NLS-1$
        }
    }

    private static void enableButton(Composite bar, String key, boolean enabled)
    {
        if (bar == null)
            return;
        Object raw = bar.getData(key);
        if (raw instanceof Button b && !b.isDisposed())
            b.setEnabled(enabled);
    }

    private static boolean isConfigTab(Object dialog)
    {
        Object tabItem = Global.getField(dialog, "tabItemFromConfig"); //$NON-NLS-1$
        if (!(tabItem instanceof CTabItem item) || item.isDisposed())
            return false;
        CTabFolder folder = item.getParent();
        if (folder == null || folder.isDisposed())
            return false;
        return folder.getSelection() == item;
    }

    private static CommonPicture selectedCommonPicture(TableViewer commonViewer)
    {
        if (commonViewer == null)
            return null;
        IStructuredSelection sel = commonViewer.getStructuredSelection();
        if (sel == null || sel.isEmpty())
            return null;
        Object first = sel.getFirstElement();
        return first instanceof CommonPicture cp ? cp : null;
    }

    private static void openEdit(Shell shell, Object dialog, TableViewer commonViewer)
    {
        CommonPicture picture = selectedCommonPicture(commonViewer);
        if (picture == null)
            return;

        // Пока открыт модальный «Выбор картинки», workbench-редактор не активируется.
        Shell parent = shell.getParent() instanceof Shell p ? p : null;
        // Как кнопка «Отмена»: close() не трогает returnCode, а у Window он изначально OK —
        // open() вернул бы OK, и редактор свойства записал бы выбранную картинку в объект.
        try
        {
            Global.invoke(dialog, "buttonPressed", IDialogConstants.CANCEL_ID); //$NON-NLS-1$
        }
        catch (RuntimeException ex)
        {
            Global.logError("PictureDialog", "close", ex); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!shell.isDisposed())
        {
            Global.invoke(dialog, "setReturnCode", IDialogConstants.CANCEL_ID); //$NON-NLS-1$
            shell.close();
        }

        Display display = Display.getDefault();
        display.asyncExec(() ->
        {
            org.eclipse.ui.IWorkbenchWindow window =
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            org.eclipse.ui.IEditorPart active = window != null && window.getActivePage() != null
                ? window.getActivePage().getActiveEditor() : null;
            Shell host = parent != null && !parent.isDisposed()
                ? parent
                : display.getActiveShell();
            CompareConfigOpenObjectHandler.openInEditor(picture, active, host);
        });
    }

    private static void showInNavigator(TableViewer commonViewer)
    {
        CommonPicture picture = selectedCommonPicture(commonViewer);
        if (picture instanceof EObject eObject)
            NavigatorReveal.revealAndActivateIfHidden(eObject);
    }

    private static void selectInIr(Shell shell, Object dialog, TableViewer commonViewer)
    {
        CommonPicture selected = selectedCommonPicture(commonViewer);
        String name = selected != null && selected.getName() != null ? selected.getName() : ""; //$NON-NLS-1$

        IV8Project v8Project = (IV8Project) Global.getField(dialog, "v8project"); //$NON-NLS-1$
        IDtProject dtProject = null;
        if (v8Project != null)
        {
            try
            {
                dtProject = v8Project.getDtProject();
            }
            catch (Exception ex)
            {
                Global.logError("PictureDialog", "dtProject", ex); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        if (dtProject == null)
        {
            org.eclipse.core.resources.IProject wp =
                Global.getActiveProject((org.eclipse.ui.IWorkbenchPage) null, false);
            dtProject = Global.getDtProjectFromWorkspaceProject(wp);
        }
        if (dtProject == null)
        {
            ToastNotification.show("Выбрать ИР", //$NON-NLS-1$
                "Не удалось определить проект EDT"); //$NON-NLS-1$
            return;
        }

        IRSession irSession = IRApplication.getSession(dtProject, true);
        if (irSession == null || irSession.executor == null)
            return;

        final String pictureName = name;
        irSession.executor.submit(() ->
        {
            try
            {
                String result = irSession.runIrModalDialog(IR_PICTURE_TITLE, IR_DIALOG_WAIT_MS, () ->
                {
                    Object irClient = irSession.getModule("ирКлиент"); //$NON-NLS-1$
                    irSession.showWindow();
                    Object raw = ComBridge.invoke(irClient, "ОткрытьВыборКартинкиЛкс", //$NON-NLS-1$
                        ComBridge.undefinedParam(), pictureName);
                    if (IRApplication.isCancelled(raw))
                        return null;
                    return ComBridge.toString(raw);
                });
                if (result == null || result.isBlank())
                    return;
                String chosen = result.trim();
                Display.getDefault().asyncExec(() -> activateInList(commonViewer, chosen));
            }
            catch (Exception ex)
            {
                Global.logError("PictureDialog", "selectInIr", ex); //$NON-NLS-1$ //$NON-NLS-2$
                Display.getDefault().asyncExec(() ->
                    ToastNotification.show("Выбрать ИР", //$NON-NLS-1$
                        "Ошибка вызова ИР: " + ex.getMessage())); //$NON-NLS-1$
            }
        });
    }

    private static void activateInList(TableViewer commonViewer, String name)
    {
        if (commonViewer == null || commonViewer.getControl().isDisposed() || name == null)
            return;
        // Второй проход — после refresh: картинку могли только что добавить.
        if (selectByName(commonViewer, name))
            return;
        commonViewer.refresh();
        if (selectByName(commonViewer, name))
            return;
        ToastNotification.show("Выбрать ИР", //$NON-NLS-1$
            "Картинка «" + name + "» не найдена в списке конфигурации"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean selectByName(TableViewer commonViewer, String name)
    {
        Table table = commonViewer.getTable();
        for (int i = 0; i < table.getItemCount(); i++)
        {
            if (commonViewer.getElementAt(i) instanceof CommonPicture cp && nameEquals(cp, name))
            {
                commonViewer.setSelection(new StructuredSelection(cp), true);
                table.setFocus();
                return true;
            }
        }
        return false;
    }

    private static boolean nameEquals(CommonPicture cp, String name)
    {
        if (cp == null || name == null)
            return false;
        if (name.equalsIgnoreCase(cp.getName()))
            return true;
        try
        {
            Object ru = Global.invoke(cp, "getNameRu"); //$NON-NLS-1$
            if (ru instanceof String s && name.equalsIgnoreCase(s))
                return true;
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    /**
     * Пол ширины: две равные колонки (список|превью), левая не уже ряда кнопок
     * «Добавить…Выбрать ИР» — иначе середина обрезает фильтр и список.
     */
    private static void installMinWidth(Shell shell, Object dialog)
    {
        shell.getDisplay().asyncExec(() ->
        {
            if (shell.isDisposed())
                return;
            try
            {
                Button addButton = (Button) Global.invoke(dialog, "getButton", 1030); //$NON-NLS-1$
                if (addButton == null || addButton.isDisposed())
                    return;
                Composite bar = addButton.getParent();
                if (bar == null || bar.isDisposed())
                    return;
                bar.layout(true, true);
                Point barSize = bar.computeSize(SWT.DEFAULT, SWT.DEFAULT);
                Point shellSize = shell.getSize();
                Rectangle client = shell.getClientArea();
                int frameX = Math.max(0, shellSize.x - client.width);
                // equalWidth(true) на 2 колонки → контент ≥ 2 × ряд кнопок
                int minW = 2 * barSize.x + frameX + 32;
                int minH = Math.max(360, shell.getMinimumSize().y);
                shell.setMinimumSize(minW, minH);
            }
            catch (Exception ex)
            {
                Global.logError("PictureDialog", "minWidth", ex); //$NON-NLS-1$ //$NON-NLS-2$
            }
        });
    }

    private static void installPreviewEnhance(Object dialog)
    {
        Label fromLibrary = (Label) Global.getField(dialog, "pictureLabelFromLibrary"); //$NON-NLS-1$
        Label sizeLibrary = (Label) Global.getField(dialog, "pictureSizeLableFromLibrary"); //$NON-NLS-1$
        Label fromFile = (Label) Global.getField(dialog, "pictureLabelFromFile"); //$NON-NLS-1$
        Label sizeFile = (Label) Global.getField(dialog, "pictureSizeLableFromFile"); //$NON-NLS-1$

        java.util.function.Supplier<Image> source = () ->
            (Image) Global.getField(dialog, "currentImage"); //$NON-NLS-1$

        if (fromLibrary != null && !fromLibrary.isDisposed())
            PictureFieldEnhance.installSwtPreview(fromLibrary, sizeLibrary, source,
                () -> libraryBytes(dialog));
        if (fromFile != null && !fromFile.isDisposed())
            PictureFieldEnhance.installSwtPreview(fromFile, sizeFile, source,
                () -> fileBytes(dialog));
    }

    /**
     * Исходные байты картинки вкладки «Из библиотеки» — так же, как их берёт сам диалог
     * ({@code IPictureManager.getPictureManifest(picture).getInputStream(query)}).
     */
    private static PictureFieldEnhance.SourceBytes libraryBytes(Object dialog)
    {
        String viewerField = isConfigTab(dialog) ? "commonPictureViewer" : "standartPictureViewer"; //$NON-NLS-1$ //$NON-NLS-2$
        Object viewerObj = Global.getField(dialog, viewerField);
        Object first = viewerObj instanceof TableViewer v ? v.getStructuredSelection().getFirstElement() : null;
        Object managerObj = Global.getField(dialog, "pictureManager"); //$NON-NLS-1$
        Object queryObj = Global.getField(dialog, "pictureManifestQueryComputer"); //$NON-NLS-1$
        if (!(first instanceof Picture picture))
            return null;
        if (!(managerObj instanceof IPictureManager manager)
            || !(queryObj instanceof IPictureManifestQueryComputer queryComputer))
            return null;
        try
        {
            IPictureManifest manifest = manager.getPictureManifest(picture);
            if (manifest == null)
                return null;
            Optional<ByteArrayInputStream> in = manifest.getInputStream(queryComputer.compute());
            byte[] bytes = in.map(ByteArrayInputStream::readAllBytes).orElse(null);
            if (bytes == null)
                return null;
            int variants = 0;
            if (manifest instanceof IZipPictureManifest zipManifest)
            {
                try
                {
                    variants = PictureFieldEnhance.countSetVariants(zipManifest.getZipPictureContent());
                }
                catch (IOException | RuntimeException e)
                {
                    Global.logError("PictureDialog", "librarySet", e); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            return new PictureFieldEnhance.SourceBytes(bytes, variants);
        }
        catch (IOException | RuntimeException e)
        {
            Global.logError("PictureDialog", "libraryBytes", e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * Исходные байты картинки вкладки «Из файла». Архив набора картинок ({@code .zip}) —
     * байты показанного варианта из архива, как его выбирает сам диалог.
     */
    private static PictureFieldEnhance.SourceBytes fileBytes(Object dialog)
    {
        Object viewerObj = Global.getField(dialog, "fileListViewer"); //$NON-NLS-1$
        Object first = viewerObj instanceof TableViewer v ? v.getStructuredSelection().getFirstElement() : null;
        if (!(first instanceof String path))
            return null;
        try (InputStream file = new FileInputStream(path))
        {
            if (!path.trim().endsWith(".zip")) //$NON-NLS-1$
                return PictureFieldEnhance.SourceBytes.single(file.readAllBytes());
            if (!(Global.getField(dialog, "pictureManifestQueryComputer") //$NON-NLS-1$
                instanceof IPictureManifestQueryComputer queryComputer))
                return null;
            IZipPictureContent zip = ZipPictureContentStore.INSTANCE.read(file);
            byte[] bytes = zip.getInputStreamByQuery(queryComputer.compute())
                .map(ByteArrayInputStream::readAllBytes).orElse(null);
            return bytes != null
                ? new PictureFieldEnhance.SourceBytes(bytes, PictureFieldEnhance.countSetVariants(zip))
                : null;
        }
        catch (IOException | RuntimeException e)
        {
            Global.logError("PictureDialog", "fileBytes", e); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    private static void notifyPreviewImageChanged(Object dialog)
    {
        Display display = Display.getDefault();
        // uploadImage штатно после выделения — подождать и обновить мету/зум.
        for (int delay : new int[] { 50, 200 })
        {
            display.timerExec(delay, () ->
            {
                Label fromLibrary = (Label) Global.getField(dialog, "pictureLabelFromLibrary"); //$NON-NLS-1$
                Label fromFile = (Label) Global.getField(dialog, "pictureLabelFromFile"); //$NON-NLS-1$
                if (fromLibrary != null && !fromLibrary.isDisposed())
                    PictureFieldEnhance.notifySwtImageChanged(fromLibrary);
                if (fromFile != null && !fromFile.isDisposed())
                    PictureFieldEnhance.notifySwtImageChanged(fromFile);
            });
        }
    }

    private static void applyFilter(
            Object dialog,
            SmartMatcherPictureFilter customFilter,
            SmartMatcherPictureFilter customFilterStandart,
            TableViewer commonViewer,
            TableViewer standartViewer,
            String pattern) {

        customFilter.setPattern(pattern);
        customFilterStandart.setPattern(pattern);

        if (commonViewer != null && !commonViewer.getControl().isDisposed())
            commonViewer.refresh();
        if (standartViewer != null && !standartViewer.getControl().isDisposed())
            standartViewer.refresh();

        Table activeTable = tableForActiveTab(dialog, commonViewer, standartViewer);
        FilterInputBoxListNavigation.selectFirstRowIfSelectionLost(activeTable);
    }

    private static void hookTabSwitching(
            Object dialog,
            SmartMatcherPictureFilter customFilter,
            SmartMatcherPictureFilter customFilterStandart,
            TableViewer commonViewer,
            TableViewer standartViewer,
            Control filterControl) {

        CTabFolder tabFolder = findTabFolder(dialog);
        if (tabFolder == null || tabFolder.isDisposed())
            return;

        tabFolder.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                String pattern = getFilterPattern(filterControl);
                if (!pattern.isEmpty())
                    applyFilter(dialog, customFilter, customFilterStandart,
                            commonViewer, standartViewer, pattern);
                selectFirstRowIfEmpty(dialog, commonViewer, standartViewer);
            }
        });
    }

    private static CTabFolder findTabFolder(Object dialog) {
        CTabFolder inner = findInnerTabFolder(dialog);
        if (inner != null)
            return inner;
        return findOuterTabFolder(dialog);
    }

    private static CTabFolder findInnerTabFolder(Object dialog) {
        for (String field : new String[]{"tabItemFromConfig", "tabItemStandart"}) { //$NON-NLS-1$ //$NON-NLS-2$
            Object tabItem = Global.getField(dialog, field);
            if (tabItem instanceof CTabItem item) {
                CTabFolder folder = item.getParent();
                if (folder != null && !folder.isDisposed())
                    return folder;
            }
        }
        return null;
    }

    private static CTabFolder findOuterTabFolder(Object dialog) {
        for (String field : new String[]{"tabItemFromLibrary", "tabItemFromFile"}) { //$NON-NLS-1$ //$NON-NLS-2$
            Object tabItem = Global.getField(dialog, field);
            if (tabItem instanceof CTabItem item) {
                CTabFolder folder = item.getParent();
                if (folder != null && !folder.isDisposed())
                    return folder;
            }
        }
        return null;
    }

    private static String getFilterPattern(Control filterControl) {
        if (filterControl == null || filterControl.isDisposed())
            return ""; //$NON-NLS-1$
        if (filterControl instanceof Text t)
            return t.getText();
        if (filterControl instanceof StyledText st)
            return st.getText();
        return ""; //$NON-NLS-1$
    }

    private static void addFilterModifyListener(Control filterControl, ModifyListener listener) {
        if (filterControl instanceof Text t)
            t.addModifyListener(listener);
        else if (filterControl instanceof StyledText st)
            st.addModifyListener(listener);
    }

    private static void addHighlightToTable(TableViewer viewer,
            SmartMatcherPictureFilter filter) {
        if (viewer == null)
            return;
        Table table = viewer.getTable();
        if (table == null || table.isDisposed())
            return;

        table.addListener(SWT.PaintItem, e -> {
            SmartMatcher m = filter.getMatcher();
            if (m == null || m.isEmpty)
                return;
            TableItem item = (TableItem) e.item;
            SmartMatchHighlight.paintTableCellMatchOverlay(
                    e, table, item, m, false, 0, false);
        });
    }

    private static void installFilterNavigation(Control filterControl, Object dialog,
            TableViewer commonViewer, TableViewer standartViewer) {
        Table table = tableForActiveTab(dialog, commonViewer, standartViewer);
        if (table == null)
            return;

        FilterInputBoxListNavigation.installTableNavigation(
                filterControl, table, null, false, () -> {
                    Global.invoke(dialog, "okPressed"); //$NON-NLS-1$
                    return true;
                });

        CTabFolder tabFolder = findTabFolder(dialog);
        if (tabFolder == null || tabFolder.isDisposed())
            return;

        tabFolder.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateNavigationTarget(filterControl, dialog,
                        commonViewer, standartViewer);
            }
        });
    }

    private static void updateNavigationTarget(Control filterControl, Object dialog,
            TableViewer commonViewer, TableViewer standartViewer) {
        Table newTable = tableForActiveTab(dialog, commonViewer, standartViewer);
        if (newTable == null)
            return;

        var searchBox = FilterInputBox.resolveSearchBox(filterControl);
        if (searchBox == null || searchBox.isDisposed())
            return;

        Object navCtx = searchBox.getData("tormozit.filterNavContext"); //$NON-NLS-1$
        if (navCtx != null)
            Global.setField(navCtx, "table", newTable); //$NON-NLS-1$
    }

    private static Table tableForActiveTab(Object dialog,
            TableViewer commonViewer, TableViewer standartViewer) {
        CTabFolder tabFolder = findTabFolder(dialog);
        if (tabFolder == null || tabFolder.isDisposed())
            return null;
        CTabItem selected = tabFolder.getSelection();
        if (selected == null || selected.isDisposed())
            return null;
        Control tabControl = selected.getControl();
        if (tabControl != null && !tabControl.isDisposed()) {
            Table t = tableInsideControl(tabControl, commonViewer, standartViewer);
            if (t != null)
                return t;
        }
        return null;
    }

    private static Table tableInsideControl(Control parent,
            TableViewer commonViewer, TableViewer standartViewer) {
        if (commonViewer != null) {
            Table t = commonViewer.getTable();
            if (t != null && !t.isDisposed() && isDescendantOf(t, parent))
                return t;
        }
        if (standartViewer != null) {
            Table t = standartViewer.getTable();
            if (t != null && !t.isDisposed() && isDescendantOf(t, parent))
                return t;
        }
        return null;
    }

    private static boolean isDescendantOf(Control child, Control ancestor) {
        for (Control p = child.getParent(); p != null && !p.isDisposed(); p = p.getParent())
            if (p == ancestor)
                return true;
        return false;
    }

    private static void selectFirstRowIfEmpty(Object dialog,
            TableViewer commonViewer, TableViewer standartViewer) {
        CTabFolder tabFolder = findTabFolder(dialog);
        if (tabFolder == null || tabFolder.isDisposed())
            return;
        CTabItem selected = tabFolder.getSelection();
        if (selected == null || selected.isDisposed())
            return;
        Control tabControl = selected.getControl();
        if (tabControl == null || tabControl.isDisposed())
            return;
        Table table = tableInsideControl(tabControl, commonViewer, standartViewer);
        if (table == null || table.isDisposed() || table.getItemCount() == 0)
            return;
        if (table.getSelectionCount() > 0)
            return;
        table.setSelection(0);
    }

}
