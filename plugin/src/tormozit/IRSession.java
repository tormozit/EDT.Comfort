package tormozit;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.TextRegion;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

public final class IRSession
    {
        public final IRApplication.State state;
        public final LocalDateTime startTime;
        public final long pid;
        public final String platformVersion;
        final Object root; // V8X.Application
        final Object processObj; // WMIProcess
        public String appTitle;
        public IProject project;
        public final ExecutorService executor; // Выделенный поток для всех операций с этой COM-сессией
        /** Не null, если ИР подключён портативно (ирПортативный.epf), а не через расширение.
         *  В этом случае getModule() использует эту форму вместо root (COM-приложения). */
        public Object moduleRoot = null;
        public InfobaseReference infobase;
        public Object codeEditor = null; // ирКлсПолеТекстаПрограммы
        public TextRegion changedTextRange = null;
        public String newTextOfRange = ""; 

        IRSession(IRApplication.State state, LocalDateTime startTime, long pid, String platformVersion,
                  Object root, Object processObj, String appTitle, IProject project, ExecutorService executor, InfobaseReference infobase)
        {
            this.state = state;
            this.startTime = startTime;
            this.pid = pid;
            this.platformVersion = platformVersion;
            this.root = root;
            this.processObj = processObj;
            this.appTitle = appTitle;
            this.project = project;
            this.executor = executor;
            this.infobase = infobase;
        }

        public Object getModule(String name)
        {
            return ComBridge.getProperty(moduleRoot != null ? moduleRoot : root, name);
        }
        public <T> T executeOnComThread(Callable<T> task) {
            if (executor == null || executor.isShutdown()) {
                throw new IllegalStateException("COM-executor не инициализирован или остановлен");
            }
            try {
                return executor.submit(task).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Прервано ожидание COM-потока", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException("Ошибка в COM-потоке", cause);
            } catch (TimeoutException e) {
                throw new RuntimeException("Таймаут ожидания COM-потока (10 сек)", e);
            }
        }

        public boolean isProcessAlive() {
            if (pid <= 0) return true; // pid неизвестен — не блокируем
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }

        public void syncCodeEditorToIR(BslXtextEditor editor) {
            ISourceViewer viewer = editor.getInternalSourceViewer();
            Object sel = viewer.getSelectionProvider().getSelection();
            ITextSelection textSelection = (ITextSelection) sel;
            IXtextDocument doc = (IXtextDocument) viewer.getDocument();
            final String moduleName = ""; // TODO: вычислить при необходимости
            final String text       = doc.get();
            final int offset        = textSelection.getOffset();
            final int endOffset     = offset + textSelection.getLength();

            // Инициализация и setText выполняются в COM-потоке, результат возвращается синхронно
            executeOnComThread(() -> {
                if (codeEditor == null) {
                    Object irCache = getModule("ирКэш");
                    codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0);
                }
                setText(text, moduleName, offset, endOffset);
                return null;
            });
        }
        public void syncCodeEditorFromIR(BslXtextEditor editor) {
            executeOnComThread(() -> {
                readChangedTextRange();
                return null;
            });
            ISourceViewer viewer = editor.getInternalSourceViewer();
            IXtextDocument doc = (IXtextDocument) viewer.getDocument();
            doc.modify(resource -> {
                try {
                    doc.replace(changedTextRange.getOffset(), changedTextRange.getLength(), newTextOfRange);
                } catch (BadLocationException e) {
                    throw new RuntimeException("Ошибка позиционирования при вставке текста из ИР", e);
                }
                return null;
            });
            int newOffset = changedTextRange.getOffset();
            int newLength = newTextOfRange.length();
            viewer.setSelectedRange(newOffset, newLength);
            changedTextRange = null;
            newTextOfRange = "";
        }
        /**
         * 
         */
        public void openTextEditor(String text, String sourceRef)
        {
            Object irClient = getModule("ирКлиент"); //$NON-NLS-1$
            // (Текст, Знач Заголовок = "", ВариантПросмотра = "Компактный", ТолькоПросмотр = Ложь, Знач КлючУникальности = Неопределено, ВладелецФормы = Неопределено, ВыделитьВсе = Ложь,
            // Знач Модально = Ложь, ВыделениеДвумерное = Неопределено, Знач ИскомаяСтрока = "", Знач КлючИсточника = "")
            ComBridge.invoke(irClient, "ОткрытьТекстЛкс", text, sourceRef, null, false, sourceRef, null, false, false, null, "", sourceRef); //$NON-NLS-1$                
        }
        
        public void setText(String text
            , String moduleName
            , int startOffset // from 0
            , int endOffset // from 0
        )
        {
//            Процедура УстановитьТекст(Знач Текст = Неопределено, Знач Активировать = Ложь, Знач НачальныйТекстДляСравнения = Неопределено, Знач СохранитьГраницыВыделения = Ложь, Знач ИмяМодуляСжатое = Неопределено,
//                Знач ИмяМодуля = Неопределено, Знач НовоеНачалоВыделения = 0, Знач НовоеКонецВыделения = 0) Экспорт
            ComBridge.invoke(codeEditor, "УстановитьТекст", text, false, null, false, "", moduleName, startOffset + 1,
                endOffset + 1);
        }

        public Object replaceSelectedText(String text)
        {
     //        ВставитьИзмененныйТекстовыйЛитерал(Знач НовыйТекст, Знач СтарыйТекстЛитерала = "", выхТекстИзменен = Ложь)
            String string = ComBridge.toString(ComBridge.invoke(codeEditor, "ВставитьИзмененныйТекстовыйЛитерал", text));
            return string;
            
       }

        public void readChangedTextRange()
        {
            newTextOfRange = ComBridge.toString(ComBridge.getProperty(codeEditor, "мЗамещающийФрагмент"));
            Object comRange = ComBridge.getProperty(codeEditor, "мЗаменяемыйДиапазон");
            int rangeStart = (int) ComBridge.toLong(ComBridge.getProperty(comRange, "Начало")) - 1;
            changedTextRange = new TextRegion(rangeStart, (int) ComBridge.toLong(ComBridge.getProperty(comRange, "Конец")) - 1 - rangeStart);
        }

        public String selectTextLiteral()
        {
//            Функция ВыделитьТекстовыйЛитерал(Знач ПолеТекстаЛ = Неопределено, выхНачальнаяПозиция0 = 0, выхКонечнаяПозиция0 = 0, Знач РазбиратьКонтекст = Истина, выхВыражение = "",
//                Знач РазрешитьПотерюКомментариев = Истина) Экспорт 
            return ComBridge.toString(ComBridge.invoke(codeEditor, "ВыделитьТекстовыйЛитерал", null, null, null, true, null, false));
        }

        // Модальный
        public boolean openTextLiteralEditor()
        {
            return ComBridge.toBoolean(ComBridge.invoke(codeEditor, "ОткрытьРедакторТекстовогоЛитерала", null, null, null, true, null, false));
        }

        public void showWindow()
        {
           ComBridge.setProperty(root, "Visible", true);
           if (pid > 0)
               WinWindowActivator.activateMainWindow(pid);
         }

    }
