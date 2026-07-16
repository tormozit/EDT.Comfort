package tormozit;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jface.preference.IPreferenceNode;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.preference.PreferenceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ExpandBar;
import org.eclipse.swt.widgets.ExpandItem;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;

/**
 * Индекс текстов контролов страниц диалога «Параметры», построенный офскрин,
 * чтобы фильтр в этом диалоге мог искать не только по заголовкам и keywords,
 * но и по подписям виджетов внутри самих страниц. Кэшируется на диск между
 * сессиями EDT — реальная (небыстрая) индексация запускается только по явному
 * клику пользователя (см. {@link PreferenceSearchFilterAugmenter}), никогда
 * автоматически.
 */
final class PreferenceSearchIndex
{
    enum IndexReadiness
    {
        /** Кэша на диске нет вообще — поиск по содержимому страниц недоступен. */
        NOT_BUILT,
        /** Кэш есть, но сигнатура (узлы + бандлы с версиями) не совпадает. */
        STALE,
        /** Кэш есть и полностью соответствует текущей сигнатуре. */
        FRESH
    }

    interface ProgressListener
    {
        void onProgress(int done, int total);
    }

    interface ReadinessListener
    {
        void onReadinessChanged(IndexReadiness readiness);
    }

    private enum IndexState
    {
        IN_PROGRESS, DONE, FAILED, SKIPPED_LIVE
    }

    private static final PreferenceSearchIndex INSTANCE = new PreferenceSearchIndex();

    /** Отсрочка dispose офскрин-страницы — см. комментарий в indexOneNode(). */
    private static final int DISPOSE_DELAY_MS = 3000;

    /**
     * Известные проблемные страницы — не индексируем офскрин вообще, чтобы не
     * триггерить их собственные баги. Как минимум ExternalMergeToolPreferencePage
     * и TeamPreferencePage (страницы «Внешняя программа сравнения» и «Команда»
     * 1C:EDT) создают Font в setBoltFont(), который сами никогда не диспозят —
     * SWT детектит утечку при финальном обходе ресурсов на закрытии главного
     * окна IDE (см. .metadata/.log runtime-воркспейса, !ENTRY org.eclipse.ui.ide).
     * Раз баг уже встретился в двух соседних internal.*.ui.preferences пакетах
     * 1C — похоже на общий скопированный метод, поэтому режем по префиксу
     * пакета, а не по одному id за раз.
     */
    private static final String[] BLACKLISTED_NODE_ID_PREFIXES = {
            "com._1c.g5.v8.dt.internal.compare.ui.preferences.", //$NON-NLS-1$
            "com._1c.g5.v8.dt.internal.team.ui.preferences."}; //$NON-NLS-1$

    private static boolean isBlacklisted(String nodeId)
    {
        if (nodeId == null)
            return false;
        for (String prefix : BLACKLISTED_NODE_ID_PREFIXES)
        {
            if (nodeId.startsWith(prefix))
                return true;
        }
        return false;
    }

    private final Map<String, Set<String>> textsByNodeId = new ConcurrentHashMap<>();

    private final Map<String, IndexState> stateByNodeId = new ConcurrentHashMap<>();

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final List<ReadinessListener> readinessListeners = new CopyOnWriteArrayList<>();

    private volatile IndexReadiness readiness;

    private volatile boolean rebuildInProgress;

    private volatile boolean rebuildCancelled;

    static PreferenceSearchIndex getInstance()
    {
        return INSTANCE;
    }

    interface Listener
    {
        void onNodeIndexed(String nodeId);
    }

    void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    void addReadinessListener(ReadinessListener listener)
    {
        readinessListeners.add(listener);
    }

    void removeReadinessListener(ReadinessListener listener)
    {
        readinessListeners.remove(listener);
    }

    IndexReadiness getReadiness()
    {
        return readiness;
    }

    boolean matches(String nodeId, String pattern)
    {
        if (nodeId == null || pattern == null || pattern.isBlank())
            return false;
        Set<String> texts = textsByNodeId.get(nodeId);
        if (texts == null || texts.isEmpty())
            return false;
        // Гранула совпадения — один контрол, а не страница целиком: все слова
        // фильтра должны найтись в тексте ОДНОГО и того же виджета, а не быть
        // размазаны по разным надписям страницы.
        SmartMatcher matcher = new SmartMatcher(pattern);
        for (String text : texts)
        {
            if (matcher.matches(text))
                return true;
        }
        return false;
    }

    /**
     * Только читает кэш с диска (дёшево, без создания виджетов) и определяет
     * состояние свежести — саму индексацию НИКОГДА не запускает. Вызывать
     * один раз за сессию (повторные вызовы — no-op, пока не будет закрыта
     * текущая сессия плагина).
     */
    synchronized void ensureLoaded(PreferenceManager manager, Display display)
    {
        if (readiness != null || manager == null)
            return;

        PreferenceSearchIndexCache.CacheData data = PreferenceSearchIndexCache.tryLoad();
        if (data == null)
        {
            setReadiness(IndexReadiness.NOT_BUILT);
            return;
        }

        textsByNodeId.putAll(data.textsByNodeId());
        for (String nodeId : data.textsByNodeId().keySet())
            stateByNodeId.put(nodeId, IndexState.DONE);

        String currentSignature = PreferenceSearchIndexCache.computeSignature(manager);
        setReadiness(currentSignature.equals(data.signature()) ? IndexReadiness.FRESH : IndexReadiness.STALE);
    }

    /**
     * Единственный путь реальной (небыстрой) индексации — вызывается ТОЛЬКО
     * по явному клику пользователя на кнопку обновления индекса. Проходит по
     * всем неблэклистованным узлам заново (не переиспользует предыдущее
     * состояние), сообщая прогресс через {@code progressListener}.
     */
    synchronized void forceRebuild(PreferenceManager manager, Display display, ProgressListener progressListener)
    {
        if (manager == null || display == null || display.isDisposed() || rebuildInProgress)
            return;
        rebuildInProgress = true;
        rebuildCancelled = false;
        textsByNodeId.clear();
        stateByNodeId.clear();

        List<IPreferenceNode> pending = new ArrayList<>();
        collectAllNodes(manager, pending);
        int total = pending.size();
        if (progressListener != null)
            progressListener.onProgress(0, total);
        if (total == 0)
        {
            finishRebuild(manager);
            return;
        }
        indexNextNode(pending.iterator(), display, total, new int[] {0}, manager, progressListener);
    }

    /** Останавливает текущую пересборку (если идёт) — не сохраняет частичный кэш. */
    void cancelRebuild()
    {
        rebuildCancelled = true;
    }

    @SuppressWarnings("unchecked")
    private void collectAllNodes(PreferenceManager manager, List<IPreferenceNode> out)
    {
        for (Object element : manager.getElements(PreferenceManager.POST_ORDER))
        {
            IPreferenceNode node = (IPreferenceNode)element;
            if (!isBlacklisted(node.getId()))
                out.add(node);
        }
    }

    private void indexNextNode(Iterator<IPreferenceNode> pending, Display display, int total,
            int[] doneCount, PreferenceManager manager, ProgressListener progressListener)
    {
        if (display.isDisposed() || rebuildCancelled)
        {
            rebuildInProgress = false;
            rebuildCancelled = false;
            return;
        }
        if (!pending.hasNext())
        {
            finishRebuild(manager);
            return;
        }
        IPreferenceNode node = pending.next();
        display.asyncExec(() ->
        {
            indexOneNode(node);
            doneCount[0]++;
            if (progressListener != null)
                progressListener.onProgress(doneCount[0], total);
            display.asyncExec(() -> indexNextNode(pending, display, total, doneCount, manager, progressListener));
        });
    }

    private void finishRebuild(PreferenceManager manager)
    {
        rebuildInProgress = false;
        String signature = PreferenceSearchIndexCache.computeSignature(manager);
        PreferenceSearchIndexCache.save(textsByNodeId, signature);
        setReadiness(IndexReadiness.FRESH);
    }

    private void setReadiness(IndexReadiness value)
    {
        readiness = value;
        for (ReadinessListener listener : readinessListeners)
        {
            try
            {
                listener.onReadinessChanged(value);
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    private void indexOneNode(IPreferenceNode node)
    {
        String id = node.getId();

        // Страница уже создана (например, сейчас показана в диалоге или
        // была открыта пользователем ранее) — её нельзя трогать офскрин-циклом
        // создания/dispose, чтобы не испортить рабочий инстанс.
        if (node.getPage() != null)
        {
            stateByNodeId.put(id, IndexState.SKIPPED_LIVE);
            return;
        }

        stateByNodeId.put(id, IndexState.IN_PROGRESS);

        Shell offscreen = null;
        IPreferencePage page = null;
        try
        {
            offscreen = new Shell(Display.getCurrent(), SWT.NO_TRIM);
            offscreen.setLocation(-10000, -10000);
            offscreen.setLayout(new org.eclipse.swt.layout.FillLayout());

            node.createPage();
            Object created = node.getPage();
            if (!(created instanceof IPreferencePage))
            {
                stateByNodeId.put(id, IndexState.FAILED);
                return;
            }
            page = (IPreferencePage)created;
            page.createControl(offscreen);

            Set<String> texts = new LinkedHashSet<>();
            addText(texts, node.getLabelText());
            if (page.getControl() instanceof Composite composite)
                collectTexts(composite, texts);

            textsByNodeId.put(id, texts);
            stateByNodeId.put(id, IndexState.DONE);
            fireNodeIndexed(id);
        }
        catch (Throwable t)
        {
            stateByNodeId.put(id, IndexState.FAILED);
            Global.tempLog("preferenceSearchIndex", "indexOneNode " + id + ": " + describe(t)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        finally
        {
            // Узел кэширует созданную страницу навсегда — без сброса кэша
            // реальный диалог позже получил бы уже disposed-инстанс.
            Global.invoke(node, "setPage", (Object)null); //$NON-NLS-1$

            // Не диспозим сразу: некоторые страницы (например, JDT
            // JavaPreview на странице форматирования Java) сами планируют
            // asyncExec/Job для отложенной инициализации внутри своего
            // createControl — если уничтожить offscreen немедленно, этот
            // отложенный код позже падает на уже disposed виджете прямо в
            // цикле событий диалога ("Unhandled event loop exception" в
            // .metadata/.log runtime-воркспейса). Даём короткую отсрочку.
            IPreferencePage disposePage = page;
            Shell disposeShell = offscreen;
            Display display = Display.getCurrent();
            if (display != null && !display.isDisposed())
            {
                display.timerExec(DISPOSE_DELAY_MS, () -> disposeOffscreen(disposePage, disposeShell));
            }
            else
            {
                disposeOffscreen(disposePage, disposeShell);
            }
        }
    }

    private static void disposeOffscreen(IPreferencePage page, Shell offscreen)
    {
        if (page != null)
        {
            try
            {
                page.dispose();
            }
            catch (Throwable ignored)
            {
            }
        }
        if (offscreen != null && !offscreen.isDisposed())
            offscreen.dispose();
    }

    private static String describe(Throwable t)
    {
        return t.getClass().getName() + ": " + t.getMessage(); //$NON-NLS-1$
    }

    private void collectTexts(Composite composite, Set<String> out)
    {
        for (Control child : composite.getChildren())
        {
            if (child instanceof Group group)
            {
                addText(out, fuseTextAndTooltip(group, group.getText()));
                collectTexts(group, out);
            }
            else if (child instanceof TabFolder tabFolder)
            {
                for (TabItem item : tabFolder.getItems())
                {
                    addText(out, item.getText());
                    if (item.getControl() instanceof Composite tabComposite)
                        collectTexts(tabComposite, out);
                }
            }
            else if (child instanceof CTabFolder tabFolder)
            {
                for (CTabItem item : tabFolder.getItems())
                {
                    addText(out, item.getText());
                    if (item.getControl() instanceof Composite tabComposite)
                        collectTexts(tabComposite, out);
                }
            }
            else if (child instanceof ExpandBar expandBar)
            {
                for (ExpandItem item : expandBar.getItems())
                    addText(out, item.getText());
            }
            else if (child instanceof Composite childComposite)
            {
                collectTexts(childComposite, out);
            }
            else if (child instanceof Label label)
            {
                addText(out, fuseTextAndTooltip(label, label.getText()));
            }
            else if (child instanceof Button button)
            {
                addText(out, fuseTextAndTooltip(button, button.getText()));
            }
            else if (child instanceof Link link)
            {
                addText(out, fuseTextAndTooltip(link, stripMarkup(link.getText())));
            }
            else if (child instanceof CLabel clabel)
            {
                addText(out, fuseTextAndTooltip(clabel, clabel.getText()));
            }
        }
    }

    /**
     * Склеивает текст контрола с его подсказкой (tooltip) в одну строку —
     * с точки зрения AND-поиска по словам это один атом: если часть слов
     * запроса лежит в подписи виджета, а часть — в его подсказке, элемент
     * всё равно должен считаться найденным целиком.
     */
    static String fuseTextAndTooltip(Control control, String text)
    {
        String tooltip = control.getToolTipText();
        StringBuilder sb = new StringBuilder();
        if (text != null && !text.isBlank())
            sb.append(text.strip());
        if (tooltip != null && !tooltip.isBlank())
        {
            if (sb.length() > 0)
                sb.append(' ');
            sb.append(tooltip.strip());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    static String stripMarkup(String text)
    {
        if (text == null)
            return null;
        return text.replaceAll("</?a[^>]*>", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void addText(Set<String> out, String text)
    {
        if (text == null)
            return;
        String trimmed = text.strip();
        if (!trimmed.isEmpty())
            out.add(trimmed.toLowerCase(Locale.ROOT));
    }

    private void fireNodeIndexed(String nodeId)
    {
        for (Listener listener : listeners)
        {
            try
            {
                listener.onNodeIndexed(nodeId);
            }
            catch (Throwable ignored)
            {
            }
        }
    }
}
