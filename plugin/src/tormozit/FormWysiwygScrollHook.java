package tormozit;

import java.io.File;
import java.lang.reflect.Field;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IStartup;

/**
 * Прокрутка колесом мыши в макете (предпросмотре) формы: сначала крутится вложенная область
 * под курсором, а не сразу весь макет.
 *
 * <p><b>Как макет общается с платформой.</b> Макет рисует нативный визуализатор (DLL) в
 * разделяемую память, живых дочерних окон у него нет — форма целиком картинка. Единственный
 * канал — {@code NativeRenderEvent}: он уходит в {@code FormNativeVisualizerLoader.render}, а
 * оттуда в нативный метод тремя значениями — идентификатор типа, {@code windowHandle} и
 * {@code getData()} (упакованные в один {@code long} четыре {@code short}: {@code startX},
 * {@code startY}, {@code endX}, {@code endY}). Больше DLL о событии не знает ничего.
 *
 * <p><b>Почему колесо крутит верхний уровень.</b> {@code FormWysiwygRepresentation} при
 * нативной отрисовке ставит фильтр {@code display.addFilter(SWT.MouseWheel, …)}, который на
 * колесе, дошедшем до внешнего {@code WysiwygScrolledComposite}, вызывает
 * {@code WysiwygNativeComposite.blockSingleScroll()}. Тот взводит {@code blockScroll}, и
 * следующий щелчок штатный обработчик молча проглатывает. Со второго щелчка DLL не получает
 * ничего — крутится только внешний вьюпорт.
 *
 * <p><b>Что проверено и закрыто.</b> Снятие блокировки не помогает: событие {@code SCROLL} с
 * верным {@code windowHandle} (это именно {@code formWindow} из {@code setWindows}) и любой
 * дельтой — сырой {@code MouseEvent.count} или единицы {@code WHEEL_DELTA} — вызывает лишь
 * перерисовку макета, но не прокрутку. Тип {@code SCROLL} нативная сторона при проектировании
 * не отрабатывает, и подбирать в событии больше нечего.
 *
 * <p><b>Рабочий канал — клик.</b> Нажатие на нарисованную визуализатором полосу прокрутки её
 * двигает, а клик мы синтезировать умеем: штатный путь ровно такой же ({@code mouseDown}
 * только запоминает точку, {@code mouseUp} шлёт {@code LEFT_MOUSE_BUTTON} с ней в
 * {@code startX}/{@code startY}). Для DLL настоящий клик и синтетический неразличимы.
 *
 * <p><b>Что осталось — геометрия.</b> Позиций элементов нет нигде: в модели раскладки только
 * размеры, отступы и интервалы, а {@code getControlUnderPoint} в нативном режиме всегда
 * {@code null}. Единственный источник — сама картинка: {@code getFormImageData()} у
 * представления, причём рисуется она в композит от точки {@code (0,0)}, то есть координаты
 * картинки совпадают с координатами клика.
 *
 * <p>Поэтому хук сейчас <b>пассивный</b>: колесо не трогает (штатное поведение EDT сохранено),
 * а по колесу над макетом выгружает отрисованную картинку в {@code .tmp/} — чтобы найти полосу
 * прокрутки по настоящим пикселям, а не по догадкам о цветах. Выгрузка не чаще раза в
 * {@link #DUMP_INTERVAL_MS} мс, чтобы колесо не превратилось в поток файлов.
 */
public final class FormWysiwygScrollHook implements IStartup
{
    /** Тема временного лога пробы. */
    private static final String TEMP_TOPIC = "макет-прокрутка"; //$NON-NLS-1$

    /** Простое имя композита, в который нативный визуализатор рисует макет. */
    private static final String NATIVE_COMPOSITE = "WysiwygNativeComposite"; //$NON-NLS-1$

    /** Куда складывать выгруженные картинки макета (личное РМ автора, как и временные логи). */
    private static final String DUMP_DIR = "C:\\VC\\EDT.Comfort\\.tmp\\макет-снимки"; //$NON-NLS-1$

    /** Минимальный интервал между выгрузками картинки, мс. */
    private static final long DUMP_INTERVAL_MS = 3000L;

    private static long lastDumpTime;

    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() -> install(Display.getDefault()));
    }

    public static void install(Display display)
    {
        if (display == null || display.isDisposed())
            return;
        display.addFilter(SWT.MouseWheel, FormWysiwygScrollHook::handleMouseWheel);
    }

    private static void handleMouseWheel(Event e)
    {
        Widget widget = e.widget;
        if (widget == null || widget.isDisposed() || !NATIVE_COMPOSITE.equals(widget.getClass().getSimpleName()))
            return;

        long now = System.currentTimeMillis();
        if (now - lastDumpTime < DUMP_INTERVAL_MS)
            return;
        lastDumpTime = now;

        ImageData image = formImageData(widget);
        if (image == null)
        {
            Global.tempLog(TEMP_TOPIC, "картинка макета недоступна, курсор x=" + e.x + " y=" + e.y); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        String path = dump(image, now);
        Global.tempLog(TEMP_TOPIC, "курсор x=" + e.x + " y=" + e.y //$NON-NLS-1$ //$NON-NLS-2$
            + " картинка=" + image.width + "x" + image.height //$NON-NLS-1$ //$NON-NLS-2$
            + " глубина=" + image.depth + " файл=" + path); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Отрисованная визуализатором картинка формы — из представления макета. */
    private static ImageData formImageData(Widget nativeComposite)
    {
        try
        {
            Field field = nativeComposite.getClass().getDeclaredField("representation"); //$NON-NLS-1$
            field.setAccessible(true);
            Object representation = field.get(nativeComposite);
            if (representation == null)
                return null;

            Field imageField = representation.getClass().getDeclaredField("formImageData"); //$NON-NLS-1$
            imageField.setAccessible(true);
            ImageData buffered = (ImageData)imageField.get(representation);
            if (buffered != null)
                return buffered;

            // Небуферизованный режим: картинка живёт в самом композите отдельным Image.
            Field ownField = nativeComposite.getClass().getDeclaredField("image"); //$NON-NLS-1$
            ownField.setAccessible(true);
            Image own = (Image)ownField.get(nativeComposite);
            return own == null || own.isDisposed() ? null : own.getImageData();
        }
        catch (Exception ex)
        {
            Global.tempLogException(TEMP_TOPIC, "картинка макета не получена", ex); //$NON-NLS-1$
            return null;
        }
    }

    /** Сохраняет картинку макета в PNG; возвращает путь или причину неудачи. */
    private static String dump(ImageData image, long stamp)
    {
        try
        {
            File dir = new File(DUMP_DIR);
            dir.mkdirs();
            File file = new File(dir, "макет-" + stamp + ".png"); //$NON-NLS-1$ //$NON-NLS-2$

            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { image };
            loader.save(file.getAbsolutePath(), SWT.IMAGE_PNG);
            return file.getAbsolutePath();
        }
        catch (Exception ex)
        {
            Global.tempLogException(TEMP_TOPIC, "картинка не сохранена", ex); //$NON-NLS-1$
            return "не сохранена"; //$NON-NLS-1$
        }
    }
}
