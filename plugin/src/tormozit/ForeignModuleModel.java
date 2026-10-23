package tormozit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;

/**
 * Модель BSL-модуля с рассчитанными типами для фоновых досчётов, которым не хватает копии модуля
 * у вызывающего редактора (у каждого редактора EDT свой {@code ResourceSet}, чужой модуль там без
 * {@code linkBatched}): полный расчёт модуля в новом наборе ресурсов — из снимка текста открытого
 * редактора (несохранённые правки) или с диска.
 *
 * <p>Модель самого редактора здесь не читается: {@code readOnly} держит блокировку документа, и ввод
 * в редакторе ждёт фоновый расчёт. Снимок текста — {@code IDocument.get()} в UI-потоке, без
 * блокировки модели. Расчёты — по одному ({@link #SERIAL}). Готовые состояния родной модели
 * редактора потребитель читает сам — по согласованию с автором.
 */
final class ForeignModuleModel
{
    /** Расчёты по одному: модель BSL на общих ресурсах не потокобезопасна. */
    static final ISchedulingRule SERIAL = new ISchedulingRule()
    {
        @Override
        public boolean contains(ISchedulingRule rule)
        {
            return rule == this;
        }

        @Override
        public boolean isConflicting(ISchedulingRule rule)
        {
            return rule == this;
        }
    };

    /** Результат чтения и откуда взят текст модуля: {@code editor} (снимок) или {@code disk}. */
    record Read<T>(T value, String source)
    {
    }

    private ForeignModuleModel()
    {
    }

    static IFile fileOf(URI uri)
    {
        if (uri == null || !uri.isPlatformResource())
            return null;
        IFile file = ResourcesPlugin.getWorkspace().getRoot()
            .getFile(new Path(uri.toPlatformString(true)));
        return file.exists() ? file : null;
    }

    /** Документ уже открытого редактора модуля или {@code null}. Только UI-поток. */
    static IXtextDocument openDocument(IFile file)
    {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference reference : page.getEditorReferences())
                {
                    IEditorPart part = reference.getEditor(false);
                    BslXtextEditor editor = part != null ? GetRef.getActiveBslEditor(part) : null;
                    if (editor == null || editor.getEditorInput() == null)
                        continue;
                    if (file.equals(editor.getEditorInput().getAdapter(IFile.class)))
                        return editor.getDocument();
                }
            }
        }
        return null;
    }

    /** Модуль открыт в редакторе с несохранёнными правками. UI-поток. */
    static boolean isDirty(IFile file)
    {
        for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                for (IEditorReference reference : page.getEditorReferences())
                {
                    IEditorPart part = reference.getEditor(false);
                    BslXtextEditor editor = part != null ? GetRef.getActiveBslEditor(part) : null;
                    if (editor != null && editor.getEditorInput() != null
                        && file.equals(editor.getEditorInput().getAdapter(IFile.class)) && editor.isDirty())
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * Штамп состояния модуля: документа открытого редактора ({@code d…}, несохранённые правки),
     * иначе файла ({@code f…}).
     */
    static String stamp(IFile file, IXtextDocument document)
    {
        return document instanceof IDocumentExtension4 extension
            ? "d" + extension.getModificationStamp() //$NON-NLS-1$
            : "f" + file.getModificationStamp(); //$NON-NLS-1$
    }

    /**
     * Снимок текста открытого редактора без блокировки модели; {@code null} — редактора нет.
     * UI-поток.
     */
    static String snapshot(IXtextDocument document)
    {
        return document != null ? document.get() : null;
    }

    /**
     * Полный расчёт модуля в новом наборе ресурсов вне UI-потока: из снимка {@code text}, если он
     * есть, иначе с диска. {@code reader} получает рассчитанный ресурс модуля.
     */
    static <T> Read<T> read(URI uri, IFile file, String text, Function<Resource, T> reader)
    {
        String source = text != null ? "editor" : "disk"; //$NON-NLS-1$ //$NON-NLS-2$
        IResourceServiceProvider rsp =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
        if (rsp == null)
            return new Read<>(null, source);
        ResourceSet set = BslOccurrenceContextResolver.newLiveResourceSet(rsp, file.getProject());
        if (set == null)
            return new Read<>(null, source);
        try
        {
            if (text != null && !loadText(set, uri, text))
                return new Read<>(null, source);
            XtextResource resource = BslOccurrenceContextResolver.loadLinkedResource(uri, set);
            return new Read<>(reader.apply(resource), source);
        }
        finally
        {
            set.getResources().clear();
        }
    }

    /**
     * Только разбор модуля, без связывания и расчёта типов (быстро) — для того, что видно по
     * синтаксису и комментариям. Новый набор ресурсов, из снимка {@code text} или с диска.
     */
    static <T> T parse(URI uri, IFile file, String text, Function<Resource, T> reader)
    {
        IResourceServiceProvider rsp =
            IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
        if (rsp == null)
            return null;
        ResourceSet set = BslOccurrenceContextResolver.newLiveResourceSet(rsp, file.getProject());
        if (set == null)
            return null;
        try
        {
            if (text != null && !loadText(set, uri, text))
                return null;
            return reader.apply(set.getResource(uri, true));
        }
        finally
        {
            set.getResources().clear();
        }
    }

    /** Ресурс модуля из текста: дальше {@code loadLinkedResource} найдёт его уже загруженным. */
    private static boolean loadText(ResourceSet set, URI uri, String text)
    {
        Resource resource = set.createResource(uri);
        if (resource == null)
            return false;
        Map<Object, Object> options = new HashMap<>(set.getLoadOptions());
        options.put(XtextResource.OPTION_ENCODING, StandardCharsets.UTF_8.name());
        try
        {
            resource.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), options);
            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }
}
