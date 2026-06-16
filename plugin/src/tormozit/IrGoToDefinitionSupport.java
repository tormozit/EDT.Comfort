package tormozit;



import java.util.ArrayList;

import java.util.Collections;

import java.util.List;



import org.eclipse.core.resources.IProject;

import org.eclipse.jface.window.Window;

import org.eclipse.swt.widgets.Shell;



import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

import com._1c.g5.v8.dt.core.platform.IDtProject;

import org.eclipse.ui.IWorkbenchPage;



/**

 * Порт ветки «текстовый документ» из {@code ПерейтиКОпределению} RDT:

 * {@code ПолеТекстаПрограммы.ПерейтиКОпределению} после sync в codeEditor.

 */

public final class IrGoToDefinitionSupport

{

    public static final int RESULT_OK = 1;

    public static final int RESULT_WAIT_CONNECT = 2;

    public static final int RESULT_NOT_HANDLED = 3;



    private IrGoToDefinitionSupport() {}



    /**

     * Единственная точка {@link IRApplication#getSession} для команды «Перейти к определению».

     */

    public static int tryFromBslEditor(

        BslXtextEditor editor, Shell shell, IWorkbenchPage page, IProject project)

    {

        if (editor == null || shell == null || page == null || project == null)

            return RESULT_NOT_HANDLED;



        IDtProject dtProject = Global.getDtProjectFromBslEditor(editor);

        if (dtProject == null)

        {

            debugProblem("IDtProject не найден"); //$NON-NLS-1$

            return RESULT_NOT_HANDLED;

        }



        IRSession session = IRApplication.getSession(dtProject);

        if (session == null || session.executor == null)

        {

            debugStep("session", "ожидание подключения ИР"); //$NON-NLS-1$ //$NON-NLS-2$

            return RESULT_WAIT_CONNECT;

        }



        session.syncCodeEditorToIR(editor);



        GotoDefOutcome outcome;

        try

        {

            outcome = session.executeOnComThread(() ->

            {

                ensureCodeEditor(session);

                ComBridge.invoke(session.codeEditor, "РазобратьТекущийКонтекст"); //$NON-NLS-1$

                ComBridge.invoke(session.codeEditor, "ЗапомнитьИсточникПерехода"); //$NON-NLS-1$

                Object raw = ComBridge.invoke(session.codeEditor, "ПерейтиКОпределению", null, null, false); //$NON-NLS-1$

                return marshalOutcome(raw);

            });

        }

        catch (Exception e)

        {

            debugProblem("COM: " + e.getMessage()); //$NON-NLS-1$

            return RESULT_NOT_HANDLED;

        }



        debugLog("ПерейтиКОпределению → " + describeOutcome(outcome)); //$NON-NLS-1$

        return handleOutcome(outcome, session, editor, shell, page, project);

    }



    /** Plain Java-результат после маршалинга в COM-потоке. */

    private static final class GotoDefOutcome

    {

        enum Kind { NOT_HANDLED, IR_SYNC, TARGET, PICK_LIST }



        final Kind kind;

        final String value;

        final String presentation;

        final List<GotoDefItem> items;



        private GotoDefOutcome(Kind kind, String value, String presentation, List<GotoDefItem> items)

        {

            this.kind = kind;

            this.value = value;

            this.presentation = presentation;

            this.items = items;

        }



        static GotoDefOutcome notHandled()

        {

            return new GotoDefOutcome(Kind.NOT_HANDLED, "", "", Collections.emptyList()); //$NON-NLS-1$ //$NON-NLS-2$

        }



        static GotoDefOutcome irSync()

        {

            return new GotoDefOutcome(Kind.IR_SYNC, "", "", Collections.emptyList()); //$NON-NLS-1$ //$NON-NLS-2$

        }



        static GotoDefOutcome target(String value, String presentation)

        {

            return new GotoDefOutcome(Kind.TARGET, value, presentation, Collections.emptyList());

        }



        static GotoDefOutcome pickList(List<GotoDefItem> items)

        {

            return new GotoDefOutcome(Kind.PICK_LIST, "", "", items); //$NON-NLS-1$ //$NON-NLS-2$

        }

    }



    private static final class GotoDefItem

    {

        final String value;

        final String presentation;



        GotoDefItem(String value, String presentation)

        {

            this.value = value;

            this.presentation = presentation;

        }

    }



    /** Только COM-поток: разбор сырого результата {@code ПерейтиКОпределению}. */

    private static GotoDefOutcome marshalOutcome(Object raw)

    {

        if (raw == null)

            return GotoDefOutcome.notHandled();



        if (isValueList(raw))

        {

            long count = valueListCount(raw);

            if (count <= 0)

                return GotoDefOutcome.notHandled();



            List<GotoDefItem> items = new ArrayList<>();

            for (int i = 0; i < count; i++)

            {

                Object item = valueListItem(raw, i);

                if (item == null)

                    continue;

                String value = ComBridge.toString(ComBridge.getProperty(item, "Значение")).strip(); //$NON-NLS-1$

                String presentation = ComBridge.toString(ComBridge.getProperty(item, "Представление")).strip(); //$NON-NLS-1$

                if (!value.isEmpty())

                    items.add(new GotoDefItem(value, presentation));

            }

            if (items.isEmpty())

                return GotoDefOutcome.notHandled();

            if (items.size() == 1)

            {

                GotoDefItem single = items.get(0);

                return GotoDefOutcome.target(single.value, single.presentation);

            }

            return GotoDefOutcome.pickList(items);

        }



        if (isTrue(raw))

            return GotoDefOutcome.irSync();



        String text = ComBridge.toString(raw).strip();

        if (text.isEmpty())

            return GotoDefOutcome.notHandled();



        return GotoDefOutcome.target(text, ""); //$NON-NLS-1$

    }



    private static int handleOutcome(

        GotoDefOutcome outcome, IRSession session, BslXtextEditor editor,

        Shell shell, IWorkbenchPage page, IProject project)

    {

        switch (outcome.kind)

        {

            case NOT_HANDLED:

                return RESULT_NOT_HANDLED;

            case IR_SYNC:

                session.syncCodeEditorFromIR(editor);

                return RESULT_OK;

            case TARGET:

                return resolveTarget(outcome.value, outcome.presentation, session, editor, shell, page, project);

            case PICK_LIST:

                return handlePickList(outcome.items, shell, page, project);

            default:

                return RESULT_NOT_HANDLED;

        }

    }



    private static int handlePickList(

        List<GotoDefItem> items, Shell shell, IWorkbenchPage page, IProject project)

    {

        List<MdObjectPickDialog.Entry> rows = new ArrayList<>();

        for (GotoDefItem item : items)

            rows.add(MdObjectPickDialog.Entry.irItem(item.value, item.presentation));



        MdObjectPickDialog dlg = new MdObjectPickDialog(shell, rows);

        if (dlg.open() != Window.OK)

        {

            GoToDefinition.markJumpCancelled();

            return RESULT_OK;

        }

        String chosen = dlg.getSelectedFullName();

        if (chosen == null || chosen.isBlank())

            return RESULT_NOT_HANDLED;



        return GoToDefinition.jump(chosen, shell, page, project) ? RESULT_OK : RESULT_NOT_HANDLED;

    }



    private static int resolveTarget(

        String value, String presentation, IRSession session, BslXtextEditor editor,

        Shell shell, IWorkbenchPage page, IProject project)

    {

        if (presentation.startsWith("Создать метод")) //$NON-NLS-1$

        {

            IrMethodConstructorHandler.openMethodConstructor(editor);

            return RESULT_OK;

        }

        if (presentation.startsWith("ГиперСсылка")) //$NON-NLS-1$

        {

            openUrl(value);

            return RESULT_OK;

        }

        if (presentation.startsWith("Колонка БД")) //$NON-NLS-1$

        {

            openIrClientMethod(session, "ОткрытьКолонкуБДЛкс", value); //$NON-NLS-1$

            return RESULT_OK;

        }

        if (presentation.startsWith("Цвет") || presentation.startsWith("Шрифт")) //$NON-NLS-1$ //$NON-NLS-2$

        {

            openIrClientMethod(session, "ОткрытьЦветИлиШрифтПоПолномуИмениЛкс", value); //$NON-NLS-1$

            return RESULT_OK;

        }



        if (value.isEmpty())

            return RESULT_NOT_HANDLED;



        return GoToDefinition.jump(value, shell, page, project) ? RESULT_OK : RESULT_NOT_HANDLED;

    }



    private static void openIrClientMethod(IRSession session, String method, String arg)

    {

        try

        {

            session.executeOnComThread(() ->

            {

                Object irClient = session.getModule("ирКлиент"); //$NON-NLS-1$

                ComBridge.invoke(irClient, method, arg);

                return null;

            });

            session.showWindow();

        }

        catch (Exception e)

        {

            debugProblem(method + ": " + e.getMessage()); //$NON-NLS-1$

        }

    }



    private static boolean isValueList(Object obj)

    {

        if (obj == null || obj instanceof String || obj instanceof Boolean || obj instanceof Number)

            return false;

        try

        {

            ComBridge.invoke(obj, "Количество"); //$NON-NLS-1$

            return true;

        }

        catch (Exception e)

        {

            return false;

        }

    }



    private static long valueListCount(Object list)

    {

        return ComBridge.toLong(ComBridge.invoke(list, "Количество")); //$NON-NLS-1$

    }



    private static Object valueListItem(Object list, int index)

    {

        return ComBridge.invoke(list, "Получить", index); //$NON-NLS-1$

    }



    private static boolean isTrue(Object result)

    {

        if (result == null || result instanceof String)

            return false;

        if (result instanceof Boolean)

            return (Boolean) result;

        if (isValueList(result))

            return false;

        return ComBridge.toBoolean(result);

    }



    private static String describeOutcome(GotoDefOutcome outcome)

    {

        switch (outcome.kind)

        {

            case NOT_HANDLED:

                return "null"; //$NON-NLS-1$

            case IR_SYNC:

                return "true"; //$NON-NLS-1$

            case TARGET:

                return "string:" + truncate(outcome.value, 80); //$NON-NLS-1$

            case PICK_LIST:

                return "list:" + outcome.items.size(); //$NON-NLS-1$

            default:

                return outcome.kind.name();

        }

    }



    private static String truncate(String s, int max)

    {

        return s.length() <= max ? s : s.substring(0, max) + "\u2026"; //$NON-NLS-1$

    }



    private static void openUrl(String url)

    {

        try

        {

            org.eclipse.swt.program.Program.launch(url);

        }

        catch (Exception e)

        {

            debugProblem("openUrl: " + e.getMessage()); //$NON-NLS-1$

        }

    }



    private static void ensureCodeEditor(IRSession session)

    {

        if (session.codeEditor != null)

            return;

        Object irCache = session.getModule("ирКэш"); //$NON-NLS-1$

        session.codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$

    }



    private static final String DEBUG_TAG = "IrGoToDef"; //$NON-NLS-1$



    private static void debugLog(String msg)

    {

        if (Global.isLogEnabled())

            Global.log(DEBUG_TAG, msg);

    }



    private static void debugStep(String phase, String detail)

    {

        if (Global.isLogEnabled())

            Global.log(DEBUG_TAG, phase + ": " + detail); //$NON-NLS-1$

    }



    private static void debugProblem(String msg)

    {

        if (Global.isLogEnabled())

            Global.log(DEBUG_TAG, "[!] " + msg); //$NON-NLS-1$

    }

}


