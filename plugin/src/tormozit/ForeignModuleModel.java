package tormozit;

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
 * {@code linkBatched}): модель открытого редактора модуля, иначе полный расчёт в новом наборе
 * ресурсов с диска. Расчёты — по одному ({@link #SERIAL}).
 *
 * <p>Модель модуля из снимка текста открытого редактора не строить: её связывание и производное
 * состояние задевали общий объект модуля, и в других редакторах переставали разрешаться обращения
 * через точку (#877, 23.10.2026).
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
     * Чтение модели модуля вне UI-потока: сначала модель открытого редактора ({@code readOnly}),
     * если она не дала ответа — полный расчёт модуля с диска. {@code reader} получает ресурс модуля.
     */
    static <T> Read<T> read(URI uri, IFile file, IXtextDocument document,
        Function<Resource, T> reader)
    {
        T value = null;
        if (document != null)
            value = document.readOnly(resource -> reader.apply(resource));
        if (value != null)
            return new Read<>(value, "editor"); //$NON-NLS-1$
        return new Read<>(fromFullComputation(uri, file, reader), "full"); //$NON-NLS-1$
    }

    /** Полный расчёт модуля, как у редактора, в новом наборе ресурсов (с диска). */
    private static <T> T fromFullComputation(URI uri, IFile file, Function<Resource, T> reader)
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
            XtextResource resource = BslOccurrenceContextResolver.loadLinkedResource(uri, set);
            return reader.apply(resource);
        }
        finally
        {
            set.getResources().clear();
        }
    }
}
