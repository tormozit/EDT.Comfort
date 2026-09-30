package tormozit;

import com._1c.g5.v8.dt.md.ui.aef2.rules.BasicFormRule;
import com._1c.g5.v8.dt.md.ui.editor.aef.descriptor.MdEditorSection;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.ui.aef.component.ListSelectionComponent;
import com._1c.g5.v8.dt.ui.aef.parameterization.SelectionListDialogParameterization;
import com._1c.g5.v8.dt.ui.editor.aef.definition.builder.DtGranularEditorPageSingleColumnBuilder;
import com._1c.g5.v8.dt.ui.editor.aef.definition.builder.DtGranularEditorPageTwoColumnBuilder;
import com._1c.g5.v8.dt.ui.editor.aef.descriptor.AbstractDtGranularEditorAefPageDescriptor;
import com._1c.g5.aef2.standard.parameterization.LinkParameterization;
import com.e1c.g5.v8.dt.check.suppress.ui.aef.components.OpenSuppressionSettingsEditorByLinkComponent;

/** Только свойства {@code BasicForm}, общие для всех дочерних форм. */
public final class ChildFormMainPageDescriptor extends AbstractDtGranularEditorAefPageDescriptor
{
    @Override
    protected void build()
    {
        rule(new BasicFormRule());
        DtGranularEditorPageTwoColumnBuilder columns = twoColumns(true);
        DtGranularEditorPageSingleColumnBuilder<DtGranularEditorPageTwoColumnBuilder> left = columns.leftColumn();
        var main = left.section(MdEditorSection.MAIN);
        main.element(MdClassPackage.Literals.MD_OBJECT__NAME);
        main.element(MdClassPackage.Literals.MD_OBJECT__SYNONYM);
        main.element(MdClassPackage.Literals.MD_OBJECT__COMMENT);
        main.separator();
        main.element(MdClassPackage.Literals.BASIC_FORM__FORM_TYPE);
        main.element(MdClassPackage.Literals.BASIC_FORM__USE_PURPOSES)
                .setup()
                .component(ListSelectionComponent.class,
                        new SelectionListDialogParameterization("Назначения формы", false, false)) //$NON-NLS-1$
                .endSetup();
        main.separator();
        main.element(MdClassPackage.Literals.BASIC_FORM__SUPPRESS_OBJECT)
                .setup()
                .component(OpenSuppressionSettingsEditorByLinkComponent.class, LinkParameterization.OPEN)
                .endSetup();
        main.endSection();
        left.endColumn();

        DtGranularEditorPageSingleColumnBuilder<DtGranularEditorPageTwoColumnBuilder> right = columns.rightColumn();
        var other = right.section(MdEditorSection.OTHER);
        other.element(MdClassPackage.Literals.BASIC_FORM__INCLUDE_HELP_IN_CONTENTS);
        other.element(MdClassPackage.Literals.BASIC_FORM__HELP);
        other.endSection();
        right.endColumn();
        columns.endTwoColumn();
    }
}
