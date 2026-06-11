package tormozit;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.IStructuredSelection;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.metadata.mdclass.BasicForm;

/**
 * Разрешение элемента дерева навигатора EDT в ближайший {@link IResource} на диске.
 */
public final class NavigatorResourceResolver
{
    private static final String[] PARENT_METHODS = {
            "getParent", "getOwner", "getContainer", "getInput" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    };

    private NavigatorResourceResolver() {}

    public static IResource resolveFirst(IStructuredSelection selection)
    {
        if (selection == null || selection.isEmpty())
            return null;
        return resolve(selection.getFirstElement());
    }

    public static IResource resolve(Object selected)
    {
        if (selected == null)
            return null;

        IResourceLookup lookup = Global.getOsgiService(IResourceLookup.class);
        if (lookup == null)
            return null;

        IResource direct = NavigatorElementModels.adapt(selected, IResource.class);
        if (isValid(direct))
            return direct;

        EObject model = NavigatorElementModels.resolveEObject(selected);
        if (model != null)
        {
            IResource fromModel = resolveFromModel(model, lookup);
            if (fromModel != null)
                return fromModel;
        }

        for (String method : PARENT_METHODS)
        {
            Object parent = Global.invoke(selected, method);
            if (parent != null && parent != selected)
            {
                IResource fromParent = resolve(parent);
                if (fromParent != null)
                    return fromParent;
            }
        }
        return null;
    }

    private static IResource resolveFromModel(EObject model, IResourceLookup lookup)
    {
        if (model instanceof BasicForm)
        {
            IResource formFolder = getFormFolder((BasicForm) model, lookup);
            if (isValid(formFolder))
                return formFolder;
        }

        IFile file = lookup.getPlatformResource(model);
        if (isValid(file))
            return file;

        if (model.eResource() != null)
        {
            file = lookup.getPlatformResource(model.eResource());
            if (isValid(file))
                return file;
        }

        for (EObject owner = model.eContainer(); owner != null; owner = owner.eContainer())
        {
            file = lookup.getPlatformResource(owner);
            if (isValid(file))
                return file;

            IContainer ownerFolder = file != null ? file.getParent() : null;
            if (isValid(ownerFolder))
                return ownerFolder;
        }
        return null;
    }

    private static IResource getFormFolder(BasicForm form, IResourceLookup resourceLookup)
    {
        IFile formFile = resourceLookup.getPlatformResource(form);
        IResource directFolder = getExistingParentFolder(formFile);
        if (directFolder != null && isFormFolder(directFolder))
            return directFolder;

        for (EObject owner = form.eContainer(); owner != null; owner = owner.eContainer())
        {
            IFile ownerFile = resourceLookup.getPlatformResource(owner);
            IContainer ownerFolder = ownerFile != null ? ownerFile.getParent() : null;
            if (ownerFolder != null)
            {
                IFolder formFolder = ownerFolder.getFolder(new Path("Forms/" + form.getName())); //$NON-NLS-1$
                if (formFolder.exists())
                    return formFolder;
            }
        }
        return directFolder;
    }

    private static IResource getExistingParentFolder(IFile file)
    {
        if (file == null)
            return null;
        IContainer parent = file.getParent();
        return parent != null && parent.exists() ? parent : null;
    }

    private static boolean isFormFolder(IResource resource)
    {
        IContainer parent = resource.getParent();
        return parent != null && "Forms".equals(parent.getName()); //$NON-NLS-1$
    }

    private static boolean isValid(IResource resource)
    {
        return resource != null && resource.exists() && resource.getLocation() != null;
    }
}
