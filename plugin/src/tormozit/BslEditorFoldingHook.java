package tormozit;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.URI;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.model.RegionPreprocessor;

/**
 * Начальное сворачивание областей ({@code #Область}) по настройке «Автоматически сворачиваемые
 * области» (issue #527) — в том же месте, где EDT решает про штатные «Сворачивать автоматически»:
 * {@code BslFoldingRegionProvider.isInitiallyCollapsed(EObject)}. Тогда область рисуется свёрнутой
 * сразу, без кадра с развёрнутым текстом. Сворачивает и при открытии модуля, и по команде
 * «Сбросить сворачиваемые группы» ({@link ResetFoldingHandler}) — оба пути идут через
 * {@code initialize()} провайдера.
 *
 * <p>Вплетение в {@code isInitiallyCollapsed} делает ранний бандл {@code tormozit.comfort.bslparser}
 * (см. его README): класс провайдера свёрток грузится раньше, чем активируется основной бандл, и
 * {@code WeavingHook} отсюда его уже не успевает. Вплетённый код вызывает функцию, которую
 * регистрирует {@link #install()} в {@code System.getProperties()}.
 */
public final class BslEditorFoldingHook
{
    /** Ключ функции; должен совпадать с {@code PROP_INITIALLY_COLLAPSED} в {@code tormozit.bslparser.Activator}. */
    static final String PROP_INITIALLY_COLLAPSED = "tormozit.bslFolding.initiallyCollapsed"; //$NON-NLS-1$

    private static final AtomicBoolean installed = new AtomicBoolean();

    private BslEditorFoldingHook()
    {
    }

    /** Регистрация функции для вплетённого кода; как можно раньше из {@code Activator.start}. */
    public static void install()
    {
        if (!installed.compareAndSet(false, true))
            return;
        System.getProperties().put(PROP_INITIALLY_COLLAPSED,
            (Function<Object, Object>) BslEditorFoldingHook::isInitiallyCollapsed);
    }

    /** Вызов из вплетённого {@code isInitiallyCollapsed}: {@code element → TRUE/FALSE}. */
    public static Object isInitiallyCollapsed(Object element)
    {
        try
        {
            if (!(element instanceof RegionPreprocessor region))
                return Boolean.FALSE;
            Set<String> names = ComfortSettings.getAutoCollapseRegionNames();
            String name = region.getName();
            if (names.isEmpty() || name == null || !names.contains(name.toLowerCase(Locale.ROOT)))
                return Boolean.FALSE;
            // Область с запомненной кареткой не сворачиваем: восстановление позиции
            // (BslModulePositionMemoryHook) её тут же развернёт.
            return !containsSavedCaret(region);
        }
        catch (Throwable t)
        {
            // Любой сбой — штатное поведение EDT, свёртки не должны ломаться.
            return Boolean.FALSE;
        }
    }

    private static boolean containsSavedCaret(RegionPreprocessor region)
    {
        ICompositeNode node = NodeModelUtils.getNode(region);
        URI uri = region.eResource() != null ? region.eResource().getURI() : null;
        if (node == null || uri == null || !uri.isPlatformResource())
            return false;
        IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(
            new org.eclipse.core.runtime.Path(uri.toPlatformString(true)));
        int caretLine = BslModulePositionMemoryHook.savedCaretLine(file) + 1; // строки узла — с 1
        if (caretLine <= 0)
            return false;
        int endOffset = BracketContentHintIndex.resolveEndOffset(region, node);
        int endLine = endOffset > node.getOffset()
            ? NodeModelUtils.getLineAndColumn(node, endOffset - 1).getLine()
            : node.getStartLine();
        return caretLine >= node.getStartLine() && caretLine <= endLine;
    }
}
