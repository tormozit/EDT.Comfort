package tormozit;

import java.util.Collections;
import java.util.List;

/** Результат анализа пересечений для вкладок «Клавиши». */
final class ComfortKeysAnalysisResult
{
    static final ComfortKeysAnalysisResult EMPTY =
            new ComfortKeysAnalysisResult(Collections.emptyList(), Collections.emptyList());

    final List<ComfortKeysLocalConflictRow> globalRows;
    final List<ComfortKeysLocalConflictRow> localRows;

    ComfortKeysAnalysisResult(
            List<ComfortKeysLocalConflictRow> globalRows,
            List<ComfortKeysLocalConflictRow> localRows)
    {
        this.globalRows = globalRows != null ? globalRows : Collections.emptyList();
        this.localRows = localRows != null ? localRows : Collections.emptyList();
    }
}
