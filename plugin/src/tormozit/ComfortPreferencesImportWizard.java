package tormozit;

import java.util.Map;

import org.eclipse.jface.bindings.Scheme;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.internal.wizards.preferences.PreferencesImportWizard;
import org.eclipse.ui.keys.IBindingService;

/**
 * Замена штатного {@link PreferencesImportWizard}, вызываемая из кнопки
 * «Импортировать...» окна «Параметры» ({@link PreferencesDialogTransferHook}).
 */
public class ComfortPreferencesImportWizard extends PreferencesImportWizard
{
    private static final String TAG = "ComfortPreferencesImportWizard"; //$NON-NLS-1$

    /**
     * Схемы клавиш, которые в некоторых сборках EDT не зарегистрированы (бандл,
     * объявляющий их через {@code org.eclipse.ui.bindings}, отсутствует), но на
     * которые может ссылаться импортируемый файл параметров — тогда выбор такой
     * схемы в «Общие → Клавиши» без досоздания молча ничего не делает. Досоздаём
     * пустую схему с тем же id/именем, что в {@code designer/plugin.xml}: набора
     * клавиш это не даёт, но схема становится определённой и выбираемой.
     */
    private static final Map<String, String> KNOWN_SCHEME_NAMES =
            Map.of("com._1c.g5.v8.designer.scheme", "Конфигуратор 1С"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String DEFAULT_SCHEME_PARENT_ID = "org.eclipse.ui.defaultAcceleratorConfiguration"; //$NON-NLS-1$

    /**
     * См. {@link ComfortPreferencesExportWizard#page} — та же причина
     * переопределять {@code performFinish()} без вызова {@code super}.
     */
    private ComfortPreferencesImportPage page;

    @Override
    public void init(IWorkbench workbench, IStructuredSelection selection)
    {
        // См. ComfortPreferencesExportWizard.init() — super.init(...) сам зовёт
        // setWindowTitle(PreferencesMessages.PreferencesImportWizard_import).
        super.init(workbench, selection);
        setWindowTitle(Global.withPluginWindowTitle("Импортировать параметры")); //$NON-NLS-1$
    }

    @Override
    public void addPages()
    {
        page = new ComfortPreferencesImportPage();
        addPage(page);
    }

    @Override
    public boolean performFinish()
    {
        boolean ok = page.finish();
        if (ok)
            ensureKnownSchemesDefined();
        return ok;
    }

    private static void ensureKnownSchemesDefined()
    {
        IBindingService bindingService = PlatformUI.getWorkbench().getService(IBindingService.class);
        if (bindingService == null)
            return;
        for (Map.Entry<String, String> known : KNOWN_SCHEME_NAMES.entrySet())
        {
            Scheme scheme = bindingService.getScheme(known.getKey());
            if (scheme.isDefined())
                continue;
            scheme.define(known.getValue(), known.getValue(), DEFAULT_SCHEME_PARENT_ID);
            Global.tempLog(TAG, "ensureKnownSchemesDefined: defined missing scheme " + known.getKey()); //$NON-NLS-1$
        }
    }
}
