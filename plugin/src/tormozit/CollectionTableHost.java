package tormozit;



import org.eclipse.swt.widgets.Table;



/**

 * Контракт таблицы «Коллекция» / скелета для interaction и подсветки фильтра.

 */

interface CollectionTableHost

{

    Table collectionTable();



    int displayIndexToLogical(int displayIndex);



    String getCellDisplayText(int logicalRow, int visibleCol);



    SmartMatcher activeFilterMatcher();

}

