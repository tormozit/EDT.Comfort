package tormozit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Орфография в HTML-редакторе форматированной строки окна «Строки на разных языках».
 * Точки подключения проверены в mdui: FormattedTextEditor.getFormattedTextEditableView(),
 * FormattedTextViewFx.getWebEngine(), wysiwygeditor.getElement()/insertHTML()/sync().
 * Canvas — сосед body, а не его потомок: generateFormattedText(Document) обходит только body.
 * Текстовые узлы при отрисовке не меняются; каретка, ссылки и штатные операции остаются у EDT.
 */
public final class FormattedTextEditorHook implements IStartup
{
    private static final String EDITOR_CLASS =
        "com._1c.g5.v8.dt.md.ui.editor.formattedtext.FormattedTextEditor"; //$NON-NLS-1$
    private static final String DIALOG_CLASS = "com._1c.g5.v8.dt.ui.dialog.LocalStringDialog"; //$NON-NLS-1$
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong REVISION = new AtomicLong();
    private static final Map<Control, EditorSession> SESSIONS = new HashMap<>();
    private static boolean installed;

    static void invalidateSpelling()
    {
        REVISION.incrementAndGet();
    }

    static long spellingRevision()
    {
        return REVISION.get();
    }

    /** Те же HTML-подчёркивания и меню в браузерных страницах редактора справки. */
    static void installHelpBrowser(Browser browser, boolean editable)
    {
        if (browser == null || browser.isDisposed() || SESSIONS.containsKey(browser))
            return;
        SESSIONS.put(browser, new EditorSession(browser, null, browser, editable));
    }

    @Override
    public void earlyStartup()
    {
        if (!ComfortJdtAvailability.isJdtUiAvailable())
            return;
        Display display = Display.getDefault();
        display.asyncExec(() ->
        {
            if (installed || display.isDisposed())
                return;
            installed = true;
            HelpEditorSupport.install();
            pulse(display);
        });
    }

    private static boolean hasType(Object value, String name)
    {
        if (value == null)
            return false;
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass())
            if (name.equals(type.getName()))
                return true;
        return false;
    }

    private static void pulse(Display display)
    {
        if (display.isDisposed())
            return;
        try
        {
            boolean enabled = SpellCheckHook.isComfortPlatformSpellingActive();
            SESSIONS.keySet().removeIf(Control::isDisposed);
            for (Shell shell : display.getShells())
                if (shell.isVisible() && hasType(shell.getData(), DIALOG_CLASS))
                    collectEditors(shell, enabled);
            for (EditorSession session : List.copyOf(SESSIONS.values()))
                if (!session.control.isDisposed() && session.control.isVisible())
                    session.poll(enabled);
        }
        catch (Exception | LinkageError e)
        {
        }
        display.timerExec(400, () -> pulse(display));
    }

    private static void collectEditors(Control control, boolean enabled)
    {
        if (enabled && hasType(control, EDITOR_CLASS) && !SESSIONS.containsKey(control))
        {
            Object view = Global.invoke(control, "getFormattedTextEditableView"); //$NON-NLS-1$
            if (view != null)
            {
                SESSIONS.put(control, new EditorSession(control, view));
            }
        }
        if (control instanceof Composite composite)
            for (Control child : composite.getChildren())
                collectEditors(child, enabled);
    }

    private static boolean runOnFx(Runnable action)
    {
        try
        {
            Class<?> platform = Class.forName("javafx.application.Platform"); //$NON-NLS-1$
            platform.getMethod("runLater", Runnable.class).invoke(null, action); //$NON-NLS-1$
            return true;
        }
        catch (Exception | LinkageError e)
        {
            return false;
        }
    }

    private static final class EditorSession
    {
        final Control control;
        final Object view;
        final Browser browser;
        final boolean editable;
        final Display display;
        final AtomicBoolean polling = new AtomicBoolean();
        volatile boolean disposed;
        String lastText;
        String lastHtml;
        String lastInstance;
        long lastRevision = -1;
        long lastEdit = -1;
        boolean lastEnabled;
        Menu menu;

        EditorSession(Control control, Object view)
        {
            this(control, view, null, true);
        }

        EditorSession(Control control, Object view, Browser browser, boolean editable)
        {
            this.control = control;
            this.view = view;
            this.browser = browser;
            this.editable = editable;
            display = control.getDisplay();
            control.addDisposeListener(event ->
            {
                disposed = true;
                if (menu != null && !menu.isDisposed())
                    menu.dispose();
            });
        }

        Object execute(String script) throws Exception
        {
            if (disposed)
                return null;
            if (browser != null)
                return browser.evaluate("return eval(" + JSON.writeValueAsString(script) + ");"); //$NON-NLS-1$ //$NON-NLS-2$
            Object engine = Global.invoke(view, "getWebEngine"); //$NON-NLS-1$
            return Global.invoke(engine, "executeScript", script); //$NON-NLS-1$
        }

        boolean dispatch(Runnable action)
        {
            if (browser == null)
                return runOnFx(action);
            if (disposed || display.isDisposed())
                return false;
            // SWT Browser.evaluate и виджетное меню работают в одном UI-потоке.
            display.asyncExec(action);
            return true;
        }

        void poll(boolean enabled)
        {
            if (!polling.compareAndSet(false, true))
                return;
            boolean scheduled = dispatch(() ->
            {
                try
                {
                    String options = browser == null ? "" : //$NON-NLS-1$
                        "window.comfortSpellingOptions={preview:" + !editable + "};\n"; //$NON-NLS-1$ //$NON-NLS-2$
                    Object result = execute(options + SCRIPT + "\nwindow.comfortSpelling ? " //$NON-NLS-1$
                        + "window.comfortSpelling.snapshot(" + enabled + ") : null;"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (result instanceof String snapshot && !display.isDisposed())
                    {
                        Point anchor = keyboardAnchor(snapshot);
                        display.asyncExec(() -> acceptSnapshot(snapshot, enabled, anchor));
                        return;
                    }
                }
                catch (Exception | LinkageError e)
                {
                }
                polling.set(false);
            });
            if (!scheduled)
                polling.set(false);
        }

        /** CSS → координаты сцены JavaFX, только в FX-потоке. */
        Point keyboardAnchor(String snapshot) throws Exception
        {
            JsonNode request = JSON.readTree(snapshot).path("request"); //$NON-NLS-1$
            if (!request.path("keyboard").asBoolean()) //$NON-NLS-1$
                return null;
            if (browser != null)
                return new Point((int)Math.round(request.path("x").asDouble()), //$NON-NLS-1$
                    (int)Math.round(request.path("y").asDouble())); //$NON-NLS-1$
            Object webView = Global.getField(view, "webView"); //$NON-NLS-1$
            Object zoomValue = Global.invoke(webView, "getZoom"); //$NON-NLS-1$
            if (!(zoomValue instanceof Number zoom))
                return null;
            Object point = Global.invoke(webView, "localToScene", //$NON-NLS-1$
                request.path("x").asDouble() * zoom.doubleValue(), //$NON-NLS-1$
                request.path("y").asDouble() * zoom.doubleValue()); //$NON-NLS-1$
            Object x = Global.invoke(point, "getX"); //$NON-NLS-1$
            Object y = Global.invoke(point, "getY"); //$NON-NLS-1$
            return x instanceof Number px && y instanceof Number py
                ? new Point((int)Math.round(px.doubleValue()), (int)Math.round(py.doubleValue())) : null;
        }

        void acceptSnapshot(String snapshot, boolean enabled, Point anchor)
        {
            try
            {
                if (disposed)
                    return;
                JsonNode data = JSON.readTree(snapshot);
                String text = data.path("text").asText(); //$NON-NLS-1$
                String html = data.path("html").asText(); //$NON-NLS-1$
                long revision = REVISION.get();
                long edit = data.path("edit").asLong(); //$NON-NLS-1$
                String instance = data.path("instance").asText(); //$NON-NLS-1$
                if (enabled != lastEnabled || !text.equals(lastText) || !html.equals(lastHtml)
                    || revision != lastRevision || edit != lastEdit || !instance.equals(lastInstance))
                {
                    List<int[]> errors = enabled ? ComfortSpellingEngine.findMisspelledRanges(text) : List.of();
                    String arguments = JSON.writeValueAsString(List.of(text, html, errors));
                    dispatch(() ->
                    {
                        try
                        {
                            execute("window.comfortSpelling && window.comfortSpelling.apply(" //$NON-NLS-1$
                                + arguments + ");"); //$NON-NLS-1$
                        }
                        catch (Exception | LinkageError e)
                        {
                        }
                    });
                    lastText = text;
                    lastHtml = html;
                    lastRevision = revision;
                    lastEdit = edit;
                    lastInstance = instance;
                    lastEnabled = enabled;
                }
                JsonNode request = data.path("request"); //$NON-NLS-1$
                if (enabled && request.isObject())
                    showMenu(request, anchor);
            }
            catch (Exception | LinkageError e)
            {
            }
            finally
            {
                polling.set(false);
            }
        }

        void showMenu(JsonNode request, Point anchor)
        {
            Point location;
            if (request.path("keyboard").asBoolean()) //$NON-NLS-1$
            {
                Object canvas = browser != null ? browser : Global.getField(view, "canvas"); //$NON-NLS-1$
                if (anchor == null || !(canvas instanceof Control host) || host.isDisposed())
                {
                    return;
                }
                location = host.toDisplay(anchor);
            }
            else
                location = display.getCursorLocation();
            if (menu != null && !menu.isDisposed())
                menu.dispose();
            String word = request.path("word").asText(); //$NON-NLS-1$
            long id = request.path("id").asLong(); //$NON-NLS-1$
            Menu current = new Menu(control);
            menu = current;
            current.addListener(SWT.Hide, event -> display.asyncExec(() ->
            {
                if (!current.isDisposed())
                    current.dispose();
            }));
            MenuItem add = new MenuItem(current, SWT.PUSH);
            add.setText("Добавить в словарь: " + word); //$NON-NLS-1$
            ComfortSubmenuHelper.setMenuItemTooltip(add, "Добавить слово в пользовательский словарь"); //$NON-NLS-1$
            add.addListener(SWT.Selection, event -> display.asyncExec(() ->
            {
                if (!disposed)
                {
                    ComfortSpellingEngine.addUserWordFromUi(word);
                    invalidateSpelling();
                }
            }));
            MenuItem waiting = new MenuItem(current, SWT.PUSH);
            waiting.setText("Поиск вариантов…"); //$NON-NLS-1$
            waiting.setEnabled(false);
            Job job = new Job("Комфорт: варианты орфографии") //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        List<String> suggestions = ComfortSpellingEngine.suggest(word, 12);
                        if (!display.isDisposed())
                            display.asyncExec(() ->
                            {
                                if (disposed || current.isDisposed())
                                    return;
                                waiting.dispose();
                                for (String suggestion : suggestions)
                                {
                                    MenuItem item = new MenuItem(current, SWT.PUSH);
                                    item.setText(suggestion.replace("&", "&&")); //$NON-NLS-1$ //$NON-NLS-2$
                                    item.setEnabled(editable);
                                    ComfortSubmenuHelper.setMenuItemTooltip(item, editable ? "Заменить ошибочное слово" //$NON-NLS-1$
                                        : "Для замены слова откройте страницу «Редактирование» или «Текст»"); //$NON-NLS-1$
                                    item.addListener(SWT.Selection, event -> replace(id, suggestion));
                                }
                                if (suggestions.isEmpty())
                                {
                                    MenuItem empty = new MenuItem(current, SWT.PUSH);
                                    empty.setText("Нет вариантов замены"); //$NON-NLS-1$
                                    empty.setEnabled(false);
                                }
                            });
                        return Status.OK_STATUS;
                    }
                    catch (Exception | LinkageError e)
                    {
                        return Status.CANCEL_STATUS;
                    }
                }
            };
            job.setSystem(true);
            current.addDisposeListener(event -> job.cancel());
            job.schedule();
            current.setLocation(location);
            current.setVisible(true);
        }

        void replace(long id, String word)
        {
            try
            {
                String argument = JSON.writeValueAsString(word);
                dispatch(() ->
                {
                    try
                    {
                        execute("window.comfortSpelling && window.comfortSpelling.replace(" //$NON-NLS-1$
                            + id + "," + argument + ");"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    catch (Exception | LinkageError e)
                    {
                    }
                });
            }
            catch (Exception e)
            {
            }
        }
    }

    /** ES5: WebKit EDT; никаких узлов/атрибутов орфографии внутри сохраняемого body. */
    private static final String SCRIPT = """
        (function () {
            var options = window.comfortSpellingOptions || {};
            if (window.comfortSpelling || (!options.preview && !window.wysiwygeditor) || !document.body) return;
            var body = options.preview ? document.body : wysiwygeditor.getElement();
            if (!body || !document.body.contains(body) || (!options.preview && !body.isContentEditable)) return;
            var editable = !options.preview && body.isContentEditable;
            var instance = String(Date.now()) + ':' + Math.random();
            var canvas = document.createElement('canvas');
            canvas.style.cssText = 'position:fixed;left:0;top:0;pointer-events:none;z-index:2147483647';
            canvas.setAttribute('aria-hidden', 'true');
            document.documentElement.appendChild(canvas);
            var tooltip = document.createElement('div');
            tooltip.style.cssText = 'display:none;position:fixed;pointer-events:none;z-index:2147483647;' +
                'max-width:500px;box-sizing:border-box;padding:6px 8px;border:1px solid #777;' +
                'background:#ffffe1;color:#111;font:13px sans-serif;white-space:normal';
            tooltip.textContent = 'Возможно, орфографическая ошибка. Правая кнопка мыши — варианты замены ' +
                'и добавление в словарь. Ctrl+1 — те же команды при каретке в слове.';
            if (!editable) tooltip.textContent = 'Возможно, орфографическая ошибка. Правая кнопка мыши — ' +
                'варианты написания и добавление в словарь. Для замены слова откройте страницу «Редактирование» или «Текст».';
            document.documentElement.appendChild(tooltip);
            var context = canvas.getContext('2d'), nodes = [], text = '', errors = [];
            var enabled = false, pending = null, held = null, nextId = 0, frame = false, edit = 0;
            var secondaryRequested = false;
            var tooltipTimer = null, tooltipError = null;
            function hideTooltip() {
                if (tooltipTimer !== null) window.clearTimeout(tooltipTimer);
                tooltipTimer = null; tooltipError = null; tooltip.style.display = 'none';
            }
            function showTooltip(x, y, error) {
                tooltipTimer = null;
                if (!enabled || errorAt(x, y) !== error) { hideTooltip(); return; }
                tooltip.style.display = 'block';
                var left = Math.max(0, Math.min(x + 12,
                    document.documentElement.clientWidth - tooltip.offsetWidth));
                var top = y + 18;
                if (top + tooltip.offsetHeight > document.documentElement.clientHeight)
                    top = Math.max(0, y - tooltip.offsetHeight - 8);
                tooltip.style.left = left + 'px'; tooltip.style.top = top + 'px';
            }
            function errorAt(x, y) {
                if (!enabled) return null;
                for (var i = 0; i < errors.length; i++) {
                    var rects = errors[i].range.getClientRects();
                    for (var j = 0; j < rects.length; j++) {
                        var r = rects[j];
                        if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return errors[i];
                    }
                }
                return null;
            }
            function gather() {
                var previous = nodes;
                nodes = []; text = '';
                function newline() { if (text && text.charAt(text.length - 1) !== '\\n') text += '\\n'; }
                function visit(node) {
                    if (node.nodeType === 3) {
                        nodes.push({node:node, start:text.length, length:node.nodeValue.length});
                        text += node.nodeValue;
                    } else if (node.nodeType === 1) {
                        if (/^(SCRIPT|STYLE)$/.test(node.tagName)) return;
                        if (node.tagName === 'BR') { text += '\\n'; return; }
                        var block = /^(DIV|P|LI|UL|OL|H[1-6]|TR|BLOCKQUOTE)$/.test(node.tagName);
                        if (block) newline();
                        for (var child = node.firstChild; child; child = child.nextSibling) visit(child);
                        if (block) newline();
                    }
                }
                visit(body);
                var changed = previous.length !== nodes.length;
                for (var i = 0; !changed && i < nodes.length; i++)
                    changed = previous[i].node !== nodes[i].node || previous[i].start !== nodes[i].start ||
                        previous[i].length !== nodes[i].length;
                // setHTML при переключении страниц пересоздаёт узлы даже при неизменном HTML.
                // Прежние DOM Range после этого больше не указывают на слова редактора.
                if (changed) {
                    edit++; errors = []; pending = null; held = null; hideTooltip();
                }
            }
            function rangeAt(start, length) {
                var first = null, last = null, end = start + length;
                for (var i = 0; i < nodes.length; i++) {
                    var n = nodes[i];
                    if (!first && start >= n.start && start < n.start + n.length) first = n;
                    if (end > n.start && end <= n.start + n.length) last = n;
                }
                if (!first || !last) return null;
                var range = document.createRange();
                range.setStart(first.node, start - first.start);
                range.setEnd(last.node, end - last.start);
                return range;
            }
            function repaint() {
                frame = false;
                // Client rects и canvas — в одних CSS-координатах; zoom WebView масштабирует оба.
                var width = document.documentElement.clientWidth, height = document.documentElement.clientHeight;
                var ratio = window.devicePixelRatio || 1;
                canvas.width = Math.ceil(width * ratio); canvas.height = Math.ceil(height * ratio);
                canvas.style.width = width + 'px'; canvas.style.height = height + 'px';
                context.scale(ratio, ratio);
                if (!enabled) return;
                context.strokeStyle = '#e04040'; context.lineWidth = 1;
                for (var i = 0; i < errors.length; i++) {
                    var rects = errors[i].range.getClientRects();
                    for (var j = 0; j < rects.length; j++) {
                        var r = rects[j], y = r.bottom - 1;
                        if (r.width <= 0 || y < 0 || r.top >= height) continue;
                        context.beginPath(); context.moveTo(r.left, y);
                        for (var x = r.left; x < r.right; x += 4) {
                            context.lineTo(Math.min(x + 2, r.right), y - 2);
                            context.lineTo(Math.min(x + 4, r.right), y);
                        }
                        context.stroke();
                    }
                }
            }
            function schedulePaint() {
                if (!frame) { frame = true; window.requestAnimationFrame(repaint); }
            }
            function request(error, event) {
                hideTooltip();
                held = {id:++nextId, word:error.word, range:error.range.cloneRange(), html:body.innerHTML};
                var keyboard = event.type === 'keydown', rect = error.range.getBoundingClientRect();
                pending = {id:held.id, word:held.word, keyboard:keyboard, x:rect.left, y:rect.bottom};
                event.preventDefault(); event.stopImmediatePropagation();
            }
            // Штатный wysiwyg.js тоже определяет ПКМ по mousedown/mouseup (button === 2).
            // Не ждём только contextmenu: оно проходит отдельным путём в JavaFX WebView.
            document.addEventListener('mousedown', function (event) {
                var error = errorAt(event.clientX, event.clientY);
                secondaryRequested = false;
                hideTooltip();
                if (event.button === 2 && error) {
                    secondaryRequested = true; request(error, event);
                }
            }, true);
            document.addEventListener('contextmenu', function (event) {
                var error = errorAt(event.clientX, event.clientY);
                if (secondaryRequested) {
                    secondaryRequested = false;
                    event.preventDefault(); event.stopImmediatePropagation();
                } else if (error) request(error, event);
            }, true);
            document.addEventListener('mousemove', function (event) {
                var error = errorAt(event.clientX, event.clientY);
                if (!error) { hideTooltip(); return; }
                // Открытая подсказка остаётся на месте, пока указатель внутри того же слова.
                if (tooltipError === error && tooltip.style.display === 'block') return;
                hideTooltip(); tooltipError = error;
                var x = event.clientX, y = event.clientY;
                // Показываем после остановки указателя; движение до открытия начинает ожидание заново.
                tooltipTimer = window.setTimeout(function () { showTooltip(x, y, error); }, 500);
            }, true);
            body.addEventListener('mouseleave', hideTooltip, false);
            window.addEventListener('blur', hideTooltip, false);
            document.addEventListener('keydown', function (event) {
                hideTooltip();
                if (!enabled || !event.ctrlKey || event.altKey || event.shiftKey || event.keyCode !== 49) return;
                var selection = window.getSelection();
                if (!selection.rangeCount || !body.contains(selection.focusNode)) return;
                for (var i = 0; i < errors.length; i++) {
                    var r = errors[i].range;
                    if (r.comparePoint(selection.focusNode, selection.focusOffset) === 0) {
                        request(errors[i], event); return;
                    }
                }
            }, true);
            function viewportChanged() { hideTooltip(); schedulePaint(); }
            document.addEventListener('scroll', viewportChanged, true);
            window.addEventListener('resize', viewportChanged, false);
            body.addEventListener('input', function () {
                hideTooltip();
                edit++;
                errors = []; pending = null; held = null; schedulePaint();
            }, false);
            window.comfortSpelling = {
                snapshot:function (active) {
                    enabled = active;
                    // При перестройке страницы EDT может снять и внешний слой.
                    if (canvas.parentNode !== document.documentElement) {
                        document.documentElement.appendChild(canvas); edit++;
                    }
                    if (tooltip.parentNode !== document.documentElement)
                        document.documentElement.appendChild(tooltip);
                    if (!active) { errors = []; pending = null; held = null; hideTooltip(); }
                    gather(); schedulePaint();
                    var result = JSON.stringify({text:text, html:body.innerHTML, edit:edit, instance:instance,
                        page:options.preview ? 'preview' : 'editing',
                        request:pending});
                    pending = null;
                    return result;
                },
                apply:function (data) {
                    gather();
                    if (text !== data[0] || body.innerHTML !== data[1]) return false;
                    hideTooltip();
                    errors = [];
                    for (var i = 0; i < data[2].length; i++) {
                        var e = data[2][i], range = rangeAt(e[0], e[1]);
                        if (range) errors.push({range:range, word:text.substr(e[0], e[1])});
                    }
                    schedulePaint(); return true;
                },
                replace:function (id, replacement) {
                    if (!editable || !enabled || !held || held.id !== id || body.innerHTML !== held.html ||
                        held.range.toString() !== held.word) return false;
                    var range = held.range, fragment = range.cloneContents();
                    var walker = document.createTreeWalker(fragment, 4, null, false), parts = [], node;
                    while ((node = walker.nextNode())) parts.push(node);
                    // Сохраняем вложенные теги; новую длину распределяем по прежним текстовым узлам.
                    var offset = 0;
                    for (var i = 0; i < parts.length; i++) {
                        var count = i === parts.length - 1 ? replacement.length - offset :
                            Math.min(parts[i].nodeValue.length, replacement.length - offset);
                        parts[i].nodeValue = replacement.substr(offset, count); offset += count;
                    }
                    var holder = document.createElement('div'); holder.appendChild(fragment);
                    body.focus();
                    var selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
                    // insertHTML использует штатный execCommand: уведомления EDT и стек отмены.
                    wysiwygeditor.insertHTML(holder.innerHTML); wysiwygeditor.sync();
                    held = null; errors = []; schedulePaint(); return true;
                }
            };
        })();
        """;

    /** Орфография трёх страниц MdHelpContentEditor; поля проверены в бандле html-ui. */
    private static final class HelpEditorSupport
    {
        private static final String EDITOR = "com._1c.g5.v8.dt.md.help.ui.editor.MdHelpContentEditor"; //$NON-NLS-1$
        private static boolean installed;

        static void install()
        {
            if (!ComfortJdtAvailability.isJdtUiAvailable())
                return;
            Display display = Display.getDefault();
            display.asyncExec(() ->
            {
                if (installed || display.isDisposed())
                    return;
                installed = true;
                scan(display);
            });
        }

        private static void scan(Display display)
        {
            if (display.isDisposed())
                return;
            try
            {
                boolean enabled = SpellCheckHook.isComfortPlatformSpellingActive();
                if (enabled)
                    for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows())
                        for (IWorkbenchPage page : window.getPages())
                            for (IEditorReference reference : page.getEditorReferences())
                            {
                                IEditorPart editor = reference.getEditor(false);
                                if (hasType(editor, EDITOR))
                                    attach(editor);
                            }
            }
            catch (Exception | LinkageError e)
            {
            }
            display.timerExec(600, () -> scan(display));
        }

        private static void attach(IEditorPart editor)
        {
            attachBrowser(Global.getField(editor, "wysiwygHtmlPage"), true); //$NON-NLS-1$
            attachBrowser(Global.getField(editor, "previewHtmlPage"), false); //$NON-NLS-1$
            Object source = Global.getField(editor, "textEditor"); //$NON-NLS-1$
            if (!(source instanceof ITextEditor textEditor))
                return;
            ISourceViewer viewer = TextEditor.getSourceViewer(textEditor);
            if (viewer == null)
                return;
            StyledText styled = viewer.getTextWidget();
            if (styled == null || styled.isDisposed() || StyledTextSpellCheck.isWired(styled))
                return;
            SourceSpelling spelling = new SourceSpelling(viewer);
            StyledTextSpellCheck.install(styled, spelling::ranges);
            styled.addListener(SWT.MouseMove, event ->
            {
                String tooltip = null;
                if (SpellCheckHook.isComfortPlatformSpellingActive())
                {
                    int offset = styled.getOffsetAtPoint(new org.eclipse.swt.graphics.Point(event.x, event.y));
                    StyledTextSpellCheck.WordSpan word = offset < 0 ? null : StyledTextSpellCheck.wordSpanAt(styled, offset);
                    if (word != null && word.misspelled)
                        tooltip = TooltipText.wrap(styled, "Возможно, орфографическая ошибка. " //$NON-NLS-1$
                            + "Правая кнопка мыши или Ctrl+1 — варианты замены и добавление в словарь."); //$NON-NLS-1$
                }
                if (!java.util.Objects.equals(styled.getToolTipText(), tooltip))
                    styled.setToolTipText(tooltip);
            });
        }

        private static void attachBrowser(Object page, boolean editable)
        {
            Object browser = Global.getField(page, "browser"); //$NON-NLS-1$
            if (browser instanceof Browser html)
                FormattedTextEditorHook.installHelpBrowser(html, editable);
        }

        private static final class SourceSpelling
        {
            final ISourceViewer viewer;
            String lastText;
            long lastRevision = -1;
            List<int[]> errors = List.of();

            SourceSpelling(ISourceViewer viewer)
            {
                this.viewer = viewer;
            }

            List<int[]> ranges(String visibleText)
            {
                IDocument document = viewer.getDocument();
                if (document == null)
                    return List.of();
                String text = document.get();
                long revision = FormattedTextEditorHook.spellingRevision();
                boolean cached = text.equals(lastText) && revision == lastRevision;
                if (!cached)
                {
                    errors = ComfortSpellingEngine.findMisspelledRanges(maskMarkup(text));
                    lastText = text;
                    lastRevision = revision;
                }
                List<int[]> result = new ArrayList<>();
                for (int[] range : errors)
                {
                    int start = range[0], end = start + range[1];
                    if (viewer instanceof ITextViewerExtension5 projection)
                    {
                        start = projection.modelOffset2WidgetOffset(start);
                        end = projection.modelOffset2WidgetOffset(end);
                    }
                    else
                    {
                        start -= viewer.getVisibleRegion().getOffset();
                        end -= viewer.getVisibleRegion().getOffset();
                    }
                    if (start >= 0 && end > start && end <= visibleText.length())
                        result.add(new int[] { start, end - start });
                }
                return result;
            }

            /** Маска сохраняет длину и модельные смещения, включая CRLF и сущности HTML. */
            static String maskMarkup(String text)
            {
                char[] masked = text.toCharArray();
                for (int i = 0; i < text.length();)
                {
                    int end = i;
                    if (text.startsWith("<!--", i)) //$NON-NLS-1$
                    {
                        int close = text.indexOf("-->", i + 4); //$NON-NLS-1$
                        end = close < 0 ? text.length() : close + 3;
                    }
                    else if (text.charAt(i) == '<' && i + 1 < text.length()
                        && (Character.isLetter(text.charAt(i + 1)) || "/!?".indexOf(text.charAt(i + 1)) >= 0)) //$NON-NLS-1$
                    {
                        char quote = 0;
                        end = i + 1;
                        while (end < text.length())
                        {
                            char c = text.charAt(end++);
                            if (quote != 0)
                            {
                                if (c == quote) quote = 0;
                            }
                            else if (c == '\'' || c == '"') quote = c;
                            else if (c == '>') break;
                        }
                        for (String raw : List.of("script", "style")) //$NON-NLS-1$ //$NON-NLS-2$
                            if (text.regionMatches(true, i, "<" + raw, 0, raw.length() + 1) //$NON-NLS-1$
                                && i + raw.length() + 1 < text.length()
                                && !Character.isLetterOrDigit(text.charAt(i + raw.length() + 1)))
                            {
                                int close = end;
                                while (close < text.length()
                                    && !text.regionMatches(true, close, "</" + raw, 0, raw.length() + 2)) close++; //$NON-NLS-1$
                                end = close;
                            }
                    }
                    else if (text.charAt(i) == '&')
                    {
                        int cursor = i + 1;
                        while (cursor < text.length() && (Character.isLetterOrDigit(text.charAt(cursor))
                            || text.charAt(cursor) == '#')) cursor++;
                        if (cursor > i + 1 && cursor < text.length() && text.charAt(cursor) == ';')
                            end = cursor + 1;
                    }
                    if (end == i) { i++; continue; }
                    for (; i < end; i++)
                        if (masked[i] != '\r' && masked[i] != '\n') masked[i] = ' ';
                }
                return new String(masked);
            }
        }
    }
}
