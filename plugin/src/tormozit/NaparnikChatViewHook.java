package tormozit;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.events.EventTarget;

import com.sun.jna.platform.win32.User32;

/** Доработки панели чатов и страницы «Внешний вид» 1С:Напарника. */
public final class NaparnikChatViewHook implements IStartup
{
    private static final String VIEW_ID = "com.e1c.edt.ai.ui.views.ChatView"; //$NON-NLS-1$
    private static final String EVENT = "comfort-reply-state"; //$NON-NLS-1$
    private static final String MARKER = "tormozit.naparnik.replyNotification"; //$NON-NLS-1$
    private static final Set<Shell> dialogs = Collections.newSetFromMap(new WeakHashMap<>());
    private static final java.util.Map<IViewPart, ChatPanel> panels = new IdentityHashMap<>();

    /* WebKit перерисовывает FXCanvas даже при opacity: 0 у вращающегося SVG.
     * Штатный CSS Напарника останавливаем только для невидимого индикатора в заголовке.
     */
    private static final String FIX_HIDDEN_SPINNER = """
        (function(){
          var id='comfort-naparnik-hidden-spinner';
          if(document.getElementById(id))return 'installed';
          if(!document.head)return 'waiting';
          var style=document.createElement('style');
          style.id=id;
          style.textContent='.jump-spinner:not(.visible){animation:none!important;-webkit-animation:none!important;}';
          document.head.appendChild(style);
          return 'installed';
        })()
        """;

    /* API проверено по ChatSessionImpl, ChatStateMachine, SessionRegistry и ChatApi
     * в .tmp/issue-804/chat.js. Подписки только наблюдают штатную модель, не заменяют её методы.
     * Уведомляем об окончании ответа и об ожидании действия пользователя.
     * Отмена, ошибка, автоматические вызовы инструментов и загрузка истории не уведомляют.
     */
    private static final String OBSERVE = """
        (function(){
          if(window.__comfortReplyObserver)return 'installed';
          var api=window.chatApi, registry=api&&api.sessionRegistry;
          if(!registry||!registry.sessions||!registry.bus)return 'waiting';
          var seen=new WeakSet();
          function wire(session){
            if(!session||seen.has(session))return;
            var runner=session.runner, sm=runner&&runner.sm;
            if(!sm||typeof sm.subscribeStateChange!=='function')return;
            seen.add(session);
            var unsubscribe=sm.subscribeStateChange(function(to,from){
              // ChatClient обновляет messages перед вызовом onMessage/advanceRound.
              // В существующих чатах session.conversation.messages может оставаться пустым.
              var chat=api.chatClient.getChat(session.id);
              var clientMessages=chat&&chat.messages;
              var messages=clientMessages&&clientMessages.length
                ?clientMessages:session.conversation.messages;
              var last=messages.length?messages[messages.length-1]:null;
              var cancelled=runner.cancelledStream;
              var waiting=to==='awaitingToolConfirm';
              var complete=!cancelled&&(waiting||(to==='idle'&&from==='generating'
                &&last&&last.role==='assistant'&&last.finished===true
                &&!(last.content&&last.content.tool_calls&&last.content.tool_calls.length)));
              var component=api.componentRegistry.getActiveComponent();
              var values={id:String(session.id),title:String((chat&&chat.title)
                ||session.conversation.title||'Без названия'),
                active:!!component&&String(component.chatId)===String(session.id),
                complete:!!complete,waiting:waiting};
              var root=document.documentElement;
              Object.keys(values).forEach(function(k){root.setAttribute('data-comfort-'+k,String(values[k]));});
              var event=document.createEvent('Event');
              event.initEvent('comfort-reply-state',false,false);document.dispatchEvent(event);
            });
            session.on('disposed',function(){unsubscribe();});
          }
          registry.sessions.forEach(wire);
          registry.bus.on('session-added',function(e){wire(registry.get(e.id));});
          window.__comfortReplyObserver=true;
          return 'installed';
        })()
        """;

    @Override
    public void earlyStartup()
    {
        Display display = Display.getDefault();
        display.asyncExec(() ->
        {
            display.addFilter(SWT.Show, event ->
            {
                if (event.widget instanceof Shell shell)
                    wirePreferences(shell, display);
            });
            for (Shell shell : display.getShells())
                wirePreferences(shell, display);
            scan(display);
        });
    }

    private static void scan(Display display)
    {
        if (display.isDisposed() || !PlatformUI.isWorkbenchRunning())
            return;
        Set<IViewPart> live = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var window : PlatformUI.getWorkbench().getWorkbenchWindows())
            for (var page : window.getPages())
                for (var ref : page.getViewReferences())
                    if (VIEW_ID.equals(ref.getId()))
                    {
                        IViewPart view = ref.getView(false);
                        if (view == null)
                            continue;
                        live.add(view);
                        ChatPanel panel = panels.computeIfAbsent(view, ChatPanel::new);
                        panel.observe(display);
                    }
        panels.keySet().retainAll(live);
        display.timerExec(1000, () -> scan(display));
    }

    private static void runOnFx(Runnable action)
    {
        try
        {
            Class.forName("javafx.application.Platform").getMethod("runLater", Runnable.class) //$NON-NLS-1$ //$NON-NLS-2$
                .invoke(null, action);
        }
        catch (ReflectiveOperationException | RuntimeException error)
        {
        }
    }

    private static final class ChatPanel
    {
        private final IViewPart view;
        private final AtomicBoolean pending = new AtomicBoolean();
        private Document document;
        private Document styledDocument;

        ChatPanel(IViewPart view)
        {
            this.view = view;
        }

        boolean contains(Control control)
        {
            Object canvas = Global.getField(view, "canvas"); //$NON-NLS-1$
            for (Control current = control; current != null && !current.isDisposed(); current = current.getParent())
                if (current == canvas)
                    return true;
            return false;
        }

        void observe(Display display)
        {
            Object chat = Global.getField(view, "chatDialog"); //$NON-NLS-1$
            Object webView = Global.getField(chat, "webView"); //$NON-NLS-1$
            if (webView == null || !pending.compareAndSet(false, true))
                return;
            runOnFx(() ->
            {
                try
                {
                    Object engine = Global.invoke(webView, "getEngine"); //$NON-NLS-1$
                    Object current = Global.invoke(engine, "getDocument"); //$NON-NLS-1$
                    if (!(current instanceof Document dom) || !(dom instanceof EventTarget target))
                        return;
                    if (styledDocument != dom)
                    {
                        Object result = Global.invoke(engine, "executeScript", FIX_HIDDEN_SPINNER); //$NON-NLS-1$
                        if ("installed".equals(result)) //$NON-NLS-1$
                            styledDocument = dom;
                    }
                    if (document != dom)
                    {
                        document = dom;
                        target.addEventListener(EVENT, event -> onState(dom, display), false);
                    }
                    Global.invoke(engine, "executeScript", OBSERVE); //$NON-NLS-1$
                }
                catch (RuntimeException error)
                {
                }
                finally
                {
                    pending.set(false);
                }
            });
        }

        private void onState(Document dom, Display display)
        {
            Element root = dom.getDocumentElement();
            String id = root.getAttribute("data-comfort-id"); //$NON-NLS-1$
            String title = root.getAttribute("data-comfort-title"); //$NON-NLS-1$
            boolean active = "true".equals(root.getAttribute("data-comfort-active")); //$NON-NLS-1$ //$NON-NLS-2$
            boolean complete = "true".equals(root.getAttribute("data-comfort-complete")); //$NON-NLS-1$ //$NON-NLS-2$
            boolean waiting = "true".equals(root.getAttribute("data-comfort-waiting")); //$NON-NLS-1$ //$NON-NLS-2$
            if (display.isDisposed())
                return;
            display.asyncExec(() ->
            {
                // SWT может помнить активный контрол, когда EDT свёрнута или за другим приложением.
                // Все виджеты и окно переднего плана проверяем непосредственно перед решением.
                Control focus = display.getFocusControl();
                boolean panelFocus = contains(focus);
                Object canvas = Global.getField(view, "canvas"); //$NON-NLS-1$
                Shell shell = canvas instanceof Control control && !control.isDisposed()
                    ? control.getShell() : null;
                boolean visible = shell != null && !shell.isDisposed() && shell.isVisible();
                boolean minimized = shell != null && !shell.isDisposed() && shell.getMinimized();
                boolean foreground = false;
                if (visible && !minimized)
                {
                    if (WinWindowActivator.isWindows())
                    {
                        try
                        {
                            var foregroundWindow = User32.INSTANCE.GetForegroundWindow();
                            var chatWindow = WinWindowActivator.hwndFromShell(shell);
                            foreground = foregroundWindow != null && chatWindow != null
                                && WinWindowActivator.hwndEquals(foregroundWindow, chatWindow);
                        }
                        catch (RuntimeException | LinkageError error)
                        {
                            Global.tempLog("naparnik-reply-focus", "foreground check failed: " + error); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                    else
                        foreground = display.getActiveShell() == shell;
                }
                boolean inFocus = active && panelFocus && foreground;
                boolean enabled = ComfortSettings.getInstance().getPreferenceStore()
                    .getBoolean(ComfortSettings.PREF_NAPARNIK_REPLY_NOTIFICATION);
                Global.tempLog("naparnik-reply-focus", "chat=" + id + " complete=" + complete //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " active=" + active + " panelFocus=" + panelFocus + " visible=" + visible //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " minimized=" + minimized + " foreground=" + foreground + " inFocus=" + inFocus //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " enabled=" + enabled); //$NON-NLS-1$
                if (!complete || inFocus)
                    return;
                if (enabled)
                    ToastNotification.show(waiting ? "1С:Напарник ожидает вашего действия"
                        : "1С:Напарник завершил ответ", title, 10_000,
                        () -> activate(id), "Открыть чат");
            });
        }

        private void activate(String id)
        {
            try
            {
                IWorkbenchPage page = view.getSite().getPage();
                IViewPart current = page.showView(VIEW_ID);
                Shell shell = current.getSite().getShell();
                shell.setMinimized(false);
                shell.forceActive();
                page.activate(current);
                current.setFocus();
                Object chat = Global.getField(current, "chatDialog"); //$NON-NLS-1$
                Object webView = Global.getField(chat, "webView"); //$NON-NLS-1$
                String route = "/chat-page/" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8); //$NON-NLS-1$
                runOnFx(() ->
                {
                    Object engine = Global.invoke(webView, "getEngine"); //$NON-NLS-1$
                    Global.invoke(engine, "executeScript", //$NON-NLS-1$
                        "window.chatApi.router.load('" + route + "')"); //$NON-NLS-1$ //$NON-NLS-2$
                });
            }
            catch (org.eclipse.ui.PartInitException | RuntimeException error)
            {
            }
        }
    }

    private static void wirePreferences(Shell shell, Display display)
    {
        if (shell.isDisposed() || !(shell.getData() instanceof PreferenceDialog dialog) || !dialogs.add(shell))
            return;
        dialog.addPageChangedListener(event -> display.asyncExec(() -> addPreference(dialog.getSelectedPage())));
        display.asyncExec(() -> addPreference(dialog.getSelectedPage()));
    }

    private static void addPreference(Object selected)
    {
        if (!(selected instanceof FieldEditorPreferencePage page)
            || !"com.e1c.edt.ai.ui.preferences.AppearancePreferencePage".equals(page.getClass().getName())) //$NON-NLS-1$
            return;
        try
        {
            Method parentMethod = FieldEditorPreferencePage.class.getDeclaredMethod("getFieldEditorParent"); //$NON-NLS-1$
            parentMethod.setAccessible(true);
            Composite parent = (Composite) parentMethod.invoke(page);
            if (parent.isDisposed() || Boolean.TRUE.equals(parent.getData(MARKER)))
                return;
            Method add = FieldEditorPreferencePage.class.getDeclaredMethod("addField", FieldEditor.class); //$NON-NLS-1$
            add.setAccessible(true);
            ReplyField field = new ReplyField(parent);
            add.invoke(page, field);
            field.load();
            parent.setData(MARKER, Boolean.TRUE);
            parent.layout(true, true);
        }
        catch (ReflectiveOperationException | RuntimeException error)
        {
        }
    }

    private static final class ReplyField extends BooleanFieldEditor
    {
        ReplyField(Composite parent)
        {
            super(ComfortSettings.PREF_NAPARNIK_REPLY_NOTIFICATION, "Уведомление об ответе", parent);
            setPreferenceStore(ComfortSettings.getInstance().getPreferenceStore());
            Control control = getDescriptionControl(parent);
            control.setToolTipText(TooltipText.wrap(control,
                "Показывать уведомление с названием чата и ссылкой на него, когда Напарник завершил ответ"
                    + " или ожидает вашего действия, а фокус ввода находится вне этого чата." + Global.pluginSignForTooltip()));
        }

        @Override
        public void store()
        {
            super.store();
            ComfortSettings.setAndSave(getPreferenceName(), getBooleanValue());
        }
    }
}
