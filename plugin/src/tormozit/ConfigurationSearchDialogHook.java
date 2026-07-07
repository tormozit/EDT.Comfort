package tormozit;

import java.lang.reflect.Proxy;

import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
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

        Button cbWholeWord = new Button(parent, SWT.CHECK);
        cbWholeWord.setText("Слово целиком");
        cbWholeWord.setToolTipText("Искать только целые слова, а не подстроки"
            + Global.pluginSignForTooltip());
        cbWholeWord.setSelection(settings.getBoolean(KEY_WHOLE_WORD));
        cbWholeWord.addListener(SWT.Selection,
            e -> settings.put(KEY_WHOLE_WORD, cbWholeWord.getSelection()));

        if (btnCase != null)
        {
            GridData gd = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
            cbWholeWord.setLayoutData(gd);
            cbWholeWord.moveBelow(btnCase);
            parent.layout(true, true);
            gd.horizontalIndent = btnCase.getLocation().x;
            parent.layout(true, true);
        }
        else
        {
            cbWholeWord.setLayoutData(
                new GridData(GridData.BEGINNING, GridData.CENTER, false, false));
            parent.layout(true, true);
        }
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

                    Object origTp = Global.getField(executor, "textSearchIndexProvider");
                    if (origTp != null)
                    {
                        Object proxyTp = createProxyIndexProvider(origTp);
                        Global.setFieldForce(executor, "textSearchIndexProvider", proxyTp);
                    }
                    return executor;
                }
                return method.invoke(origExecProvider, args);
            });
    }

    private static Object createProxyIndexProvider(Object origTp) throws Exception
    {
        ClassLoader cl = ConfigurationSearchDialogHook.class.getClassLoader();
        Class<?> provInterface = Class.forName(
            "com._1c.g5.v8.dt.search.core.text.ITextSearchIndexProvider");
        Class<?> idxInterface = Class.forName(
            "com._1c.g5.v8.dt.search.core.text.ITextSearchIndex");

        return Proxy.newProxyInstance(cl, new Class[] { provInterface },
            (proxy, method, args) ->
            {
                if ("get".equals(method.getName()))
                {
                    Object origIndex = method.invoke(origTp, args);
                    if (origIndex == null)
                        return null;

                    return Proxy.newProxyInstance(cl, new Class[] { idxInterface },
                        (idxProxy, idxMethod, idxArgs) ->
                        {
                            if ("search".equals(idxMethod.getName())
                                && idxArgs.length == 3)
                            {
                                Object query = idxArgs[0];
                                String sq = (String) Global.invoke(query, "getSearchString");
                                if (getDialogSettings().getBoolean(KEY_WHOLE_WORD))
                                {
                                    Object luceneQuery = Global.invoke(query, "getQuery");
                                    log("WW sq=" + sq + " origQuery="
                                        + Global.invoke(luceneQuery, "toString"));
                                    ClassLoader luceneCL = luceneQuery.getClass().getClassLoader();
                                    Object modified = replaceWildcardWithTerm(luceneQuery, luceneCL);
                                    if (modified != luceneQuery)
                                    {
                                        boolean caseSensitive =
                                            (Boolean) Global.invoke(query, "isCaseSensitive");

                                        Class<?> lqClass = luceneCL.loadClass(
                                            "org.apache.lucene.search.Query");
                                        Class<?> tqClass = query.getClass();
                                        Object newQuery = tqClass
                                            .getConstructor(String.class, boolean.class, lqClass)
                                            .newInstance(sq, caseSensitive, modified);
                                        log("WW modQuery="
                                            + Global.invoke(modified, "toString"));
                                        return idxMethod.invoke(origIndex,
                                            new Object[] { newQuery, idxArgs[1], idxArgs[2] });
                                    }
                                    else
                                    {
                                        log("WW no WildcardQuery found, origQuery="
                                            + Global.invoke(luceneQuery, "toString"));
                                    }
                                }
                                else
                                {
                                    log("WW disabled, sq=" + sq);
                                }
                            }
                            return idxMethod.invoke(origIndex, idxArgs);
                        });
                }
                return method.invoke(origTp, args);
            });
    }

    private static Object replaceWildcardWithTerm(Object query, ClassLoader luceneCL) throws Exception
    {
        if (query == null)
            return null;

        Class<?> bqClass = luceneCL.loadClass("org.apache.lucene.search.BooleanQuery");
        Class<?> bqBuilderClass = luceneCL.loadClass("org.apache.lucene.search.BooleanQuery$Builder");
        Class<?> wqClass = luceneCL.loadClass("org.apache.lucene.search.WildcardQuery");
        Class<?> tqClass = luceneCL.loadClass("org.apache.lucene.search.TermQuery");
        Class<?> bcClass = luceneCL.loadClass("org.apache.lucene.search.BooleanClause");
        Class<?> occurClass = luceneCL.loadClass("org.apache.lucene.search.BooleanClause$Occur");
        Class<?> termClass = luceneCL.loadClass("org.apache.lucene.index.Term");

        if (bqClass.isInstance(query))
        {
            Iterable<?> clauses = (Iterable<?>) bqClass.getMethod("clauses").invoke(query);

            Object builder = bqBuilderClass.getConstructor().newInstance();
            java.lang.reflect.Method addMethod = null;
            java.lang.reflect.Method buildMethod = null;
            for (java.lang.reflect.Method m : bqBuilderClass.getMethods())
            {
                if ("add".equals(m.getName()) && m.getParameterCount() == 1)
                    addMethod = m;
                if ("build".equals(m.getName()) && m.getParameterCount() == 0)
                    buildMethod = m;
            }
            if (addMethod == null || buildMethod == null)
                return query;

            boolean changed = false;
            java.lang.reflect.Method getQueryM = bcClass.getMethod("getQuery");
            java.lang.reflect.Method getOccurM = bcClass.getMethod("getOccur");

            for (Object clause : clauses)
            {
                Object inner = getQueryM.invoke(clause);
                Object occur = getOccurM.invoke(clause);

                if (wqClass.isInstance(inner))
                {
                    Object term = wqClass.getMethod("getTerm").invoke(inner);
                    String field = (String) termClass.getMethod("field").invoke(term);
                    String rawText = (String) termClass.getMethod("text").invoke(term);

                    if ("text".equals(field) || "textLowerCase".equals(field))
                    {
                        log("WW replace WQ field=" + field + " rawText=" + rawText);
                        String cleanText = rawText.replaceAll("^[*?]+", "").replaceAll("[*?]+$", "");
                        log("WW cleanText=" + cleanText);
                        Object cleanTerm = termClass
                            .getConstructor(String.class, String.class)
                            .newInstance(field, cleanText);
                        inner = tqClass.getConstructor(termClass).newInstance(cleanTerm);
                        changed = true;
                    }
                }
                else if (bqClass.isInstance(inner))
                {
                    Object mod = replaceWildcardWithTerm(inner, luceneCL);
                    if (mod != inner)
                    {
                        inner = mod;
                        changed = true;
                    }
                }

                Object newClause = bcClass
                    .getConstructor(
                        luceneCL.loadClass("org.apache.lucene.search.Query"), occurClass)
                    .newInstance(inner, occur);
                addMethod.invoke(builder, newClause);
            }

            return changed ? buildMethod.invoke(builder) : query;
        }

        if (wqClass.isInstance(query))
        {
            Object term = wqClass.getMethod("getTerm").invoke(query);
            String field = (String) termClass.getMethod("field").invoke(term);
            String rawText = (String) termClass.getMethod("text").invoke(term);

            log("WW top-level WQ field=" + field + " rawText=" + rawText);
            if ("text".equals(field) || "textLowerCase".equals(field))
            {
                String cleanText = rawText.replaceAll("^[*?]+", "").replaceAll("[*?]+$", "");
                log("WW cleanText=" + cleanText);
                Object cleanTerm = termClass
                    .getConstructor(String.class, String.class)
                    .newInstance(field, cleanText);
                return tqClass.getConstructor(termClass).newInstance(cleanTerm);
            }
        }

        return query;
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
