package tormozit;

import com._1c.g5.v8.dt.md.ui.editor.aef.DtGranularEditorAefDescriptorBasedPage;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;

/** Страница свойств формы, принадлежащей объекту метаданных. */
public final class ChildFormMainPage extends DtGranularEditorAefDescriptorBasedPage<BasicForm>
{
    public static final String PAGE_ID = "tormozit.comfort.childForm.pages.main"; //$NON-NLS-1$

    public ChildFormMainPage()
    {
        super(PAGE_ID, "Основные"); //$NON-NLS-1$
        setDefaultFeature(MdClassPackage.Literals.MD_OBJECT__NAME);
    }
}
