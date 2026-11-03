package tormozit;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.xtext.nodemodel.ILeafNode;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.EObjectAtOffsetHelper;
import org.eclipse.xtext.resource.XtextResource;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

/**
 * Поиск позиции для ссылок BSL без повторного вычисления границ составных узлов.
 * В Xtext 2.33 CompositeNode.getTotalOffset для пустого конечного узла обходит
 * дерево от корня; NodeModelUtils.findLeafNodeAtOffset повторяет этот обход.
 * Индекс использует только готовые границы листьев и живёт внутри одного доступа
 * к модели документа. Вне такого доступа сохраняется штатный поиск Xtext.
 */
final class BslNodeLookup
{
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    private BslNodeLookup() {}

    static ILeafNode leafAt(INode node, int offset)
    {
        return indexed(() -> findLeafNodeAtOffset(node, offset));
    }

    static EObject containedAt(EObjectAtOffsetHelper helper, XtextResource resource, int offset)
    {
        return helper != null && helper.getClass() == EObjectAtOffsetHelper.class
            ? indexed(() -> resolveContainedElementAt(resource, offset))
            : helper.resolveContainedElementAt(resource, offset);
    }

    /** Срок жизни индекса ограничен текущим вызовом, если внешнего чтения модели нет. */
    private static <T> T indexed(Supplier<T> action)
    {
        Session parent = CURRENT.get();
        if (parent != null && parent.modelRead)
            return action.get();
        try (Session session = new Session(parent == null ? null : parent.monitor, true))
        {
            return action.get();
        }
    }

    static <T> T compute(IProgressMonitor monitor, Supplier<T> action)
    {
        try (Session session = new Session(monitor, false))
        {
            checkCanceled();
            T result = action.get();
            checkCanceled();
            return result;
        }
    }

    /** Вызывать только внутри readOnly/tryReadOnly: индекс не переживает отпускание модели. */
    static <T> T read(IUnitOfWork<T, XtextResource> work, XtextResource resource) throws Exception
    {
        Session parent = CURRENT.get();
        try (Session session = new Session(parent == null ? null : parent.monitor, true))
        {
            checkCanceled();
            T result = work.exec(resource);
            checkCanceled();
            return result;
        }
    }

    static void checkCanceled()
    {
        Session session = CURRENT.get();
        if (session != null && session.monitor != null && session.monitor.isCanceled())
            throw new OperationCanceledException();
    }

    static ILeafNode findLeafNodeAtOffset(INode node, int offset)
    {
        checkCanceled();
        Session session = CURRENT.get();
        if (session == null || !session.modelRead)
            return NodeModelUtils.findLeafNodeAtOffset(node, offset);
        LeafIndex index = session.indices.get(node);
        if (index == null)
        {
            index = new LeafIndex(node);
            session.indices.put(node, index);
        }
        return index.find(offset);
    }

    /** Семантика EObjectAtOffsetHelper.resolveContainedElementAt из Xtext 2.33. */
    static EObject resolveContainedElementAt(XtextResource resource, int offset)
    {
        Session session = CURRENT.get();
        if (session == null || !session.modelRead)
            return new EObjectAtOffsetHelper().resolveContainedElementAt(resource, offset);
        var parsed = resource.getParseResult();
        if (parsed == null)
            return null;
        ILeafNode leaf = findLeafNodeAtOffset(parsed.getRootNode(), offset);
        if (leaf != null && leaf.isHidden() && leaf.getOffset() == offset)
            leaf = findLeafNodeAtOffset(parsed.getRootNode(), offset - 1);
        return leaf == null ? parsed.getRootASTElement() : NodeModelUtils.findActualSemanticObjectFor(leaf);
    }

    private static final class Session implements AutoCloseable
    {
        private final Session previous = CURRENT.get();
        private final IProgressMonitor monitor;
        private final boolean modelRead;
        private final Map<INode, LeafIndex> indices = new IdentityHashMap<>();

        Session(IProgressMonitor monitor, boolean modelRead)
        {
            this.monitor = monitor;
            this.modelRead = modelRead;
            CURRENT.set(this);
        }

        @Override
        public void close()
        {
            if (previous == null)
                CURRENT.remove();
            else
                CURRENT.set(previous);
        }
    }

    private static final class LeafIndex
    {
        private final List<ILeafNode> leaves = new ArrayList<>();
        private final int[] starts;

        LeafIndex(INode node)
        {
            int visited = 0;
            // getLeafNodes использует basicIterator без getTotalLength составных узлов.
            // LeafNode.getTotalOffset/getTotalLength читают поля (Xtext 2.33).
            for (ILeafNode leaf : node.getLeafNodes())
            {
                if ((visited++ & 127) == 0)
                    checkCanceled();
                // Xtext intersects использует полуинтервал: пустой лист не содержит позицию.
                if (leaf.getTotalLength() > 0)
                    leaves.add(leaf);
            }
            starts = new int[leaves.size()];
            for (int i = 0; i < starts.length; i++)
                starts[i] = leaves.get(i).getTotalOffset();
        }

        ILeafNode find(int offset)
        {
            int low = 0;
            int high = starts.length;
            while (low < high)
            {
                int middle = (low + high) >>> 1;
                if (starts[middle] <= offset)
                    low = middle + 1;
                else
                    high = middle;
            }
            if (low == 0)
                return null;
            ILeafNode leaf = leaves.get(low - 1);
            return offset < leaf.getTotalEndOffset() ? leaf : null;
        }
    }
}
