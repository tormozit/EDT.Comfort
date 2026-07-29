package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Дополняет текст текущей фоновой задачи в индикаторе строки состояния (тот самый узкий виджет
 * рядом с «1С:Напарник» — {@code org.eclipse.ui.internal.progress.ProgressCanvasViewer}, который
 * сам рисует текст через {@code LabelProvider}, а не через {@code Label}/{@code CLabel}) счётчиком
 * активных задач и процентами остальных: "N. Имя задачи NN%, п1%, п2%".
 *
 * <p>Подменяем {@code LabelProvider} этого JFace-вьювера на свой обёрточный — тогда вьювер сам
 * вызывает {@link BackgroundJobsLabelProvider#getText} при каждой штатной перерисовке (её вызывает
 * {@code ProgressViewerUpdater} при каждом обновлении прогресса задачи), никакого опроса/гонки за
 * запись не нужно.
 */
public class BackgroundJobsStatusLineHook implements IStartup
{
    private static final int RECHECK_MS = 3000;

    private static boolean isInstalled = false;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (isInstalled || display == null || display.isDisposed())
            return;
        isInstalled = true;
        scheduleRecheck(display);
    }

    /**
     * Периодически (не часто — это лишь подстраховка) проверяет, что LabelProvider вьювера всё ещё
     * наш обёрточный — окно/trim могут быть пересозданы (новое окно EDT, сброс перспективы и т.п.).
     */
    private static void scheduleRecheck(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.timerExec(RECHECK_MS, () ->
        {
            if (isWorkbenchClosing())
                return; // не переопланируем дальше — иначе зависание javaw.exe при закрытии (issue #130)

            try
            {
                ensureLabelProviderWrapped();
            }
            catch (Throwable ignored) {}
            finally
            {
                scheduleRecheck(display);
            }
        });
    }

    private static boolean isWorkbenchClosing()
    {
        return !PlatformUI.isWorkbenchRunning() || PlatformUI.getWorkbench().isClosing();
    }

    private static void ensureLabelProviderWrapped()
    {
        Object viewer = findActiveProgressCanvasViewer();
        if (viewer == null)
            return;
        Object currentProvider = Global.invoke(viewer, "getLabelProvider"); //$NON-NLS-1$
        if (currentProvider == null || currentProvider instanceof BackgroundJobsLabelProvider)
            return;
        Global.invoke(viewer, "setLabelProvider", new BackgroundJobsLabelProvider(currentProvider, viewer)); //$NON-NLS-1$
    }

    /**
     * {@code AnimationManager.getInstance().animationProcessor.items} — список всех {@code AnimationItem}
     * (в т.ч. {@code ProgressAnimationItem}, у которого есть {@code progressRegion.viewer}) через
     * рефлексию — внутренний пакет {@code org.eclipse.ui.internal.progress}, не экспортирован для
     * прямой компиляции. Предпочитаем элемент активного окна, иначе берём первый найденный.
     */
    private static Object findActiveProgressCanvasViewer()
    {
        try
        {
            ClassLoader loader = IStartup.class.getClassLoader(); // org.eclipse.ui.workbench, тот же бандл
            Class<?> animationManagerClass = Class.forName(
                "org.eclipse.ui.internal.progress.AnimationManager", true, loader); //$NON-NLS-1$
            Object animationManager = Global.invoke(animationManagerClass, "getInstance"); //$NON-NLS-1$
            Object processor = Global.getField(animationManager, "animationProcessor"); //$NON-NLS-1$
            Object itemsObj = Global.getField(processor, "items"); //$NON-NLS-1$
            if (!(itemsObj instanceof List))
                return null;

            IWorkbenchWindow activeWindow = PlatformUI.isWorkbenchRunning()
                ? PlatformUI.getWorkbench().getActiveWorkbenchWindow() : null;

            Object fallback = null;
            for (Object item : (List<?>) itemsObj)
            {
                Object progressRegion = Global.getField(item, "progressRegion"); //$NON-NLS-1$
                if (progressRegion == null)
                    continue;
                Object viewer = Global.getField(progressRegion, "viewer"); //$NON-NLS-1$
                if (viewer == null)
                    continue;
                if (fallback == null)
                    fallback = viewer;
                Object window = Global.getField(progressRegion, "workbenchWindow"); //$NON-NLS-1$
                if (activeWindow != null && window == activeWindow)
                    return viewer;
            }
            return fallback;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static final int MIN_TASK_NAME_LETTERS = 6;
    private static final String ELLIPSIS = "…"; //$NON-NLS-1$

    /**
     * @return {@code [prefix, taskName, suffix]}, где итоговый текст — {@code prefix + taskName + suffix},
     *         а усекать (в середине) можно только {@code taskName}; или {@code null}, если не наша задача.
     */
    private static String[] formatParts(Object mainJobInfo, Object[] jobInfos)
    {
        Integer mainPercent = percentOf(mainJobInfo);
        String taskName = jobName(mainJobInfo);
        if (taskName == null)
            return null;
        int mainPercentValue = mainPercent != null ? mainPercent : 0; // indeterminate (-1) — показываем как 0

        List<Integer> otherPercents = new ArrayList<>();
        for (Object jobInfo : jobInfos)
        {
            if (jobInfo == mainJobInfo)
                continue;
            Integer percent = percentOf(jobInfo);
            if (percent != null)
                otherPercents.add(percent);
        }

        StringBuilder suffix = new StringBuilder();
        suffix.append(' ').append(mainPercentValue).append('%');
        for (int percent : otherPercents)
            suffix.append(", ").append(percent).append('%'); //$NON-NLS-1$

        String prefix = jobInfos.length + ". "; //$NON-NLS-1$
        return new String[] { prefix, taskName, suffix.toString() };
    }

    /**
     * Подгоняет {@code prefix + name + suffix} под доступную ширину {@code control} через
     * {@link GC#textExtent}, усекая {@code name} по середине (вставляя {@value #ELLIPSIS}), но
     * оставляя не менее {@link #MIN_TASK_NAME_LETTERS} букв самого имени задачи.
     */
    private static String fitToControlWidth(Control control, String prefix, String name, String suffix)
    {
        String full = prefix + name + suffix;
        if (control == null || control.isDisposed())
            return full;
        int available = control.getBounds().width; // getClientArea() есть только у Scrollable, не у Control
        if (available <= 0)
            return full;

        GC gc = new GC(control);
        try
        {
            gc.setFont(control.getFont());
            if (gc.textExtent(full).x <= available)
                return full;

            for (int keep = name.length() - 1; keep >= MIN_TASK_NAME_LETTERS; keep--)
            {
                String shortened = middleTruncate(name, keep);
                String candidate = prefix + shortened + suffix;
                if (gc.textExtent(candidate).x <= available)
                    return candidate;
            }
            return prefix + middleTruncate(name, MIN_TASK_NAME_LETTERS) + suffix; // предел — как есть
        }
        finally
        {
            gc.dispose();
        }
    }

    /** Оставляет {@code keep} букв {@code text} (примерно поровну с начала и с конца) + многоточие в середине. */
    private static String middleTruncate(String text, int keep)
    {
        if (keep >= text.length())
            return text;
        int headLen = (keep + 1) / 2;
        int tailLen = keep - headLen;
        return text.substring(0, headLen) + ELLIPSIS + text.substring(text.length() - tailLen);
    }

    private static String jobName(Object jobInfo)
    {
        Object job = Global.invoke(jobInfo, "getJob"); //$NON-NLS-1$
        return job instanceof Job ? ((Job) job).getName() : null;
    }

    private static Integer percentOf(Object jobInfo)
    {
        Object percent = Global.invoke(jobInfo, "getPercentDone"); //$NON-NLS-1$
        if (!(percent instanceof Integer) || (Integer) percent < 0)
            return null;
        return (Integer) percent;
    }

    /** {@code org.eclipse.ui.internal.progress.ProgressManager.getInstance().getJobInfos(false)} через рефлексию. */
    private static Object[] activeJobInfos()
    {
        try
        {
            ClassLoader loader = IStartup.class.getClassLoader();
            Class<?> progressManagerClass = Class.forName(
                "org.eclipse.ui.internal.progress.ProgressManager", true, loader); //$NON-NLS-1$
            Object progressManager = Global.invoke(progressManagerClass, "getInstance"); //$NON-NLS-1$
            Object result = Global.invoke(progressManager, "getJobInfos", Boolean.FALSE); //$NON-NLS-1$
            return result instanceof Object[] ? (Object[]) result : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    /**
     * Обёртка над штатным {@code ProgressViewerLabelProvider} (internal, поэтому оборачиваем не
     * подклассом, а композицией + рефлексией на исходный провайдер). Наследуем публичный JFace
     * {@link LabelProvider}, чтобы объект реально был {@code ILabelProvider}/{@code IBaseLabelProvider}
     * без Proxy — вьювер проверяет конкретный тип при выборе стратегии рендеринга.
     */
    private static final class BackgroundJobsLabelProvider extends LabelProvider
    {
        private final Object original;
        private final Object viewer;

        BackgroundJobsLabelProvider(Object original, Object viewer)
        {
            this.original = original;
            this.viewer = viewer;
        }

        @Override
        public String getText(Object element)
        {
            Object base = Global.invoke(original, "getText", element); //$NON-NLS-1$
            String baseText = base instanceof String ? (String) base : null;

            if (jobName(element) == null) // не JobInfo (например GroupInfo) — не наша забота
                return baseText;
            Object[] jobInfos = activeJobInfos();
            if (jobInfos == null || jobInfos.length == 0)
                return baseText;
            // Префикс "N." показываем и при N=1 — иначе текст "прыгает" между форматами
            // при колебании числа задач около 1↔2

            String[] parts = formatParts(element, jobInfos);
            if (parts == null)
                return baseText;
            Object control = Global.invoke(viewer, "getControl"); //$NON-NLS-1$
            return fitToControlWidth(control instanceof Control ? (Control) control : null,
                parts[0], parts[1], parts[2]);
        }

        @Override
        public Image getImage(Object element)
        {
            Object image = Global.invoke(original, "getImage", element); //$NON-NLS-1$
            return image instanceof Image ? (Image) image : null;
        }
    }
}
