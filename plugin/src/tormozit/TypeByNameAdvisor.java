package tormozit;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IWorkbenchPage;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.form.model.AbstractFormAttribute;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormAttribute;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.DefinedType;

/**
 * Подбор типа значения по имени реквизита — единая точка для всех мест плагина: автоподбор в
 * мастерах «Новый ...» ({@link NewAttributeNameIdentifierHook}), в панели «Свойства»
 * ({@link PropertyNameIdentifierHook}) и кнопка «Лучший тип» диалога выбора типа
 * ({@link SmartOutlineHook}).
 *
 * <p>Источник один на всех, см. {@link #availableSource}: подключено приложение ИР —
 * {@code ирОбщий.ИмяТипаИзИмениПеременнойЛкс(Имя)}; ИР не подключён, но 1С:Напарник в сети —
 * тот же вопрос задаётся ему ({@link Naparnik}). Нет ни того, ни другого — ничего не
 * происходит (тихо).
 *
 * <p>{@code typeModel} везде — {@code ITypeDescriptionModel} (через рефлексию).
 */
final class TypeByNameAdvisor
{
    static final String SOURCE_IR = "ИР"; //$NON-NLS-1$
    static final String SOURCE_NAPARNIK = "Напарник"; //$NON-NLS-1$
    /** Тип объекта метаданных с именем, равным имени реквизита, — без ИР и Напарника. */
    static final String SOURCE_EXACT_NAME = "совпадение имени"; //$NON-NLS-1$

    private static final String TAG = "TypeByNameAdvisor"; //$NON-NLS-1$

    private static final String IR_TYPE_MODULE = "ирОбщий"; //$NON-NLS-1$
    private static final String IR_TYPE_FUNCTION = "ИмяТипаИзИмениПеременнойЛкс"; //$NON-NLS-1$
    private static final String DEFAULT_TYPE_NAME_RU = "Строка"; //$NON-NLS-1$
    private static final int DEFAULT_STRING_LENGTH = 10;
    private static final int NEW_FORM_ATTRIBUTE_STRING_LENGTH = 0;
    private static final int NAME_COMMIT_ATTEMPTS = 30;
    private static final int NAME_COMMIT_RETRY_MS = 100;
    private static final int CARET_RESTORE_WINDOW_MS = 1500;

    private TypeByNameAdvisor()
    {
    }

    /**
     * Кто сейчас подберёт тип для {@code project}: {@link #SOURCE_IR}, {@link #SOURCE_NAPARNIK}
     * или {@code null}, если подбирать некому. Дешёвая проверка, без обращения к сети и к COM.
     */
    static String availableSource(IDtProject project)
    {
        if (project == null)
            return null;
        if (IRApplication.hasConnectedSessionForKeys(project))
            return SOURCE_IR;
        return Naparnik.isOnline() ? SOURCE_NAPARNIK : null;
    }

    /**
     * Как {@link #availableSource(IDtProject)}, но с учётом имени: тип объекта метаданных с таким
     * же именем подставляется без ИР и Напарника — тогда источник {@link #SOURCE_EXACT_NAME},
     * даже если ни ИР, ни Напарник не подключены.
     */
    static String availableSource(IDtProject project, Object typeModel, String name)
    {
        if (project == null)
            return null;
        if (typeModel != null && name != null && !name.isEmpty()
            && Naparnik.exactNameType(project.getWorkspaceProject(), typeModel, name) != null)
            return SOURCE_EXACT_NAME;
        return availableSource(project);
    }

    /**
     * Запрашивает в фоне имя типа для {@code name}. Вызывается в UI-потоке. {@code onResult}
     * получает имя типа в UI-потоке, только если {@code stillApplicable} на тот момент
     * возвращает {@code true}; сопоставить имя с типом модели — {@link #findTypeItem}.
     */
    static void suggest(IDtProject project, Object typeModel, String name, Supplier<Boolean> stillApplicable,
        Consumer<String> onResult)
    {
        if (project == null || typeModel == null || name == null || name.isEmpty())
            return;
        String exact = Naparnik.exactNameType(project.getWorkspaceProject(), typeModel, name);
        if (exact != null)
        {
            // Как и ответ ИР/Напарника — отдельным шагом очереди, а не изнутри обработчика ввода.
            Display.getCurrent().asyncExec(() ->
            {
                if (Boolean.TRUE.equals(stillApplicable.get()))
                    onResult.accept(exact);
            });
            return;
        }
        if (IRApplication.hasConnectedSessionForKeys(project))
            Global.callIrFunctionInBackground(project, IR_TYPE_MODULE, IR_TYPE_FUNCTION, new Object[] { name },
                stillApplicable, onResult);
        else
            Naparnik.request(project.getWorkspaceProject(), typeModel, name, stillApplicable, onResult);
    }

    /**
     * Автоподбор в мастере «Новый ...»: без дополнительного условия на ответ и без ожидания
     * имени в модели — объект мастера до завершения мастера в модель не записан.
     */
    static void autofillDefaultType(Object typeModel, String name, String logTag)
    {
        autofillDefaultType(typeModel, name, logTag, () -> Boolean.TRUE, () -> typeModel, false);
    }

    /**
     * Автоподбор в панели «Свойства»: ответ применяется, только когда введённое имя уже дошло
     * до модели, см. {@link #applyWhenNameCommitted}.
     *
     * @param answerWanted     проверяется в UI-потоке при получении ответа (ИР или Напарника);
     *                         {@code false} — ответ отбрасывается
     * @param currentTypeModel модель типа, которую панель показывает сейчас: после записи имени
     *                         панель перестраивает строки, и прежняя модель становится
     *                         недействительной ({@code Model is offline})
     */
    static void autofillDefaultType(Object typeModel, String name, String logTag,
        Supplier<Boolean> answerWanted, Supplier<Object> currentTypeModel)
    {
        autofillDefaultType(typeModel, name, logTag, answerWanted, currentTypeModel, true);
    }

    /**
     * Автоподбор после правки имени: если поле «Тип» ещё не тронуто пользователем (см.
     * {@link #untouchedStringLength}), запрашивает тип по {@code name} и, если по готовности
     * результата тип всё ещё тот же (пользователь не успел выбрать свой), подставляет
     * предложенный. Проект — активный.
     *
     * @param logTag тег журнала «Комфорт» вызывающего хука
     */
    private static void autofillDefaultType(Object typeModel, String name, String logTag,
        Supplier<Boolean> answerWanted, Supplier<Object> currentTypeModel, boolean waitForNameInModel)
    {
        if (typeModel == null || name == null || name.isEmpty())
            return;
        Integer untouchedLength = untouchedStringLength(typeModel);
        if (untouchedLength == null)
            return;

        IProject workspaceProject = Global.getActiveProject((IWorkbenchPage) null, false);
        IDtProject project = workspaceProject != null
            ? Global.getDtProjectFromWorkspaceProject(workspaceProject) : null;
        String source = availableSource(project, typeModel, name);
        if (source == null)
            return;

        if (!waitForNameInModel)
        {
            suggest(project, typeModel, name,
                () -> untouchedLength.equals(currentStringLength(typeModel))
                    && Boolean.TRUE.equals(answerWanted.get()),
                typeName -> applySingleType(typeModel, typeName, source, logTag));
            return;
        }
        // Нетронутость типа здесь проверяется позже и по текущей модели панели: исходная к
        // приходу ответа может быть уже недействительной.
        suggest(project, typeModel, name, answerWanted, typeName -> applyWhenNameCommitted(typeModel,
            currentTypeModel, name, typeName, source, logTag, untouchedLength, answerWanted, 0));
    }

    /**
     * Подставляет тип, когда введённое имя уже записано в модель, и в ту модель типа, которую
     * панель показывает сейчас. Подстановка раньше записи имени заставляет панель перерисоваться
     * по модели со старым именем — введённое имя пропадает (ответ Напарника приходит за 0,3–0,5 с
     * и успевал раньше). Имя читается из модели заново ({@link #committedOwnerName}); не дошло
     * за {@link #NAME_COMMIT_ATTEMPTS} попыток или прочитать его нечем — тип не подставляется:
     * имя важнее.
     */
    private static void applyWhenNameCommitted(Object capturedModel, Supplier<Object> currentTypeModel, String name,
        String typeName, String source, String logTag, Integer untouchedLength, Supplier<Boolean> answerWanted,
        int attempt)
    {
        Object current = currentTypeModel.get();
        Object target = current != null ? current : capturedModel;
        if (name.equals(committedOwnerName(target)))
        {
            if (untouchedLength.equals(currentStringLength(target)) && Boolean.TRUE.equals(answerWanted.get()))
            {
                keepCaretAtEndAfterRebuild(name);
                applySingleType(target, typeName, source, logTag);
            }
            return;
        }
        Display display = Display.getCurrent();
        if (attempt >= NAME_COMMIT_ATTEMPTS || display == null || display.isDisposed())
            return;
        display.timerExec(NAME_COMMIT_RETRY_MS, () -> applyWhenNameCommitted(capturedModel, currentTypeModel, name,
            typeName, source, logTag, untouchedLength, answerWanted, attempt + 1));
    }

    /**
     * После подстановки типа панель «Свойства» перестраивает строки и возвращает фокус в поле
     * «Имя», а поле при получении фокуса выделяет весь свой текст — пользователь, только что
     * закончивший ввод, видит выделенное имя и следующим нажатием стёр бы его
     * ({@code LightText.setFocus} → {@code selectAllOverlayText}). В течение
     * {@link #CARET_RESTORE_WINDOW_MS} после подстановки у поля ввода с текстом {@code name},
     * получившего фокус, выделение снимается, курсор ставится в конец.
     */
    private static void keepCaretAtEndAfterRebuild(String name)
    {
        Display display = Display.getCurrent();
        if (display == null || display.isDisposed())
            return;
        Listener filter = event ->
        {
            // Поле ввода LWT редактирует текст в оверлее StyledText (LightText.overlay).
            if (!(event.widget instanceof StyledText text) || text.isDisposed() || !name.equals(text.getText()))
                return;
            // Поле выделяет текст уже после получения фокуса — снимаем следующим шагом очереди.
            display.asyncExec(() ->
            {
                if (text.isDisposed() || !name.equals(text.getText()))
                    return;
                text.setSelection(name.length());
            });
        };
        display.addFilter(SWT.FocusIn, filter);
        display.timerExec(CARET_RESTORE_WINDOW_MS, () ->
        {
            if (!display.isDisposed())
                display.removeFilter(SWT.FocusIn, filter);
        });
    }

    /**
     * Имя владельца типа, как оно записано в модели сейчас, или {@code null}, если прочитать
     * нечем. Экземпляр владельца, который держит {@code typeModel}, может быть устаревшим и
     * оторванным от контейнера (путь EMF по нему не строится), поэтому объект читается из BM
     * заново: корневой объект — по идентификатору BM, владелец в нём — по собственному
     * идентификатору, см. {@link #findFresh}.
     */
    private static String committedOwnerName(Object typeModel)
    {
        try
        {
            if (!(Global.invoke(typeModel, "getParent") instanceof EObject owner)) //$NON-NLS-1$
                return null;
            EObject root = EcoreUtil.getRootContainer(owner);
            if (!(root instanceof IBmObject rootObject))
                return null;
            long rootId = rootObject.bmGetId();
            List<Integer> idPath = owner instanceof AbstractFormAttribute attribute ? attributeIdPath(attribute) : null;
            Object uuid = idPath == null ? Global.invoke(owner, "getUuid") : null; //$NON-NLS-1$
            if (idPath == null && uuid == null)
                return null;

            IBmModelManager models = (IBmModelManager)Global.getServiceByClass(IBmModelManager.class);
            IBmModel model = models != null ? models.getModel(root) : null;
            // Поток уже в транзакции — вложенное чтение запрещено; подождём следующей попытки.
            if (model == null || Global.invoke(model.getEngine(), "getCurrentTransaction") != null) //$NON-NLS-1$
                return null;

            return model.executeReadonlyTask(new AbstractBmTask<String>("comfort.typeByName.committedOwnerName") //$NON-NLS-1$
            {
                @Override
                public String execute(IBmTransaction transaction, IProgressMonitor monitor)
                {
                    Object freshObject = transaction.getObjectById(rootId);
                    if (!(freshObject instanceof EObject freshRoot))
                        return null;
                    EObject fresh = findFresh(freshRoot, idPath, uuid);
                    return Global.invoke(fresh, "getName") instanceof String freshName ? freshName : null; //$NON-NLS-1$
                }
            });
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "committedOwnerName", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Объект в свежем корне: реквизит формы — по цепочке идентификаторов, объект метаданных — по
     * {@code getUuid()}.
     */
    private static EObject findFresh(EObject freshRoot, List<Integer> idPath, Object uuid)
    {
        if (idPath != null)
            return freshRoot instanceof Form form ? findAttribute(form, idPath) : null;
        if (uuid.equals(Global.invoke(freshRoot, "getUuid"))) //$NON-NLS-1$
            return freshRoot;
        for (TreeIterator<EObject> contents = freshRoot.eAllContents(); contents.hasNext();)
        {
            EObject candidate = contents.next();
            if (uuid.equals(Global.invoke(candidate, "getUuid"))) //$NON-NLS-1$
                return candidate;
        }
        return null;
    }

    /**
     * Доступные типы владельца, у которого нет {@code ITypeDescriptionModel} (поле набора данных
     * схемы компоновки): годится везде, где вместо модели типа передаётся {@code typeModel}.
     */
    record TypeCatalog(List<TypeItem> items)
    {
    }

    /** Доступные типы {@code typeModel}: {@link TypeCatalog} или {@code ITypeDescriptionModel.getTypes(false)}. */
    private static List<?> typeItems(Object typeModel)
    {
        if (typeModel instanceof TypeCatalog catalog)
            return catalog.items();
        return Global.invoke(typeModel, "getTypes", Boolean.FALSE) instanceof List<?> types ? types : null; //$NON-NLS-1$
    }

    /**
     * {@link com._1c.g5.v8.dt.mcore.TypeItem} из доступных типов {@code typeModel}
     * ({@code ITypeDescriptionModel.getTypes(false)}), имя которого (рус. или англ.) совпадает
     * с {@code typeName}; {@code null}, если такого нет.
     */
    static Object findTypeItem(Object typeModel, String typeName)
    {
        if (typeModel == null || typeName == null || typeName.isBlank())
            return null;
        String wanted = typeName.trim();
        List<?> types = typeItems(typeModel);
        if (types == null)
            return null;
        for (Object item : types)
        {
            Object nameRu = Global.invoke(item, "getNameRu"); //$NON-NLS-1$
            Object name = Global.invoke(item, "getName"); //$NON-NLS-1$
            if (wanted.equalsIgnoreCase(String.valueOf(nameRu)) || wanted.equalsIgnoreCase(String.valueOf(name)))
                return item;
        }
        return null;
    }

    /**
     * Длина строки, при которой тип считается не тронутым пользователем, или {@code null}, если
     * тип уже выбран. Нетронутый тип — это:
     * <ul>
     * <li>Строка(10) — 1С-дефолт нового реквизита объекта метаданных;</li>
     * <li>Строка(0) — дефолт нового реквизита формы, но только пока реквизита нет в сохранённой
     * форме: у давно существующего реквизита неограниченная строка — осознанный выбор.</li>
     * </ul>
     */
    private static Integer untouchedStringLength(Object typeModel)
    {
        Integer length = currentStringLength(typeModel);
        if (length == null)
            return null;
        if (length == DEFAULT_STRING_LENGTH)
            return length;
        if (length == NEW_FORM_ATTRIBUTE_STRING_LENGTH && isUnsavedFormAttribute(typeModel))
            return length;
        return null;
    }

    /** Длина строки, если {@code typeModel} сейчас описывает одиночный тип Строка; иначе {@code null}. */
    private static Integer currentStringLength(Object typeModel)
    {
        try
        {
            Object singleTypeItemValue = Global.invoke(typeModel, "getSingleTypeItem"); //$NON-NLS-1$
            Object typeItem = Global.invoke(singleTypeItemValue, "get"); //$NON-NLS-1$
            Object nameRu = Global.invoke(typeItem, "getNameRu"); //$NON-NLS-1$
            Object stringLengthValue = Global.invoke(typeModel, "getStringLength"); //$NON-NLS-1$
            Object length = Global.invoke(stringLengthValue, "get"); //$NON-NLS-1$
            if (!DEFAULT_TYPE_NAME_RU.equals(nameRu))
                return null;
            return length instanceof Integer i ? i : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * {@code true}, если владелец типа — реквизит формы (или его колонка), которого нет в
     * сохранённой форме. Сохранённая форма — файл {@code Form.form}: в BM правки редактора
     * попадают сразу, до сохранения (чтение BM отдельной транзакцией показывало только что
     * добавленный реквизит как существующий). Файл загружается штатной фабрикой ресурсов EMF,
     * которую EDT регистрирует для расширения {@code form}; реквизит ищется по цепочке
     * идентификаторов ({@code AbstractFormAttribute.getId()}), имя к этому моменту уже изменено.
     */
    private static boolean isUnsavedFormAttribute(Object typeModel)
    {
        AbstractFormAttribute attribute = null;
        Object owner = null;
        for (String getter : new String[] { "getParent", "getParentContext" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            owner = Global.invoke(typeModel, getter);
            if (owner instanceof AbstractFormAttribute found)
            {
                attribute = found;
                break;
            }
        }
        if (attribute == null)
            return false;

        try
        {
            EObject root = EcoreUtil.getRootContainer(attribute);
            if (!(root instanceof Form))
                return false;
            List<Integer> idPath = attributeIdPath(attribute);

            IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
            IFile file = lookup != null ? lookup.getPlatformResource(root) : null;
            if (file == null)
                return false;
            // Файла формы ещё нет — форма ни разу не сохранялась.
            if (!file.exists())
                return true;

            Resource resource = new ResourceSetImpl().getResource(
                URI.createPlatformResourceURI(file.getFullPath().toString(), true), true);
            Form savedForm = null;
            for (EObject content : resource.getContents())
            {
                if (content instanceof Form form)
                    savedForm = form;
            }
            return savedForm != null && !containsAttribute(savedForm, idPath);
        }
        catch (RuntimeException e)
        {
            Global.logError(TAG, "isUnsavedFormAttribute", e); //$NON-NLS-1$
            return false;
        }
    }

    private static boolean containsAttribute(Form form, List<Integer> idPath)
    {
        return findAttribute(form, idPath) != null;
    }

    /** Реквизит формы или его колонка по цепочке идентификаторов; {@code null}, если такого нет. */
    private static AbstractFormAttribute findAttribute(Form form, List<Integer> idPath)
    {
        for (FormAttribute formAttribute : form.getAttributes())
        {
            if (idPath.equals(attributeIdPath(formAttribute)))
                return formAttribute;
            for (TreeIterator<EObject> nested = formAttribute.eAllContents(); nested.hasNext();)
            {
                if (nested.next() instanceof AbstractFormAttribute column && idPath.equals(attributeIdPath(column)))
                    return column;
            }
        }
        return null;
    }

    /** Идентификаторы реквизита формы от верхнего уровня до него самого (колонка — после своего реквизита). */
    private static List<Integer> attributeIdPath(AbstractFormAttribute attribute)
    {
        List<Integer> path = new ArrayList<>();
        for (EObject current = attribute; current != null; current = current.eContainer())
        {
            if (current instanceof AbstractFormAttribute level)
                path.add(0, Integer.valueOf(level.getId()));
        }
        return path;
    }

    /** Сопоставляет предложенное имя типа с типом модели и подставляет его единственным типом. */
    private static void applySingleType(Object typeModel, String typeName, String source, String logTag)
    {
        try
        {
            Object matched = findTypeItem(typeModel, typeName);
            if (matched == null)
                return;

            Object singleTypeItemValue = Global.invoke(typeModel, "getSingleTypeItem"); //$NON-NLS-1$
            PropertySheetLayoutDiag.dumpNow("автоподбор [" + logTag + "]: перед подстановкой «" + typeName + "»"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Global.invokeVoid(singleTypeItemValue, "set", matched); //$NON-NLS-1$
            PropertySheetLayoutDiag.dump("автоподбор [" + logTag + "]: подставлен «" + typeName + "»"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Global.log(logTag, "Тип подобран через " + source + ": " + typeName); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (Exception e)
        {
            Global.logError(logTag, "applySingleType", e); //$NON-NLS-1$
        }
    }

    /**
     * Подбор типа через 1С:Напарник.
     *
     * <p>Вход — {@code com.e1c.edt.ai.IConversationFacade.sendAsync(SendUserMessageRequest,
     * ICancellationToken)}: тот же одиночный запрос, которым штатный
     * {@code StagingViewEnhancer} получает текст сообщения коммита. Фасад и
     * {@code IStateService} берутся из инжектора {@code BaseActivator} бандла
     * {@code com.e1c.edt.ai.ui.common}; всё через рефлексию, compile-зависимости от бандлов
     * Напарника нет. Фасад есть в {@code com.e1c.edt.ai} 1.0.7 и новее — в более ранних
     * (1.0.2, 1.0.4) класса нет, Напарник считается недоступным.
     *
     * <p>Напарник не знает состава конфигурации, а разрешать ему инструменты ради одного
     * слова долго, поэтому набор инструментов запроса пуст ({@code allowedTools} — пустое
     * множество, см. {@code ConversationFacade.getToolsDefinitions}), а кандидаты передаются
     * в самом запросе: все типы без точки в имени (примитивные и т.п.) и имена объектов
     * метаданных со ссылочным типом («Валюты, Контрагенты, …»), см. {@link #collectCandidates}.
     */
    private static final class Naparnik
    {
        private static final String CORE_BUNDLE = "com.e1c.edt.ai"; //$NON-NLS-1$
        private static final String UI_COMMON_BUNDLE = "com.e1c.edt.ai.ui.common"; //$NON-NLS-1$
        private static final int MAX_REFERENCE_NAMES = 500;
        private static final int STEM_LENGTH = 5;
        private static final int TIMEOUT_SECONDS = 5;
        private static final String REFERENCE_KIND_SUFFIX = "Ссылка"; //$NON-NLS-1$
        private static final String DEFINED_TYPE_KIND = "ОпределяемыйТип"; //$NON-NLS-1$
        /**
         * Классификация определяемых типов: {@code проект/ИмяТипа} → есть ли в составе объектные
         * типы. Каждый тип классифицируется один раз за сеанс; читается и пишется в UI-потоке.
         */
        private static final Map<String, Boolean> OBJECT_DEFINED_TYPE = new HashMap<>();
        private static final String[] OBJECT_KIND_MARKERS =
            { "Объект", "НаборЗаписей", "Менеджер", "Object", "RecordSet", "Manager" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

        /**
         * Из чего Напарнику предлагается выбирать.
         *
         * @param simpleTypes   имена типов без объекта метаданных (примитивные и т.п.)
         * @param referenceTypes имя объекта метаданных → полное имя его ссылочного типа
         *                       ({@code Валюты} → {@code СправочникСсылка.Валюты})
         * @param definedTypes  полные имена определяемых типов ({@code ОпределяемыйТип.Контрагент})
         */
        private record Candidates(List<String> simpleTypes, Map<String, String> referenceTypes,
            List<String> definedTypes)
        {
        }

        static boolean isOnline()
        {
            return resolveOnlineFacade() != null;
        }

        /** Ничего не делает (тихо), если Напарник не установлен, не запущен или не в сети. */
        static void request(IProject project, Object typeModel, String name,
            Supplier<Boolean> stillApplicable, Consumer<String> onResult)
        {
            Object facade = resolveOnlineFacade();
            if (facade == null)
                return;

            Candidates candidates = collectCandidates(project, typeModel, name);
            String prompt = buildPrompt(name, candidates);

            new Job("Напарник: подбор типа") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        String answer = ask(facade, project, prompt);
                        String typeName = resolveTypeName(cleanAnswer(answer), candidates);
                        if (typeName == null)
                            return Status.OK_STATUS;

                        Display display = Display.getDefault();
                        if (display != null && !display.isDisposed())
                        {
                            display.asyncExec(() ->
                            {
                                if (Boolean.TRUE.equals(stillApplicable.get()))
                                    onResult.accept(typeName);
                            });
                        }
                    }
                    catch (Throwable t)
                    {
                        Global.logError(TAG, "запрос к Напарнику не выполнен", t); //$NON-NLS-1$
                    }
                    return Status.OK_STATUS;
                }
            }.schedule();
        }

        /**
         * {@code IConversationFacade} Напарника, если он в сети ({@code ServiceState.ONLINE});
         * иначе {@code null}. Бандл в состоянии, отличном от {@code ACTIVE}, не трогаем:
         * загрузка класса запустила бы Напарника, которым пользователь не пользуется.
         */
        private static Object resolveOnlineFacade()
        {
            try
            {
                Bundle core = Platform.getBundle(CORE_BUNDLE);
                Bundle uiCommon = Platform.getBundle(UI_COMMON_BUNDLE);
                if (core == null || uiCommon == null || core.getState() != Bundle.ACTIVE
                    || uiCommon.getState() != Bundle.ACTIVE)
                    return null;

                Class<?> activatorClass = uiCommon.loadClass("com.e1c.edt.ai.ui.BaseActivator"); //$NON-NLS-1$
                Object activator = Global.invoke(activatorClass, "getDefault"); //$NON-NLS-1$
                Object injector = Global.invoke(activator, "getInjector"); //$NON-NLS-1$
                if (injector == null)
                    return null;

                Object stateService = Global.invoke(injector, "getInstance", //$NON-NLS-1$
                    core.loadClass("com.e1c.edt.ai.IStateService")); //$NON-NLS-1$
                Object serviceState = Global.invoke(Global.invoke(stateService, "getState"), "getServiceState"); //$NON-NLS-1$ //$NON-NLS-2$
                if (!(serviceState instanceof Enum<?> state) || !"ONLINE".equals(state.name())) //$NON-NLS-1$
                    return null;

                Class<?> facadeClass;
                try
                {
                    facadeClass = core.loadClass("com.e1c.edt.ai.IConversationFacade"); //$NON-NLS-1$
                }
                catch (ClassNotFoundException oldVersion)
                {
                    // В этой версии Напарника фасада нет (см. javadoc класса).
                    return null;
                }
                return Global.invoke(injector, "getInstance", facadeClass); //$NON-NLS-1$
            }
            catch (Throwable t)
            {
                Global.logError(TAG, "не удалось получить фасад Напарника", t); //$NON-NLS-1$
                return null;
            }
        }

        /** Одиночный запрос в новом диалоге без инструментов; текст ответа или {@code null}. */
        private static String ask(Object facade, IProject project, String prompt) throws Exception
        {
            Bundle core = Platform.getBundle(CORE_BUNDLE);
            Class<?> requestClass = core.loadClass("com.e1c.edt.ai.assistent.SendUserMessageRequest"); //$NON-NLS-1$
            Class<?> tokenClass = core.loadClass("com.e1c.edt.ai.ICancellationToken"); //$NON-NLS-1$
            Object noCancellation = core.loadClass("com.e1c.edt.ai.CancellationTokens") //$NON-NLS-1$
                .getField("NONE").get(null); //$NON-NLS-1$

            // (project, message, conversationSession, forceNewConversation, skillName, chat,
            // maxToolRounds, allowedTools, completionPolicy)
            Object request = null;
            for (Constructor<?> constructor : requestClass.getConstructors())
            {
                if (constructor.getParameterCount() == 9)
                    request = constructor.newInstance(project, prompt, null, Boolean.TRUE, null, null, null,
                        Collections.emptySet(), null);
            }
            if (request == null)
            {
                Global.log(TAG, "[!] нет конструктора SendUserMessageRequest с 9 параметрами"); //$NON-NLS-1$
                return null;
            }

            // Метод по умолчанию интерфейса: у класса-реализации его нет, Global.invoke не найдёт.
            Object future = core.loadClass("com.e1c.edt.ai.IConversationFacade") //$NON-NLS-1$
                .getMethod("sendAsync", requestClass, tokenClass) //$NON-NLS-1$
                .invoke(facade, request, noCancellation);
            if (!(future instanceof CompletableFuture<?> completable))
                return null;

            Object result = completable.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return Global.invoke(result, "getText") instanceof String text ? text : null; //$NON-NLS-1$
        }

        /**
         * Читает {@code typeModel} — только в UI-потоке. Ссылочных объектов и определяемых типов
         * вместе — не больше {@link #MAX_REFERENCE_NAMES}; если их больше, первыми идут те, чьё
         * имя содержит начало одного из слов имени реквизита, остальные — в порядке модели, пока
         * есть место. Одно имя у объектов разных видов (справочник и перечисление) — остаётся
         * первый. Определяемые типы идут полными именами: имя определяемого типа может совпадать
         * с именем объекта метаданных; определяемые типы с объектными типами в составе не
         * предлагаются, см. {@link #hasObjectTypes}.
         */
        private static Candidates collectCandidates(IProject project, Object typeModel, String name)
        {
            List<String> stems = new ArrayList<>();
            for (String word : name.split("(?<=[\\p{Ll}\\d])(?=\\p{Lu})|_")) //$NON-NLS-1$
            {
                if (word.length() >= 3)
                    stems.add(word.substring(0, Math.min(word.length(), STEM_LENGTH)).toLowerCase(Locale.ROOT));
            }

            List<String> simpleTypes = new ArrayList<>();
            Map<String, String> matching = new LinkedHashMap<>();
            Map<String, String> others = new LinkedHashMap<>();
            Map<String, String> matchingDefined = new LinkedHashMap<>();
            Map<String, String> otherDefined = new LinkedHashMap<>();
            DefinedTypeClassifier classifier = new DefinedTypeClassifier(project);
            if (typeItems(typeModel) instanceof List<?> types)
            {
                for (Object item : types)
                {
                    if (!(Global.invoke(item, "getNameRu") instanceof String typeName) || typeName.isBlank()) //$NON-NLS-1$
                        continue;
                    int dot = typeName.indexOf('.');
                    if (dot < 0)
                    {
                        // «СправочникСсылка», «ДокументСсылка», «ЛюбаяСсылка» — обобщённые ссылки без
                        // объекта метаданных: подбирать по имени их нельзя.
                        if (!typeName.endsWith(REFERENCE_KIND_SUFFIX))
                            simpleTypes.add(typeName);
                        continue;
                    }
                    String kind = typeName.substring(0, dot);
                    String objectName = typeName.substring(dot + 1);
                    boolean matches = stems.stream().anyMatch(objectName.toLowerCase(Locale.ROOT)::contains);
                    if (DEFINED_TYPE_KIND.equals(kind))
                    {
                        if (!classifier.hasObjectTypes(objectName))
                            (matches ? matchingDefined : otherDefined).put(typeName, typeName);
                    }
                    else if (kind.endsWith(REFERENCE_KIND_SUFFIX) && !matching.containsKey(objectName)
                        && !others.containsKey(objectName))
                        (matches ? matching : others).put(objectName, typeName);
                }
            }

            // Квота общая: сначала совпавшие по имени (объекты, затем определяемые типы), потом остальные.
            int[] quota = { MAX_REFERENCE_NAMES };
            Map<String, String> referenceTypes = new LinkedHashMap<>();
            Map<String, String> definedTypes = new LinkedHashMap<>();
            takeWithinQuota(matching, referenceTypes, quota);
            takeWithinQuota(matchingDefined, definedTypes, quota);
            takeWithinQuota(others, referenceTypes, quota);
            takeWithinQuota(otherDefined, definedTypes, quota);
            return new Candidates(simpleTypes, referenceTypes, new ArrayList<>(definedTypes.keySet()));
        }

        private static void takeWithinQuota(Map<String, String> source, Map<String, String> target, int[] quota)
        {
            for (Map.Entry<String, String> entry : source.entrySet())
            {
                if (quota[0] <= 0)
                    return;
                target.put(entry.getKey(), entry.getValue());
                quota[0]--;
            }
        }

        /**
         * Полное имя типа объекта метаданных, имя которого без учёта регистра совпадает с именем
         * реквизита ({@code ОпределяемыйТип.Имя}, {@code СправочникСсылка.Имя} и т.п.), или
         * {@code null}. Такой тип подставляется сразу, без ИР и Напарника: совпадение имён
         * однозначно, а Напарник на один и тот же запрос отвечал то одним типом, то другим.
         *
         * <p>Одно имя у нескольких объектов: определяемый тип важнее ссылочного, среди ссылочных
         * берётся первый в порядке модели. Определяемый тип с объектными типами в составе не
         * годится и здесь.
         */
        static String exactNameType(IProject project, Object typeModel, String name)
        {
            if (!(typeItems(typeModel) instanceof List<?> types))
                return null;
            String reference = null;
            for (Object item : types)
            {
                if (!(Global.invoke(item, "getNameRu") instanceof String typeName)) //$NON-NLS-1$
                    continue;
                int dot = typeName.indexOf('.');
                if (dot < 0 || !name.equalsIgnoreCase(typeName.substring(dot + 1)))
                    continue;
                String kind = typeName.substring(0, dot);
                if (DEFINED_TYPE_KIND.equals(kind))
                {
                    if (!new DefinedTypeClassifier(project).hasObjectTypes(typeName.substring(dot + 1)))
                        return typeName;
                }
                else if (reference == null && kind.endsWith(REFERENCE_KIND_SUFFIX))
                    reference = typeName;
            }
            return reference;
        }

        /**
         * Есть ли в составе определяемого типа объектные типы. Классификация каждого типа
         * запоминается ({@link #OBJECT_DEFINED_TYPE}): состав читается один раз на тип, а перечень
         * определяемых типов проекта нужен, только пока встречаются неклассифицированные, —
         * поэтому он читается лениво и живёт один проход.
         */
        private static final class DefinedTypeClassifier
        {
            private final IProject project;
            private final String cachePrefix;
            private Map<String, DefinedType> byName;

            DefinedTypeClassifier(IProject project)
            {
                this.project = project;
                this.cachePrefix = (project != null ? project.getName() : "") + '/'; //$NON-NLS-1$
            }

            boolean hasObjectTypes(String name)
            {
                Boolean cached = OBJECT_DEFINED_TYPE.get(cachePrefix + name);
                if (cached != null)
                    return cached;
                if (byName == null)
                    byName = definedTypesByName(project);
                DefinedType definedType = byName.get(name);
                // Тип не найден в проекте (заимствованный без состава) — проверить нечем:
                // считается пригодным и не запоминается.
                if (definedType == null)
                    return false;
                try
                {
                    boolean result = Naparnik.hasObjectTypes(definedType);
                    OBJECT_DEFINED_TYPE.put(cachePrefix + name, Boolean.valueOf(result));
                    return result;
                }
                catch (RuntimeException e)
                {
                    Global.logError(TAG, "состав определяемого типа " + name, e); //$NON-NLS-1$
                    return false;
                }
            }
        }

        /** Определяемые типы проекта по имени — без чтения их состава; пусто, если конфигурация недоступна. */
        private static Map<String, DefinedType> definedTypesByName(IProject project)
        {
            Map<String, DefinedType> result = new HashMap<>();
            try
            {
                IV8ProjectManager projectManager = (IV8ProjectManager)Global.getServiceByClass(IV8ProjectManager.class);
                IV8Project v8Project = projectManager != null ? projectManager.getProject(project) : null;
                Configuration configuration = v8Project instanceof IConfigurationAware aware
                    ? aware.getConfiguration() : null;
                if (configuration == null)
                    return result;
                for (DefinedType definedType : configuration.getDefinedTypes())
                    result.put(definedType.getName(), definedType);
            }
            catch (RuntimeException e)
            {
                Global.logError(TAG, "definedTypesByName", e); //$NON-NLS-1$
            }
            return result;
        }

        /**
         * {@code true}, если в составе определяемого типа есть объектные типы (объект, набор
         * записей, менеджер): реквизиту такой тип не назначают, предлагать его незачем.
         */
        private static boolean hasObjectTypes(DefinedType definedType)
        {
            TypeDescription description = definedType.getTypeDescription();
            if (description == null)
                return false;
            for (TypeItem type : description.getTypes())
            {
                if (isObjectTypeName(type.getNameRu()) || isObjectTypeName(type.getName()))
                    return true;
            }
            return false;
        }

        /** {@code СправочникОбъект.Х}, {@code РегистрСведенийНаборЗаписей.Х}, {@code СправочникМенеджер.Х} и т.п. */
        private static boolean isObjectTypeName(String typeName)
        {
            int dot = typeName != null ? typeName.indexOf('.') : -1;
            if (dot < 0)
                return false;
            String kind = typeName.substring(0, dot);
            for (String marker : OBJECT_KIND_MARKERS)
            {
                if (kind.contains(marker))
                    return true;
            }
            return false;
        }

        private static String buildPrompt(String name, Candidates candidates)
        {
            // Объекты — первым списком и с явным приоритетом: на равноправные списки Напарник
            // отвечал «Строка» даже при точном совпадении имени («валюта» при объекте «Валюты»).
            return "Определи тип значения реквизита конфигурации 1С:Предприятие с именем «" + name + "».\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "Правило: если имя реквизита по смыслу соответствует одному из объектов метаданных " //$NON-NLS-1$
                + "из списка ниже (с учётом числа и падежа: «Валюта» — «Валюты», «Контрагент» — " //$NON-NLS-1$
                + "«Контрагенты», «СкладОтправитель» — «Склады»), реквизит ссылается на этот объект — " //$NON-NLS-1$
                + "ответь именем объекта. Если подходящего объекта нет, но имя реквизита по смыслу " //$NON-NLS-1$
                + "соответствует одному из определяемых типов, ответь его полным именем, как в списке " //$NON-NLS-1$
                + "(вместе с «ОпределяемыйТип.»). Только если не подходит ни то, ни другое, ответь " //$NON-NLS-1$
                + "одним из простых типов.\n" //$NON-NLS-1$
                + "Ответ — ровно одно имя из списков, одной строкой, без пояснений и оформления.\n\n" //$NON-NLS-1$
                + "Объекты метаданных: " //$NON-NLS-1$
                + String.join(", ", candidates.referenceTypes().keySet()) //$NON-NLS-1$
                + "\n\nОпределяемые типы: " //$NON-NLS-1$
                + String.join(", ", candidates.definedTypes()) //$NON-NLS-1$
                + "\n\nПростые типы: " //$NON-NLS-1$
                + String.join(", ", candidates.simpleTypes()); //$NON-NLS-1$
        }

        /**
         * Ответ Напарника → имя типа модели: имя объекта метаданных заменяется полным именем его
         * ссылочного типа; простой тип и определяемый тип остаются как есть. Ответ вне списков
         * запроса (в том числе обобщённая ссылка вроде «СправочникСсылка») отбрасывается.
         */
        private static String resolveTypeName(String answer, Candidates candidates)
        {
            if (answer == null)
                return null;
            for (Map.Entry<String, String> entry : candidates.referenceTypes().entrySet())
            {
                if (entry.getKey().equalsIgnoreCase(answer))
                    return entry.getValue();
            }
            for (String name : candidates.simpleTypes())
            {
                if (name.equalsIgnoreCase(answer))
                    return name;
            }
            for (String name : candidates.definedTypes())
            {
                if (name.equalsIgnoreCase(answer))
                    return name;
            }
            return null;
        }

        /** Первая непустая строка ответа без кавычек, обратных апострофов и точки в конце. */
        private static String cleanAnswer(String answer)
        {
            if (answer == null)
                return null;
            for (String line : answer.split("\\R")) //$NON-NLS-1$
            {
                String cleaned = line.replaceAll("[`\"«»*]", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
                if (cleaned.endsWith(".")) //$NON-NLS-1$
                    cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
                if (!cleaned.isEmpty())
                    return cleaned;
            }
            return null;
        }
    }
}
