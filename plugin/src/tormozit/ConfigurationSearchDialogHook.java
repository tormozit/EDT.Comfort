package tormozit;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;

public class ConfigurationSearchDialogHook implements IStartup
{
    private static final String SETTINGS_SECTION = "TormozitConfigurationSearchSettings";
    private static final String KEY_WHOLE_WORD = "wholeWord";
    private static final String SEARCH_DIALOG_CLASS = "org.eclipse.search.internal.ui.SearchDialog";
    private static final String PAGE_CLASS = "com._1c.g5.v8.dt.internal.search.ui.dialog.ConfigurationSearchDialogPage";
    private static final String HOOKED_KEY = "tormozit.configSearchHooked";
    private static final String LISTENER_KEY = "tormozit.configSearchListener";

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> {
            Display.getDefault().addFilter(SWT.Show, event ->
            {
                if (!(event.widget instanceof Shell))
                    return;
                Shell shell = (Shell) event.widget;
                Object dialog = findSearchDialog(shell);
                if (dialog == null)
                    return;

                if (shell.getData(LISTENER_KEY) == null)
                {
                    shell.setData(LISTENER_KEY, Boolean.TRUE);
                    addPageChangeListener(dialog, shell);
                }

                schedulePatch(shell, dialog, 0);
            });
        });
    }

    private static Object findSearchDialog(Shell shell)
    {
        Object dialog = shell.getData();
        if (dialog != null && SEARCH_DIALOG_CLASS.equals(dialog.getClass().getName()))
            return dialog;
        dialog = shell.getData("org.eclipse.jface.window.Window");
        if (dialog != null && SEARCH_DIALOG_CLASS.equals(dialog.getClass().getName()))
            return dialog;
        return null;
    }

    private static void addPageChangeListener(Object dialog, Shell shell)
    {
        try
        {
            Class<?> listenerClass = Class.forName(
                "org.eclipse.jface.dialogs.IPageChangedListener");
            Object listener = Proxy.newProxyInstance(
                ConfigurationSearchDialogHook.class.getClassLoader(),
                new Class[] { listenerClass },
                (proxy, method, args) -> {
                    schedulePatch(shell, dialog, 0);
                    return null;
                });
            dialog.getClass().getMethod("addPageChangedListener", listenerClass)
                .invoke(dialog, listener);
        }
        catch (Exception e)
        {
            log("addPageChangeListener error: " + e);
        }
    }

    private static void schedulePatch(Shell shell, Object dialog, int attempt)
    {
        if (shell == null || shell.isDisposed())
            return;
        Display.getDefault().timerExec(attempt == 0 ? 0 : 200, () ->
        {
            if (shell.isDisposed())
                return;

            Object page = getSelectedPage(dialog);
            if (page == null)
            {
                if (attempt < 100)
                    schedulePatch(shell, dialog, attempt + 1);
                return;
            }

            if (!PAGE_CLASS.equals(page.getClass().getName()))
            {
                if (attempt < 100)
                    schedulePatch(shell, dialog, attempt + 1);
                return;
            }

            if (Global.getField(page, "searchExecutorProvider") == null)
            {
                if (attempt < 100)
                    schedulePatch(shell, dialog, attempt + 1);
                return;
            }

            patchPage(shell, dialog, page);
        });
    }

    private static Object getSelectedPage(Object dialog)
    {
        try
        {
            return dialog.getClass().getMethod("getSelectedPage").invoke(dialog);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static void patchPage(Shell shell, Object dialog, Object page)
    {
        if (shell.getData(HOOKED_KEY) != null)
            return;
        shell.setData(HOOKED_KEY, Boolean.TRUE);

        Button btnCase = findCaseSensitiveButton(shell);
        Composite parent = btnCase != null ? btnCase.getParent()
            : (Composite) Global.invoke(page, "getControl");

        if (parent == null)
        {
            log("cannot determine parent composite, aborting");
            return;
        }

        IDialogSettings settings = getDialogSettings();

        if (btnCase != null)
        {
            // Create vertical group to hold both checkboxes
            Composite vGroup = new Composite(parent, SWT.NONE);
            GridLayout vLayout = new GridLayout(1, false);
            vLayout.marginWidth = 0;
            vLayout.marginHeight = 0;
            vLayout.verticalSpacing = 0;
            vGroup.setLayout(vLayout);

            // Copy case button's GridData to the vertical group
            GridData caseGd = (GridData) btnCase.getLayoutData();
            GridData vGd;
            if (caseGd != null)
            {
                vGd = new GridData(caseGd.horizontalAlignment, caseGd.verticalAlignment,
                    caseGd.grabExcessHorizontalSpace, caseGd.grabExcessVerticalSpace);
                vGd.horizontalIndent = caseGd.horizontalIndent;
                vGd.horizontalSpan = caseGd.horizontalSpan;
                vGd.verticalSpan = caseGd.verticalSpan;
                vGd.widthHint = caseGd.widthHint;
                vGd.heightHint = caseGd.heightHint;
                vGd.exclude = caseGd.exclude;
            }
            else
            {
                vGd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
            }
            vGroup.setLayoutData(vGd);

            // Position vGroup right after case button in the parent grid
            vGroup.moveBelow(btnCase);

            // Reparent case button into the vertical group
            btnCase.setParent(vGroup);
            btnCase.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));

            // Create whole word checkbox in the vertical group
            Button cbWholeWord = new Button(vGroup, SWT.CHECK);
            cbWholeWord.setText("Слово целиком");
            cbWholeWord.setToolTipText("Искать только целые слова, а не подстроки"
                + Global.pluginSignForTooltip());
            cbWholeWord.setSelection(settings.getBoolean(KEY_WHOLE_WORD));
            cbWholeWord.addListener(SWT.Selection,
                e -> settings.put(KEY_WHOLE_WORD, cbWholeWord.getSelection()));
            cbWholeWord.setLayoutData(new GridData(SWT.BEGINNING, SWT.CENTER, false, false));
        }
        else
        {
            Button cbWholeWord = new Button(parent, SWT.CHECK);
            cbWholeWord.setText("Слово целиком");
            cbWholeWord.setToolTipText("Искать только целые слова, а не подстроки"
                + Global.pluginSignForTooltip());
            cbWholeWord.setSelection(settings.getBoolean(KEY_WHOLE_WORD));
            cbWholeWord.setLayoutData(
                new GridData(GridData.BEGINNING, GridData.CENTER, false, false));
            cbWholeWord.addListener(SWT.Selection,
                e -> settings.put(KEY_WHOLE_WORD, cbWholeWord.getSelection()));
        }
        parent.layout(true, true);
        shell.pack();

        patchExecutor(page);
    }

    private static Button findCaseSensitiveButton(Shell shell)
    {
        return Global.findControl(shell, Button.class, btn ->
        {
            if ((btn.getStyle() & SWT.CHECK) == 0)
                return false;
            String text = btn.getText();
            return text != null
                && (text.contains("регистр") || text.toLowerCase().contains("case"));
        });
    }

    private static void patchExecutor(Object page)
    {
        Object origExecProvider = Global.getField(page, "searchExecutorProvider");
        if (origExecProvider == null)
        {
            log("searchExecutorProvider is null");
            return;
        }
        try
        {
            Object proxyProvider = createExecutorProviderProxy(origExecProvider);
            Global.setFieldForce(page, "searchExecutorProvider", proxyProvider);
            log("executor provider patched");
        }
        catch (Exception e)
        {
            log("patchExecutor error: " + e);
        }
    }

    private static Object createExecutorProviderProxy(Object origExecProvider) throws Exception
    {
        ClassLoader cl = ConfigurationSearchDialogHook.class.getClassLoader();

        Class<?> providerInterface = null;
        for (String cn : new String[] {
            "com.google.inject.Provider",
            "javax.inject.Provider",
            "jakarta.inject.Provider"
        }) {
            try { providerInterface = Class.forName(cn); break; } catch (Exception ignored) {}
        }
        if (providerInterface == null)
        {
            log("cannot find Provider interface");
            return origExecProvider;
        }

        return Proxy.newProxyInstance(cl, new Class[] { providerInterface },
            (proxy, method, args) ->
            {
                if ("get".equals(method.getName()))
                {
                    Object executor = method.invoke(origExecProvider, args);
                    if (executor == null)
                        return null;
                    return wrapExecutor(executor);
                }
                return method.invoke(origExecProvider, args);
            });
    }

    private static Object wrapExecutor(Object executor) throws Exception
    {
        ClassLoader cl = executor.getClass().getClassLoader();
        final ClassLoader executorCL = cl != null ? cl
            : ConfigurationSearchDialogHook.class.getClassLoader();
        Class<?>[] interfaces = executor.getClass().getInterfaces();
        if (interfaces == null || interfaces.length == 0)
            return executor;
        return Proxy.newProxyInstance(executorCL, interfaces,
            (proxy, method, args) ->
            {
                if ("run".equals(method.getName()) && args != null && args.length == 3
                    && getDialogSettings().getBoolean(KEY_WHOLE_WORD))
                {
                    Object input = args[0];
                    String sq = (String) Global.invoke(input, "getSearchString");
                    if (sq != null && !sq.contains("?") && !sq.contains("*"))
                    {
                        boolean caseSensitive = (Boolean) Global.invoke(input, "isCaseSensitive");
                        Object filteredCollector = createFilteredCollector(
                            args[1], sq, caseSensitive, executorCL);
                        args[1] = filteredCollector;
                    }
                }
                return method.invoke(executor, args);
            });
    }

    private static Object createFilteredCollector(Object origCollector, String searchString,
        boolean caseSensitive, ClassLoader cl) throws Exception
    {
        Class<?> iface = Class.forName(
            "com._1c.g5.v8.dt.search.core.ISearchResultCollector");
        ClassLoader ifaceCL = iface.getClassLoader();
        if (ifaceCL == null)
            ifaceCL = cl;
        return Proxy.newProxyInstance(ifaceCL, new Class[] { iface },
            (proxy, method, args) ->
            {
                if ("addMatch".equals(method.getName()) && args != null && args.length == 1)
                {
                    if (isWholeWordMatch(args[0], searchString, caseSensitive))
                        return method.invoke(origCollector, args);
                    return null;
                }
                if ("addMatches".equals(method.getName()) && args != null && args.length == 1)
                {
                    Collection<?> matches = (Collection<?>) args[0];
                    List<Object> filtered = new ArrayList<>();
                    for (Object m : matches)
                        if (isWholeWordMatch(m, searchString, caseSensitive))
                            filtered.add(m);
                    if (filtered.size() == matches.size())
                        return method.invoke(origCollector, args);
                    return method.invoke(origCollector, new Object[] { filtered });
                }
                return method.invoke(origCollector, args);
            });
    }

    private static boolean isWholeWordMatch(Object match, String searchWord,
        boolean caseSensitive) throws Exception
    {
        if (match == null || searchWord == null)
            return true;
        String cn = match.getClass().getName();
        if (!cn.startsWith("com._1c.g5.v8.dt.search.core.text.TextSearch"))
            return true;

        String fullText = (String) Global.invoke(match, "getText");
        if (fullText == null)
            return true;

        int offset = (Integer) Global.invoke(match, "getTextOffset");
        int length = (Integer) Global.invoke(match, "getTextLength");

        if (offset < 0 || length <= 0 || offset + length > fullText.length())
            return true;

        String matched = fullText.substring(offset, offset + length);
        if (!caseSensitive)
        {
            matched = matched.toLowerCase(java.util.Locale.ROOT);
            searchWord = searchWord.toLowerCase(java.util.Locale.ROOT);
        }
        if (!matched.equals(searchWord))
            return false;

        if (offset > 0 && isWordChar(fullText.charAt(offset - 1)))
            return false;
        int end = offset + length;
        if (end < fullText.length() && isWordChar(fullText.charAt(end)))
            return false;
        return true;
    }

    private static boolean isWordChar(char c)
    {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static IDialogSettings getDialogSettings()
    {
        IDialogSettings top = Activator.getDefault().getDialogSettings();
        IDialogSettings section = top.getSection(SETTINGS_SECTION);
        if (section == null)
            section = top.addNewSection(SETTINGS_SECTION);
        return section;
    }

    private static void log(String msg)
    {
        if (Global.isLogEnabled())
            Global.log("ConfigSearchHook", msg);
        Activator.getDefault().getLog().log(
            new Status(Status.INFO, "tormozit.comfort", "ConfigurationSearchHook: " + msg));
    }
}
