package tormozit;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.ui.AbstractLaunchConfigurationTab;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/** Параметры RAC/RAS в штатной конфигурации подключения к серверу отладки. */
public final class DebugSessionRasTab extends AbstractLaunchConfigurationTab
{
    static final String ADDRESS = "tormozit.comfort.debug.rasAddress"; //$NON-NLS-1$
    static final String USER = "tormozit.comfort.debug.rasUser"; //$NON-NLS-1$
    static final String PASSWORD = "tormozit.comfort.debug.rasPassword"; //$NON-NLS-1$
    private static volatile String requestedConfiguration;
    private Text address;
    private Text user;
    private Text password;
    private Label addressProblem;
    private boolean loading;

    static ILaunchConfiguration savedConfiguration(ILaunchConfiguration configuration)
    {
        if (configuration instanceof ILaunchConfigurationWorkingCopy copy && copy.getOriginal() != null)
            return copy.getOriginal();
        return configuration;
    }

    static void requestFocus(ILaunchConfiguration configuration) throws CoreException
    {
        requestedConfiguration = savedConfiguration(configuration).getMemento();
    }

    static void clearFocusRequest()
    {
        requestedConfiguration = null;
    }

    @Override
    public void createControl(Composite parent)
    {
        Composite area = new Composite(parent, SWT.NONE);
        area.setLayout(new GridLayout(2, false));
        setControl(area);
        Label description = new Label(area, SWT.WRAP);
        description.setText("Параметры команды «Удалить сеанс» в панели «Отладка». "
            + "RAS должен быть запущен на сервере. RAC берётся из каталога конфигуратора приложения, "
            + "кластер определяется по выбранному сеансу.");
        GridData descriptionData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        descriptionData.widthHint = 520;
        description.setLayoutData(descriptionData);
        addressProblem = new Label(area, SWT.WRAP);
        addressProblem.setForeground(area.getDisplay().getSystemColor(SWT.COLOR_RED));
        GridData problemData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        problemData.widthHint = 520;
        addressProblem.setLayoutData(problemData);
        address = field(area, "Адрес RAS:", SWT.NONE,
            "Имя сервера и порт, например server:1545. "
                + "Это адрес сервиса администрирования RAS.");
        user = field(area, "Администратор кластера:", SWT.NONE,
            "Имя администратора кластера. Если в кластере нет администроров, оставьте пустым.");
        password = field(area, "Пароль администратора:", SWT.PASSWORD,
            "Пароль администратора кластера.");
    }

    private Text field(Composite parent, String label, int style, String tooltip)
    {
        new Label(parent, SWT.NONE).setText(label);
        Text text = new Text(parent, SWT.BORDER | style);
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.setToolTipText(TooltipText.wrap(text, tooltip + Global.pluginSignForTooltip()));
        text.addModifyListener(event ->
        {
            if (!loading)
            {
                showAddressProblem();
                updateLaunchConfigurationDialog();
            }
        });
        return text;
    }

    @Override
    public void initializeFrom(ILaunchConfiguration configuration)
    {
        loading = true;
        try
        {
            address.setText(configuration.getAttribute(ADDRESS, "")); //$NON-NLS-1$
            user.setText(configuration.getAttribute(USER, "")); //$NON-NLS-1$
            password.setText(configuration.getAttribute(PASSWORD, "")); //$NON-NLS-1$
            if (savedConfiguration(configuration).getMemento().equals(requestedConfiguration))
            {
                clearFocusRequest();
                getControl().getDisplay().asyncExec(() ->
                {
                    if (!getControl().isDisposed())
                    {
                        getLaunchConfigurationDialog().setActiveTab(this);
                        address.setFocus();
                    }
                });
            }
        }
        catch (CoreException e)
        {
            setErrorMessage(e.getLocalizedMessage());
        }
        finally
        {
            loading = false;
            showAddressProblem();
        }
    }

    @Override
    public void performApply(ILaunchConfigurationWorkingCopy configuration)
    {
        configuration.removeAttribute("tormozit.comfort.debug.rac"); //$NON-NLS-1$
        configuration.removeAttribute("tormozit.comfort.debug.rasCluster"); //$NON-NLS-1$
        configuration.setAttribute(ADDRESS, normalizedAddress(address.getText()));
        configuration.setAttribute(USER, user.getText().trim());
        configuration.setAttribute(PASSWORD, password.getText());
    }

    @Override
    public void setDefaults(ILaunchConfigurationWorkingCopy configuration)
    {
        // Пустые параметры RAS не мешают обычному запуску отладки.
    }

    @Override
    public boolean isValid(ILaunchConfiguration configuration)
    {
        setErrorMessage(null);
        setMessage(null);
        try
        {
            String problem = validationProblem(configuration);
            if (problem != null)
                setMessage(problem + " Параметры нужны только для команды «Удалить сеанс».");
        }
        catch (CoreException e)
        {
            setErrorMessage(e.getLocalizedMessage());
            return false;
        }
        return true;
    }

    static String validationProblem(ILaunchConfiguration configuration) throws CoreException
    {
        return addressProblem(configuration.getAttribute(ADDRESS, "")); //$NON-NLS-1$
    }

    /** RAC получает только сервер[:порт]; http:// из скопированного адреса не передаётся ему. */
    static String normalizedAddress(String text)
    {
        String value = text.trim();
        return value.regionMatches(true, 0, "http://", 0, 7) ? value.substring(7) : value; //$NON-NLS-1$
    }

    private void showAddressProblem()
    {
        String problem = addressProblem(address.getText());
        addressProblem.setText(problem != null ? problem : ""); //$NON-NLS-1$
        addressProblem.getParent().layout(true, true);
    }

    private static String addressProblem(String text)
    {
        String endpoint = normalizedAddress(text);
        if (endpoint.isEmpty())
            return "Адрес RAS не заполнен. Укажите сервер и порт, например server:1545.";
        try
        {
            java.net.URI uri = new java.net.URI("tcp://" + endpoint); //$NON-NLS-1$
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() == 0
                || uri.getPort() > 65535 || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawPath().isEmpty())
                return "Некорректный адрес RAS. Укажите сервер:порт, например server:1545; без пути, параметров и имени пользователя.";
        }
        catch (java.net.URISyntaxException e)
        {
            return "Некорректный адрес RAS. Укажите сервер:порт, например server:1545.";
        }
        return null;
    }

    @Override
    public String getName()
    {
        return "RAS"; //$NON-NLS-1$
    }

    @Override
    public String getId()
    {
        return "tormozit.comfort.debug.ras"; //$NON-NLS-1$
    }
}
