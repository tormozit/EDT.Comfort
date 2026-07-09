package tormozit;

import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IBaseLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

public class SmartMatcherPictureFilter extends ViewerFilter {

    private SmartMatcher matcher = new SmartMatcher("");

    public void setPattern(String pattern) {
        this.matcher = new SmartMatcher(pattern);
    }

    public SmartMatcher getMatcher() {
        return matcher;
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        if (matcher.isEmpty)
            return true;
        return matchesAnyName(viewer, element);
    }

    private boolean matchesAnyName(Viewer viewer, Object element) {
        IBaseLabelProvider baseLp = ((TableViewer) viewer).getLabelProvider();
        if (baseLp instanceof ILabelProvider lp) {
            String text = lp.getText(element);
            if (text != null && !text.isEmpty() && matcher.matches(text))
                return true;
        }
        try {
            Object name = Global.invoke(element, "getName");
            if (name instanceof String s && !s.isEmpty() && matcher.matches(s))
                return true;
        } catch (Exception ignored) {
        }
        try {
            Object name = Global.invoke(element, "getNameRu");
            if (name instanceof String s && !s.isEmpty() && matcher.matches(s))
                return true;
        } catch (Exception ignored) {
        }
        return matcher.matches(String.valueOf(element));
    }
}
