package tormozit;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.InternalEObject;

import com._1c.g5.v8.dt.common.localization.FeatureNameLocalizationProvider;
import com._1c.g5.v8.dt.form.model.Form;
import com._1c.g5.v8.dt.form.model.FormPackage;
import com._1c.g5.v8.dt.mcore.McorePackage;
import com._1c.g5.v8.dt.mcore.NamedElement;
import com._1c.g5.v8.dt.mcore.Picture;
import com._1c.g5.v8.dt.mcore.PictureRef;
import com.e1c.g5.v8.dt.check.CheckComplexity;
import com.e1c.g5.v8.dt.check.ICheckParameters;
import com.e1c.g5.v8.dt.check.components.BasicCheck;
import com.e1c.g5.v8.dt.check.components.BasicCheck.CheckConfigurer;
import com.e1c.g5.v8.dt.check.components.BasicCheck.ResultAcceptor;
import com.e1c.g5.v8.dt.check.settings.IssueSeverity;
import com.e1c.g5.v8.dt.check.settings.IssueType;

/**
 * Проверка форм на битые ссылки на картинки (PictureRef, чья ссылка не резолвится,
 * например на удалённую/переименованную общую картинку).
 * https://github.com/1C-Company/1c-edt-issues/issues/2153
 */
public class BrokenFormPictureCheck extends BasicCheck<Void>
{
    /** Идентификатор проверки — для фильтрации маркеров в панели «Ошибки конфигурации». */
    public static final String CHECK_ID = "tormozit.comfort.check.brokenFormPicture";

    private static final FeatureNameLocalizationProvider FEATURE_NAMES = new FeatureNameLocalizationProvider();

    @Override
    public String getCheckId()
    {
        return CHECK_ID;
    }

    @Override
    protected void configureCheck(CheckConfigurer configurer)
    {
        configurer.title("Битая ссылка на картинку в форме")
            .description("Ссылка на картинку в элементе формы указывает на несуществующий объект"
                + " (например, общая картинка была удалена или переименована)."
                + " Такая форма вызовет ошибку при загрузке в конфигуратор"
                + " https://github.com/1C-Company/1c-edt-issues/issues/2153")
            .severity(IssueSeverity.MAJOR)
            .issueType(IssueType.ERROR)
            .complexity(CheckComplexity.NORMAL)
            .topObject(FormPackage.Literals.FORM)
                .containment(McorePackage.Literals.PICTURE_REF)
                    .features(McorePackage.Literals.PICTURE_REF__PICTURE);
    }

    @Override
    protected void check(Object object, ResultAcceptor resultAcceptor, ICheckParameters parameters,
        IProgressMonitor monitor)
    {
        PictureRef pictureRef = (PictureRef)object;
        Picture picture = pictureRef.getPicture();
        if (picture == null || !picture.eIsProxy())
            return;

        // Владелец картинки (Кнопка, КомандаФормы, Декорация...) — именно он открывается
        // в редакторе формы по двойному щелчку в панели «Ошибки конфигурации»;
        // сам PictureRef — безымянный служебный узел, для него навигация не работает.
        EObject owner = pictureRef.eContainer();
        EStructuralFeature ownerFeature = pictureRef.eContainingFeature();

        String message = "Битая ссылка на картинку: " + formatProxyUri(picture)
            + " в свойстве " + buildPropertyPath(owner, ownerFeature);

        if (owner != null && ownerFeature != null)
            resultAcceptor.addIssue(message, owner, ownerFeature);
        else
            resultAcceptor.addIssue(message, pictureRef, McorePackage.Literals.PICTURE_REF__PICTURE);
    }

    /**
     * Ссылка на картинку в понятном пользователю виде:
     * <pre>
     *   bm://БСП_3/CommonPicture._ДемоРазделГлавное1#/ → ОбщаяКартинка._ДемоРазделГлавное1
     *   unresolved:/StdPicture.Print1                  → StdPicture.Print1
     * </pre>
     * Для неизвестных {@link MdTypeMapping} типов (стандартные картинки платформы) остаётся
     * исходное имя — придумывать для них русский синоним не за что.
     */
    private static String formatProxyUri(Picture picture)
    {
        String uri = String.valueOf(((InternalEObject)picture).eProxyURI());
        int hash = uri.indexOf('#');
        if (hash >= 0)
            uri = uri.substring(0, hash);
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < uri.length())
            uri = uri.substring(lastSlash + 1);
        String ru = MdTypeMapping.bmFqnToRuFullName(uri);
        return ru != null ? ru : uri;
    }

    /**
     * Иерархический путь к свойству от формы вниз, как в колонке «Свойство» результатов поиска
     * по конфигурации: имена именованных владельцев через точку плюс локализованное имя свойства,
     * например {@code ПодменюПечать.Картинка}.
     */
    private static String buildPropertyPath(EObject owner, EStructuralFeature ownerFeature)
    {
        List<String> segments = new ArrayList<>();
        for (EObject current = owner; current != null && !(current instanceof Form); current = current.eContainer())
        {
            if (current instanceof NamedElement named)
            {
                String name = named.getName();
                if (name != null && !name.isBlank())
                    segments.add(0, name);
            }
        }
        segments.add(featureName(owner, ownerFeature));
        return String.join(".", segments);
    }

    /** Локализованное имя свойства («Картинка»); при отсутствии перевода — техническое имя. */
    private static String featureName(EObject owner, EStructuralFeature ownerFeature)
    {
        EStructuralFeature feature = ownerFeature != null && owner != null
            ? ownerFeature : McorePackage.Literals.PICTURE_REF__PICTURE;
        String localized = null;
        try
        {
            localized = FEATURE_NAMES.getString(feature);
        }
        catch (RuntimeException e)
        {
            // локализация недоступна — ниже используется техническое имя
        }
        return localized != null && !localized.isBlank() ? localized : feature.getName();
    }
}
