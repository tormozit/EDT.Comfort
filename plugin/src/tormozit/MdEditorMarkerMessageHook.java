package tormozit;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.forms.IManagedForm;
import org.eclipse.ui.forms.IMessage;
import org.eclipse.ui.forms.editor.IFormPage;
import org.eclipse.ui.forms.events.HyperlinkAdapter;
import org.eclipse.ui.forms.events.HyperlinkEvent;
import org.eclipse.ui.forms.widgets.Form;
import org.eclipse.ui.forms.widgets.ScrolledForm;
import org.eclipse.ui.internal.forms.MessageManager;

import com._1c.g5.v8.dt.md.ui.editor.base.DtGranularEditor;

/**
 * Штатная надпись шапки редактора объекта («Обнаружено N предупреждений / ошибок»)
 * становится ссылкой: клик открывает панель «Ошибки конфигурации» с отбором
 * «Текущий объект».
 * <p>
 * {@code DtGranularEditorMarkerSupport} пишет сводку через
 * {@code ScrolledForm.setMessage}. Eclipse Forms рисует её гиперссылкой только
 * если у формы есть {@code IHyperlinkListener} — штатно его нет, поэтому надпись
 * обычный {@code CLabel}. Хук вешает слушатель через публичный
 * {@link Form#addMessageHyperlinkListener}.
 */
public final class MdEditorMarkerMessageHook implements IStartup
{
    private static final String TAG = "MdEditorMarkerMessage"; //$NON-NLS-1$

    /** Ключ пометки формы: слушатель уже подключён. */
    private static final String KEY_INSTALLED = "tormozit.mdEditorMarkerMessage"; //$NON-NLS-1$

    /** Поле {@code Form} с менеджером сообщений — тип поля конкретный, потому и наследуемся. */
    private static final String FIELD_MESSAGE_MANAGER = "messageManager"; //$NON-NLS-1$

    /** Поле {@code MessageManager} со списком сообщений текущего цикла. */
    private static final String FIELD_MESSAGES = "messages"; //$NON-NLS-1$

    /** Поле {@code MessageManager} с признаком автообновления. */
    private static final String FIELD_AUTO_UPDATE = "autoUpdate"; //$NON-NLS-1$

    private final Set<DtGranularEditor<?>> hookedEditors =
        Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new IWindowListener()
            {
                @Override public void windowOpened(IWorkbenchWindow w) { hookWindow(w); }
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
                @Override public void windowClosed(IWorkbenchWindow w) {}
            });
            for (IWorkbenchWindow w : workbench.getWorkbenchWindows())
                hookWindow(w);
        });
    }

    private void hookWindow(IWorkbenchWindow window)
    {
        if (window == null)
            return;
        IWorkbenchPage page = window.getActivePage();
        if (page != null)
        {
            for (IEditorReference ref : page.getEditorReferences())
            {
                if (ref.getEditor(false) instanceof DtGranularEditor<?> granular)
                    hookEditor(granular);
            }
        }
        window.getPartService().addPartListener(new IPartListener2()
        {
            @Override public void partOpened(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partActivated(IWorkbenchPartReference ref) { hookFromRef(ref); }
            @Override public void partBroughtToTop(IWorkbenchPartReference ref) { hookFromRef(ref); }
        });
    }

    private void hookFromRef(IWorkbenchPartReference ref)
    {
        if (ref != null && ref.getPart(false) instanceof DtGranularEditor<?> granular)
            hookEditor(granular);
    }

    private void hookEditor(DtGranularEditor<?> editor)
    {
        try
        {
            if (hookedEditors.add(editor))
                editor.addPageChangedListener(event -> installOnActivePage(editor));
            installOnActivePage(editor);
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "hook editor", e); //$NON-NLS-1$
        }
    }

    private static void installOnActivePage(DtGranularEditor<?> editor)
    {
        try
        {
            IFormPage page = editor.getActivePageInstance();
            if (page == null)
                return;
            IManagedForm managedForm = page.getManagedForm();
            if (managedForm == null)
                return;
            ScrolledForm scrolledForm = managedForm.getForm();
            if (scrolledForm == null || scrolledForm.isDisposed())
                return;
            Form form = scrolledForm.getForm();
            if (form == null || form.isDisposed() || form.getData(KEY_INSTALLED) != null)
                return;
            form.setData(KEY_INSTALLED, Boolean.TRUE);
            form.addMessageHyperlinkListener(new HyperlinkAdapter()
            {
                @Override
                public void linkActivated(HyperlinkEvent e)
                {
                    ProblemViewMarkers.showForCurrentObject();
                }
            });
            installIdleRebuildGuard(form);
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "install on active page", e); //$NON-NLS-1$
        }
    }

    /**
     * Убирает перестроение шапки, когда сводка по маркерам не изменилась.
     * <p>
     * {@code DtGranularEditorMarkerSupport.handleMarkerChanged} реагирует на любое изменение
     * маркеров <b>проекта</b> — своих маркеров объекта он не проверяет. Пока по конфигурации идёт
     * проверка, коммиттер маркеров рассылает событие каждые 1,5 с, и шапка открытого объекта
     * перестраивается снова и снова с тем же самым текстом. Перестроение начинается с
     * {@code MessageManager.removeAllMessages()} при включённом автообновлении: сообщение
     * снимается сразу, значок исчезает, заголовок уезжает влево — это и видно как мигание.
     * <p>
     * Подменяем менеджер сообщений формы своим. Он копит цикл перестроения молча, а в конце
     * сравнивает получившийся набор сообщений с прежним: совпал — форму вообще не трогаем,
     * не совпал — отдаём штатное обновление. Отличие от исходного поведения ровно одно:
     * повторная установка того же самого текста больше не доходит до формы.
     */
    private static void installIdleRebuildGuard(Form form)
    {
        try
        {
            if (Global.getField(form, FIELD_MESSAGE_MANAGER) instanceof IdleAwareMessageManager)
                return;
            Global.setField(form, FIELD_MESSAGE_MANAGER, new IdleAwareMessageManager(form));
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "install idle rebuild guard", e); //$NON-NLS-1$
        }
    }

    /** См. {@link MdEditorMarkerMessageHook#installIdleRebuildGuard(Form)}. */
    private static final class IdleAwareMessageManager extends MessageManager
    {
        private final Form form;

        /** Идёт цикл перестроения: сообщения сняты, новые ещё добавляются. */
        private boolean rebuilding;

        /** Автообновление выключено нами на время цикла. */
        private boolean suppressed;

        /** Набор сообщений до начала цикла — с ним сравниваем результат. */
        private Set<String> before = Set.of();

        IdleAwareMessageManager(Form form)
        {
            super(form);
            this.form = form;
        }

        @Override
        public void removeAllMessages()
        {
            if (!rebuilding)
            {
                before = currentKeys();
                rebuilding = true;
                if (super.isAutoUpdate())
                {
                    super.setAutoUpdate(false);
                    suppressed = true;
                }
                scheduleFinish();
            }
            super.removeAllMessages();
        }

        /**
         * Для внешнего кода менеджер по-прежнему «с автообновлением»: EDT запоминает это значение
         * до своего {@code setAutoUpdate(false)} и в конце восстанавливает запомненное. Соври мы
         * здесь {@code false} — сводка перестала бы обновляться совсем.
         */
        @Override
        public boolean isAutoUpdate()
        {
            return suppressed || super.isAutoUpdate();
        }

        @Override
        public void setAutoUpdate(boolean value)
        {
            if (!value)
            {
                super.setAutoUpdate(false);
                return;
            }
            if (!rebuilding)
            {
                suppressed = false;
                super.setAutoUpdate(true);
                return;
            }
            finishRebuild();
        }

        /** Конец цикла: либо тихо возвращаем автообновление, либо отдаём штатное обновление. */
        private void finishRebuild()
        {
            rebuilding = false;
            suppressed = false;
            if (currentKeys().equals(before) && enableAutoUpdateSilently())
                return;
            super.setAutoUpdate(true);
        }

        /**
         * Включает автообновление, не вызывая {@code update()}: штатный {@code setAutoUpdate(true)}
         * на переходе {@code false → true} всегда обновляет форму, а нам нужно именно её не трогать.
         *
         * @return {@code false}, если записать поле не удалось — тогда вызывающий делает штатное
         *         обновление, то есть поведение остаётся прежним
         */
        private boolean enableAutoUpdateSilently()
        {
            return Global.setFieldForce(this, FIELD_AUTO_UPDATE, Boolean.TRUE);
        }

        /** Сообщения, накопленные менеджером (в форму они попадают только при обновлении). */
        private Set<String> currentKeys()
        {
            Set<String> keys = new LinkedHashSet<>();
            if (!(Global.getField(this, FIELD_MESSAGES) instanceof List<?> messages))
                return keys;
            for (Object item : messages)
            {
                if (item instanceof IMessage message)
                    keys.add(message.getMessageType() + "|" + message.getKey() + '|' + message.getMessage()); //$NON-NLS-1$
            }
            return keys;
        }

        /**
         * Страховка: если вызывающий не включил автообновление обратно (свой путь обновления,
         * исключение посреди перебора маркеров), закрываем цикл сами в следующем такте UI-потока.
         * К этому моменту штатный цикл EDT уже отработал целиком.
         */
        private void scheduleFinish()
        {
            form.getDisplay().asyncExec(() ->
            {
                if (rebuilding && !form.isDisposed())
                    finishRebuild();
            });
        }
    }
}
