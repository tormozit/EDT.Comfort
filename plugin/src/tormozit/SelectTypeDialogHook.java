package tormozit;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IStartup;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.util.McoreUtil;
import com._1c.g5.v8.dt.mcore.TypeDescription;

/**
 * Вместо самобытного диалога «Редактирование типа данных» ({@code md.ui SelectTypeDialog}) открывает
 * общий AEF-диалог ({@code TypeDescriptionDialogComponent}) — тот же, что у полей типа реквизитов.
 * Самобытный диалог создают напрямую редактор компоновки ({@code dcs.ui TypeDescriptionEditor}) и
 * конструктор запросов ({@code qw.ui}: вкладка «Характеристики», поля временных таблиц); все они после
 * {@code open()} читают {@code getResultTypeDescription()}.
 *
 * <p>Подмена — в {@code SWT.Show} старого диалога (окно ещё не на экране), по образцу
 * {@link ChoiceParametersHook}: открываем общий диалог, результат пишем в поле {@code typeDescription}
 * старого диалога, закрываем его с тем же кодом. Ограничения на типы берём у старого диалога как есть
 * (поле {@code tdi}) и подставляем в модель ({@code typeDescInfo}), потому что владельца значения
 * ({@code EObject} + {@code EReference}) вызывающий в диалог не передаёт.
 *
 * <p>Пакеты AEF плагином не импортируются — классы берутся из бандла {@code md.ui}. При любой ошибке
 * до показа общего диалога остаётся штатный.
 */
public class SelectTypeDialogHook implements IStartup
{
    private static final String TAG = "selectTypeDialog"; //$NON-NLS-1$
    private static final String PATCHED_KEY = "tormozit.selectTypeDialogPatched"; //$NON-NLS-1$
    private static final String OLD_DIALOG_CLASS =
        "com._1c.g5.v8.dt.md.ui.dialogs.types.SelectTypeDialog"; //$NON-NLS-1$
    private static final String MD_UI_BUNDLE = "com._1c.g5.v8.dt.md.ui"; //$NON-NLS-1$
    private static final String MODEL_CLASS =
        "com._1c.g5.v8.dt.md.ui.aef.models.EmfTypeDescriptionModel"; //$NON-NLS-1$
    private static final String COMPONENT_CLASS =
        "com._1c.g5.v8.dt.md.ui.aef.components.type.TypeDescriptionDialogComponent"; //$NON-NLS-1$
    private static final String LISTENER_CLASS = "com._1c.g5.aef2.events.IEventChannelListener"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    private static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        Listener listener = event ->
        {
            if (!(event.widget instanceof Shell shell) || shell.isDisposed())
                return;
            if (shell.getData(PATCHED_KEY) != null)
                return;
            Object dialog = shell.getData();
            if (dialog == null || !OLD_DIALOG_CLASS.equals(dialog.getClass().getName()))
                return;
            shell.setData(PATCHED_KEY, Boolean.TRUE);
            try
            {
                replace(shell, (Window)dialog);
            }
            catch (RuntimeException | LinkageError e)
            {
                Global.log(TAG, "replace FAIL " + e); //$NON-NLS-1$
            }
        };
        // SWT.Show приходит до ShowWindow — старый диалог ещё не на экране
        display.addFilter(SWT.Show, listener);
        // Владелец типа известен только обработчику кнопки «...» редактора компоновки; фильтр
        // выполняется до него, то есть до открытия диалога.
        display.addFilter(SWT.Selection, SelectTypeDialogHook::captureTypeOwner);
    }

    private static final String TYPE_EDITOR_LISTENER_CLASS =
        "com._1c.g5.v8.dt.dcs.ui.valueeditors.TypeDescriptionEditor$1"; //$NON-NLS-1$
    /** Владелец типа, чья кнопка «...» нажата последней, и время нажатия. */
    private static WeakReference<EObject> lastTypeOwner;
    private static long lastTypeOwnerTime;
    private static final long TYPE_OWNER_TTL_MS = 5000;
    /** Владелец значения для держателя — по идентичности держателя. */
    private static final java.util.Map<EObject, EObject> HOLDER_OWNERS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Нажата кнопка «...» ячейки типа редактора компоновки: слушатель кнопки — {@code TypeDescriptionEditor$1},
     * у него {@code this$0.data.object} — объект (поле, параметр, ресурс...), чей тип редактируется.
     */
    private static void captureTypeOwner(org.eclipse.swt.widgets.Event event)
    {
        if (!(event.widget instanceof Button button) || button.isDisposed())
            return;
        for (Listener l : button.getListeners(SWT.Selection))
        {
            Object inner = Global.unwrapTypedListener(l);
            if (inner == null || !TYPE_EDITOR_LISTENER_CLASS.equals(inner.getClass().getName()))
                continue;
            Object data = Global.getField(Global.getField(inner, "this$0"), "data"); //$NON-NLS-1$ //$NON-NLS-2$
            if (Global.getField(data, "object") instanceof EObject owner) //$NON-NLS-1$
            {
                lastTypeOwner = new WeakReference<>(owner);
                lastTypeOwnerTime = System.currentTimeMillis();
                Global.tempLog("best-type-owner", "владелец типа из кнопки: " + owner.eClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return;
        }
    }

    /** Владелец типа для открываемого сейчас диалога; {@code null}, если диалог открыт не из редактора компоновки. */
    private static EObject takeTypeOwner()
    {
        EObject owner = lastTypeOwner != null ? lastTypeOwner.get() : null;
        lastTypeOwner = null;
        return owner != null && System.currentTimeMillis() - lastTypeOwnerTime <= TYPE_OWNER_TTL_MS ? owner : null;
    }

    /**
     * Владелец значения, ради которого создан {@code holder} (держатель общего диалога); {@code null}, если
     * {@code holder} не наш или владелец неизвестен.
     */
    static EObject holderOwner(Object holder)
    {
        return holder instanceof EObject key ? HOLDER_OWNERS.get(key) : null;
    }

    /**
     * {@code IEngine.showDialog} строит окно с родителем «активное окно рабочей среды», а когда оно не
     * определяется (активно другое окно), родителя нет и по закрытию диалога ОС активирует прежнее
     * окно — экран мигает. Поэтому сцену диалога создаём сами с явным родителем — родителем старого
     * диалога ({@code createScene(компонент, LwtDialogRenderingParameters(родитель))}), повторяя
     * подготовку компонента из {@code showDialog}.
     *
     * @return {@code false}, если так открыть нельзя (тяжёлые контролы SWT) — тогда обычный {@code showDialog}
     */
    private static boolean showWithParent(Bundle bundle, Object engine, Object component, Object dialogModel,
        Shell parent) throws ReflectiveOperationException
    {
        if (parent == null || Boolean.TRUE.equals(Global.invoke(
            bundle.loadClass("com._1c.g5.aef2.utils.Aef2Utils"), "areHeavyControlsPreferred"))) //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        Object params = bundle.loadClass("com._1c.g5.aef2.lwt.LwtDialogRenderingParameters") //$NON-NLS-1$
            .getConstructor(Shell.class).newInstance(parent);
        Global.invokeVoid(component, "setChildCommitsEnabled", Boolean.FALSE); //$NON-NLS-1$
        Global.invokeVoid(component, "setModel", dialogModel); //$NON-NLS-1$
        Global.invoke(engine, "createScene", component, params); //$NON-NLS-1$
        return true;
    }

    private static void replace(Shell shell, Window oldDialog)
    {
        Object tdi = Global.getField(oldDialog, "tdi"); //$NON-NLS-1$
        Object current = Global.getField(oldDialog, "currentTypeDescription"); //$NON-NLS-1$
        IV8Project v8Project = (IV8Project)Global.getField(oldDialog, "v8project"); //$NON-NLS-1$
        if (tdi == null || v8Project == null)
            return;
        Bundle bundle = Platform.getBundle(MD_UI_BUNDLE);
        if (bundle == null)
            return;

        Object engine = null;
        Object[] outcome = new Object[2]; // [0] — TypeDescription, [1] — Boolean «закрыто»
        try
        {
            Class<?> modelClass = bundle.loadClass(MODEL_CLASS);
            Class<?> componentClass = bundle.loadClass(COMPONENT_CLASS);
            Class<?> listenerClass = bundle.loadClass(LISTENER_CLASS);

            // Держатель значения: модель читает начальный тип из владельца и работает поверх него, а
            // ограничения на типы каждый раз запрашивает у TypeProviderService по этому владельцу —
            // наш провайдер отдаёт для держателя ограничения старого диалога (tdi)
            EObject holder = EcoreUtil.create(holderClass());
            if (current instanceof TypeDescription currentType)
                holder.eSet(holderClass().getEStructuralFeature(HOLDER_FEATURE), EcoreUtil.copy(currentType));
            registerTypeProvider(bundle);
            HOLDERS.put(holder, tdi);
            EObject typeOwner = takeTypeOwner();
            if (typeOwner != null)
                HOLDER_OWNERS.put(holder, typeOwner);
            Object model = Global.newInstance(modelClass, holder, null,
                holderClass().getEStructuralFeature(HOLDER_FEATURE), v8Project);
            if (model == null)
                return;
            Object dialogModel = Global.invoke(model, "createDialogModel"); //$NON-NLS-1$
            Object component = Global.newInstance(componentClass);
            engine = createEngine(bundle);
            if (dialogModel == null || component == null || engine == null)
                return;

            final Object engineRef = engine;
            InvocationHandler handler = (proxy, method, args) ->
            {
                if (!"eventReceived".equals(method.getName()) || args == null || args.length != 1) //$NON-NLS-1$
                    return null;
                String event = args[0].getClass().getSimpleName();
                if ("CommitEvent".equals(event)) //$NON-NLS-1$
                {
                    Object value = Global.invoke(dialogModel, "getTypeDescription"); //$NON-NLS-1$
                    Object result = value != null ? Global.invoke(value, "get") : null; //$NON-NLS-1$
                    outcome[0] = result instanceof TypeDescription type ? EcoreUtil.copy(type) : null;
                    outcome[1] = Boolean.TRUE;
                    Global.invoke(engineRef, "dispose"); //$NON-NLS-1$
                }
                else if ("DiscardEvent".equals(event)) //$NON-NLS-1$
                {
                    outcome[1] = Boolean.TRUE;
                    Global.invoke(engineRef, "dispose"); //$NON-NLS-1$
                }
                return null;
            };
            Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[] { listenerClass }, handler);
            Global.invokeVoid(component, "addListener", listener); //$NON-NLS-1$

            Shell parent = shell.getParent() instanceof Shell parentShell ? parentShell : null;
            boolean withParent = showWithParent(bundle, engine, component, dialogModel, parent);
            if (!withParent)
                Global.invoke(engine, "showDialog", component, dialogModel); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e)
        {
            Global.log(TAG, "open FAIL " + e); //$NON-NLS-1$
            if (outcome[1] == null)
                return; // общий диалог не показан — остаётся штатный
        }

        // showDialog не блокирует (окно общего диалога открывается позже, из очереди): ждём
        // Commit/Discard во вложенном цикле. Окно закрылось без события — считаем отменой; окно,
        // которое так и не появилось за WAIT_SHOW_MS, тоже.
        Display display = shell.getDisplay();
        long started = System.currentTimeMillis();
        boolean seen = false;
        while (outcome[1] == null && !shell.isDisposed())
        {
            boolean open = hasAefDialogShell(display);
            seen |= open;
            if (seen && !open || !seen && System.currentTimeMillis() - started > WAIT_SHOW_MS)
                break;
            if (!display.readAndDispatch())
            {
                display.timerExec(200, () -> {}); // будит цикл для повторной проверки окна
                display.sleep();
            }
        }
        boolean ok = outcome[0] != null;
        if (ok)
            applyResult(bundle, oldDialog, tdi, (TypeDescription)outcome[0]);
        Global.invokeVoid(oldDialog, "setReturnCode", Integer.valueOf(ok ? Window.OK : Window.CANCEL)); //$NON-NLS-1$
        oldDialog.close();
    }

    /**
     * {@code getResultTypeDescription()} старого диалога перед возвратом пересобирает свой
     * {@code typeDescription} из выбранных строк ({@code selectedItems}) и значений композитов
     * квалификаторов — запись готового результата в поле затёрлась бы. Поэтому переносим результат в
     * то состояние, из которого старый диалог его собирает сам: выбранные строки и квалификаторы.
     */
    private static void applyResult(Bundle bundle, Window oldDialog, Object tdi, TypeDescription result)
    {
        try
        {
            Object selected = Global.getField(oldDialog, "selectedItems"); //$NON-NLS-1$
            Object infos = Global.invoke(tdi, "getTypeInfos"); //$NON-NLS-1$
            if (!(selected instanceof java.util.List<?>) || !(infos instanceof Iterable<?>))
                return;
            @SuppressWarnings("unchecked")
            java.util.List<Object> selectedItems = (java.util.List<Object>)selected;
            Class<?> itemClass = bundle.loadClass("com._1c.g5.v8.dt.md.ui.dialogs.types.TypeInfoTreeItem"); //$NON-NLS-1$
            selectedItems.clear();
            for (TypeItem typeItem : result.getTypes())
            {
                String name = McoreUtil.getTypeName(typeItem);
                Object found = null;
                for (Object info : (Iterable<?>)infos)
                {
                    Object infoType = Global.invoke(info, "getType"); //$NON-NLS-1$
                    if (infoType instanceof TypeItem candidate
                        && (candidate == typeItem || name != null && name.equals(McoreUtil.getTypeName(candidate))))
                    {
                        found = info;
                        break;
                    }
                }
                if (found == null)
                    continue;
                Object treeItem = Global.newInstance(itemClass, name);
                Global.invokeVoid(treeItem, "setType", found); //$NON-NLS-1$
                selectedItems.add(treeItem);
            }
            setQualifiers(oldDialog, "nqc", "nq", result.getNumberQualifiers()); //$NON-NLS-1$ //$NON-NLS-2$
            setQualifiers(oldDialog, "sqc", "sq", result.getStringQualifiers()); //$NON-NLS-1$ //$NON-NLS-2$
            setQualifiers(oldDialog, "dqc", "dq", result.getDateQualifiers()); //$NON-NLS-1$ //$NON-NLS-2$
            setQualifiers(oldDialog, "bqc", "bq", result.getBinaryQualifiers()); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            Global.log(TAG, "applyResult FAIL " + e); //$NON-NLS-1$
        }
    }

    private static void setQualifiers(Object dialog, String compositeField, String valueField, EObject qualifiers)
    {
        Object composite = Global.getField(dialog, compositeField);
        if (composite != null && qualifiers != null)
            Global.setFieldForce(composite, valueField, EcoreUtil.copy(qualifiers));
    }

    private static final long WAIT_SHOW_MS = 10000;
    private static final String HOLDER_FEATURE = "type"; //$NON-NLS-1$
    private static final String TYPE_PROVIDER_BUNDLE = "com._1c.g5.v8.dt.platform.core"; //$NON-NLS-1$
    private static final String TYPE_PROVIDER_CLASS = "com._1c.g5.v8.dt.platform.core.typeinfo.ITypeProvider"; //$NON-NLS-1$
    private static final String TYPE_PROVIDER_SERVICE =
        "com._1c.g5.v8.dt.platform.core.typeinfo.TypeProviderService"; //$NON-NLS-1$

    /** Ограничения на типы для держателей значения, по идентичности держателя. */
    private static final java.util.Map<EObject, Object> HOLDERS =
        java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static EClass holderClass;
    private static boolean providerRegistered;

    /**
     * Свой класс держателя ({@code type: TypeDescription}) — чтобы ни один штатный провайдер типов не
     * счёл держатель «своим» и ответил вместо нашего.
     */
    private static synchronized EClass holderClass()
    {
        if (holderClass == null)
        {
            EClass eClass = EcoreFactory.eINSTANCE.createEClass();
            eClass.setName("ComfortTypeHolder"); //$NON-NLS-1$
            EReference feature = EcoreFactory.eINSTANCE.createEReference();
            feature.setName(HOLDER_FEATURE);
            feature.setEType(McorePackage.Literals.TYPE_DESCRIPTION);
            feature.setContainment(true);
            eClass.getEStructuralFeatures().add(feature);
            EPackage ePackage = EcoreFactory.eINSTANCE.createEPackage();
            ePackage.setName("comfortTypeHolder"); //$NON-NLS-1$
            ePackage.setNsPrefix("comfortTypeHolder"); //$NON-NLS-1$
            ePackage.setNsURI("http://tormozit/comfortTypeHolder"); //$NON-NLS-1$
            ePackage.getEClassifiers().add(eClass);
            holderClass = eClass;
        }
        return holderClass;
    }

    /**
     * Регистрирует (один раз) провайдер типов {@code ITypeProvider} для {@code TypeProviderService}: для
     * держателя отдаёт ограничения старого диалога, для всех остальных объектов отвечает {@code null}.
     */
    private static synchronized void registerTypeProvider(Bundle mdUi) throws ReflectiveOperationException
    {
        if (providerRegistered)
            return;
        Bundle platformCore = Platform.getBundle(TYPE_PROVIDER_BUNDLE);
        if (platformCore == null)
            throw new ClassNotFoundException(TYPE_PROVIDER_BUNDLE);
        Class<?> providerClass = platformCore.loadClass(TYPE_PROVIDER_CLASS);
        InvocationHandler handler = (proxy, method, args) ->
        {
            String name = method.getName();
            if ("hashCode".equals(name)) //$NON-NLS-1$
                return System.identityHashCode(proxy);
            if ("equals".equals(name)) //$NON-NLS-1$
                return proxy == args[0];
            if ("toString".equals(name)) //$NON-NLS-1$
                return "ComfortTypeHolderProvider"; //$NON-NLS-1$
            if ("getTypeDescriptionInfoWithTypeInfo".equals(name) && args != null && args.length > 0 //$NON-NLS-1$
                && args[0] instanceof EObject owner)
                return HOLDERS.get(owner);
            return null;
        };
        Object provider = Proxy.newProxyInstance(providerClass.getClassLoader(), new Class<?>[] { providerClass },
            handler);
        Object service = platformCore.loadClass(TYPE_PROVIDER_SERVICE).getField("INSTANCE").get(null); //$NON-NLS-1$
        Global.invokeVoid(service, "addTypeProvider", provider); //$NON-NLS-1$
        providerRegistered = true;
    }

    /** Открыто ли окно AEF-диалога (данные окна — класс из пакета {@code com._1c.g5.aef2}). */
    private static boolean hasAefDialogShell(Display display)
    {
        for (Shell shell : display.getShells())
        {
            if (shell.isDisposed() || !shell.isVisible())
                continue;
            Object data = shell.getData();
            if (data != null && data.getClass().getName().startsWith("com._1c.g5.aef2.")) //$NON-NLS-1$
                return true;
        }
        return false;
    }

    /**
     * Отдельный движок AEF для диалога — как {@code AbstractAefDialogHandler.createEngine()}: сцены
     * у вызывающего (редактор компоновки, конструктор запросов) нет.
     */
    private static Object createEngine(Bundle bundle) throws ReflectiveOperationException
    {
        Object engine = Global.invoke(bundle.loadClass("com._1c.g5.aef2.engines.EngineFactory"), //$NON-NLS-1$
            "createEngine", "Comfort.SelectTypeDialog"); //$NON-NLS-1$ //$NON-NLS-2$
        if (engine == null)
            return null;
        register(bundle, engine, "com._1c.g5.v8.dt.md.ui.aef.swt.MdSwtRenderer", //$NON-NLS-1$
            "com._1c.g5.aef2.swt.renderers.SwtRenderingParameters"); //$NON-NLS-1$
        register(bundle, engine, "com._1c.g5.v8.dt.ui.aef.swt.SwtMdDialogRenderer", //$NON-NLS-1$
            "com._1c.g5.aef2.swt.renderers.SwtDialogRenderingParameters"); //$NON-NLS-1$
        register(bundle, engine, "com._1c.g5.v8.dt.md.ui.aef.lwt.MdLwtRenderer", //$NON-NLS-1$
            "com._1c.g5.aef2.lwt.LwtRenderingParameters"); //$NON-NLS-1$
        register(bundle, engine, "com._1c.g5.v8.dt.ui.aef.lwt.LwtMdDialogRenderer", //$NON-NLS-1$
            "com._1c.g5.aef2.lwt.LwtDialogRenderingParameters"); //$NON-NLS-1$
        Object heavy = Global.invoke(bundle.loadClass("com._1c.g5.aef2.utils.Aef2Utils"), //$NON-NLS-1$
            "areHeavyControlsPreferred"); //$NON-NLS-1$
        Class<?> dialogRenderer = bundle.loadClass(Boolean.TRUE.equals(heavy)
            ? "com._1c.g5.v8.dt.ui.aef.swt.SwtMdDialogRenderer" //$NON-NLS-1$
            : "com._1c.g5.v8.dt.ui.aef.lwt.LwtMdDialogRenderer"); //$NON-NLS-1$
        Global.invoke(engine, "setDialogRenderer", dialogRenderer); //$NON-NLS-1$
        return engine;
    }

    private static void register(Bundle bundle, Object engine, String renderer, String parameters)
        throws ReflectiveOperationException
    {
        Global.invoke(engine, "registerRenderer", bundle.loadClass(renderer), bundle.loadClass(parameters)); //$NON-NLS-1$
    }
}
