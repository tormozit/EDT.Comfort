package tormozit;


public enum CompareConfigExpandMode
{
    /** Развернуть до верхних объектов (без погружения в свойства). */
    toObject,

    /** Развернуть до узлов фильтра «Показывать измененные» (без неизменённых поддеревьев). */
    toBothElement,

    /** Развернуть до помеченных листьев. */
    toMarked
}
