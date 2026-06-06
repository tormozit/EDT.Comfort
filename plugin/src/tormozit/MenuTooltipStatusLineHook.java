package tormozit;
import java.lang.reflect.Method;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.window.ApplicationWindow;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Глобальный слушатель, который выводит Tooltip текущей команды (меню или тулбара)
 * в строку состояния (Status Line) главного окна 1C:EDT.
 */
public class MenuTooltipStatusLineHook implements IStartup
{
    private static boolean isInstalled = false;
    
    @Override
    public void earlyStartup()
    {
        Display.getDefault().asyncExec(() ->
        {
//          Activator.getDefault().getInjector().injectMembers(this); // Слишком рано?
//          install(Display.getDefault()); // Пока отключил, т.к. польза сопоставима с вредом
        });
    }
    
    /**
     * Устанавливает хук. Рекомендуется вызывать один раз при старте плагина
     * (например, в IStartup.earlyStartup() или Activator.start()).
     */
    public static void install(Display display)
    {
        if (isInstalled || display == null || display.isDisposed())
            return;

        Listener listener = new Listener()
        {
            @Override
            public void handleEvent(Event event)
            {
                switch (event.type)
                {
                    case SWT.Arm: // Навигация по пунктам меню
                        handleMenuArm(event.widget);
                        break;

                    case SWT.MouseEnter: // Наведение мыши на тулбар
                        handleMouseEnter(event.widget);
                        break;

                    case SWT.Hide: // Закрытие меню
                    case SWT.MouseExit: // Уход курсора с тулбара
                        setStatusLineMessage(null);
                        break;
                }
            }
        };

        // Подписываемся глобально на уровне Display
        display.addFilter(SWT.Arm, listener);
        display.addFilter(SWT.Hide, listener);
        display.addFilter(SWT.MouseEnter, listener);
        display.addFilter(SWT.MouseExit, listener);

        isInstalled = true;
    }

    private static void handleMenuArm(Widget widget)
    {
        if (!(widget instanceof MenuItem))
            return;

        MenuItem item = (MenuItem) widget;
        Object data = item.getData(); // В Eclipse здесь обычно лежит элемент модели e4 или JFace Action

        if (data != null)
        {
            String tooltip = extractTooltip(data);
            if (tooltip != null && !tooltip.trim().isEmpty())
            {
                setStatusLineMessage(tooltip);
                return;
            }
        }
        
        // Если Tooltip нет, очищаем строку, чтобы не висела старая подсказка
        setStatusLineMessage(null);
    }

    private static void handleMouseEnter(Widget widget)
    {
        if (widget instanceof ToolItem)
        {
            String tooltip = ((ToolItem) widget).getToolTipText();
            if (tooltip != null && !tooltip.trim().isEmpty())
            {
                setStatusLineMessage(tooltip);
            }
        }
    }

    /**
     * Пытается вытащить Tooltip через рефлексию (покрывает e4 MMenuItem, JFace Actions, CommandContributionItem).
     */
    private static String extractTooltip(Object data)
    {
        // 1. Попытка извлечь JFace IAction, в который обернут элемент
        try
        {
            Method getActionMethod = data.getClass().getMethod("getAction");
            Object action = getActionMethod.invoke(data);
            if (action != null)
            {
                Method getToolTipMethod = action.getClass().getMethod("getToolTipText");
                Object tooltip = getToolTipMethod.invoke(action);
                if (tooltip instanceof String)
                    return (String) tooltip;
            }
        }
        catch (Exception ignored) {}

        // 2. Поиск прямого геттера (покрывает e4 MUIElement: getTooltip, getLocalizedTooltip)
        String[] possibleMethods = { "getLocalizedTooltip", "getTooltip", "getToolTipText" };
        for (String methodName : possibleMethods)
        {
            try
            {
                Method method = data.getClass().getMethod(methodName);
                Object tooltip = method.invoke(data);
                if (tooltip instanceof String)
                    return (String) tooltip;
            }
            catch (Exception ignored) {}
        }

        return null;
    }

    /**
     * Безопасно обновляет глобальную строку состояния активного окна Workbench.
     */
    private static void setStatusLineMessage(String message)
    {
        try
        {
            if (!PlatformUI.isWorkbenchRunning())
                return;

            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null)
                return;

            IStatusLineManager statusLine = null;

            // Способ 1: Через ApplicationWindow (дефолт для главного окна Eclipse RCP)
            if (window instanceof ApplicationWindow)
            {
                statusLine = (IStatusLineManager)Global.call(window, "getStatusLineManager");
            }

            // Способ 2: Через активный редактор/вьюшку (если первый способ не сработал)
            if (statusLine == null)
            {
                IWorkbenchPage page = window.getActivePage();
                if (page != null)
                {
                    IWorkbenchPart part = page.getActivePart();
                    if (part instanceof IViewPart)
                        statusLine = ((IViewPart) part).getViewSite().getActionBars().getStatusLineManager();
                    else if (part instanceof IEditorPart)
                        statusLine = ((IEditorPart) part).getEditorSite().getActionBars().getStatusLineManager();
                }
            }

            if (statusLine != null)
            {
                // Очищаем сообщение, если передан null
                statusLine.setMessage(message == null ? "" : message);
            }
        }
        catch (Throwable ignored)
        {
            // Игнорируем ошибки при закрытии окна или отсутствии контекста
        }
    }
}